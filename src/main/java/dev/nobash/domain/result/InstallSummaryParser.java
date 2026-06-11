package dev.nobash.domain.result;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@link InstallSummary} from {@code npm install} stdout (PRD-3, slice 3).
 *
 * <p>npm prints a summary line such as:</p>
 * <pre>
 *   added 12 packages, removed 3 packages, changed 1 package, and audited 42 packages in 2s
 *   added 5 packages in 1s
 *   up to date, audited 42 packages in 1s
 * </pre>
 *
 * <p>The parser extracts the {@code added}, {@code removed}, and {@code changed} counts
 * defensively — each defaults to zero when not present in the output (e.g. a no-op install
 * that produces only "up to date" will yield {@code InstallSummary(0,0,0)}). The parsing is
 * best-effort: any format the parser does not recognise falls back to
 * {@link InstallSummary#EMPTY} and the install is still reported as {@code ok=true}.</p>
 *
 * <p>Pure domain — no I/O, no process interaction; consumes already-captured npm stdout.</p>
 */
public final class InstallSummaryParser {

    private static final Pattern ADDED   = Pattern.compile("\\badded\\s+(\\d+)\\s+package");
    private static final Pattern REMOVED = Pattern.compile("\\bremoved\\s+(\\d+)\\s+package");
    private static final Pattern CHANGED = Pattern.compile("\\bchanged\\s+(\\d+)\\s+package");

    private InstallSummaryParser() {
    }

    /**
     * Parse the {@link InstallSummary} from npm stdout.
     *
     * @param npmStdout the captured standard output of an {@code npm install} invocation;
     *                  {@code null} or blank is treated as empty output (all zeros)
     * @return the parsed counts; never {@code null}
     */
    public static InstallSummary parse(String npmStdout) {
        if (npmStdout == null || npmStdout.isBlank()) {
            return InstallSummary.EMPTY;
        }
        int added   = extractCount(ADDED,   npmStdout);
        int removed = extractCount(REMOVED, npmStdout);
        int changed = extractCount(CHANGED, npmStdout);
        return new InstallSummary(added, removed, changed);
    }

    private static int extractCount(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
