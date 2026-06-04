// Axes 1,2,3,4,7: nested describe (ancestorTitles), a pass, an assertion failure
// (message + diff + file:line buried in the stack string of failureMessages[]),
// a skip (pending), and a parametrized test.each (interpolated titles).
describe('math', () => {
  describe('addition', () => {
    test('adds correctly', () => {
      expect(2 + 3).toBe(5);
    });

    test('fails on purpose', () => {
      expect(2 + 2).toBe(5);
    });
  });

  test.skip('skipped: not implemented', () => {
    expect(true).toBe(false);
  });

  test.each([
    [2, true],
    [3, false],
  ])('isEven(%i) === %s', (n, expected) => {
    expect(n % 2 === 0).toBe(expected);
  });
});
