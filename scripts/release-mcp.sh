#!/usr/bin/env bash
#
# scripts/release-mcp.sh — cut and verify an MCP release end to end.
#
# WHAT  Enforces the pre-1.0 versioning policy, creates+pushes the `v<version>` tag, watches
#       the native-release.yml run to its REAL conclusion, then verifies the registry publish.
#
# WHY   Releasing has several non-obvious traps this encodes once:
#         - D58 policy: MAJOR must stay 0 until maintainer lifts it (the CI gate also enforces
#           this fail-closed, but failing here saves a 30-min CI round-trip).
#         - `gh run watch --exit-status` returns 0 on a CANCELLED run — never trust it; judge
#           by `gh run view --json conclusion`.
#         - A green publish job != a real publish — always finish with the registry check.
#
# WHEN  To ship an in-development release (`0.MINOR.PATCH[-alpha|beta|rc.N]`) once the scoped
#       packages already exist on npm (the one-time OIDC bootstrap is HITL — see the handoff).
#
# HOW   scripts/release-mcp.sh 0.0.1-alpha.3
#       Pass --dry-run to validate the version + show the plan WITHOUT tagging or pushing.
#
# PRECONDITIONS  on `development` HEAD, clean tree, gh authenticated, scoped packages bootstrapped.
#
set -euo pipefail
VER="${1:?usage: release-mcp.sh <version, e.g. 0.0.1-alpha.3> [--dry-run]}"
DRY=0; [ "${2:-}" = "--dry-run" ] && DRY=1
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- D58 pre-1.0 versioning policy: MAJOR must be 0 (binding; see CLAUDE.md) ---
if ! printf '%s' "$VER" | grep -qE '^0\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?$'; then
  echo "REFUSED: '$VER' violates the pre-1.0 policy — must match 0.MINOR.PATCH[-alpha|beta|rc.N]." >&2
  echo "         (MAJOR stays 0 until the maintainer lifts the rule; -omega and major>=1 are banned.)" >&2
  exit 2
fi

TAG="v$VER"
echo "Release plan: tag $TAG at $(git rev-parse --short HEAD) on $(git rev-parse --abbrev-ref HEAD)"
if [ "$DRY" -eq 1 ]; then echo "(--dry-run: not tagging/pushing)"; exit 0; fi

git tag -a "$TAG" -m "PRD-5 release $VER" && git push origin "$TAG"

echo "Waiting for native-release.yml run on $TAG ..."
sleep 8
run=$(gh run list --workflow native-release.yml --limit 5 \
        --json databaseId,headBranch --jq "map(select(.headBranch==\"$TAG\"))[0].databaseId")
[ -n "${run:-}" ] || { echo "Could not find a native-release.yml run for $TAG." >&2; exit 1; }
echo "Run: $run — polling to its real conclusion (gh run watch lies on cancel) ..."
while [ "$(gh run view "$run" --json status --jq .status)" != "completed" ]; do sleep 30; done

concl=$(gh run view "$run" --json conclusion --jq .conclusion)
echo "Run conclusion: $concl"
[ "$concl" = "success" ] || { echo "Release run did not succeed — aborting verification." >&2; exit 1; }

echo "== verifying registry publish =="
exec "$DIR/verify-npm-release.sh" "$VER"
