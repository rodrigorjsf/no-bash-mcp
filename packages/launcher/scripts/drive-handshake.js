'use strict';

// CI launcher-handshake acceptance driver (PRD-5 S3). Spawns the installed Launcher, completes the
// MCP `initialize` + `tools/list` handshake over STDIO, asserts the real tool catalog, then closes
// stdin so the launcher-spawned native binary exits cleanly (EOF). Cross-platform and bounded by a
// hard timeout.
//
// This is the NativeAcceptanceIT direct-STDIO-drive technique applied to the Launcher. It is the
// per-tuple acceptance gate across all four tuples (AC#3 of #75/#77): it has no opaque MCP Inspector
// subprocess lifecycle (the cause of S1's teardown hang) and does NOT depend on spawning an
// extensionless `.bin` shim, which would hit the documented Windows `.cmd`/shim spawn limitation
// (#62/G13). darwin-arm64 additionally runs MCP Inspector --cli (the literal AC#3 leg) in CI.
//
// Usage: node drive-handshake.js <path-to-launcher-index.js>
// Exit 0 = handshake proven; non-zero = failure (with stdout/stderr dumped for diagnosis).

const { spawn } = require('node:child_process');

const launcherEntry = process.argv[2];
if (!launcherEntry) {
  console.error('usage: node drive-handshake.js <path-to-launcher-index.js>');
  process.exit(2);
}

const TIMEOUT_MS = 120000;
const REQUIRED_TOOLS = ['run_tests', 'git_status'];

const proc = spawn(process.execPath, [launcherEntry], { stdio: ['pipe', 'pipe', 'pipe'] });
let out = '';
let err = '';
proc.stdout.on('data', (d) => { out += d.toString(); });
proc.stderr.on('data', (d) => { err += d.toString(); });

function fail(msg) {
  console.error(`FAIL: ${msg}\n--- launcher stdout ---\n${out}\n--- launcher stderr ---\n${err}`);
  try { proc.kill('SIGKILL'); } catch (_e) { /* already gone */ }
  process.exit(1);
}

const hardTimer = setTimeout(() => fail(`timed out after ${TIMEOUT_MS}ms (no id:2 response)`), TIMEOUT_MS);

const send = (o) => proc.stdin.write(JSON.stringify(o) + '\n');

proc.on('error', (e) => fail(`spawn failed: ${e.message}`));

// Drive the handshake.
send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 's3-acceptance', version: '0' } } });
send({ jsonrpc: '2.0', method: 'notifications/initialized' });
send({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });

// Poll the accumulated stdout for the id:2 (tools/list) response, then assert + shut down cleanly.
const poll = setInterval(() => {
  const gotId2 = out.includes('"id":2') && (out.includes('"result"') || out.includes('"error"'));
  if (!gotId2) return;
  clearInterval(poll);
  clearTimeout(hardTimer);

  proc.stdin.end(); // EOF -> native binary exits -> launcher exits

  let ok = true;
  if (!(out.includes('"id":1') && out.includes('"result"'))) {
    console.error('initialize (id:1) did not return a result');
    ok = false;
  }
  for (const t of REQUIRED_TOOLS) {
    if (!out.includes(`"${t}"`)) { console.error(`tools/list did not list ${t}`); ok = false; }
  }
  if (!ok) fail('handshake payload assertions failed');

  console.log('HANDSHAKE OK: initialize + tools/list succeeded via the launcher-spawned binary (no SIGKILL)');
  // Give the launcher a moment to exit on EOF, then return success regardless.
  setTimeout(() => process.exit(0), 2000);
}, 100);
