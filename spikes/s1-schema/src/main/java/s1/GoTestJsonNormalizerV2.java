package s1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spike REMEDY for the compile-error signal-loss the verbatim normalizer surfaced.
 *
 * Go 1.26 emits a compile failure as JSON {@code build-output}/{@code build-fail}
 * events keyed by {@code ImportPath} (e.g. "pkg [pkg.test]"), NOT by {@code Package}.
 * The original normalizer dropped them, so the package ContainerFinding lost the
 * actual compiler error ("undefined: Heigth") and its file:line. This version folds
 * build-output text into the owning package's buffer (stripping the " [..test]"
 * suffix) and stamps rawStatus="build-fail" so the agent can tell a COMPILE failure
 * from a runtime init panic. The schema is UNCHANGED — only the Go-format knowledge
 * in this adapter grows. This is the fix to carry into the production Go normalizer.
 */
final class GoTestJsonNormalizerV2 {

    private static final Pattern GO_SRC = Pattern.compile("([\\w./-]+\\.go):(\\d+)");
    private final ObjectMapper mapper = new ObjectMapper();

    private record Key(String pkg, String test) {}

    /** "spike/compileerror/buggy [spike/compileerror/buggy.test]" -> "spike/compileerror/buggy" */
    private static String pkgOf(String importPath) {
        int sp = importPath.indexOf(" [");
        return sp >= 0 ? importPath.substring(0, sp) : importPath;
    }

    NormalizedRun normalize(String stdout) throws IOException {
        Map<Key, StringBuilder> output = new LinkedHashMap<>();
        Map<Key, String> terminal = new LinkedHashMap<>();
        Set<String> buildFailed = new LinkedHashSet<>(); // packages that failed to compile

        for (String line : stdout.split("\n")) {
            if (line.isBlank()) continue;
            JsonNode ev = mapper.readTree(line);
            String action = ev.path("Action").asText("");
            switch (action) {
                case "build-output" -> {
                    String pkg = pkgOf(ev.path("ImportPath").asText(""));
                    output.computeIfAbsent(new Key(pkg, ""), x -> new StringBuilder())
                            .append(ev.path("Output").asText());
                }
                case "build-fail" -> buildFailed.add(pkgOf(ev.path("ImportPath").asText("")));
                case "output" -> output.computeIfAbsent(
                                new Key(ev.path("Package").asText(""), ev.path("Test").asText("")),
                                x -> new StringBuilder())
                        .append(ev.path("Output").asText());
                case "pass", "fail", "skip" -> terminal.put(
                        new Key(ev.path("Package").asText(""), ev.path("Test").asText("")), action);
                default -> { /* run, start, pause, cont */ }
            }
        }

        Set<String> failingTests = new LinkedHashSet<>();
        terminal.forEach((k, status) -> {
            if (!k.test().isEmpty() && status.equals("fail")) failingTests.add(k.pkg() + " " + k.test());
        });

        List<Finding> findings = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;

        for (var e : terminal.entrySet()) {
            Key k = e.getKey();
            String status = e.getValue();
            String detail = output.getOrDefault(k, new StringBuilder()).toString();

            if (k.test().isEmpty()) {
                boolean pkgHasFailingTest = failingTests.stream().anyMatch(s -> s.startsWith(k.pkg() + " "));
                if (status.equals("fail") && !pkgHasFailingTest) {
                    boolean isBuild = buildFailed.contains(k.pkg());
                    findings.add(new ContainerFinding(ContainerScope.PACKAGE, k.pkg(),
                            isBuild ? Outcome.ERRORED : Outcome.FAILED,   // compile error = ERRORED, not a test FAIL
                            isBuild ? "build-fail" : "fail",
                            goMessage(detail), parseSrc(detail), detail));
                }
                continue;
            }
            switch (status) {
                case "pass" -> passed++;
                case "skip" -> skipped++;
                case "fail" -> {
                    if (failingTests.stream().anyMatch(s -> s.startsWith(k.pkg() + " " + k.test() + "/"))) continue;
                    failed++;
                    String[] segs = k.test().split("/");
                    findings.add(new TestFinding(k.pkg(), segs[segs.length - 1],
                            List.of(segs).subList(0, segs.length - 1), Outcome.FAILED, "fail",
                            goMessage(detail), parseSrc(detail), detail));
                }
                default -> { }
            }
        }
        return new NormalizedRun("go-test", new Summary(passed + failed + skipped, passed, failed, 0, skipped), findings);
    }

    private static String goMessage(String s) {
        if (s == null) return null;
        String fallback = null;
        for (String l : s.split("\n")) {
            String t = l.strip();
            if (t.isEmpty() || t.startsWith("=== ") || t.startsWith("--- ") || t.startsWith("# ")) continue;
            if (fallback == null) fallback = t;
            Matcher m = Pattern.compile("\\.go:\\d+:\\d*:?\\s*(.+)").matcher(t);
            if (m.find()) return m.group(1).strip();
        }
        return fallback;
    }

    private static SourceRef parseSrc(String detail) {
        if (detail == null) return null;
        Matcher m = GO_SRC.matcher(detail);
        return m.find() ? new SourceRef(m.group(1), Integer.valueOf(m.group(2))) : null;
    }
}
