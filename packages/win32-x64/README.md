# @no-bash-mcp/win32-x64 (Platform package)

The **Platform package** carrying the `win32-x64` **native binary** (`no-bash-mcp.exe`) of the
no-bash-mcp MCP server. npm installs it only on a matching host (`os: ["win32"]`, `cpu: ["x64"]`); the
[`no-bash-mcp` launcher](../launcher/README.md) resolves and spawns `bin/no-bash-mcp.exe`. Not meant to
be invoked directly.

The binary is **system-dynamic** (Windows links system DLLs; no static-libc option). It is a **build
artifact**, not committed: CI builds it from the same Micronaut codebase via GraalVM JDK-25 and stages
it under `bin/` before packing.

> **Windows caveat.** The server runs and completes the MCP handshake, but `run_tests` for Maven/Node
> is unsupported on the native Windows binary (their `.cmd` launchers cannot be spawned without a shell
> the trusted-launcher posture forbids — `go` works). Use the JVM jar or WSL2 for Maven/Node on
> Windows. See the repository README "Platform support".
