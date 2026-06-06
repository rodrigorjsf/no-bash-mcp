package dev.nobash.domain.result;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds a real {@code jest --json} report into a {@link NormalizedRun} (ADR-0007). PURE and
 * I/O-FREE: it takes the report JSON already in memory (a {@code String} read from the fresh
 * {@code --outputFile} by the adapter) and parses it — NO {@code File}, NO {@code Path} read, NO
 * directory walk, NO process, NO STDIO. Reading the report (jest writes it to the injected
 * {@code --outputFile}, so the adapter hands the file content straight here) is the adapter's
 * concern; this is the deterministic heart the {@code NodeEcosystemAdapter} wraps — the jest
 * analogue of {@link SurefireNormalizer} and {@link GoTestJsonParser}.
 *
 * <p>Unlike {@code go test -json} (flat NDJSON, one object per line), a jest report is ONE big
 * JSON object with NESTED arrays ({@code testResults[]}, {@code assertionResults[]},
 * {@code failureMessages[]}) and nested objects ({@code location{line,column}}). To keep the
 * domain dependency-free (micronaut-serde provides only the Jackson <em>annotations</em>, not an
 * {@code ObjectMapper}), the report is parsed with a small, escape-aware, brace/bracket-balanced
 * scanner — no JSON library. The Go flat-line scanner is NOT reusable verbatim; this one walks the
 * {@code testResults[]} array, then each element's {@code assertionResults[]} array, extracting
 * each field WITHIN the bounds of the current element (never a global {@code indexOf}, which would
 * pick up stray {@code line}/numbers from {@code failureMessages} or snapshot blocks).</p>
 *
 * <p>Counting &amp; identity rules (ADR-0007), keyed off the jest grammar (jest 30.4.1):</p>
 * <ul>
 *   <li><b>Per-assertion outcome</b> — counts derive from {@code assertionResults[].status}, NEVER
 *       from the top-level {@code numPassedTests}/{@code numFailedTests} header (ADR-0007
 *       counts-from-elements). {@code passed → passed++}; {@code failed → failed++} with a
 *       {@link TestFinding}{@code (FAILED, "failed")}; {@code pending}/{@code todo}/{@code skipped}
 *       → {@code skipped++} (excluded from {@code executedTests} downstream, D29).</li>
 *   <li><b>Identity</b> (axes 1 &amp; 7) — {@code suite} is the test-file basename
 *       ({@code testResults[].name}); {@code name} is the assertion {@code title}; the
 *       {@code ancestorTitles[]} ({@code describe} nesting) fold into the flexible {@code path[]}.
 *       {@code source} is best-effort: {@code SourceRef(fileBasename, location.line)} ONLY when
 *       {@code location} is present (no {@code --testLocationInResults} → {@code location:null} →
 *       file-only ref, never an NPE); the column is dropped ({@code SourceRef} has no column).</li>
 *   <li><b>No-test-owner / module-load failure</b> (axis 5, ADR-0007 rule 4) — a
 *       {@code testResults[]} file element with {@code status:"failed"} AND an EMPTY
 *       {@code assertionResults} becomes a {@link ContainerFinding}{@code (FILE, ERRORED)} carrying
 *       the file-level {@code message} ({@code "● Test suite failed to run …"}) and a best-effort
 *       {@link SourceRef} parsed from that message. The discriminator is the EMPTY
 *       {@code assertionResults}, NEVER {@code testExecError} (which is {@code null} in jest 30 for
 *       a top-level throw). It is EXCLUDED from the test counts, so a module-load-only run is
 *       {@code executedTests==0} yet NOT ok — the floor's G5 keystone.</li>
 * </ul>
 *
 * <p>This class imports only {@code java.*} and the result records — no application/adapter
 * dependency, no {@code java.nio.file}/{@code java.io.File}/{@code Process} (ArchUnit domain
 * I/O-freedom, {@code the_result_domain_is_io_free}).</p>
 */
public final class JestJsonParser {

    /** The Reporter name (CONTEXT.md) this parser folds into {@link NormalizedRun#tool()}. */
    private static final String TOOL = "jest";

    /** A {@code file.ext:line[:col]} anywhere in a message (the column, if any, is ignored). */
    private static final Pattern SRC = Pattern.compile("([\\w./\\\\-]+\\.[a-zA-Z]+):(\\d+)");

    /** A jest code-frame source row: a line number then a {@code |} gutter (e.g. {@code "2 | throw …"}). */
    private static final Pattern CODE_FRAME_ROW = Pattern.compile("^>?\\s*\\d+\\s*\\|");

