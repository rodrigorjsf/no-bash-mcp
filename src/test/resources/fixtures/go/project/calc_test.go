package calc

import "testing"

// TestAdd passes — Add is correct.
func TestAdd(t *testing.T) {
	if got := Add(2, 3); got != 5 {
		t.Errorf("Add(2, 3) = %d; want 5", got)
	}
}

// TestSubtract FAILS on purpose — it asserts a wrong expected value so the Inspector acceptance
// leg sees a real go-test failure (ok=false, a failures[].kind="test" finding) end-to-end.
func TestSubtract(t *testing.T) {
	if got := Subtract(5, 3); got != 99 {
		t.Errorf("Subtract(5, 3) = %d; want 99", got)
	}
}
