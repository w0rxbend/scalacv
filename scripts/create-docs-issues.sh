#!/usr/bin/env bash
#
# Files the deferred documentation-site work (the VitePress -> Docusaurus rebuild left these as
# follow-up; see DOCS-PLAN.md "Remaining work tracks") as GitHub issues.
#
#   ./scripts/create-docs-issues.sh --dry-run    # print what would be created (default)
#   ./scripts/create-docs-issues.sh --create     # actually create the issues
#
# Requires: gh, authenticated, with the repo as origin. Skips a title that already has an open issue.
set -euo pipefail

MODE="${1:---dry-run}"
REPO="${GH_REPO:-w0rxbend/scalacv}"
[[ "$MODE" == "--create" || "$MODE" == "--dry-run" ]] || { echo "usage: $0 [--dry-run|--create]" >&2; exit 2; }

[[ "$MODE" == "--create" ]] && gh label create docs --repo "$REPO" --color 1d76db --description "Documentation" 2>/dev/null || true

exists() { gh issue list --repo "$REPO" --state open --search "in:title \"$1\"" --json title -q '.[].title' 2>/dev/null | grep -qxF "$1"; }

issue() {
  local title="$1" body="$2"
  if exists "$title"; then echo "skip (open issue exists): $title"; return; fi
  if [[ "$MODE" == "--dry-run" ]]; then echo "would create: $title"; return; fi
  gh issue create --repo "$REPO" --title "$title" --label docs --body "$body" >/dev/null
  echo "created: $title"
}

issue "docs: Scaladoc pass — fix @link warnings and add native-semantics annotations" \
"The unified apidocs.docJar emits unresolved @link warnings (usingFile, FFmpeg, Video.frames, IllegalStateException). Fix them, then audit every public symbol for the three facts a JavaCPP-wrapper user needs at the call site: does it allocate? does it alias the input? does it mutate a dst? Land as a separate commit series with a diff summary of corrected claims. See DOCS-PLAN.md."

issue "docs: pass -external-mappings/-source-links/-groups to the Scaladoc build" \
"Wire the verified Scala 3.3.8 flags (API-REFERENCE.md) into the apidocs/zio docJar: -external-mappings (JDK + Bytedeco types link out instead of rendering as dead text), -source-links (GitHub line per member), -project-logo, -social-links, -snippet-compiler, -groups. Align Scaladoc CSS with DESIGN.md tokens; inject the site header post-build to close the guide->API chrome seam."

issue "docs: self-host fonts + memory admonition + Mill|sbt|scala-cli Tabs" \
"DESIGN.md follow-ups: self-host a variable sans + a Scala-legible monospace as subset WOFF2 (no CDN); swizzle Admonition (--wrap) to add a 'memory' variant for the use-after-free content; add Tabs with a shared groupId to the install/getting-started page (requires converting it to .mdx)."

issue "docs: Memory page — WRONG/RIGHT paired example and the borrowing contract" \
"The Memory & resources page is the trust page. Add a WRONG-vs-RIGHT paired code block for the ONE real aliasing surface — the borrowed one-buffer frames from Video.frames / zio frameStream (NOT view submats: crop copies, there is no row/col/submat aliasing API and no PointerScope). State scalacv's ownership rule once, plainly, and add the low -Dorg.bytedeco.javacpp.maxBytes leak-check recipe."

issue "docs: bidirectional guide <-> API links" \
"Every guide links forward to the API entry for each type on first mention; every documented type that has a guide links back. This connective tissue is what most Scala library sites lack."

issue "docs: Lighthouse CI + axe + SEO (sitemap, robots, per-page OG images)" \
"Add Lighthouse CI (mobile emulation; Perf>=95, A11y 100, BP>=95, SEO>=95) and axe to the docs workflow, failing on regressions. Add @docusaurus/plugin-sitemap, robots.txt, canonical URLs, and generated per-page OG images (satori). Measure experimental_faster (Rspack) build-time + bundle delta -> PERF-REPORT.md. Report measured numbers on the deployed URL."

issue "docs: search relevance tuning for GaussianBlur / canny" \
"SEARCH-REPORT.md: GaussianBlur tops the image-processing guide rather than its API page, and canny tops hough over image-processing. Tune Pagefind ranking (e.g. data-pagefind-weight on API signature headings). Coverage is fine (203/203 indexed); ranking only."

issue "chore: repo metadata + detach fork" \
"Set the GitHub About description + website field (https://w0rxbend.github.io/scalacv/); add topics scala, scala3, opencv, computer-vision, javacpp, bytedeco, image-processing; add a social-preview image. The repo is a GitHub fork of an unrelated project, which suppresses search indexing — detach it."

echo "done ($MODE)."
