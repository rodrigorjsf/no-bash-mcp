package dev.nobash.domain.result;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the maven-compiler-plugin's structured console output into a list of
 * {@link CompileDiagnostic} entries (ADR-0009). PURE and I/O-FREE: it takes the combined
 * stdout+stderr as an already-in-memory string and returns the parsed diagnostics list —
 * NO File, NO Path read, NO directory walk, NO process (see the {@code the_result_domain_is_io_free}
 * ArchUnit rule in {@code ArchitectureTest}).
 *
 * <h3>Recognized line shape</h3>
 * <pre>
 *   [ERROR] &lt;file&gt;:[&lt;line&gt;,&lt;col&gt;] &lt;message&gt;
 *   [WARNING] &lt;file&gt;:[&lt;line&gt;,&lt;col&gt;] &lt;message&gt;
 * </pre>
 *
 * <p>Coordinates are locale-independent: {@code line} and {@code col} are parsed as plain
 * integers from the numeric tokens between {@code [} and {@code ]}. The {@code message} is
 * everything after the closing {@code ]} and one optional space, passed through verbatim as
 * untrusted content (P9 — the Envelope neutralizes it before returning to the agent).</p>
 *
 * <h3>Noise filtering</h3>
 * <p>Maven emits many {@code [ERROR]} and {@code [WARNING]} lines that are NOT compile
 * diagnostics (e.g. {@code [ERROR] BUILD FAILURE}, {@code [ERROR] -> [Help 1]},
 * {@code [ERROR] Failed to execute goal...}). This parser matches ONLY lines that contain
 * the {@code [<line>,<col>]} coordinate bracket immediately after the file path — lines
 * without this shape are silently dropped.</p>
 *
 * <h3>Thread-safety</h3>
 * <p>The class is stateless and immutable; a single shared instance is safe for concurrent use.</p>
 */
public final class CompileDiagnosticParser {

    /**
     * Pattern matching a maven-compiler-plugin diagnostic line:
     * <pre>
     *   [ERROR]&nbsp;&lt;file&gt;:[\d+,\d+] &lt;message&gt;
     * </pre>
     * Group 1: severity label (ERROR or WARNING, without brackets)
     * Group 2: file path (everything before the :[line,col])
     * Group 3: line number (decimal digits)
     * Group 4: column number (decimal digits)
     * Group 5: the rest of the line after the closing bracket (the message, may contain spaces)
     *
     * <p>The file path terminates at the mandatory colon-bracket prefix {@code :[}. The message
     * is optional (the compiler sometimes emits a bare location with no trailing text).</p>
     */
    private static final Pattern DIAGNOSTIC_LINE = Pattern.compile(
            "^\\[(ERROR|WARNING)\\]\\s+(.+):\\[(\\d+),(\\d+)\\]\\s?(.*)$"
    );

    /**
     * Parse all compiler diagnostics from the combined compiler output string.
     *
     * @param compilerOutput the combined stdout+stderr of the maven compile/test-compile run;
     *                       null is treated as empty
     * @return an immutable list of parsed {@link CompileDiagnostic} entries; empty when the
     *         output contains no recognized diagnostic lines
     */
    public List<CompileDiagnostic> parse(String compilerOutput) {
        if (compilerOutput == null || compilerOutput.isBlank()) {
            return List.of();
        }
        List<CompileDiagnostic> result = new ArrayList<>();
        for (String line : compilerOutput.split("\r?\n", -1)) {
            Matcher m = DIAGNOSTIC_LINE.matcher(line);
            if (m.matches()) {
                String severity = m.group(1);    // ERROR or WARNING
                String file     = m.group(2);    // file path (locale-independent path text)
                int lineNum     = Integer.parseInt(m.group(3));  // locale-independent digit parse
                int colNum      = Integer.parseInt(m.group(4));  // locale-independent digit parse
                String message  = m.group(5);    // verbatim, passed through as untrusted content
                result.add(new CompileDiagnostic(file, lineNum, colNum, severity, message));
            }
        }
        return List.copyOf(result);
    }
}
