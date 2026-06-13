# no-bash-mcp (Launcher)

The **Launcher** package the agent harness invokes (`npx -y no-bash-mcp@<version>`). Each session it:

1. resolves the host tuple from `process.platform` + `process.arch`;
2. finds the matching **Platform package** `@no-bash-mcp/<os>-<arch>` (installed by npm via `os`/`cpu`,
   declared here as an `optionalDependency`);
3. makes its native binary executable (defensive `chmod`) and spawns it;
4. forwards the STDIO JSON-RPC channel verbatim (`stdio: 'inherit'`).

All launcher diagnostics go to **stderr**; **stdout stays the byte-pure JSON-RPC channel**. An
unsupported `(os, arch)`, or a supported tuple whose Platform package is absent, yields a structured
JSON error on stderr and a non-zero exit — never a half-open channel.

The runtime selection logic is the pure `resolveTuple(platform, arch)` in `lib/resolve.js`; run its
host-portable coverage with `npm test`.

> **PRD-5 status.** This is the **S1 tracer** form (`@no-bash-mcp/darwin-arm64` only, version `0.0.0`,
> never published). S3 expands `optionalDependencies` to the full four-tuple matrix and wires
> `npm publish --provenance`. See [`docs/design/build-and-distribution.md`](../../docs/design/build-and-distribution.md)
> and [ADR-0010](../../docs/adr/0010-npm-launcher-distribution.md).
