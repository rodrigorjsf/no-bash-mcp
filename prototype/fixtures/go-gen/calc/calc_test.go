package calc

import "testing"

// Axis 2: a normal pass.
func TestAddPasses(t *testing.T) {
	if Add(2, 3) != 5 {
		t.Errorf("Add(2,3) = %d, want 5", Add(2, 3))
	}
}

// Axis 2/3/4: a normal failure. file:line is NOT a JSON field — it is emitted
// inside the Output text ("calc_test.go:15:") and must be parsed from there.
func TestAddFails(t *testing.T) {
	got := Add(2, 2)
	if got != 5 {
		t.Errorf("Add(2,2) = %d, want 5", got)
	}
}

// Axis 2: a skip.
func TestSkipped(t *testing.T) {
	t.Skip("not implemented on this platform")
}

// Axis 1/7: subtests (parametrized identity via t.Run, "/"-joined names).
func TestTableDriven(t *testing.T) {
	cases := []struct {
		name string
		a, b int
		want int
	}{
		{"two_plus_two", 2, 2, 4},
		{"wrong_on_purpose", 1, 1, 3},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Add(c.a, c.b); got != c.want {
				t.Errorf("Add(%d,%d) = %d, want %d", c.a, c.b, got, c.want)
			}
		})
	}
}
