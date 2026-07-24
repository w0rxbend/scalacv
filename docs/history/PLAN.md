# PLAN — scalacv modernization (`w0rxbend/scalacv`) · agent-orchestrated

## 0. Mission

Resurrect **scalacv** — a 2015 Scala 2.11 / sbt 0.13 wrapper over the OpenCV
**3.0** Java API — into a maintained, idiomatic **Scala 3 (latest stable)**
library built with **Mill (latest)**, targeting **OpenCV 4.13.0**
(https://docs.opencv.org/4.13.0/), plus a VitePress+mdoc docs microsite on
GH Pages and a styled README with SVG logo. Act as a Principal Scala engineer
and open-source maintainer.

---

## 1. Ground truth (verified — trust these; everything else gets re-verified)

- Latest OpenCV: **4.13.0**
- Java binding: **`org.bytedeco:opencv-platform:4.13.0-1.5.13`** (Maven
  Central; bundles per-platform natives + official `org.opencv.*` classes;
  load via `Loader.load(classOf[org.bytedeco.opencv.opencv_java])`). Kills the
  vendored `lib/` natives hack.
- API drift 3.0 → 4.x: C API removed; DNN first-class (ONNX); ArUco/barcode in
  `objdetect` (`ArucoDetector`, `CharucoDetector`, `BarcodeDetector`);
  DNN-based `FaceDetectorYN`/`FaceRecognizerSF`; `QRCodeDetector`.
- **Never invent API names or version numbers.** Anything not listed above —
  latest Mill version, latest Scala 3 stable, mdoc/VitePress state, 4.12→4.13
  changelog — must be verified by the research agents with WebSearch/coursier.

---

## 2. Orchestration protocol

You (the top-level session) are the **Orchestrator**. You spawn subagents via
the Task tool, merge their outputs, own all commits, and keep `ROADMAP.md`
truthful at all times. Subagents never commit; they write notes files and
report back.

### Phase R — Research (two subagents, in parallel)

**Agent R1 — "Archaeologist" (repo analysis).** Read-only. Tasks:
- Read every file: `build.sbt`, `project/`, `src/main/scala-2.11/**`, `lib/`,
  `.github/`, `.mergify.yml`, `.whitesource`
- Inventory the public API surface + the three example apps (FaceDetect,
  CamFaceDetect, CannySlider): what survives as *design intent* vs rewrite
- List dead weight to delete and anything with attribution/license implications
  (this is a fork of mcallisto/scalacv — attribution is non-negotiable)
- Output: `NOTES-audit.md`

**Agent R2 — "Scout" (upstream & ecosystem research).** Tasks:
- Verify current **latest stable Scala 3** and **latest Mill** version; capture
  modern `build.mill` idioms for a publishable multi-module library
  (core / zio / examples), scalafmt/scalafix wiring, SonatypeCentral publish
- `coursier resolve org.bytedeco:opencv-platform:4.13.0-1.5.13` — confirm
  resolution, platforms bundled, jar sizes
- WebSearch OpenCV 4.12→4.13 changelog; pick 3–5 headline features worth
  showcasing in examples/docs
- Verify mdoc + Mill integration status (maintained plugin vs coursier CLI in
  CI) and VitePress → `actions/deploy-pages` recipe
- Output: `NOTES-upstream.md`, every claim with a source or a command output

**Orchestrator then:** merge both notes + the track seed (§4) into
**`ROADMAP.md`** persisted in the repo root — checkbox items, per-track
verification gates, milestone order, explicit version pins discovered by R2,
and a Decisions table (defaults from §3, amended by research). Commit:
`docs: add modernization roadmap (research phase)` — include the two NOTES
files in the same commit.

### Phase V — Review (one subagent, fresh eyes)

**Agent V — "Reviewer".** Spawn with a fresh context; give it ONLY
`ROADMAP.md`, `NOTES-audit.md`, `NOTES-upstream.md`. Instruct it to be
adversarial:
- Re-verify every version pin and API-name claim (spot-check with
  WebSearch/coursier — flag anything unproven)
- Attack the API design: Mat lifecycle soundness, error model, enum coverage,
  anything that will break users or leak natives
- Find missing work: CI headless traps, macOS/arm64 natives, license/
  attribution, MiMa timing, doc snippet drift
- Rank findings Blocker / Should / Nit; output `REVIEW.md`

**Orchestrator then:** amend `ROADMAP.md` to resolve every Blocker and Should
(or record an explicit rejection with rationale). Commit:
`docs: apply plan review findings`. **Show me the amended roadmap and pause
for my ack before Phase I.**

### Phase I — Implementation loop (gradual commits)

Repeat until `ROADMAP.md` has no unchecked items:

1. Pick the next unchecked item respecting track order (§4: A → B, then C–G
   fan out)
2. TDD where it pays: failing test → implement → green
3. Run the track's Gate command for anything touched
4. Atomic conventional commit (`feat:`/`build:`/`docs:`/`test:`/`chore:`)
   **including the ROADMAP.md checkbox flip in the same commit** — the roadmap
   in git history always reflects reality
5. At each milestone (§5): 5-line status summary to me

Rules: never commit mid-broken-state; tests needing camera/GUI get tagged
`Hardware` and auto-skip, never deleted; ask before force-push, license
change, or renaming publish coordinates. For long tracks (B especially),
you may spawn short-lived implementation subagents per independent item, but
you review their diffs and own the commits.

---

## 3. Decisions (defaults — research/review may amend with rationale in ROADMAP.md)

| # | Decision | Default |
|---|----------|---------|
| D1 | Build | **Mill, latest stable — locked.** No sbt anywhere. mdoc via Mill task if a maintained plugin exists, else coursier CLI in docs CI. |
| D2 | Scala | **Latest stable Scala 3** (R2 verifies exact version). Note LTS trade-off in ROADMAP for the record, but latest is the pick. |
| D3 | Binding | `org.bytedeco:opencv-platform:4.13.0-1.5.13` |
| D4 | Effects | No effect system in core (pure, sync, resource-safe: `Using`, `Releasable`, opaque types). Optional `scalacv-zio` module (ZIO 2, `Scoped` Mats, `ZStream` frames). Old `Future` API dies. |
| D5 | Modules | `core` / `zio` / `examples` — don't over-modularize a wrapper |
| D6 | Tests | munit in core, zio-test in zio module |
| D7 | Style | scalafmt (Scala 3 dialect) + scalafix (organize imports), enforced in CI |
| D8 | Docs | mdoc-checked snippets → VitePress → GH Pages via `actions/deploy-pages` |
| D9 | Publishing | New coordinates `bend.worx::scalacv` (confirm with me pre-publish); restart at `0.1.0`; MiMa from `0.2.0` |
| D10 | JDK | 17 minimum |

---

## 4. Track seed for ROADMAP.md

**A — Build resurrection 🏗️**: rm `lib/` + sbt scaffolding; `build.mill`
(core/zio/examples, latest Scala 3, bytedeco dep); scalafmt/scalafix/gitignore;
JDK pin. *Gate:* `./mill __.compile` + smoke main prints `Core.VERSION == 4.13.0`.

**B — Core API 🧠 (TDD, long pole)**: idempotent `OpenCv.load()`; Mat lifecycle
(`Mat.use`, `Using.Manager`, `Releasable` — no GC finalizers); enum wrappers
(`ColorConversion`, `ImreadFlag`, `BorderType`, `ThresholdType`,
`InterpolationFlag`); extension syntax (`cvtColor`, `gaussianBlur`, `canny`,
`resize`); `Imread → Either[CvError, Mat]`; `CvError` ADT; `VideoCapture.use`
+ frame LazyList; wrappers for `FaceDetectorYN`, `QRCodeDetector`,
`ArucoDetector`; minimal `Net.fromOnnx` + `blobFromImage`. Synthetic test
fixtures generated programmatically. *Gate:* `./mill core.test` green + API
surface dump reviewed by me.

**C — ZIO module ⚡**: `acquireRelease` Mats, `ZStream` camera frames,
zio-test. *Gate:* `./mill zio.test`.

**D — Examples 🎨**: `CannyEdges`, `FaceDetectHaar` (heritage) +
`FaceDetectYN` (modern), `CamFaceDetect`, `ArucoMarkers`, `QrDecode`.
*Gate:* all compile; `CannyEdges` + `QrDecode` produce asserted file output in CI.

**E — Docs microsite 📚**: VitePress (landing hero + logo, Getting Started,
Mat lifecycle concepts, Cookbook per example, ZIO page, 3.x migration note);
all snippets through mdoc; deploy workflow. *Gate:* site live at
`w0rxbend.github.io/scalacv`, snippets compile-checked.

**F — README + logo ✨**: SVG logo (eye/aperture × Scala swirl, light/dark
`<picture>` variants); README: centered logo → badges (CI, Maven Central,
Scala, license, docs) → ✨ Features → 🚀 Quick start → 🧠 Why (Mat safety
pitch) → 📚 Docs → 🗺️ Roadmap → 🤝 Contributing → ⚖️ License;
CONTRIBUTING/CoC/issue templates; keep original license + mcallisto lineage
credit. *Gate:* renders in both GitHub themes.

**G — CI/CD 🔁**: JDK 17+21 matrix (compile/test/fmt/fix, coursier cache);
headless-safe (no HighGUI in CI); tag-driven Sonatype Central release scaffold
(secrets left as marked TODOs); Scala Steward/Dependabot. *Gate:* CI green on
PR; `publishLocal` dry-run.

---

## 5. Milestones

M0 research merged (`ROADMAP.md` committed) → M1 review applied + my ack →
M2 Track A gate → M3 Track B gate → M4 C+D → M5 E+F+G → M6 `v0.1.0` tag +
announcement blurb.

---

## 6. CLAUDE.md (create verbatim in repo root during Phase R merge; extend as learned)

```markdown
# scalacv — agent context

Scala 3 wrapper for OpenCV 4.13 (official Java API via bytedeco javacpp-presets).

## Build
- Mill (latest). `./mill __.compile`, `./mill core.test`, `./mill examples.runMain <fqcn>`
- JDK 17+. Natives: `OpenCv.load()` → `Loader.load(classOf[opencv_java])`

## Conventions
- Latest stable Scala 3; no effects in core; ZIO 2 only in `zio` module
- Public API: no raw int constants, no unmanaged Mats, errors as `CvError` ADT
- scalafmt + scalafix pass before every commit; conventional commits
- ROADMAP.md checkbox flips ship in the same commit as the work

## Verification
- Never claim an OpenCV symbol/version without checking 4.13 docs or resolved jar
- Camera/GUI tests: tag `Hardware`, auto-skip when unavailable

## Do not
- Re-vendor natives in `lib/`
- Drop attribution to mcallisto/scalacv
- Change license or publish coordinates without asking
```

---

## 7. Kickoff command

> Read `PLAN.md`. Start **Phase R**: spawn Agent R1 (repo archaeologist) and
> Agent R2 (upstream scout) in parallel, merge their notes into `ROADMAP.md`
> + `CLAUDE.md`, commit, then run **Phase V** (reviewer subagent) and apply
> its findings. Pause for my ack before the implementation loop.


