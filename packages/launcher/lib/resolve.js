'use strict';

// Pure host -> platform-package resolution. No filesystem, no process exit, no I/O: the bin shim
// (../index.js) owns those side effects so this unit is trivially testable on any OS/arch.
//
// The supported set is the PRD-4 four-tuple native matrix. Each entry maps a `<os>-<arch>` host to
// the scoped platform package that carries its native binary plus the binary's on-disk filename.
//   process.platform tokens: 'darwin' | 'linux' | 'win32'
//   process.arch     tokens: 'arm64'  | 'x64'
// npm's own `os`/`cpu` fields use these same tokens, so the launcher and npm agree on selection.
const SUPPORTED = Object.freeze({
  'linux-x64':    { pkg: '@no-bash-mcp/linux-x64',    binary: 'no-bash-mcp' },
  'linux-arm64':  { pkg: '@no-bash-mcp/linux-arm64',  binary: 'no-bash-mcp' },
  'darwin-arm64': { pkg: '@no-bash-mcp/darwin-arm64', binary: 'no-bash-mcp' },
  'win32-x64':    { pkg: '@no-bash-mcp/win32-x64',    binary: 'no-bash-mcp.exe' },
});

/**
 * Resolve the platform package and native binary filename for a host (platform, arch) pair.
 *
 * Returns a discriminated verdict; it never throws. The caller inspects `ok` and, on failure,
 * writes `error` as a structured fail-clear payload to stderr (never stdout).
 *
 * @param {string} platform - a `process.platform` value (e.g. 'darwin')
 * @param {string} arch     - a `process.arch` value (e.g. 'arm64')
 * @returns {{ok: true, tuple: string, pkg: string, binary: string}
 *          | {ok: false, error: {error: string, reason: string, platform: string,
 *                                 supported: string[], hint: string}}}
 */
function resolveTuple(platform, arch) {
  const tuple = `${platform}-${arch}`;
  const entry = SUPPORTED[tuple];
  if (entry) {
    return { ok: true, tuple, pkg: entry.pkg, binary: entry.binary };
  }
  return {
    ok: false,
    error: {
      error: 'no-bash-mcp-launcher',
      reason: 'unsupported-platform',
      platform: tuple,
      supported: Object.keys(SUPPORTED),
      hint:
        'No native binary is produced for this OS/arch. Run the server from the JVM jar instead '
        + '(java -jar no-bash-mcp.jar), or, on win32-arm64, the win32-x64 binary under emulation.',
    },
  };
}

module.exports = { resolveTuple, SUPPORTED };
