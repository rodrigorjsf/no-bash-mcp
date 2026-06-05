package dev.nobash.fixture;

import org.junit.jupiter.api.Test;

/**
 * Fixture test source: deliberately does NOT compile.
 * The undeclared method reference causes a compile error.
 * Used by InspectorAcceptanceIT to assert the REPORT_NOT_PRODUCED envelope.
 */
class CompileFailTest {

    @Test
    void this_will_not_compile() {
        // Deliberately reference an undefined method so the compiler fails.
        String result = UndefinedClass.undefinedMethod();
    }
}
