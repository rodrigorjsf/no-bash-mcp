package buggy

import "testing"

// Deliberate compile error: Heigth is undefined (typo) and undeclaredVar does not
// exist. The package does not compile, so `go test -json` cannot run any test and
// must report a build failure. The spike captures exactly what Go emits on stdout.
func TestWidth(t *testing.T) {
	if Heigth() != 10 { // undefined: Heigth
		t.Errorf("got %d", undeclaredVar) // undefined: undeclaredVar
	}
}
