package dev.nobash.domain.git;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the output of {@code git log --format=<FORMAT>} into a list of {@link GitCommit}
 * records (PRD-002, issue #26). PURE and I/O-FREE: takes the git stdout as an already-in-memory
 * string and returns the parsed list — NO File, NO Path, NO process (see the
 * {@code the_git_domain_is_io_free} ArchUnit rule).
 *
 * <h3>Format contract</h3>
 * <p>The parser expects output produced by the format string:
 * {@code %H%x1f%h%x1f%an%x1f%aI%x1f%s%x1e}
 * where {@code %x1f} (ASCII 31, unit separator US) delimits fields within a record, and
 * {@code %x1e} (ASCII 30, record separator RS) terminates each record. This scheme is chosen
 * because neither the unit separator nor the record separator appears in normal git content
 * (author names, subjects, dates, SHAs), giving a robust, locale-independent parse.</p>
 *
 * <h3>Robustness</h3>
 * <p>The parser NEVER throws on real git output. Malformed records (too few fields) yield a
 * partial {@link GitCommit} with null fields rather than an exception. An empty/null input
 * yields an empty list.</p>
 *
 * <h3>Thread-safety</h3>
 * <p>The class is stateless and immutable; a single shared instance is safe for concurrent use.</p>
 */
public final class GitLogParser {

    /** ASCII 31: unit separator — used as the field delimiter within a record. */
    private static final char UNIT_SEP = (char) 0x1F;

    /** ASCII 30: record separator — used as the record terminator. */
    private static final char RECORD_SEP = (char) 0x1E;

    /**
     * The exact format string to pass to {@code git log --format=<FORMAT>} to produce output
     * this parser can consume.
     */
    public static final String FORMAT = "%H" + UNIT_SEP + "%h" + UNIT_SEP
            + "%an" + UNIT_SEP + "%aI" + UNIT_SEP + "%s" + RECORD_SEP;

    /**
     * Parse the git log output into a list of {@link GitCommit} records.
     *
     * @param logOutput the stdout of {@code git log --format=<FORMAT>}; null or blank → empty list
     * @return the list of parsed commits, in log order (newest first, matching git's default)
     */
    public List<GitCommit> parse(String logOutput) {
        List<GitCommit> commits = new ArrayList<>();
        if (logOutput == null || logOutput.isBlank()) {
            return commits;
        }

        // Split on the record separator; each segment is one commit's field data.
        String[] records = logOutput.split(String.valueOf(RECORD_SEP), -1);
        for (String record : records) {
            String trimmed = record.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            commits.add(parseRecord(trimmed));
        }
        return commits;
    }

    /**
     * Parse a single record (one commit) split on the unit separator into up to 5 fields:
     * sha, abbrev, author, dateIso, subject. Missing fields yield null.
     */
    private static GitCommit parseRecord(String record) {
        String[] fields = record.split(String.valueOf(UNIT_SEP), -1);
        String sha     = fields.length > 0 ? nullIfBlank(fields[0]) : null;
        String abbrev  = fields.length > 1 ? nullIfBlank(fields[1]) : null;
        String author  = fields.length > 2 ? nullIfBlank(fields[2]) : null;
        String dateIso = fields.length > 3 ? nullIfBlank(fields[3]) : null;
        String subject = fields.length > 4 ? nullIfBlank(fields[4]) : null;
        return new GitCommit(sha, abbrev, author, dateIso, subject);
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
