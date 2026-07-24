# DOCS-PLAN.md — scalacv documentation, Phase 0 and plan

This is the pre-build inventory and the verified pipeline for the scalacv docs site. It reflects the
**actual** repository, not the stale fork the original brief assumed: the repo already has a README,
LICENSE, CHANGELOG, CONTRIBUTING, `.github/`, and — until this migration — a working VitePress site.

## Decisions on record

- **Stack: Docusaurus 3.10** (pinned), replacing the previous VitePress site. Requested explicitly;
  not re-litigated. The Docusaurus app lives in `website/` and is built **alongside** the VitePress
  site so every intermediate commit is safe to push; VitePress is removed only at the final flip.
- **Build tool: Mill 1.1.7.** No maintained Mill mdoc plugin exists, so mdoc runs as a JVM main
  (already wired as `docs.mdoc` / `docs.mdocCheck`). Node builds only the static site.
- **API reference: themed/unified Scaladoc into `static/api/`** (spike result A — see
  `API-REFERENCE.md`).
- **Search: Pagefind** over the built HTML (guides + Scaladoc), local, no account, works on PR
  previews (see `SEARCH-REPORT.md` when it lands).

## Corrections the source forced on the brief

The brief made assumptions that the source contradicts. Documenting observed behavior, not intended:

1. **No view-aliasing API.** The brief's Memory page assumes `row`/`col`/`rowRange`/`colRange`/`Rect`
   submats that share the parent buffer. **They don't exist** in the public API: `Image.crop`
   returns an independent copy (submat + clone, view released before the parent). The only
   buffer-borrowing surface is `Video.frames` / the zio `frameStream`. The Memory page documents
   *that*, not a generic view-aliasing hazard.
2. **No `PointerScope`.** Lifetime is scalacv's own `Managed` / `Releasable`, not JavaCPP
   `PointerScope`. The thread-local scope caveat in the brief does not apply.
3. **`docJar` output is `out.jar`, not `*-javadoc.jar`.** The existing `docs.yml` globs
   `*-javadoc.jar` and silently produces no Scaladoc; the Docusaurus pipeline resolves the path via
   `mill show` instead. (Fix `docs.yml` at the flip.)

## Phase 0 — public API inventory (grouped by concern)

Enumerated from source (`*/src/scalacv/**.scala`, package `scalacv`; zio is `scalacv.zio`). Every
`scala mdoc` snippet in the docs already compiles against this API (the `docs.mdocCheck` gate is
green), so **fenced examples are accurate by construction**; the audit target is prose and Scaladoc
comments.

| Concern | Entry points | Guide page |
|---|---|---|
| I/O | `Image.read/decode/reading/blank/write/bytes`, `Images.*` | image-io |
| Interop | `Image.toBufferedImage/fromBufferedImage/mat/managed/wrap`, `Interop` (private) | notebooks, image-io |
| Color / depth | `Image.gray/convert/toHsv/channel/colorMap/normalize/equalizeHist/…`, `Ops` ext(Mat) | color-masking, image-processing |
| Filtering | `Image.blur/gaussianBlur/canny/threshold/adaptiveThreshold/sharpen/…`, `Filter` | filters, image-processing |
| Geometry | `Point/Point3/Size/Rect/Scalar` (immutable case classes) | geometry |
| Transforms / morphology | `Image.resize/crop/flip/rotate/undistort/erode/dilate/morphology`, `Ops` | transforms |
| Contours / features | `Contour`, `Mat.findContours`, `Features`/`Descriptors`; Hough `PolarLine/Segment` | contours, hough |
| Drawing | `Image.drawRect/drawCircle/drawText/drawContours`, `Draw` ext(Mat) (mutates) | drawing |
| Detection / DNN | `Dnn`, `Cascades`, `FaceDetect`/`Face`, `Qr`, `Aruco`, `FaceRecognizer`/`Gallery` | object-detection, face-recognition, dnn |
| Tracking / motion | `Tracker`, `Kalman`, `ObjectTracker`, `Motion`, `OpticalFlow` | tracking, motion-detection |
| Pose / AR / depth | `PoseEstimator`/`Pose`, `HeadPose`, `Ar`/`Pose3D`, `StereoDepth`, `Calibration` | pose-estimation, marker-ar, calibration |
| OCR / background | `Ocr`/`OcrEngine`, `BackgroundEffect`/`Segmenter` | ocr, conferencing |
| SLAM / navigation | `Odometry`, `VisualOdometry`, `Localizer`, `LoopDetector`, `Navigator`, `OccupancyGrid` | navigation |
| Graphics | `Picture` scene graph, `Color`, `Chart`, `Animation` | graphics |
| Lifetime | `Managed` (`use`/`release`/`isReleased`), `Releasable`, `Camera`/`Recorder`, `Video.frames` | mat-lifecycle, native-cache, video |
| Error model | `CvError` ADT: `NativesMissing`/`DecodeFailed`/`LoadFailed`/`EncodeFailed`/`CalibrationFailed`/`NativeCall`; `Cv.attempt/orThrow` | error-model |
| ZIO | `acquireRelease`, `loadNatives`, `fromCv`, `readImage`, `imageScoped`, `frameStream`, `framesCopied` | zio |

