#!/usr/bin/env bash
#
# scripts/mermaid-gate.sh — render-gate every Mermaid diagram before you trust it.
#
# WHAT  Extracts every ```mermaid block from the given markdown files (default: git-changed
#       *.md, staged + unstaged) and renders each to a PNG, failing on any parse error.
#       PNGs land in /tmp/mermaid-gate/ so you can EYEBALL color/legibility (the Read tool
#       renders PNGs).
#
# WHY   "diagram added" != "renders" != "legible". An unsupported construct (e.g. edge
#       animation `e1@-->`) is a parse error that kills the WHOLE diagram on GitHub — it does
#       not degrade gracefully. This catches it before commit. Pairs with
#       .claude/rules/diagrams.md (colored classDef, theme-neutral, static GitHub-safe syntax).
#
# WHEN  After adding or editing any Mermaid diagram, before committing.
#
# HOW   scripts/mermaid-gate.sh                  # gate all git-changed *.md
#       scripts/mermaid-gate.sh DESIGN.md a.md   # gate specific files
#       # then visually inspect /tmp/mermaid-gate/*.png (dark bg) before declaring done.
#
# REQUIRES  libasound2t64 for the puppeteer chromium (else mermaid-cli dies with "Code: 127"):
#               sudo apt-get install -y libasound2t64
#           Renders to a real .png — never `-o /dev/null` (format is inferred from the extension).
#
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
NPM="$DIR/npm"
OUT=/tmp/mermaid-gate; rm -rf "$OUT"; mkdir -p "$OUT"

# Target files: args, else git-changed markdown (staged + unstaged, added/copied/modified).
if [ "$#" -gt 0 ]; then
  files=("$@")
else
  mapfile -t files < <(cd "$ROOT" && { git diff --name-only --diff-filter=ACM -- '*.md'
                                       git diff --cached --name-only --diff-filter=ACM -- '*.md'; } | sort -u)
fi
[ "${#files[@]}" -gt 0 ] || { echo "No markdown files to gate."; exit 0; }

# Extract each ```mermaid block to a numbered .mmd (tolerant of CRLF / trailing spaces).
total=0
for f in "${files[@]}"; do
  [ -f "$ROOT/$f" ] || f="$f"          # allow absolute/relative paths as given
  src="$ROOT/$f"; [ -f "$src" ] || src="$f"
  [ -f "$src" ] || { echo "skip (not found): $f"; continue; }
  slug=$(printf '%s' "$f" | tr '/.' '__')
  awk -v out="$OUT" -v base="$slug" '
    /^[ \t]*```mermaid[ \t]*\r?$/ { inb=1; n++; file=sprintf("%s/%s__%d.mmd", out, base, n); next }
    inb && /^[ \t]*```[ \t]*\r?$/ { inb=0; close(file); next }
    inb { sub(/\r$/, ""); print > file }
  ' "$src"
done
total=$(find "$OUT" -name '*.mmd' | wc -l | tr -d ' ')
[ "$total" -gt 0 ] || { echo "No \`\`\`mermaid blocks found in the target files."; exit 0; }

echo "Rendering $total diagram(s) on dark background ..."
pass=0; fail=0; failed=""
for m in "$OUT"/*.mmd; do
  if timeout 120 "$NPM" exec -- @mermaid-js/mermaid-cli -i "$m" -o "${m%.mmd}.png" -b '#0d1117' >/dev/null 2>&1; then
    pass=$((pass+1))
  else
    fail=$((fail+1)); failed="$failed $(basename "$m")"
  fi
done

echo "GATE: PASS=$pass FAIL=$fail"
[ -n "$failed" ] && echo "FAILED:$failed"
echo "PNGs: $OUT/ — eyeball them (colour, legibility on dark AND light) before committing."
exit "$fail"
