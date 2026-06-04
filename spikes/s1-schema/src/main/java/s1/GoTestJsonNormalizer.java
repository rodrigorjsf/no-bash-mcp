package s1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds a real {@code go test -json} stream (JSON Lines) into a NormalizedRun.
 * COPIED VERBATIM from prototype/ — this is the artifact the spike falsifies.
 *
 * The spike feeds it (a) a multi-package report to stress the parent/child/package
 * dedup heuristic at scale, and (b) a compile-error report (Go 1.26 emits the build
 * failure as JSON build-output/build-fail events keyed by ImportPath, not Package).
 */
final class GoTestJsonNormalizer {

    private static final Pattern GO_SRC = Pattern.compile("([\\w./-]+\\.go):(\\d+)");
    private final ObjectMapper mapper = new ObjectMapper();

    private record Key(String pkg, String test) {}

    NormalizedRun normalize(Path jsonl) throws IOException {
        return normalize(Files.readString(jsonl));
    }

    /** {@code go test -json} writes its report to STDOUT, so the verb hands us the
     *  port-captured stdout string directly (no file involved). */
    NormalizedRun normalize(String stdout) throws IOException {
        Map<Key, StringBuilder> output = new LinkedHashMap<>();
        Map<Key, String> terminal = new LinkedHashMap<>(); // pass | fail | skip

        for (String line : stdout.split("\n")) {
            if (line.isBlank()) continue;
            JsonNode ev = mapper.readTree(line);
            Key k = new Key(ev.path("Package").asText(""), ev.path("Test").asText(""));
            switch (ev.path("Action").asText("")) {
                case "output" -> output.computeIfAbsent(k, x -> new StringBuilder())
                        .append(ev.path("Output").asText());
                case "pass", "fail", "skip" -> terminal.put(k, ev.path("Action").asText());
                default -> { /* run, start, pause, cont — ignored */ }
            }
        }

        Set<String> failingTests = new LinkedHashSet<>(); // "pkg test"
        terminal.forEach((k, status) -> {
            if (!k.test().isEmpty() && status.equals("fail")) failingTests.add(k.pkg() + " " + k.test());
        });

        List<Finding> findings = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;

        for (var e : terminal.entrySet()) {
            Key k = e.getKey();
            String status = e.getValue();
            String detail = output.getOrDefault(k, new StringBuilder()).toString();

            if (k.test().isEmpty()) { // package-level terminal
                boolean pkgHasFailingTest = failingTests.stream().anyMatch(s -> s.startsWith(k.pkg() + " "));
                if (status.equals("fail") && !pkgHasFailingTest) { // axis 5: no test owner
                    findings.add(new ContainerFinding(ContainerScope.PACKAGE, k.pkg(),
                            Outcome.FAILED, "fail", goMessage(detail), parseSrc(detail), detail));
                }
                continue;
            }

            switch (status) {
                case "pass" -> passed++;
                case "skip" -> skipped++;
                case "fail" -> {
                    boolean failedOnlyViaChild = failingTests.stream()
                            .anyMatch(s -> s.startsWith(k.pkg() + " " + k.test() + "/"));
                    if (failedOnlyViaChild) continue; // suppress redundant parent
                    failed++;
                    String[] segs = k.test().split("/");
                    String name = segs[segs.length - 1];
                    List<String> path = List.of(segs).subList(0, segs.length - 1);
                    findings.add(new TestFinding(k.pkg(), name, path, Outcome.FAILED, "fail",
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
            if (t.isEmpty() || t.startsWith("=== ") || t.startsWith("--- ")) continue;
            if (fallback == null) fallback = t;
            Matcher m = Pattern.compile("\\.go:\\d+:\\s*(.+)").matcher(t);
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
