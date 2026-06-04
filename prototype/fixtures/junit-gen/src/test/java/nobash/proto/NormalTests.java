package nobash.proto;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Axes 1,2,3,4,7: a class (suite) with two-level identity (classname + name),
// a pass, an assertion failure (expected-vs-actual + file:line in the stacktrace),
// an errored test (exception, distinct from a failure), a skip, and a
// parametrized test (name[1], name[2] identity).
class NormalTests {

    @Test
    void addPasses() {
        assertEquals(5, 2 + 3);
    }

    @Test
    void addFailsAssertion() {
        // Surefire records message ("expected: <5> but was: <4>") + a stacktrace
        // whose first project frame carries file:line.
        assertEquals(5, 2 + 2);
    }

    @Test
    void throwsError() {
        throw new IllegalStateException("boom: not a test failure, an error");
    }

    @Disabled("not implemented on this platform")
    @Test
    void skipped() {
        assertEquals(1, 2);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void evenNumbers(int n) {
        // n=3 fails -> one parametrized invocation passes, one fails.
        assertEquals(0, n % 2, "n should be even");
    }
}
