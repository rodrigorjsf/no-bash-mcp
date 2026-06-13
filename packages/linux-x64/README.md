# @no-bash-mcp/linux-x64 (Platform package)

The **Platform package** carrying the `linux-x64` **native binary** of the no-bash-mcp MCP server.
npm installs it only on a matching host (`os: ["linux"]`, `cpu: ["x64"]`); the
[`no-bash-mcp` launcher](../launcher/README.md) resolves and spawns `bin/no-bash-mcp`. Not meant to be
invoked directly.

The binary is **statically linked** (`--static-nolibc`), so it is portable across glibc hosts. It is a
**build artifact**, not committed: CI builds it from the same Micronaut codebase via GraalVM JDK-25 and
stages it under `bin/` before packing.

> See [`docs/design/build-and-distribution.md`](../../docs/design/build-and-distribution.md).
