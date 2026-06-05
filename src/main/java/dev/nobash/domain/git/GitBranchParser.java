package dev.nobash.domain.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the output of {@code git branch --format=<FORMAT>} into a list of
 * {@link GitBranchEntry} records (PRD-002, issue #28). PURE and I/O-FREE: takes git's
 * machine-format stdout as an already-in-memory string and returns the parsed list — NO File,
 * NO Path, NO process (see the {@code the_git_domain_is_io_free} ArchUnit rule).
 *
 * <h3>Format contract</h3>
 * <p>The parser expects output produced by the format string:
 * {@link #FORMAT} — each branch occupies exactly one output line; fields within the line are
 * delimited by {@link #FIELD_SEP} (ASCII 31, unit separator). The four fields in order are:</p>
 * <ol>
 *   <li>{@code %(refname:short)} — the short branch name</li>
 *   <li>{@code %(HEAD)} — {@code *} when this branch is currently checked-out, space otherwise</li>
 *   <li>{@code %(upstream:short)} — the upstream ref in short form; empty when no upstream</li>
 *   <li>{@code %(upstream:track,nobracket)} — track info without brackets (e.g.
 *       {@code ahead 2, behind 1}); empty when up-to-date or no upstream</li>
 * </ol>
 *
 * <p>The unit-separator delimiter (ASCII 31) is chosen because it cannot appear in a git
 * branch name or upstream ref name — git itself rejects such names — making the parse
 * unambiguous without quoting.</p>
 *
 * <h3>Ahead/behind parsing</h3>
 * <p>The track field can contain:
 * <ul>
 *   <li>{@code ""} (empty) — upstream is up to date: ahead=0, behind=0</li>
 *   <li>{@code "ahead N"} — ahead=N, behind=0</li>
 *   <li>{@code "behind N"} — ahead=0, behind=N</li>
 *   <li>{@code "ahead M, behind N"} — ahead=M, behind=N</li>
 *   <li>{@code "gone"} — upstream is gone; treat as ahead=null, behind=null, upstream=null</li>
 * </ul>
 * When the upstream field itself is empty, upstream/ahead/behind are all null.</p>
 *
 * <h3>Robustness</h3>
 * <p>The parser NEVER throws on real git output. Malformed lines (too few or extra fields) are
 * skipped rather than throwing. An empty/null input yields an empty list.</p>
 *
 * <h3>Thread-safety</h3>
 * <p>The class is stateless and immutable; a single shared instance is safe for concurrent use.</p>
 */
public final class GitBranchParser {

    /** ASCII 31: unit separator — used as the field delimiter within each output line. */
    public static final char FIELD_SEP = (char) 0x1F;

    /**
     * The exact format string to pass to {@code git branch --format=<FORMAT>} to produce output
     * this parser can consume.
     *
     * <p>Fields: {@code refname:short} | {@code HEAD} | {@code upstream:short} |
     * {@code upstream:track,nobracket}. Each branch produces exactly one output line.</p>
     */
    public static final String FORMAT =
            "%(refname:short)" + FIELD_SEP
            + "%(HEAD)" + FIELD_SEP
            + "%(upstream:short)" + FIELD_SEP
            + "%(upstream:track,nobracket)";

    /** Pattern to extract the ahead count from the track field. */
    private static final Pattern AHEAD_PATTERN = Pattern.compile("ahead (\\d+)");

    /** Pattern to extract the behind count from the track field. */
    private static final Pattern BEHIND_PATTERN = Pattern.compile("behind (\\d+)");

    /**
     * Parse the git branch output into a list of {@link GitBranchEntry} records.
     *
     * @param branchOutput the stdout of {@code git branch --format=<FORMAT>}; null/blank → empty
     * @return the list of parsed branch entries, in the order git emits them; never null
     */
    public List<GitBranchEntry> parse(String branchOutput) {
        List<GitBranchEntry> entries = new ArrayList<>();
        if (branchOutput == null || branchOutput.isBlank()) {
            return entries;
        }

        for (String line : branchOutput.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            GitBranchEntry entry = parseLine(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Parse one output line into a {@link GitBranchEntry}. Returns null if the line is
     * malformed (not enough fields).
     */
    private static GitBranchEntry parseLine(String line) {
        // Split on FIELD_SEP into at most 4 parts (name, HEAD marker, upstream, track).
        String[] fields = line.split(String.valueOf(FIELD_SEP), 4);
        if (fields.length < 4) {
            // Tolerate lines with fewer fields but require at least name + HEAD.
            if (fields.length < 2) {
                return null;
            }
            String name = nullIfBlank(fields[0]);
            boolean current = fields[1].trim().equals("*");
            return new GitBranchEntry(name, current, null, null, null);
        }

        String name     = nullIfBlank(fields[0]);
        boolean current = fields[1].trim().equals("*");
        String upstream = nullIfBlank(fields[2]);
        String track    = fields[3].trim();

        // No upstream → upstream/ahead/behind all null.
        if (upstream == null) {
            return new GitBranchEntry(name, current, null, null, null);
        }

        // Upstream "gone" (remote tracking branch has been deleted) → treat as no upstream.
        if ("gone".equalsIgnoreCase(track)) {
            return new GitBranchEntry(name, current, null, null, null);
        }

        // Upstream present; parse track field for ahead/behind counts.
        Integer ahead  = parseTrackCount(track, AHEAD_PATTERN);
        Integer behind = parseTrackCount(track, BEHIND_PATTERN);

        return new GitBranchEntry(name, current, upstream,
                ahead  != null ? ahead  : 0,
                behind != null ? behind : 0);
    }

    /**
     * Extract the integer count for a track-field pattern (ahead or behind).
     * Returns null if the pattern does not match (i.e. count is zero or field is empty).
     */
    private static Integer parseTrackCount(String track, Pattern pattern) {
        if (track.isEmpty()) {
            return null;
        }
        Matcher m = pattern.matcher(track);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
