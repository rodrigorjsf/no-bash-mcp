'use strict';

// Regression guard for the launcher's signal forwarding (index.js). The S1 tracer surfaced a real
// bug: when the harness (or the MCP Inspector) terminates the launcher, the launcher must NOT orphan
// the native server — an orphaned child keeps the inherited stdout pipe open and the peer blocks
// forever on EOF (a 45-min CI stall). This test spawns the launcher against a lingering stub binary,
// SIGTERMs the launcher, and asserts the child dies with it.
//
// Unlike resolve.test.js (pure, runs anywhere), this exercises the real spawn path, so it builds a
// throwaway node_modules with a stub Platform package for the HOST tuple. Skipped on win32 (the stub
// would need to be a spawnable .exe) and on any host whose tuple is unsupported.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { resolveTuple } = require('../lib/resolve');

const host = resolveTuple(process.platform, process.arch);
const SKIP = process.platform === 'win32' || !host.ok;

test('SIGTERM to the launcher is forwarded to the native binary (no orphan)',
  { skip: SKIP && `unsupported host tuple or win32 (${process.platform}-${process.arch})` },
  async () => {
    const launcherDir = path.resolve(__dirname, '..');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'nbm-launcher-sig-'));

    // node_modules/no-bash-mcp -> a copy of the launcher (so require.resolve finds the sibling stub)
    const lch = path.join(root, 'node_modules', 'no-bash-mcp');
    fs.mkdirSync(path.join(lch, 'lib'), { recursive: true });
    fs.copyFileSync(path.join(launcherDir, 'index.js'), path.join(lch, 'index.js'));
    fs.copyFileSync(path.join(launcherDir, 'lib', 'resolve.js'), path.join(lch, 'lib', 'resolve.js'));

    // node_modules/<host.pkg>/bin/<host.binary> -> a lingering stub: prints its pid, then stays alive
    // (ignores stdin EOF) so only a forwarded signal can kill it.
    const pkgDir = path.join(root, 'node_modules', host.pkg);
    fs.mkdirSync(path.join(pkgDir, 'bin'), { recursive: true });
    // The launcher resolves the binary via require.resolve('<pkg>/package.json'), so the stub package
    // needs one even though npm os/cpu gating is irrelevant for this hand-built tree.
    fs.writeFileSync(path.join(pkgDir, 'package.json'),
      JSON.stringify({ name: host.pkg, version: '0.0.0' }) + '\n');
    const stub = path.join(pkgDir, 'bin', host.binary);
    fs.writeFileSync(stub,
      '#!/usr/bin/env node\n'
      + 'process.stdout.write("STUB_PID=" + process.pid + "\\n");\n'
      + 'process.stdin.resume();\n'
      + 'setInterval(() => {}, 100000);\n');
    fs.chmodSync(stub, 0o755);

    const proc = spawn(process.execPath, [path.join(lch, 'index.js')], { stdio: ['pipe', 'pipe', 'pipe'] });

    const childPid = await new Promise((resolve, reject) => {
      let buf = '';
      const timer = setTimeout(() => reject(new Error('stub never reported its pid')), 10000);
      proc.stdout.on('data', (d) => {
        buf += d.toString();
        const m = buf.match(/STUB_PID=(\d+)/);
        if (m) { clearTimeout(timer); resolve(Number(m[1])); }
      });
      proc.on('error', reject);
    });

    proc.kill('SIGTERM');
    await new Promise((resolve) => proc.on('exit', resolve));

    // Give the forwarded signal a moment to take the child down, then assert it is gone.
    await new Promise((r) => setTimeout(r, 500));
    let alive = true;
    try {
      process.kill(childPid, 0); // throws ESRCH if the process no longer exists
    } catch (e) {
      alive = false;
    }
    if (alive) {
      try { process.kill(childPid, 'SIGKILL'); } catch (_e) { /* cleanup best-effort */ }
    }
    fs.rmSync(root, { recursive: true, force: true });

    assert.equal(alive, false, `native binary (pid ${childPid}) was orphaned after the launcher was SIGTERMed`);
  });
