package dev.nobash.domain.git;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the porcelain v2 output of {@code git status --porcelain=v2 --branch} into a normalized
 * {@link GitStatus} (PRD-002, issue #24). PURE and I/O-FREE: it takes git's machine-format stdout
 * as an already-in-memory string and returns the parsed status — NO File, NO Path read, NO
 * directory walk, NO process (see the {@code the_git_domain_is_io_free} ArchUnit rule). This is
 * the porcelain-parse keystone: we parse git's STABLE machine format, never scrape human stdout.
 *
 * <h3>Recognized line shapes (porcelain v2)</h3>
 * <pre>
 *   # branch.oid &lt;sha|(initial)&gt;
 *   # branch.head &lt;name|(detached)&gt;
 *   # branch.upstream &lt;remote/branch&gt;      (ABSENT without a tracking branch)
 *   # branch.ab +&lt;ahead&gt; -&lt;behind&gt;        (ABSENT without an upstream)
 *   1 &lt;XY&gt; &lt;sub&gt; &lt;mH&gt; &lt;mI&gt; &lt;mW&gt; &lt;hH&gt; &lt;hI&gt; &lt;path&gt;            ordinary changed entry
 *   2 &lt;XY&gt; &lt;sub&gt; &lt;mH&gt; &lt;mI&gt; &lt;mW&gt; &lt;hH&gt; &lt;hI&gt; &lt;Xscore&gt; &lt;path&gt;\t&lt;origPath&gt;  rename/copy
 *   u &lt;xy&gt; ... &lt;path&gt;                    unmerged (tolerated; not bucketed by this slice)
 *   ? &lt;path&gt;                             untracked
 *   ! &lt;path&gt;                             ignored (tolerated; not bucketed)
 * </pre>
 *
 * <h3>Robustness contract</h3>
 * <p>The parser NEVER throws on real git output. Missing header lines (no upstream → no
 * {@code branch.upstream} and no {@code branch.ab}) leave the corresponding fields null. A
 * detached HEAD ({@code # branch.head (detached)}) sets {@code detached=true}. Unknown leading
 * tokens ({@code u}, {@code !}, or anything unexpected) are tolerated and skipped rather than
 * misfiled or fatal. All status codes are locale-independent ASCII.</p>
 *
 * <h3>Bucketing</h3>
 * <p>For an ordinary/rename entry the {@code XY} field's index half (X) drives {@code staged[]}
 * and the worktree half (Y) drives {@code unstaged[]}; a half equal to {@code .} means "no change
 * on that side". A {@code MM} file is thus in BOTH buckets. {@code ?} lines drive
 * {@code untracked[]}.</p>
 *
 * <h3>Thread-safety</h3>
 * <p>The class is stateless and immutable; a single shared instance is safe for concurrent use.</p>
 */
public final class GitStatusParser {

    private static final String DETACHED = "(detached)";

    /** The "no change on this side" placeholder in a porcelain-v2 XY field. */
    private static final char UNCHANGED = '.';

    /**
     * Parse the porcelain v2 status output into a normalized {@link GitStatus}. Null/blank input
     * yields an all-null-header, all-empty-bucket status (the parser never throws).
     *
     * @param porcelainOutput the stdout of {@code git status --porcelain=v2 --branch}; null → empty
     * @return the normalized status; header fields null when their header line was absent
     */
    public GitStatus parse(String porcelainOutput) {
        String branch = null;
        boolean detached = false;
        String upstream = null;
        Integer ahead = null;
        Integer behind = null;
        List<GitStatusEntry> staged = new ArrayList<>();
        List<GitStatusEntry> unstaged = new ArrayList<>();
        List<GitStatusEntry> untracked = new ArrayList<>();

        if (porcelainOutput != null && !porcelainOutput.isBlank()) {
            for (String line : porcelainOutput.split("\r?\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                char kind = line.charAt(0);
                switch (kind) {
                    case '#' -> {
                        ParsedHeader h = parseHeader(line);
                        if (h != null) {
                            switch (h.key) {
                                case "branch.head" -> {
                                    if (DETACHED.equals(h.value)) {
                                        detached = true;
                                        branch = DETACHED;
                                    } else {
                                        branch = h.value;
                                    }
                                }
                                case "branch.upstream" -> upstream = h.value;
                                case "branch.ab" -> {
                                    int[] ab = parseAheadBehind(h.value);
                                    if (ab != null) {
                                        ahead = ab[0];
                                        behind = ab[1];
                                    }
                                }
                                default -> {
                                    // branch.oid and any future header: ignored, never fatal.
                                }
                            }
                        }
                    }
                    case '1' -> bucketOrdinary(line, staged, unstaged);
                    case '2' -> bucketRename(line, staged, unstaged);
                    case '?' -> {
                        String path = afterFirstSpace(line);
                        if (path != null) {
                            untracked.add(GitStatusEntry.of(path, "?"));
                        }
                    }
                    default -> {
                        // 'u' (unmerged), '!' (ignored), or anything unexpected: tolerated, skipped.
                    }
                }
            }
        }

        return new GitStatus(branch, detached, upstream, ahead, behind, staged, unstaged, untracked);
    }

    /**
     * Bucket an ordinary ({@code 1 <XY> ... <path>}) entry. The path is the rest of the line
     * after the 8th space-separated field (the fixed porcelain-v2 prefix), so a path containing
     * spaces survives intact. Misshapen lines are skipped, never fatal.
     */
    private static void bucketOrdinary(String line, List<GitStatusEntry> staged,
                                       List<GitStatusEntry> unstaged) {
        // 1 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <path>  → 8 prefix fields then the path.
        String xy = fieldAt(line, 1);
        String path = restAfterFields(line, 8);
        if (xy == null || xy.length() < 2 || path == null || path.isEmpty()) {
            return;
        }
        bucketByXy(xy, path, null, staged, unstaged);
    }

    /**
     * Bucket a rename/copy ({@code 2 <XY> ... <Xscore> <path>\t<origPath>}) entry. The rename has
     * one extra prefix field (the similarity score), so the path begins after the 9th field; the
     * path payload is {@code <path>\t<origPath>} (TAB-separated, new path first).
     */
    private static void bucketRename(String line, List<GitStatusEntry> staged,
                                     List<GitStatusEntry> unstaged) {
        // 2 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <Xscore> <path>\t<origPath>  → 9 prefix fields.
        String xy = fieldAt(line, 1);
        String payload = restAfterFields(line, 9);
        if (xy == null || xy.length() < 2 || payload == null || payload.isEmpty()) {
            return;
        }
        String path = payload;
        String origPath = null;
        int tab = payload.indexOf('\t');
        if (tab >= 0) {
            path = payload.substring(0, tab);
            origPath = payload.substring(tab + 1);
        }
        if (path.isEmpty()) {
            return;
        }
        bucketByXy(xy, path, origPath, staged, unstaged);
    }

    /**
     * Place a changed entry in the staged and/or unstaged buckets per its {@code XY} field.
     * X (index half) non-{@code .} → staged; Y (worktree half) non-{@code .} → unstaged.
     */
    private static void bucketByXy(String xy, String path, String origPath,
                                   List<GitStatusEntry> staged, List<GitStatusEntry> unstaged) {
        char x = xy.charAt(0);
        char y = xy.charAt(1);
        if (x != UNCHANGED) {
            staged.add(new GitStatusEntry(path, xy, origPath));
        }
        if (y != UNCHANGED) {
            unstaged.add(new GitStatusEntry(path, xy, origPath));
        }
    }

    /** Parse a {@code # <key> <value>} header line; null if the line is not a well-formed header. */
    private static ParsedHeader parseHeader(String line) {
        // "# branch.head main" → key="branch.head", value="main".
        if (line.length() < 2 || line.charAt(1) != ' ') {
            return null;
        }
        String rest = line.substring(2);
        int sp = rest.indexOf(' ');
        if (sp < 0) {
            // A header key with no value (unusual); record the key with an empty value.
            return new ParsedHeader(rest, "");
        }
        return new ParsedHeader(rest.substring(0, sp), rest.substring(sp + 1));
    }

    /**
     * Parse the {@code +<ahead> -<behind>} value of a {@code branch.ab} header into {@code [ahead,
     * behind]}. Returns null on any malformation (tolerant — a malformed ab line leaves both
     * ahead/behind null rather than throwing).
     */
    private static int[] parseAheadBehind(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        Integer ahead = parseSigned(parts[0], '+');
        Integer behind = parseSigned(parts[1], '-');
        if (ahead == null || behind == null) {
            return null;
        }
        return new int[]{ahead, behind};
    }

    /** Parse a {@code <sign><digits>} token (e.g. {@code +3}, {@code -0}); null if malformed. */
    private static Integer parseSigned(String token, char expectedSign) {
        if (token.length() < 2 || token.charAt(0) != expectedSign) {
            return null;
        }
        try {
            return Integer.valueOf(token.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Return the {@code index}-th space-separated field of a line, or null if out of range. */
    private static String fieldAt(String line, int index) {
        int start = 0;
        int field = 0;
        int n = line.length();
        while (field < index && start < n) {
            int sp = line.indexOf(' ', start);
            if (sp < 0) {
                return null;
            }
            start = sp + 1;
            field++;
        }
        if (field != index || start >= n) {
            return null;
        }
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    /**
     * Return everything after the first {@code fieldCount} space-separated fields — the entry's
     * path payload, which may itself contain spaces (porcelain v2 does NOT quote paths in
     * {@code --porcelain=v2}; the path is simply the line tail). Null if the line has fewer than
     * {@code fieldCount} fields.
     */
    private static String restAfterFields(String line, int fieldCount) {
        int start = 0;
        int field = 0;
        int n = line.length();
        while (field < fieldCount && start < n) {
            int sp = line.indexOf(' ', start);
            if (sp < 0) {
                return null;
            }
            start = sp + 1;
            field++;
        }
        if (field != fieldCount) {
            return null;
        }
        return start <= n ? line.substring(start) : null;
    }

    /** The substring after the first space (for {@code ? <path>} and {@code ! <path>} lines). */
    private static String afterFirstSpace(String line) {
        int sp = line.indexOf(' ');
        if (sp < 0 || sp + 1 >= line.length()) {
            return null;
        }
        return line.substring(sp + 1);
    }

    /** A parsed {@code # <key> <value>} header line. */
    private record ParsedHeader(String key, String value) {
    }
}
