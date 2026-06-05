package dev.nobash.domain.result;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds real Surefire JUnit-XML reports into a {@link NormalizedRun} (ADR-0007). PURE and
 * I/O-FREE: it takes report content already in memory (a {@code String} or {@code byte[]})
 * and parses it via {@link ByteArrayInputStream} — NO {@code File}, NO {@code Path} read,
 * NO directory walk, NO process, NO STDIO. Locating and reading the
 * {@code target/surefire-reports/} directory is a later slice's adapter concern (issue #4);
 * this is the deterministic heart that adapter will wrap.
 *
 * <p>Honors the frozen counting &amp; normalization rules (ADR-0007):</p>
 * <ul>
 *   <li><b>Rule 5</b> — counts derive from each {@code <testcase>}, never the
 *       {@code <testsuite tests=>} header (unreliable under {@code @Nested}). Identity:
 *       parametrized index folds into {@code name}, nested class into {@code suite}
 *       (the {@code classname} attribute).</li>
 *   <li><b>Rule 2</b> — {@code <failure>} → {@link Outcome#FAILED}; {@code <error>} →
 *       {@link Outcome#ERRORED}.</li>
 *   <li><b>Rule 1</b> — a {@code <testcase>} with a blank {@code name} (a setup failure
 *       with no single test owner, e.g. a {@code @BeforeAll} throw) becomes a
 *       {@link ContainerFinding} of scope {@link ContainerScope#SUITE}, never a degenerate
 *       empty-named {@link TestFinding}. Such a finding is NOT counted as a test.</li>
 * </ul>
 *
 * <p>{@code source} is best-effort: parsed from the stack trace when a frame matches the
 * owning {@code classname}, else null. This class imports only {@code java.*},
 * {@code javax.xml.parsers}/{@code org.w3c.dom}, and the result records — no
 * application/adapter dependency (ArchUnit domain purity).</p>
 */
public final class SurefireNormalizer {

    /** The Reporter name (CONTEXT.md) this normalizer folds into {@link NormalizedRun#tool()}. */
    private static final String TOOL = "surefire";

    /**
     * Fold a single in-memory Surefire report into a {@link NormalizedRun}.
     *
     * @param reportXml the full XML content of one {@code TEST-*.xml} report
     * @return the normalized run
     */
    public NormalizedRun normalize(String reportXml) {
        return normalizeAll(List.of(reportXml));
    }

    /**
     * Fold a list of pre-read in-memory Surefire reports into ONE {@link NormalizedRun}.
     * Under {@code @Nested}, Surefire splits one logical run across sibling files; folding
     * them together is how the per-{@code <testcase classname=>} identity is reconstructed
     * (ADR-0007 rule 5).
     *
     * @param reportXmls the XML contents of each {@code TEST-*.xml} report
     * @return the normalized run aggregating all reports
     */
    public NormalizedRun normalizeAll(List<String> reportXmls) {
        List<Finding> findings = new ArrayList<>();
        int passed = 0, failed = 0, errored = 0, skipped = 0;

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        for (String reportXml : reportXmls) {
            Document doc = parse(dbf, reportXml);
            NodeList suites = doc.getElementsByTagName("testsuite");
            for (int s = 0; s < suites.getLength(); s++) {
                Element suite = (Element) suites.item(s);
                NodeList cases = suite.getElementsByTagName("testcase");
                for (int c = 0; c < cases.getLength(); c++) {
                    Element tc = (Element) cases.item(c);
                    String name = tc.getAttribute("name");
                    String classname = tc.getAttribute("classname");
                    Element error = child(tc, "error");
                    Element failure = child(tc, "failure");
                    boolean noOwner = name == null || name.isBlank();

                    if (error != null) {
                        if (noOwner) {
                            findings.add(container(classname, Outcome.ERRORED, "error", error));
                        } else {
                            errored++;
                            findings.add(test(classname, name, Outcome.ERRORED, "error", error));
                        }
                    } else if (failure != null) {
                        if (noOwner) {
                            findings.add(container(classname, Outcome.FAILED, "failure", failure));
                        } else {
                            failed++;
                            findings.add(test(classname, name, Outcome.FAILED, "failure", failure));
                        }
                    } else if (child(tc, "skipped") != null) {
                        skipped++;
                    } else {
                        passed++;
                    }
                }
            }
        }
        int total = passed + failed + errored + skipped;
        return new NormalizedRun(TOOL, new Summary(total, passed, failed, errored, skipped), findings);
    }

    private static Document parse(DocumentBuilderFactory dbf, String reportXml) {
        try {
            DocumentBuilder builder = dbf.newDocumentBuilder();
            byte[] bytes = reportXml.getBytes(StandardCharsets.UTF_8);
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable Surefire report XML", e);
        }
    }

    private static TestFinding test(String classname, String name, Outcome o, String raw, Element el) {
        String detail = el.getTextContent();
        return new TestFinding(classname, name, List.of(), o, raw, msg(el), parseSrc(detail, classname), detail);
    }

    private static ContainerFinding container(String classname, Outcome o, String raw, Element el) {
        String detail = el.getTextContent();
        return new ContainerFinding(ContainerScope.SUITE, classname, o, raw, msg(el),
                parseSrc(detail, classname), detail);
    }

    private static String msg(Element el) {
        String m = el.getAttribute("message");
        return m == null || m.isBlank() ? null : m;
    }

    private static SourceRef parseSrc(String stack, String classname) {
        if (stack == null || classname == null || classname.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("at " + Pattern.quote(classname) + "\\.[\\w$<>]+\\(([^():]+):(\\d+)\\)")
                .matcher(stack);
        return m.find() ? new SourceRef(m.group(1), Integer.valueOf(m.group(2))) : null;
    }

    private static Element child(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        return n.getLength() > 0 ? (Element) n.item(0) : null;
    }
}
