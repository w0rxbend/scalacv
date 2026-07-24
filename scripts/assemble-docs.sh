#!/usr/bin/env bash
# Assemble the Docusaurus site inputs from the Mill build.
#
# The guide pages under website/docs/ and the Scaladoc under website/static/api/ are GENERATED and
# git-ignored — this script (and CI) produce them; contributors never edit them by hand. Edit the
# mdoc sources in docs/mdoc/ instead. Run this before `npm run build` / `npm start` in website/.
#
#   ./scripts/assemble-docs.sh          # mdoc + copy pages + Scaladoc
#   SKIP_SCALADOC=1 ./scripts/assemble-docs.sh   # pages only (faster inner loop; API links break)
set -euo pipefail
cd "$(dirname "$0")/.."

DOCS=website/docs
API=website/static/api

echo "==> mdoc: type-check and splice every Scala snippet"
./mill show docs.mdoc >/dev/null
MDOC_OUT="$(find out/docs -type d -name site | head -1)"
[ -n "$MDOC_OUT" ] || { echo "mdoc output dir not found" >&2; exit 1; }

# Scaladoc lives in static/api/ — outside Docusaurus's router — so onBrokenLinks can't resolve
# `/api/...` links and would flag every one as broken. The `pathname://` protocol tells Docusaurus
# to emit the link verbatim (baseUrl-prefixed) without route-checking it. Applied only to the
# generated Docusaurus copy; the mdoc sources (and the VitePress site) keep plain `/api/` links.
rewrite_api() { sed 's#](/api/#](pathname:///api/#g' "$1"; }

echo "==> copy generated guide pages into $DOCS"
mkdir -p "$DOCS"
# Wipe previously generated pages (but keep nothing hand-written here — the dir is fully generated).
find "$DOCS" -maxdepth 1 -name '*.md' -delete
for f in "$MDOC_OUT"/*.md; do
  base="$(basename "$f")"
  case "$base" in
    index.md)   continue ;;                 # VitePress hero — not used by Docusaurus
    landing.md) rewrite_api "$f" > "$DOCS/index.md" ;; # Docusaurus landing (slug: /)
    *)          rewrite_api "$f" > "$DOCS/$base" ;;
  esac
done

if [ "${SKIP_SCALADOC:-0}" = "1" ]; then
  echo "==> SKIP_SCALADOC=1 — skipping Scaladoc (in-content /api/ links will 404 the build)"
  exit 0
fi

echo "==> Scaladoc for core and zio -> $API"
# `docJar` emits a single doc jar; resolve its path from Mill rather than globbing a name
# (the jar is <module>/docJar.dest/out.jar, not a *-javadoc.jar).
resolve_jar() { ./mill show "$1.docJar" | tr -d '"' | sed 's/^ref:v0:[0-9a-f]*://'; }
CORE_JAR="$(resolve_jar core)"
ZIO_JAR="$(resolve_jar zio)"
rm -rf "$API"
mkdir -p "$API/core" "$API/zio"
unzip -o -q "$CORE_JAR" -d "$API/core"
unzip -o -q "$ZIO_JAR" -d "$API/zio"

echo "==> done. Next: (cd website && npm run build)"