### The five tasks a new user arrives wanting (confirmed against the API)

1. Add the dependency + natives classifier and have it load headless → **getting-started**.
2. Load / save an image → **image-io** (`Image.read` / `write` / `bytes`).
3. Run one real pipeline → **getting-started** / **image-api** (the `Image` chain).
4. Not leak native memory → **mat-lifecycle** (move semantics, `Managed`, the borrowing contract).
5. Interop with `BufferedImage` / bytes → **notebooks** / **image-io** (`fromBufferedImage`,
   `toBufferedImage`, `decode`, `bytes`).

## Verified task graph (what runs where)

```
Mill (JVM) ────────────────────────────────────────────►  Node (static site)
  core/vision/graphs/zio compile
  docs.mdoc         type-check every ```scala mdoc``` snippet, splice → out/docs/…/site/*.md
  apidocs.docJar    unified Scaladoc (core+vision+graphs) → out/apidocs/docJar.dest/out.jar
  zio.docJar        Scaladoc (zio)
        │
        ▼  scripts/assemble-docs.sh (the boundary)
  copy *.md → website/docs/           (index.md skipped; landing.md → index.md; /api/ → pathname://)
  unzip apidocs/zio doc jars → website/static/api/{core,zio}
  verify every /api/ link resolves to a generated file  (the guide→API check)
        │
        ▼
  docusaurus build   onBrokenLinks/onBrokenAnchors: throw; format:'detect' (CommonMark guides)
  pagefind (planned) index build/ HTML incl. static Scaladoc
        │
        ▼
  upload-pages-artifact + deploy-pages   →  https://w0rxbend.github.io/scalacv/
```

`website/docs/` and `website/static/api/` are **generated and git-ignored**. Contributors edit
`docs/mdoc/` sources; a header comment in the landing source says so. Dev loop: two terminals —
`./scripts/assemble-docs.sh` (or `mill docs.mdoc --watch` once wired) and `npm start` in `website/`.

## Sitemap (typed `sidebars.ts`, ordered by intent)

Introduction (What is scalacv, Getting Started) · The high-level API (Image API, 2D graphics,
Cookbook) · **Memory & resources** (Mat lifecycle, Native cache) · The OpenCV surface (Image I/O,
Image processing, Transforms, Colour/masking, Filters, Drawing, Geometry, Contours, Hough) ·
Detection & deep learning (Object detection, Face recognition, Tracking, Motion, Pose, Gestures,
DNN) · Applications (Conferencing, Screen analysis, OCR) · Robotics & 3D vision (Calibration,
Navigation, Marker AR) · Video & runtime (Video) · Concepts (Error model, Low-level) · Integrations
(Notebooks, ZIO).

## Per-page status

All 34 guide pages port cleanly (every snippet compiles via mdoc; anchors and API links resolve).
"Enhance" = agreed follow-up, not a blocker.

| Page | Status | Follow-up |
|---|---|---|
| landing (index) | done | — |
| getting-started | done | add Mill\|sbt\|scala-cli **Tabs** (needs .mdx) |
| mat-lifecycle | ported | add `memory` admonition + WRONG/RIGHT pair; fold in native-memory model |
| native-cache | ported | — |
| image-api, image-io, image-processing, transforms, color-masking, filters, drawing, geometry, contours, hough | ported | routine Scaladoc-link + prose audit |
| object-detection, face-recognition, tracking, motion-detection, pose-estimation, gestures, dnn | ported | — |
| conferencing, screen-analysis, ocr | ported | — |
| calibration, navigation, marker-ar | ported | — |
| video | ported | — |
| error-model, low-level, cookbook, graphics, notebooks, zio | ported | — |

## Remaining work tracks (dependency order)

Base-path config + mdoc wiring (**done**) unblock everything. Then, roughly parallel:

1. **Search** (Pagefind) → `SEARCH-REPORT.md`. Owner: `website/`, CI.
2. **Design polish**: self-hosted fonts, `memory` admonition swizzle, Tabs on install → `DESIGN.md`.
3. **Scaladoc pass**: fix the unresolved `@link` warnings surfaced by `apidocs.docJar`
   (`usingFile`, `FFmpeg`, `Video.frames`, `IllegalStateException`) and audit `@param`/`@return`
   allocate/alias/mutate facts. Separate commit series.
4. **CI + flip**: replace VitePress `docs.yml` with the Docusaurus build/deploy; delete
   `docs/vitepress`, `docs/package*.json`; fix the `*-javadoc.jar` glob.
5. **Perf / a11y / SEO**: Lighthouse + axe budgets, sitemap, OG images → `PERF-REPORT.md`.

Deferred items are filed by `create-issues.sh`.
