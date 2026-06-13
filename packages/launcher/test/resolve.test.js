'use strict';

// Host-portable unit coverage for the launcher's pure resolution layer (AC#1's logic). These run on
// any OS/arch — they do NOT exercise the darwin signature-survival / signed-binary-spawn gate, which
// is un-fakeable off a macOS arm64 runner and lives in the prd5-distribution-tracer.yml workflow.

const test = require('node:test');
const assert = require('node:assert/strict');
const { resolveTuple, SUPPORTED } = require('../lib/resolve');

test('resolves a supported tuple to its platform package + binary', () => {
  const v = resolveTuple('darwin', 'arm64');
  assert.equal(v.ok, true);
  assert.equal(v.tuple, 'darwin-arm64');
  assert.equal(v.pkg, '@no-bash-mcp/darwin-arm64');
  assert.equal(v.binary, 'no-bash-mcp');
});

test('the win32 tuple carries the .exe binary name', () => {
  const v = resolveTuple('win32', 'x64');
  assert.equal(v.ok, true);
  assert.equal(v.binary, 'no-bash-mcp.exe');
});

test('all four PRD-4 native tuples are supported', () => {
  for (const tuple of ['linux-x64', 'linux-arm64', 'darwin-arm64', 'win32-x64']) {
    assert.ok(SUPPORTED[tuple], `${tuple} must be a supported tuple`);
  }
});

test('an unsupported tuple yields a structured fail-clear error, never a throw', () => {
  const v = resolveTuple('sunos', 'mips');
  assert.equal(v.ok, false);
  assert.equal(v.error.error, 'no-bash-mcp-launcher');
  assert.equal(v.error.reason, 'unsupported-platform');
  assert.equal(v.error.platform, 'sunos-mips');
  assert.deepEqual(v.error.supported, Object.keys(SUPPORTED));
  assert.match(v.error.hint, /JVM jar/);
});

test('darwin-x64 (Intel, not produced) is unsupported and fails clear', () => {
  const v = resolveTuple('darwin', 'x64');
  assert.equal(v.ok, false);
  assert.equal(v.error.reason, 'unsupported-platform');
});

test('win32-arm64 (no GraalVM JDK-25 toolchain) is unsupported and fails clear', () => {
  const v = resolveTuple('win32', 'arm64');
  assert.equal(v.ok, false);
  assert.equal(v.error.reason, 'unsupported-platform');
});
