package mathx

func Add(a, b int) int { return a + b }

// Mul is deliberately wrong so TestMul fails directly (no subtests).
func Mul(a, b int) int { return a + b }
