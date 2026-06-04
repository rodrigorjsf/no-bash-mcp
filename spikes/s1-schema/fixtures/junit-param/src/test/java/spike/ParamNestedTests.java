package spike;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Parametrized identity AT SCALE — the axis-7 residual risk the spike must settle
 * before the schema freeze. Exercises: @ParameterizedTest index rendering, a custom
 * name= pattern, @Nested class nesting (Outer$Inner classname), @DisplayName
 * overrides, and a parametrized test INSIDE a @Nested class (deepest identity).
 * Several cases fail on purpose so the report carries real <failure> elements.
 */
@DisplayName("Parametrized + nested identity")
class ParamNestedTests {

    @Test
    void plainPass() {
        assertEquals(4, 2 + 2);
    }

    // Index rendering: Surefire names these isEven[1]..[4]; case 7 (index 3) fails.
    @ParameterizedTest
    @ValueSource(ints = {2, 4, 7, 8})
    void isEven(int n) {
        assertTrue(n % 2 == 0, "expected even but got " + n);
    }

    // Custom display-name pattern + CSV args; the "bad" row fails.
    @ParameterizedTest(name = "len({0})={1}")
    @CsvSource({"a,1", "bb,2", "ccc,9"})
    void wordLength(String word, int expected) {
        assertEquals(expected, word.length(), "length mismatch for " + word);
    }

    @Nested
    @DisplayName("when input is negative")
    class WhenNegative {

        @Test
        void failsDirectly() {
            fail("nested direct failure");
        }

        // Parametrized test inside a @Nested class — deepest identity. "long" fails.
        @ParameterizedTest
        @ValueSource(strings = {"x", "yy", "long"})
        void singleChar(String s) {
            assertEquals(1, s.length(), "not single char: " + s);
        }
    }
}
