// Axis 5 for jest: beforeAll throws, so the whole file fails with no test owner.
// jest reports this file's testResults[] entry with assertionResults: [] (the two
// tests below vanish) and a file-level failureMessage holding the beforeAll error.
describe('suite with broken setup', () => {
  beforeAll(() => {
    throw new Error('fixture server unavailable: simulated beforeAll failure with no test owner');
  });

  test('never runs A', () => {
    expect(1).toBe(1);
  });

  test('never runs B', () => {
    expect(2).toBe(2);
  });
});
