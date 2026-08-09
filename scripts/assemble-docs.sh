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

# ---------------------------------------------------------------------------------------------
# Guard: admonition syntax.
#
# Docusaurus 3 builds `:::note` blocks out of remark-directive, whose container syntax is
# `:::name[label]`. The Docusaurus 2 form `:::note Some title` puts bare text where the parser
# expects `[`, so the block is not recognised as a directive at all — and instead of failing, the
# whole thing lands in the page as literal ":::note Some title … :::" text. That is exactly how 180
# admonitions across 45 guides silently stopped rendering: nothing errored, the pages were just
# wrong, and only reading a built page revealed it.
#
# This check is here rather than in CI alone because it is the one step every path — local loop,
# CI, release — already runs.
# ---------------------------------------------------------------------------------------------
echo "==> check admonition syntax in docs/mdoc"
# `grep -P` would be neater, but macOS ships BSD grep; -E is the portable subset.
if bad="$(grep -rnE '^:::(note|tip|info|warning|danger|caution)[[:space:]]+[^[]' docs/mdoc/*.md || true)"; [ -n "$bad" ]; then
  echo "   Docusaurus 2 admonition titles found. Use ':::note[Title]', not ':::note Title'," >&2
  echo "   or these blocks render as literal ':::' text in the page body:" >&2
  echo "$bad" | sed 's/^/     /' >&2
  exit 1
fi
echo "   all admonition titles use the bracket form."

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
# Both extensions: guides are .md, the landing page is .mdx (see below).
find "$DOCS" -maxdepth 1 \( -name '*.md' -o -name '*.mdx' \) -delete
for f in "$MDOC_OUT"/*.md; do
  base="$(basename "$f")"
  case "$base" in
    index.md)   continue ;;                 # VitePress hero — not used by Docusaurus
    # The landing page lands as .mdx, not .md. docusaurus.config.ts sets `markdown.format: 'detect'`,
    # which parses .md as CommonMark (no JSX) and .mdx as MDX — and the landing page imports React
    # components from src/components/, so it needs the MDX parser. The guides stay .md deliberately:
    # CommonMark renders `<:`, `=>` and `?=>` literally, so Scala 3 prose cannot trip the JSX lexer.
    landing.md) rewrite_api "$f" > "$DOCS/index.mdx" ;; # Docusaurus landing (slug: /)
    *)          rewrite_api "$f" > "$DOCS/$base" ;;
  esac
done

if [ "${SKIP_SCALADOC:-0}" = "1" ]; then
  echo "==> SKIP_SCALADOC=1 — skipping Scaladoc (in-content /api/ links will 404 the build)"
  exit 0
fi

echo "==> Scaladoc -> $API (unified core+vision+graphs at /core, zio at /zio)"
# `apidocs` is a unified doc over core+vision+graphs (all package scalacv), served at /api/core so
# the content's /api/core/scalacv/*.html links to vision/graphs types (FaceRecognizer, Color, …)
# resolve. `docJar` emits <module>/docJar.dest/out.jar (not a *-javadoc.jar), so resolve the path
# from Mill rather than globbing a name.
resolve_jar() { ./mill show "$1.docJar" | tr -d '"' | sed 's/^ref:v0:[0-9a-f]*://'; }
CORE_JAR="$(resolve_jar apidocs)"
ZIO_JAR="$(resolve_jar zio)"
rm -rf "$API"
mkdir -p "$API/core" "$API/zio"
unzip -o -q "$CORE_JAR" -d "$API/core"
unzip -o -q "$ZIO_JAR" -d "$API/zio"

# Separate link check for /api/ (Scaladoc) links: Docusaurus's onBrokenLinks can't see static/, and
# pathname:// links are emitted unchecked, so verify here that every API deep-link in the generated
# pages resolves to a real Scaladoc file. This is what catches a doc pointing at a type that moved
# or never existed.
echo "==> verify in-content /api/ links resolve to generated Scaladoc"
missing=0
while IFS= read -r link; do
  rel="${link#/api/}"
  # strip any #anchor
  rel="${rel%%#*}"
  if [ ! -f "website/static/api/$rel" ]; then
    echo "  BROKEN API LINK: $link" >&2
    missing=$((missing + 1))
  fi
done < <(grep -rhoE 'pathname:///api/[A-Za-z0-9$._/-]+\.html' "$DOCS" | sed 's#pathname://##' | sort -u)
if [ "$missing" -gt 0 ]; then
  echo "==> $missing broken API link(s) — failing." >&2
  exit 1
fi
echo "   all API links resolve."

echo "==> done. Next: (cd website && npm run build)"
