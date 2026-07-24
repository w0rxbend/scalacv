# Phase 0 — Inventory before writing a word
1. Enumerate the real public API from source: every exported type and method, grouped by
   concern (I/O, color, filtering, geometry, detection, drawing, interop, lifetime).
2. For each public symbol record: has Scaladoc? is it accurate? has a compiling example?
3. List every documentation artifact that exists today (README, wiki, comments, `docs/`)
   and mark each: keep / rewrite / delete.
4. Identify the **five tasks a new user actually arrives wanting**: almost certainly
   (a) add the dependency and have it work on their OS, (b) load and save an image,
   (c) run one real pipeline end-to-end, (d) not leak native memory, (e) interop with
   `BufferedImage` / raw bytes. Confirm or correct this list from the actual API.
5. Report Phase 0 before writing.

# Phase 1 — The documentation that must exist

## 1. Installation & platform matrix  ← the highest-stakes page
JavaCPP ships native binaries per platform classifier. This is where users fail first and
loudest, and a wrong page here makes the library look broken.
- Exact dependency snippets for Mill, sbt, and scala-cli, each verified by actually
  resolving and running a hello-world in a scratch directory.
- Explicit table of supported platform classifiers (linux-x86_64, linux-arm64,
  macosx-x86_64, macosx-arm64, windows-x86_64, …) and which are tested in CI vs untested.
- How to depend on it portably: `javacpp.platform` property, `-platform` meta-artifacts,
  or per-classifier — document the one the library actually supports and show the snippet.
  State the download size implications honestly.
- Troubleshooting section written from real reproduced failures, not imagined ones:
  `UnsatisfiedLinkError`, missing classifier, extraction failure on read-only filesystems
  and in containers, `org.bytedeco.javacpp.cachedir`, concurrent JVMs sharing a cache dir.
  Each entry: the exact error text a user sees → cause → fix.
