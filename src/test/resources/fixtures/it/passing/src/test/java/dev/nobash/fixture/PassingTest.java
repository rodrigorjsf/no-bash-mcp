package dev.nobash.fixture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture test: one passing test.
 * Used by InspectorAcceptanceIT to assert the ok=true counts-only envelope.
 */
class PassingTest {

    @Test
    void one_plus_one_is_two() {
        assertTrue(1 + 1 == 2);
    }
}
