package proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds a real {@code jest --json} report into a NormalizedRun. ALL jest-specific
 * knowledge lives here.
 *
 * Hard cases handled: identity is file -> ancestorTitles[] -> title, arbitrary
 * describe nesting (axis 1); file:line is buried in the stack string of
 * failureMessages[] (axis 3); message+diff+stack arrive as ONE string (axis 4);
 * axis 5 (no test owner) is a COLLECTION/module-load failure -> assertionResults
 * is empty + a file-level message -> ContainerFinding (verified against real
 * jest 29.7: a beforeAll throw is instead attributed per-test, NOT a no-owner
 * case); test.each titles are interpolated into the title (axis 7).
 */
final class JestJsonNormalizer {

    // matches "(/path/file.test.js:14:21)" or bare "/path/file.test.js:14:21"
    private static final Pattern JS_SRC = Pattern.compile("([^()\\s]+\\.[jt]sx?):(\\d+):\\d+");
    private final ObjectMapper mapper = new ObjectMapper();

    NormalizedRun normalize(Path json) throws IOException {
        JsonNode root = mapper.readTree(json.toFile());
        List<Finding> findings = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;

        for (JsonNode file : root.path("testResults")) {
            String suite = basename(file.path("name").asText(""));
            JsonNode assertions = file.path("assertionResults");
            String fileMsg = file.path("message").asText("");

            if (!assertions.isArray() || assertions.isEmpty()) {
                // axis 5: file-level failure, no test owner (beforeAll threw)
                if (file.path("status").asText("").equals("failed") && !fileMsg.isBlank()) {
                    findings.add(new ContainerFinding(ContainerScope.FILE, suite, Outcome.FAILED, "failed",
                            firstMeaningfulLine(fileMsg), parseSrc(fileMsg, suite), fileMsg));
                }
                continue;
            }

            for (JsonNode a : assertions) {
                switch (a.path("status").asText("")) {
                    case "passed" -> passed++;
                    case "pending", "skipped", "todo", "disabled" -> skipped++;
                    case "failed" -> {
                        failed++;
                        List<String> path = new ArrayList<>();
                        a.path("ancestorTitles").forEach(t -> path.add(t.asText()));
                        String detail = join(a.path("failureMessages"));
                        findings.add(new TestFinding(suite, a.path("title").asText(""), List.copyOf(path),
                                Outcome.FAILED, "failed", firstMeaningfulLine(detail), parseSrc(detail, suite), detail));
                    }
                    default -> { }
                }
            }
        }
        return new NormalizedRun("jest", new Summary(passed + failed + skipped, passed, failed, 0, skipped), findings);
    }

    private static String basename(String p) {
        int i = p.replace('\\', '/').lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String join(JsonNode arr) {
        StringBuilder b = new StringBuilder();
        if (arr.isArray()) for (JsonNode n : arr) {
            if (b.length() > 0) b.append("\n");
            b.append(n.asText());
        }
        return b.toString();
    }

    private static String firstMeaningfulLine(String s) {
        if (s == null) return null;
        for (String l : s.split("\n")) {
            String t = l.strip().replaceAll("^[●✕✓•]\\s*", "");
            // skip blanks, stack frames, and jest's generic section header
            if (t.isEmpty() || t.startsWith("at ") || t.equals("Test suite failed to run")) continue;
            return t;
        }
        return null;
    }

    /** First frame in the test file, else the first frame anywhere (axis 3). */
    private static SourceRef parseSrc(String stack, String preferFile) {
        if (stack == null) return null;
        Matcher m = JS_SRC.matcher(stack);
        SourceRef first = null;
        while (m.find()) {
            SourceRef ref = new SourceRef(m.group(1), Integer.valueOf(m.group(2)));
            if (first == null) first = ref;
            if (m.group(1).endsWith(preferFile)) return ref;
        }
        return first;
    }
}
