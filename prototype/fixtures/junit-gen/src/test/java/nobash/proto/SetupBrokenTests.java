package nobash.proto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Axis 5 for JUnit: @BeforeAll throws, so the failure has no single test owner.
// Surefire reports a suite-level error/testcase that is not attributable to any
// one of the two tests below.
class SetupBrokenTests {

    @BeforeAll
    static void brokenSetup() {
        throw new IllegalStateException("fixture DB unavailable: simulated suite-level setup failure");
    }

    @Test
    void firstNeverRuns() {
        assertTrue(true);
    }

    @Test
    void secondNeverRuns() {
        assertTrue(true);
    }
}