    /**
     * Fold a full {@code jest --json} report into one {@link NormalizedRun}.
     *
     * @param report the raw jest {@code --outputFile} JSON content (in memory); null/blank → empty run
     * @return the normalized run
     */
    public NormalizedRun parse(String report) {
        List<Finding> findings = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;

        if (report != null && !report.isBlank()) {
            for (String fileObj : arrayElements(report, "testResults")) {
                String fileName = baseName(stringField(fileObj, "name"));
                String fileStatus = stringField(fileObj, "status");
                String fileMessage = stringField(fileObj, "message");
                List<String> assertions = arrayElements(fileObj, "assertionResults");

                if (assertions.isEmpty() && "failed".equals(fileStatus)) {
                    // No-test-owner (axis 5): a module-load / collection failure. Keyed on EMPTY
                    // assertionResults, NEVER testExecError (null in jest 30). The message is the
                    // ACTUAL error (e.g. "module load boom"), skipping jest's generic
                    // "● Test suite failed to run" wrapper; the full file message is the detail.
                    findings.add(new ContainerFinding(ContainerScope.FILE, fileName, Outcome.ERRORED,
                            "failed", containerMessage(fileMessage), parseSrc(fileMessage), fileMessage));
                    continue;
                }

                for (String assertion : assertions) {
                    String status = stringField(assertion, "status");
                    if (status == null) {
                        continue;
                    }
                    switch (status) {
                        case "passed" -> passed++;
                        case "pending", "todo", "skipped", "disabled" -> skipped++;
                        case "failed" -> {
                            failed++;
                            String name = stringField(assertion, "title");
                            List<String> path = stringArray(assertion, "ancestorTitles");
                            List<String> failureMessages = stringArray(assertion, "failureMessages");
                            String message = failureMessages.isEmpty() ? null : failureMessages.get(0);
                            String detail = failureMessages.isEmpty() ? null
                                    : String.join("\n", failureMessages);
                            findings.add(new TestFinding(fileName, name, path, Outcome.FAILED,
                                    "failed", message, locationSrc(assertion, fileName), detail));
                        }
                        default -> {
                            // any other jest status contributes no count — defensive, jest emits the above
                        }
                    }
                }
            }
        }

        int total = passed + failed + skipped;
        return new NormalizedRun(TOOL, new Summary(total, passed, failed, 0, skipped), findings);
    }

    /**
     * The best-effort {@link SourceRef} for a failing assertion: {@code file = } the test-file
     * basename, {@code line = location.line} ONLY when {@code assertionResults[].location} is a
     * present object (a {@code location:null} — no {@code --testLocationInResults} — yields a
     * file-only ref, never an NPE; the column is dropped). Extracted WITHIN the assertion's bounds.
     */
    private static SourceRef locationSrc(String assertion, String fileName) {
        Integer line = locationLine(assertion);
        if (line == null && fileName == null) {
            return null;
        }
        return new SourceRef(fileName, line);
    }

    /**
     * The {@code location.line} of one assertion element, or {@code null} when {@code location} is
     * {@code null} / absent. Bounded to the {@code location} object so a stray {@code "line"} in a
     * {@code failureMessages} string can never be picked up.
     */
    private static Integer locationLine(String assertion) {
        int key = indexOfField(assertion, "location");
        if (key < 0) {
            return null;
        }
        int colon = assertion.indexOf(':', key);
        if (colon < 0) {
            return null;
        }
        int p = skipWs(assertion, colon + 1);
        if (p >= assertion.length() || assertion.charAt(p) != '{') {
            return null;   // location:null (or any non-object) → no line
        }
        int end = matchBrace(assertion, p);
        if (end < 0) {
            return null;
        }
        return intField(assertion.substring(p, end + 1), "line");
    }

