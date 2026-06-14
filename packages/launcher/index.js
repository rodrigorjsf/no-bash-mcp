#!/usr/bin/env node
'use strict';

// no-bash-mcp Launcher — the small package the harness invokes each session. It selects the native
// binary matching the host (process.platform + process.arch), makes it executable, spawns it, and
// forwards the agent's STDIO JSON-RPC channel verbatim.
//
// HARD INVARIANT: the launcher writes diagnostics ONLY to stderr. stdout is the JSON-RPC channel and
// must stay byte-pure — a single stray byte corrupts the MCP transport. An unresolved or unsupported
// platform yields a structured JSON error on stderr and a non-zero exit, never a half-open channel.

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { resolveTuple } = require('./lib/resolve');

// sysexits.h conventions: a clean, distinguishable code beats a bare exit(1).
const EX_UNAVAILABLE = 69; // a supported tuple whose platform package / binary is missing
const EX_CONFIG = 78;      // the host OS/arch itself is unsupported

function failClear(payload, code) {
  process.stderr.write(JSON.stringify(payload) + '\n');
  process.exit(code);
}

const verdict = resolveTuple(process.platform, process.arch);
if (!verdict.ok) {
  failClear(verdict.error, EX_CONFIG);
}

// Resolve the platform package's binary path. require.resolve on its package.json is robust even if
// the package declares restrictive "exports"; we then join the known bin/ subpath.
let binaryPath;
try {
  const pkgJson = require.resolve(`${verdict.pkg}/package.json`);
  binaryPath = path.join(path.dirname(pkgJson), 'bin', verdict.binary);
} catch (_e) {
  failClear({
    error: 'no-bash-mcp-launcher',
    reason: 'platform-package-not-installed',
    platform: verdict.tuple,
    package: verdict.pkg,
    hint:
      `The platform package ${verdict.pkg} is not installed. It is an optionalDependency npm `
      + 'selects by os/cpu; reinstall without --no-optional / --ignore-scripts.',
  }, EX_UNAVAILABLE);
}

if (!fs.existsSync(binaryPath)) {
  failClear({
    error: 'no-bash-mcp-launcher',
    reason: 'binary-missing',
    platform: verdict.tuple,
    package: verdict.pkg,
    path: binaryPath,
    hint: `The platform package ${verdict.pkg} resolved but its native binary is absent.`,
  }, EX_UNAVAILABLE);
}

// Defensive chmod: the npm tar round-trip should preserve the exec bit, but harden the launcher in
// case a host or extraction tool drops it. Mode bits are NOT part of the signed Mach-O content, so
// this can never invalidate a macOS code signature.
try {
  fs.chmodSync(binaryPath, 0o755);
} catch (e) {
  process.stderr.write(`no-bash-mcp-launcher: chmod best-effort failed: ${e.message}\n`);
}

// Spawn the native binary with the launcher's own argv tail, inheriting all three stdio streams so
// the JSON-RPC channel flows agent <-> native binary with the launcher transparently in the middle.
const child = spawn(binaryPath, process.argv.slice(2), { stdio: 'inherit' });

// Forward termination to the child so killing the launcher never orphans the native server. Without
// this, a harness (or the MCP Inspector) that SIGTERMs the launcher leaves the native binary alive
// holding the inherited stdout pipe open — the peer then blocks forever waiting for EOF (the exact
// 45-min stall the S1 tracer surfaced). We do NOT exit here: the child's own 'exit' handler below is
// the single place that maps the child's disposition onto our exit code.
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(sig, () => {
    if (!child.killed) child.kill(sig);
  });
}

child.on('error', (err) => {
  failClear({
    error: 'no-bash-mcp-launcher',
    reason: 'spawn-failed',
    platform: verdict.tuple,
    path: binaryPath,
    detail: err.message,
  }, EX_UNAVAILABLE);
});

// Mirror the child's termination so the harness sees the native server's true disposition: a signal
// is re-raised as that signal, an exit code is passed through as our exit code.
child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
  } else {
    process.exit(code == null ? 0 : code);
  }
});
