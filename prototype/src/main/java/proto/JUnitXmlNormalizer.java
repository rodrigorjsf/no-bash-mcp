package proto;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds real Surefire JUnit-XML reports (one file per suite) into a NormalizedRun.
 * ALL JUnit-specific knowledge lives here.
 *
 * Hard cases handled: {@code <error>} vs {@code <failure>} as distinct outcomes
 * (axis 2); file:line parsed from the stacktrace frame matching the testcase's
 * classname, NOT the first frame which is JUnit internals (axis 3); a
 * {@code <testcase name="">} carrying the @BeforeAll error = a suite-level
 * failure with no test owner = ContainerFinding (axis 5); parametrized identity
 * rendered into the name {@code evenNumbers(int)[2]} (axis 7).
 */
final class JUnitXmlNormalizer {

    NormalizedRun normalize(Path reportsDir) throws Exception {
        List<Finding> findings = new ArrayList<>();
        int passed = 0, failed = 0, errored = 0, skipped = 0;

        var dbf = DocumentBuilderFactory.newInstance();
        try (var stream = Files.newDirectoryStream(reportsDir, "TEST-*.xml")) {
            for (Path xml : stream) {
                Document doc = dbf.newDocumentBuilder().parse(xml.toFile());
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
                            // containers are NOT tests -> not counted (consistent with jest/Go)
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
        }
        return new NormalizedRun("maven-surefire",
                new Summary(passed + failed + errored + skipped, passed, failed, errored, skipped), findings);
    }

    private static TestFinding test(String classname, String name, Outcome o, String raw, Element el) {
        String detail = el.getTextContent();
        return new TestFinding(classname, name, List.of(), o, raw, msg(el), parseSrc(detail, classname), detail);
    }

    private static ContainerFinding container(String classname, Outcome o, String raw, Element el) {
        String detail = el.getTextContent();
        return new ContainerFinding(ContainerScope.SUITE, classname, o, raw, msg(el), parseSrc(detail, classname), detail);
    }

    private static String msg(Element el) {
        String m = el.getAttribute("message");
        return m == null || m.isBlank() ? null : m;
    }

    /** file:line from the FIRST stack frame whose class matches the testcase's
     *  classname (the project frame), skipping JUnit/JDK frames above it. */
    private static SourceRef parseSrc(String stack, String classname) {
        if (stack == null || classname.isBlank()) return null;
        Matcher m = Pattern.compile("at " + Pattern.quote(classname) + "\\.[\\w$<>]+\\(([^():]+):(\\d+)\\)").matcher(stack);
        return m.find() ? new SourceRef(m.group(1), Integer.valueOf(m.group(2))) : null;
    }

    private static Element child(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        return n.getLength() > 0 ? (Element) n.item(0) : null;
    }
}
