package dev.nobash.domain.git;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the combined output of {@code git diff --numstat} and {@code git diff --name-status} into
 * a list of {@link GitDiffEntry} records (PRD-002, issue #27). PURE and I/O-FREE: takes both git
 * stdout strings as already-in-memory strings — NO File, NO Path, NO process (see the
 * {@code the_git_domain_is_io_free} ArchUnit rule).
 *
 * <h3>Parsing strategy</h3>
 * <p>Two git invocations (same scope arguments) are combined:</p>
 * <ol>
 *   <li>{@code --numstat} emits one line per file: {@code <added>\t<deleted>\t<path>} where
 *       binary files use {@code -} for both counts.</li>
 *   <li>{@code --name-status} emits one line per file: {@code <X><score?>\t<path>} (or
 *       {@code <X><score?>\t<oldPath>\t<newPath>} for renames/copies) where {@code X} is the
 *       single-letter status code ({@code M}, {@code A}, {@code D}, {@code R}, {@code C}, etc.).</li>
 * </ol>
 * <p>Both commands emit the same files in the same order for the same diff scope, so the result
 * list is assembled by position (zip). The path and status letter come from name-status; the
 * added/deleted counts come from numstat at the matching index. This avoids the rename-path
 * mismatch: numstat munges rename paths ({@code dir/{old => new}.txt}) while name-status gives a
 * clean two-field {@code newPath} for renames.</p>
 *
 * <h3>Robustness</h3>
 * <p>The parser NEVER throws on real git output. Null/blank inputs yield an empty list. If the two
 * lists differ in length (unexpected; e.g. git was inconsistent), the shorter drives the zip and
 * surplus entries are dropped. Binary file counts ({@code -\t-}) yield {@code null} {@code added}/
 * {@code deleted} in the resulting entry, not an exception.</p>
 *
 * <h3>Thread-safety</h3>
 * <p>The class is stateless and immutable; a single shared instance is safe for concurrent use.</p>
 */
public final class GitDiffParser {

    /**
     * Parse the combined numstat and name-status outputs into a list of {@link GitDiffEntry} records.
     *
     * @param numstatOutput    the stdout of {@code git diff --numstat <scope>}; null/blank → empty
     * @param nameStatusOutput the stdout of {@code git diff --name-status <scope>}; null/blank → empty
     * @return the combined list of diff entries, in order; never null, may be empty
     */
    public List<GitDiffEntry> parse(String numstatOutput, String nameStatusOutput) {
        List<NumstatRecord> numstatRecords = parseNumstat(numstatOutput);
        List<NameStatusRecord> nameStatusRecords = parseNameStatus(nameStatusOutput);

        int size = Math.min(numstatRecords.size(), nameStatusRecords.size());
        List<GitDiffEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            NumstatRecord ns = numstatRecords.get(i);
            NameStatusRecord nsr = nameStatusRecords.get(i);
            // Path from name-status (clean for renames); counts from numstat.
            entries.add(new GitDiffEntry(nsr.path(), ns.added(), ns.deleted(), nsr.status()));
        }
        return entries;
    }

    // ---- numstat parsing ----

    /**
     * Parse {@code git diff --numstat} output into a list of records. Each line has the format
     * {@code <added>\t<deleted>\t<path>} where {@code -} means binary.
     */
    private static List<NumstatRecord> parseNumstat(String output) {
        List<NumstatRecord> records = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return records;
        }
        for (String line : output.split("\r?\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            NumstatRecord r = parseNumstatLine(trimmed);
            if (r != null) {
                records.add(r);
            }
        }
        return records;
    }

    /**
     * Parse one {@code --numstat} line: {@code <added>\t<deleted>\t<path>}.
     * Returns null if the line is malformed.
     */
    private static NumstatRecord parseNumstatLine(String line) {
        // Split on the first two TABs only.
        int tab1 = line.indexOf('\t');
        if (tab1 < 0) return null;
        int tab2 = line.indexOf('\t', tab1 + 1);
        if (tab2 < 0) return null;

        String addedStr = line.substring(0, tab1);
        String deletedStr = line.substring(tab1 + 1, tab2);
        // Path is the remainder after the second TAB (may itself contain TABs for renames, ignored here).
        // We only use the numstat for its counts; path comes from name-status.
        Integer added = parseBinaryAwareCount(addedStr);
        Integer deleted = parseBinaryAwareCount(deletedStr);
        return new NumstatRecord(added, deleted);
    }

    /**
     * Parse a count field from {@code --numstat}: an integer or {@code -} for binary files.
     * Returns null for {@code -} or any non-integer; never throws.
     */
    private static Integer parseBinaryAwareCount(String s) {
        if (s == null || s.equals("-")) return null;
        try {
            return Integer.valueOf(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- name-status parsing ----

    /**
     * Parse {@code git diff --name-status} output into a list of records. Each line has the format:
     * <ul>
     *   <li>{@code <X>\t<path>} for M/A/D/T/U/X</li>
     *   <li>{@code <X><score>\t<oldPath>\t<newPath>} for R/C (renames/copies)</li>
     * </ul>
     * where {@code X} is the status letter and {@code score} is a similarity percentage (optional).
     */
    private static List<NameStatusRecord> parseNameStatus(String output) {
        List<NameStatusRecord> records = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return records;
        }
        for (String line : output.split("\r?\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            NameStatusRecord r = parseNameStatusLine(trimmed);
            if (r != null) {
                records.add(r);
            }
        }
        return records;
    }

    /**
     * Parse one {@code --name-status} line into a status letter and path. For renames/copies,
     * the new path (second path) is used as the entry's path.
     */
    private static NameStatusRecord parseNameStatusLine(String line) {
        if (line.isEmpty()) return null;

        int tab1 = line.indexOf('\t');
        if (tab1 < 0) return null;

        // The first field is the status code + optional score (e.g. "M", "R100", "C75").
        String statusField = line.substring(0, tab1);
        if (statusField.isEmpty()) return null;
        // Status letter is always the first character.
        String status = String.valueOf(statusField.charAt(0));

        // For renames/copies (R or C), the path is the second TAB-delimited field (new path).
        char statusChar = statusField.charAt(0);
        String path;
        if (statusChar == 'R' || statusChar == 'C') {
            int tab2 = line.indexOf('\t', tab1 + 1);
            if (tab2 >= 0 && tab2 + 1 < line.length()) {
                path = line.substring(tab2 + 1);
            } else {
                // Fallback: only one path field — use it.
                path = line.substring(tab1 + 1);
            }
        } else {
            path = line.substring(tab1 + 1);
        }

        return nullIfBlankPath(path) != null ? new NameStatusRecord(status, path) : null;
    }

    private static String nullIfBlankPath(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ---- private record types (package-private visibility for test access) ----

    /** A parsed {@code --numstat} record: just the counts (path comes from name-status). */
    record NumstatRecord(Integer added, Integer deleted) {
    }

    /** A parsed {@code --name-status} record: status letter + canonical path. */
    record NameStatusRecord(String status, String path) {
    }
}
