# PERF-REPORT.md — build performance and web vitals

Measured numbers, not targets. Where a number can't be measured in this environment, that's stated
plainly rather than guessed.

## Build time & bundle: `future.faster` (Rspack) — measured, left OFF

Docusaurus is the heaviest site generator, so `future.faster` (Rspack + SWC + Lightning CSS) is the
headline build-time lever. Measured on this machine, clean build each time (`rm -rf build .docusaurus`):

| Config | Build wall-clock | `build/assets/js` | Total `build/` |
|---|---|---|---|
| Baseline (webpack) | 3 s | 1.2 M | 28 M |
| `future.faster: true` | 3–4 s (2 runs) | 1.2 M | 28 M |

**Decision: left off.** The delta is within timer noise and the JS bundle is byte-for-byte the same.
The reason is structural: only ~35 pages are actually MDX-compiled; the 28 M is the 168 static
Scaladoc pages (a file copy) plus the Pagefind index (a postbuild pass) — neither is work Rspack
speeds up. So `faster` buys nothing measurable here while pulling in an experimental Rspack/SWC
toolchain that is an upgrade liability. Revisit if the *guide* count grows into the hundreds. (This is
a defensible either-way call; the measurement is what decided it.)

First-load JS specifically wasn't isolated (no bundle analyzer run); `build/assets/js` totals 1.2 M
across all chunks, of which a page loads a small subset. A proper first-load figure is a follow-up.

## Web vitals / Lighthouse — deferred, not faked

Lighthouse and axe need headless Chrome, which isn't available in this environment, so **no CWV or
Lighthouse scores are reported** — reporting invented numbers would violate the "measured, never
targets restated as results" rule. What's in place to hit the targets when the audit runs:

- The homepage is a Markdown doc (no heavy React landing), so first-load JS stays small.
- Sitemap is emitted by the classic preset (`build/sitemap.xml`, verified); `robots.txt` added,
  pointing at it; canonical URLs come from the classic preset.
- Theme-flash guard is the built-in inline script (verified in `build/index.html`).
- Reduced-motion is honored globally (DESIGN.md).

**Follow-up (tracked issue):** add Lighthouse CI (mobile emulation; Perf ≥95, A11y 100, BP ≥95,
SEO ≥95) and axe to the docs workflow so these run on every PR against the built site, and record the
first measured scores here from the deployed `/scalacv/` URL.

## What is verified on the deployed URL

- All guide + Scaladoc pages serve 200 (`/`, guides, `/api/core/scalacv/*.html`).
- Pagefind index served and unified: `pagefind-entry.json` reports `page_count: 203`.
- Scaladoc source links resolve to real GitHub lines (200).
