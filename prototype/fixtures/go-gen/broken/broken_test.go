package broken

import "testing"

// This test never runs: the package's init() panics first, so the whole package
// fails with no test owner (Axis 5).
func TestNeverRuns(t *testing.T) {
	t.Log("unreachable")
}
