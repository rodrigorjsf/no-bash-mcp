package mathx

import "testing"

// TestAdd passes. Its name is a PREFIX of TestAddTable — the dedup heuristic must
// not suppress TestAdd just because TestAddTable has a failing child.
func TestAdd(t *testing.T) {
	if Add(2, 3) != 5 {
		t.Errorf("Add(2,3) = %d, want 5", Add(2, 3))
	}
}

// TestAddTable has subtests; one fails. The parent must be suppressed (failed only
// via child) and the leaf "neg" kept with path=["TestAddTable"].
func TestAddTable(t *testing.T) {
	cases := []struct {
		name    string
		a, b, w int
	}{
		{"pos", 2, 3, 5},
		{"zero", 0, 0, 0},
		{"neg", -2, -3, -6}, // WRONG on purpose: Add(-2,-3) = -5, so this subtest fails
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Add(c.a, c.b); got != c.w {
				t.Errorf("Add(%d,%d) = %d, want %d", c.a, c.b, got, c.w)
			}
		})
	}
}

// TestMul fails directly (no subtests) — a plain leaf TestFinding.
func TestMul(t *testing.T) {
	if Mul(3, 4) != 12 {
		t.Errorf("Mul(3,4) = %d, want 12", Mul(3, 4))
	}
}
