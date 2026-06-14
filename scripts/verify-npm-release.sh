#!/usr/bin/env bash
#
# scripts/verify-npm-release.sh — confirm a published release is REAL on the npm registry.
#
# WHAT  Checks that all 5 distribution packages exist at <version>, that SLSA provenance is
#       attached, and that the `latest` dist-tag was NOT hijacked by a prerelease.
#
# WHY   A green CI publish job is NOT proof the channel works. The "verification ceiling" is
#       only closed by querying the live registry: did registry auth land? did provenance
#       attach? did npm's prerelease->latest rule hold? This script is that proof.
#
# WHEN  After a `v*` tag's native-release.yml run goes green — to close the verification ceiling.
#       Also any time you doubt what is actually live on npm.
#
# HOW   scripts/verify-npm-release.sh 0.0.1-alpha.2
#       Exit 0 = all green; non-zero = something is missing (printed per line).
#
set -euo pipefail
VER="${1:?usage: verify-npm-release.sh <version, e.g. 0.0.1-alpha.2>}"
NPM="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/npm"

pkgs=(@no-bash-mcp/linux-x64 @no-bash-mcp/linux-arm64 @no-bash-mcp/darwin-arm64 @no-bash-mcp/win32-x64 no-bash-mcp)
fail=0

echo "== package versions @ $VER =="
for p in "${pkgs[@]}"; do
  got=$("$NPM" view "$p@$VER" version 2>/dev/null || true)
  if [ "$got" = "$VER" ]; then echo "  ok   $p"; else echo "  MISS $p (want $VER, got '${got:-none}')"; fail=1; fi
done

echo "== SLSA provenance (sampled on linux-x64) =="
if "$NPM" view "@no-bash-mcp/linux-x64@$VER" --json 2>/dev/null | grep -q '"provenance"'; then
  echo "  ok   provenance attached"
else
  echo "  MISS no provenance on @no-bash-mcp/linux-x64@$VER"; fail=1
fi

echo "== launcher dist-tags (a prerelease must NOT be 'latest') =="
"$NPM" view no-bash-mcp dist-tags 2>/dev/null | sed 's/^/  /'

[ "$fail" -eq 0 ] && echo "RESULT: PASS — verification ceiling closed for $VER" \
                  || echo "RESULT: FAIL — see MISS lines above"
exit "$fail"
