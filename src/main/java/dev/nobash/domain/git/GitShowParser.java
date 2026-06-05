package dev.nobash.domain.git;

/**
 * Parses the output of {@code git show -s --format=<FORMAT>} into a {@link GitCommitDetail}
 * (PRD-002, issue #26). PURE and I/O-FREE: takes the git stdout as an already-in-memory
 * string — NO File, NO Path, NO process (see the {@code the_git_domain_is_io_free} ArchUnit rule).
 *
 * <h3>Format contract</h3>
 * <p>The parser expects output produced by the format string:
 * {@code %H%x1f%h%x1f%an%x1f%aI%x1f%s%x1f%b}
 * where {@code %x1f} (ASCII 31, unit separator US) delimits the fixed header fields from each
 * other and from the body. The body ({@code %b}) is the last field and may be multi-line; since
 * the body is the final field it is taken as everything after the fifth unit-separator.</p>
 *
 * <h3>Robustness</h3>
 * <p>The parser NEVER throws on real git output. Null/blank input returns a null-field record.
 * Missing fields (too few unit separators) yield null for the missing field.</p>
 *
 * <h3>Thread-safety</h3>
 * <p>The class is stateless and immutable; a single shared instance is safe for concurrent use.</p>
 */
public final class GitShowParser {

    /** ASCII 31: unit separator — used as the field delimiter. */
    private static final char UNIT_SEP = (char) 0x1F;

    /**
     * The exact format string to pass to {@code git show -s --format=<FORMAT>} to produce
     * output this parser can consume. Note: the body ({@code %b}) is the last field, so
     * multi-line bodies are captured in their entirety as the tail after the 5th UNIT_SEP.
     */
    public static final String FORMAT = "%H" + UNIT_SEP + "%h" + UNIT_SEP
            + "%an" + UNIT_SEP + "%aI" + UNIT_SEP + "%s" + UNIT_SEP + "%b";

    /**
     * Parse the git show metadata output into a {@link GitCommitDetail}.
     *
     * @param showOutput the stdout of {@code git show -s --format=<FORMAT>}; null/blank → null-field detail
     * @return the parsed commit detail; never null but fields may be null if output is missing/malformed
     */
    public GitCommitDetail parse(String showOutput) {
        if (showOutput == null || showOutput.isBlank()) {
            return new GitCommitDetail(null, null, null, null, null, null);
        }

        // Split on unit separator into at most 6 parts: sha, abbrev, author, dateIso, subject, body.
        // The body is the last part (may contain newlines and remaining unit separators from the message body).
        String[] fields = showOutput.split(String.valueOf(UNIT_SEP), 6);

        String sha     = fields.length > 0 ? nullIfBlank(fields[0].trim()) : null;
        String abbrev  = fields.length > 1 ? nullIfBlank(fields[1].trim()) : null;
        String author  = fields.length > 2 ? nullIfBlank(fields[2].trim()) : null;
        String dateIso = fields.length > 3 ? nullIfBlank(fields[3].trim()) : null;
        String subject = fields.length > 4 ? nullIfBlank(fields[4].trim()) : null;
        // The body is the raw remainder — may be blank if there's no message body.
        String body    = fields.length > 5 ? nullIfBlank(fields[5].trim()) : null;

        return new GitCommitDetail(sha, abbrev, author, dateIso, subject, body);
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
