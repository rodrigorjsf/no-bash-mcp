package initbroken

import "testing"

// This test would pass, but init() panics first, so it never runs. The package
// fails with no test owner.
func TestNoop(t *testing.T) {
	Noop()
}
