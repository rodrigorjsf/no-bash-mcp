// The native acceptance npm leg (PRD-4 S1 / #59) drives `run_tests` against THIS project through
// the native binary, which spawns `npx jest --json ... --no-install`. One test passes and one fails
// on purpose, so the envelope is ok=false with a failures[].kind="test" finding (the jest analogue
// of the Go fixture's failing TestSubtract) — proving the jest JSON folds into the SAME universal
// schema as Maven and Go over native serde, and that the reported manager is "npx" (ADR-0008).

function sum(a, b) {
  return a + b;
}

test('sum adds two numbers', () => {
  expect(sum(2, 3)).toBe(5);
});

test('sum deliberately fails', () => {
  expect(sum(2, 3)).toBe(99);
});