    /**
     * Split a JSON array value (the value of {@code key} within {@code obj}) into its top-level
     * OBJECT elements, each returned as the raw substring spanning its braces. Elements that are
     * not objects (e.g. the strings of {@code failureMessages}) are skipped — this is used only for
     * the object arrays {@code testResults[]} / {@code assertionResults[]}. Bracket/brace-balanced
     * and string-escape-aware, so nested arrays/objects inside an element never split it.
     */
    private static List<String> arrayElements(String obj, String key) {
        List<String> elements = new ArrayList<>();
        int key1 = indexOfField(obj, key);
        if (key1 < 0) {
            return elements;
        }
        int colon = obj.indexOf(':', key1);
        if (colon < 0) {
            return elements;
        }
        int p = skipWs(obj, colon + 1);
        if (p >= obj.length() || obj.charAt(p) != '[') {
            return elements;   // not an array (or null)
        }
        int arrEnd = matchBracket(obj, p);
        if (arrEnd < 0) {
            return elements;
        }
        // Walk the array body, collecting each balanced {...} element.
        int i = p + 1;
        while (i < arrEnd) {
            char c = obj.charAt(i);
            if (c == '{') {
                int objEnd = matchBrace(obj, i);
                if (objEnd < 0 || objEnd > arrEnd) {
                    break;
                }
                elements.add(obj.substring(i, objEnd + 1));
                i = objEnd + 1;
            } else {
                i++;
            }
        }
        return elements;
    }