- Minimum JDK, Scala 3 version, and whether GraalVM native-image works (state plainly if
  it doesn't, and what blocks it).

## 2. The memory & lifetime guide  ← the page that makes this library trustworthy
This is a native-memory library. If this page doesn't exist, users will leak, and blame you.
- Explain JavaCPP's model in plain terms: `Pointer` subclasses, off-heap allocation, the
  deallocator reference queue, `maxBytes`/`maxPhysicalBytes`, and why relying on GC gives
  unpredictable latency and OOMs that don't look like OOMs.
- The library's own rule, stated once, unambiguously, in one sentence — who closes what.
- Views alias their parent: `row`, `col`, `rowRange`, `colRange`, `Rect` submats share the
  parent buffer. Document this with a worked use-after-free example marked as WRONG next
  to the correct version. Users will not discover this on their own.
- `Indexer` lifetime, and the rule about not using an indexer after its Mat closes.
- Thread safety: state what is and isn't safe to share. Include the `PointerScope`
  thread-local caveat if the library uses scopes — a scope opened on one thread does not
  cover allocations on another (Futures, ZIO fibers, parallel collections).
- OpenBLAS + OpenCV thread pools and oversubscription: what env vars and settings matter,
  what the library sets (if anything), what the user should set.
- A short "how to verify you aren't leaking" recipe: run your workload with a low
  `-Dorg.bytedeco.javacpp.maxBytes` and watch it fail fast.

## 3. Scaladoc pass
- Every public symbol gets: one-sentence summary, `@param` for each parameter including
  units and valid ranges, `@return` including whether it's a new allocation or a view of
  the input, `@throws` only for exceptions actually thrown, `@example` that compiles.
- Explicitly document, per method: does it allocate? does it alias the input? does it
  mutate a `dst` parameter? Every wrapper over a native call needs these three answered.
- Delete or correct every doc comment that contradicts the implementation. List what you
  changed and why.
- Ensure `@since` / `@deprecated` are present where meaningful, and that Scaladoc builds
  clean with links resolving.

## 4. Executable examples — docs that cannot rot
- Set up **mdoc** so every code block on the site is compiled (and where cheap, executed)
  during CI. This is non-negotiable for this repo given the code's provenance: prose can
  lie, a compiled snippet cannot.
- Convert every existing README/site snippet into an mdoc-checked block, or delete it.
- Small committed test fixtures (a few KB image, a cascade XML if needed) so examples run
  standalone. Document their licence/provenance.
- A `examples/` module runnable via `scala-cli` with a one-line copy-paste command.

## 5. Microsite
- Recommend **one** stack and justify it in two sentences, accounting for the build tool in
  use: mdoc + Laika, mdoc + Docusaurus, mdoc + VitePress, or Scaladoc's static site
  generator. Weigh: build integration, Scaladoc API linking, search, dark mode, and how
  much CI machinery it adds for a small library.
- Information architecture, ordered by what a reader needs when:
  1. Landing — what it is, what it wraps, one honest paragraph, one compelling ~10-line
     example above the fold, install snippet, links to API docs and GitHub.
  2. Getting started — install → first image → first pipeline, in one continuous narrative.
  3. Guides — one page per real task: image I/O, color spaces and depth, filtering,
     geometric transforms, contours/features, video and camera capture, drawing and
     annotation, `BufferedImage`/byte-array interop.
  4. Memory & resources — the guide from §2, prominently linked, not buried.
  5. Concepts — how the wrapper maps onto OpenCV's C++/preset API, naming conventions,
     the enum/constant story, error model, effect story.
  6. Migrating from raw Bytedeco presets (or JavaCV) — side-by-side before/after. This is
     the page that converts users who already have working code.
  7. API reference — published Scaladoc, deep-linked from guide pages.
  8. FAQ / troubleshooting.
  9. Changelog, versioning policy, contributing.
- Every guide page ends with a runnable complete program, not a fragment.
- Deploy to GitHub Pages from CI; docs build failure fails the build.

## 6. README (separate job from the site)
The README is a landing page, not a manual. Target ~120 lines:
badges (Maven Central, CI, Scaladoc, licence) → one-paragraph what-and-why → install
snippet → one example → platform support table → link to the site for everything else.
Delete all inherited fork content and every link pointing at the upstream repository.

## 7. Repo metadata & lineage cleanup
- GitHub "About" description and website field — currently describe the wrong library.
- Topics: `scala`, `scala3`, `opencv`, `computer-vision`, `javacpp`, `bytedeco`, `image-processing`.
- LICENSE file. Note that OpenCV (Apache 2.0 since 4.5) and OpenBLAS (BSD-3) licences
  propagate to users; document that obligation on the site.
- Fork relationship: this repo is a GitHub fork of an unrelated Scala 2.11 project, which
  suppresses search indexing and confuses provenance. Recommend detaching, and — if any
  code lineage remains — how to attribute it correctly rather than silently.
- `.github/`: issue templates, PR template, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`,
  `SECURITY.md`, and a Dependabot config for the Bytedeco version bumps.
- Social preview image; ensure og: metadata renders sensibly when shared.

## 8. Versioning, releases, compatibility
- Stated versioning policy and binary-compatibility commitment (MiMa or explicitly none).
- `CHANGELOG.md` (Keep a Changelog format), seeded with the current state.
- A documented compatibility table: scalacv version ↔ Bytedeco preset version ↔ OpenCV
  version ↔ Scala version ↔ minimum JDK. Users will need this on every upgrade.
- Document release process for the maintainer, including how platform classifiers are
  published.

# Phase 2 — Deliverables
1. `DOCS-PLAN.md` — Phase 0 inventory, the chosen microsite stack with justification, full
   sitemap, and a per-page owner/status table.
2. The actual documentation: site pages, README, `CONTRIBUTING.md`, `CHANGELOG.md`,
   licence, `.github/` templates.
3. Scaladoc fixes as a separate commit series, with a diff summary of every doc claim
   corrected — I want to see what the old comments were lying about.
4. mdoc wiring + CI workflow that builds docs, compiles all snippets, builds Scaladoc, and
   deploys to Pages.
5. Parallel work tracks with explicit file ownership so multiple agents don't collide, and
   the dependency order between them.
6. `create-issues.sh` (`gh issue create`) for anything left unfinished.

# Rules
- Never write a code block you haven't compiled. If you can't verify it, don't ship it.
- Never document intended behavior. Document observed behavior, and file an issue where
  they differ.
- Prefer one worked example over three paragraphs of description.
- If a page would only restate method signatures, don't write it — link to Scaladoc.
- Where the library's behavior is genuinely bad and can't be fixed in a docs pass, document
  it honestly as a known limitation rather than writing around it.
- Ukrainian/English: site content in English only.
