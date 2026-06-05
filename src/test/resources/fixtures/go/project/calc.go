package calc

// Add returns the sum of a and b.
func Add(a, b int) int {
	return a + b
}

// Subtract returns a minus b. (Deliberately correct — the failing test below asserts a wrong
// expectation so the Inspector acceptance leg exercises a real go-test failure end-to-end.)
func Subtract(a, b int) int {
	return a - b
}
