package initbroken

// init() panics, so the test binary for this package crashes before any test runs.
// go test -json emits a package-level fail event with NO Test field — the axis-5
// "no test owner" case at PACKAGE scope. The dedup heuristic must KEEP this as a
// ContainerFinding (the package has no failing test to make it redundant).
func init() {
	panic("initbroken: simulated package init failure")
}

func Noop() {}
