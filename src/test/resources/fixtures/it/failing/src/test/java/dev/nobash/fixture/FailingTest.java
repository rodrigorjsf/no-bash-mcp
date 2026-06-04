package dev.nobash.fixture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture test: one passing test and one intentionally failing test.
 * Used by InspectorAcceptanceIT to assert the ok=false, failures[].kind envelope.
 */
class FailingTest {

    @Test
    void passing_assertion() {
        assertTrue(true);
    }

    @Test
    void intentionally_failing_assertion() {
        // This assertion is intentionally wrong to produce a test failure.
        assertEquals(1, 2, "intentional failure for fixture");
    }
}
