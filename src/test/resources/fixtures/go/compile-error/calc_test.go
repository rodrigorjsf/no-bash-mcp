package calc

import "testing"

// TestBuildFail does NOT compile: Multiply is undefined. `go test -json` therefore emits a
// package-level build failure with NO test owner, which the Go adapter folds into the universal
// graph as a ContainerFinding(PACKAGE, ERRORED) (ADR-0007 axis 5, kind="container") — never a
// degenerate empty-named TestFinding. The native acceptance leg asserts that polymorphic
// ContainerFinding branch round-trips over native serde (the kind="test" sibling is covered by the
// other Go fixture's failing TestSubtract).
func TestBuildFail(t *testing.T) {
	got := Multiply(2, 3) // undefined: Multiply — deliberate compile error
	if got != 6 {
		t.Errorf("Multiply(2, 3) = %d; want 6", got)
	}
}
