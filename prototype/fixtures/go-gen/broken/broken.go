// Package broken simulates Axis 5 for Go: a package-level failure with NO test
// owner. init() panics before any test runs, so `go test -json` emits a package
// "fail" action with no "Test" field — a failure that cannot be attributed to a
// single test.
package broken

func init() {
	panic("config missing: simulated init() panic with no test owner")
}
