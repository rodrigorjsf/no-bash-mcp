# @no-bash-mcp/darwin-arm64 (Platform package)

The **Platform package** carrying the `darwin-arm64` (Apple Silicon) **native binary** of the
no-bash-mcp MCP server. npm installs it only on a matching host (`os: ["darwin"]`, `cpu: ["arm64"]`);
the [`no-bash-mcp` launcher](../launcher/README.md) resolves and spawns `bin/no-bash-mcp`. Not meant
to be invoked directly.

The binary is **ad-hoc codesigned** (`codesign -s -`) after strip — mandatory on Apple Silicon, which
SIGKILLs unsigned or strip-invalidated arm64 code. It is a **build artifact**, not committed: CI
(`prd5-distribution-tracer.yml`, S3's publish pipeline) builds it from the same Micronaut codebase via
GraalVM JDK-25 and stages it under `bin/` before packing.

> **PRD-5 status.** S1 tracer (version `0.0.0`, never published). See
> [`docs/design/build-and-distribution.md`](../../docs/design/build-and-distribution.md).