    /**
     * The string elements of a JSON array of strings (the value of {@code key} within {@code obj})
     * — used for {@code ancestorTitles[]} and {@code failureMessages[]}. Bracket-bounded and
     * escape-aware: a {@code ]} inside a string never ends the array.
     */
    private static List<String> stringArray(String obj, String key) {
        List<String> out = new ArrayList<>();
        int key1 = indexOfField(obj, key);
        if (key1 < 0) {
            return out;
        }
        int colon = obj.indexOf(':', key1);
        if (colon < 0) {
            return out;
        }
        int p = skipWs(obj, colon + 1);
        if (p >= obj.length() || obj.charAt(p) != '[') {
            return out;
        }
        int arrEnd = matchBracket(obj, p);
        if (arrEnd < 0) {
            return out;
        }
        int i = p + 1;
        while (i < arrEnd) {
            char c = obj.charAt(i);
            if (c == '"') {
                int closingQuote = endOfString(obj, i);
                out.add(unescape(obj, i + 1, closingQuote));
                i = closingQuote + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * The decoded value of a top-level string field {@code key} directly within {@code obj}, or
     * {@code null} when absent / not a string. "Top-level" = a key whose preceding context is at
     * brace-depth 0 of {@code obj}; {@link #indexOfField} guarantees we match a real key, not a
     * substring inside a nested string/value.
     */
    private static String stringField(String obj, String key) {
        int key1 = indexOfField(obj, key);
        if (key1 < 0) {
            return null;
        }
        int colon = obj.indexOf(':', key1);
        if (colon < 0) {
            return null;
        }
        int p = skipWs(obj, colon + 1);
        if (p >= obj.length() || obj.charAt(p) != '"') {
            return null;   // not a string value (number / null / object)
        }
        int end = endOfString(obj, p);
        return unescape(obj, p + 1, end);
    }

    /** The integer value of a top-level numeric field {@code key} within {@code obj}, or null. */
    private static Integer intField(String obj, String key) {
        int key1 = indexOfField(obj, key);
        if (key1 < 0) {
            return null;
        }
        int colon = obj.indexOf(':', key1);
        if (colon < 0) {
            return null;
        }
        int p = skipWs(obj, colon + 1);
        int start = p;
        if (p < obj.length() && (obj.charAt(p) == '-' || obj.charAt(p) == '+')) {
            p++;
        }
        int digitsStart = p;
        while (p < obj.length() && Character.isDigit(obj.charAt(p))) {
            p++;
        }
        if (p == digitsStart) {
            return null;   // no digits (value was null / non-numeric)
        }
        try {
            return Integer.valueOf(obj.substring(start, p));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The index of the OPENING quote of the key {@code "key"} at brace/bracket-depth 0 of
     * {@code obj} (i.e. a direct field of this object, not a key nested inside a child object or a
     * substring inside a string value). Returns -1 when the key is not a direct field. Scanning
     * tracks string state and depth so {@code "name"} inside a {@code failureMessages} string or a
     * nested {@code matcherResult} object is never mistaken for the field.
     */
    private static int indexOfField(String obj, String key) {
        String needle = "\"" + key + "\"";
        int depth = 0;
        int i = 0;
        int n = obj.length();
        while (i < n) {
            char c = obj.charAt(i);
            if (c == '"') {
                int end = endOfString(obj, i);
                if (depth == 1 && obj.regionMatches(i, needle, 0, needle.length())) {
                    // A key candidate at depth 1 (direct field): the very next non-ws char is ':'.
                    int after = skipWs(obj, end + 1);
                    if (after < n && obj.charAt(after) == ':') {
                        return i;
                    }
                }
                i = end + 1;
            } else if (c == '{' || c == '[') {
                depth++;
                i++;
            } else if (c == '}' || c == ']') {
                depth--;
                i++;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** The index of the CLOSING quote of the JSON string whose opening quote is at {@code open}. */
    private static int endOfString(String s, int open) {
        int i = open + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return n - 1;
    }

    /** The index of the matching {@code '}'} for the {@code '{'} at {@code open}, escape-aware. */
    private static int matchBrace(String s, int open) {
        return matchPair(s, open, '{', '}');
    }

    /** The index of the matching {@code ']'} for the {@code '['} at {@code open}, escape-aware. */
    private static int matchBracket(String s, int open) {
        return matchPair(s, open, '[', ']');
    }

    /**
     * The index of the structural closer matching the opener at {@code open}, ignoring openers /
     * closers that appear inside JSON strings (escape-aware). Counts BOTH {@code {}} and {@code []}
     * nesting so a mixed nest (an object holding an array holding objects) closes correctly.
     */
    private static int matchPair(String s, int open, char openCh, char closeCh) {
        int depthCurly = 0;
        int depthSquare = 0;
        int i = open;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                i = endOfString(s, i) + 1;
                continue;
            }
            if (c == '{') {
                depthCurly++;
            } else if (c == '}') {
                depthCurly--;
            } else if (c == '[') {
                depthSquare++;
            } else if (c == ']') {
                depthSquare--;
            }
            // The opener's own pair is balanced when BOTH depths return to zero AND we are at the
            // matching closer character.
            if (c == closeCh && depthCurly == 0 && depthSquare == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** Skip ASCII whitespace from {@code p}; return the first non-ws index (or {@code s.length()}). */
    private static int skipWs(String s, int p) {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
            p++;
        }
        return p;
    }

    /**
     * Decode the JSON-escaped substring of {@code s} between {@code from} (just past the opening
     * quote) and {@code closingQuote} (the closing quote index), handling the standard escapes jest
     * emits (newline, tab, quote, backslash, slash, and the four-hex unicode form).
     */
    private static String unescape(String s, int from, int closingQuote) {
        StringBuilder sb = new StringBuilder(closingQuote - from);
        int p = from;
        while (p < closingQuote) {
            char c = s.charAt(p);
            if (c == '\\' && p + 1 < closingQuote) {
                char esc = s.charAt(p + 1);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '/' -> sb.append('/');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'u' -> {
                        if (p + 6 <= closingQuote) {
                            String hex = s.substring(p + 2, p + 6);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                p += 4;
                            } catch (NumberFormatException e) {
                                sb.append(esc);
                            }
                        } else {
                            sb.append(esc);
                        }
                    }
                    default -> sb.append(esc);
                }
                p += 2;
            } else {
                sb.append(c);
                p++;
            }
        }
        return sb.toString();
    }

    /** The basename of a (possibly absolute, possibly null) file path; null stays null. */
    private static String baseName(String path) {
        if (path == null) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * The human headline of a jest file-level {@code message} for a module-load failure: the ACTUAL
     * thrown error, skipping jest's generic {@code "● Test suite failed to run"} wrapper and the
     * code-frame lines (the numbered {@code "  N | …"} / {@code "> N | …"} / caret rows and the
     * {@code "at …"} stack). Falls back to the first non-blank line. The full message is retained
     * as the finding's {@code detail}. Null stays null.
     */
    private static String containerMessage(String message) {
        if (message == null) {
            return null;
        }
        String fallback = null;
        for (String raw : message.split("\n")) {
            String t = raw.strip();
            if (t.startsWith("●")) {
                t = t.substring(1).strip();
            }
            if (t.isEmpty()) {
                continue;
            }
            if (fallback == null) {
                fallback = t;
            }
            // Skip jest's generic wrapper headline and the code-frame / stack rows; surface the
            // actual error line (e.g. "module load boom").
            if (t.equalsIgnoreCase("Test suite failed to run")
                    || t.startsWith("at ")
                    || t.startsWith(">")
                    || t.startsWith("|")
                    || CODE_FRAME_ROW.matcher(t).find()) {
                continue;
            }
            return t;
        }
        return fallback;
    }

    /** Best-effort {@link SourceRef} from the first {@code file.ext:line} in a message; null if none. */
    private static SourceRef parseSrc(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = SRC.matcher(message);
        return m.find() ? new SourceRef(baseName(m.group(1)), Integer.valueOf(m.group(2))) : null;
    }
}
