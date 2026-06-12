package calc

// Add returns the sum of a and b. The production code compiles cleanly — only the test file
// below references an undefined symbol, so `go test` fails at BUILD time (no test ever runs).
func Add(a, b int) int {
	return a + b
}
