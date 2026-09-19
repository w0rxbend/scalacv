# Changelog

All notable changes to scalacv are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
`early-semver`: while the library is on `0.x`, a minor bump may break compatibility.

## [0.2.0] — 2026-09-19

### Fixed
- `Rect.bottomRight` no longer wraps negative for a corner past `Int.MaxValue`: the sums
  `x + width` and `y + height` were taken in `Int` and widened afterwards, so
  `Rect(Int.MaxValue - 1, Int.MaxValue - 1, 5, 5).bottomRight` returned `Point(-2147483645, …)`
  instead of `Point(2147483651, …)` — the same overflow `Rect.area` already widens to `Long` to
  avoid. Found by a new property test.

### Changed
- **OpenCV 4.14.0** (bytedeco `opencv:4.14.0-1.5.14`, JavaCPP 1.5.14) and **OpenBLAS 0.3.34**
  (`openblas:0.3.34-1.5.14`) replace 4.13.0-1.5.13 / 0.3.31-1.5.13. The two move together because
  `libopencv_core` links the OpenBLAS from the same presets line. Consumers must bump both classifier
  lines in their build to match; `Build.openCvVersion` now reports `4.14.0`. No scalacv API changed and
  the full suite passes unmodified against 4.14 on JDK 17 and 25 — including the Hough decode types and
  the `blobFromImage` mean/`swapRB` ordering, whose scaladoc now records the 4.14 verification.

### Internal
- **The test suite grew from 542 to 689 tests**, across core, vision, graphs and zio: `Image` ownership
  on the failure paths, pixel-level transform behaviour a dimension check cannot see, `Managed`
  transfer/adoption and suppressed-release policy, `Camera`/`Recorder` lifecycle at end-of-stream and on
  exceptions, the `BufferedImage` bridge, segmentation and compositing arithmetic, tracker confirmation
  and coasting, rotation conventions in `Localizer`/`VisualOdometry`, occupancy-grid ray integration,
  `Picture`/chart/GIF geometry, and ZIO scope release under interruption.
- **Two CI gates were silently hollow**, both the same Mill target-chaining bug: `./mill a.test b.test`
  passes the later targets to the first as test-name filters, so `./mill core.test zio.test examples.test`
  ran **only** `core.test` — the ZIO and examples suites never executed in CI — and the identical form in
  the scalafix step linted only `core` (20 of 75 sources) while its comment claimed all five modules.
  Both now use `+`-separated targets; nothing was hiding behind either.
- One forked JVM per test suite: a double free aborts the worker process, and Mill's default packing of
  several suites per worker meant such a crash took its co-tenants' results down unnamed.
- Build tooling: Mill 1.1.9, munit 1.3.6, munit-scalacheck 1.3.1, mdoc 2.9.2, OpenJFX 27 (local-only
  `examples-gui`). `-Werror` replaces the `-Xfatal-warnings` alias, which Scala 3.9 deprecates — the
  deprecation warning about the flag would otherwise be promoted to an error by the flag itself. Every
  CI job is now bounded by `timeout-minutes`, and `examples.test` also runs on the macOS arm64 leg.

## [0.1.0] — 2026-08-22

### Added
- `OpenCv.load()` — headless native loading that never requires a GUI toolkit, with a
  demand-driven resolver that never pulls a system OpenCV into the process.
- Resource lifecycle: `Managed[A]`, `Releasable` (with a finalizer-safe `delete(long)` bridge
  for the 185 handle types that have no public `release()`), and the `Cv.attempt` error policy.
- Typed enums and geometry value types; `Images` (read/write/encode/decode); imgproc extension
  ops with an explicit Mat-ownership contract; typed Hough, contours, cascades, QR, ArUco, YuNet
  face detection, ONNX inference, and headless drawing.
- Photo/stylisation transforms on `Image`: `colorMap`, `stylize`, `sketch`, `enhance`,
  `edgePreserving`, `inpaint`, `seamlessCloneInto`, `sepia`, `gamma`, `posterize`, `emboss`,
  `saturate`, `temperature`, plus colour-segmentation (`toHsv`/`inRange`/`applyMask`) and `blend`.
- **`scalacv-graphs`** — a 2D graphics layer: the immutable `Picture` scene graph (primitives, layout,
  affine transforms, dashed strokes), an RGBA `Color` palette (HSL, `wheel`/`ramp` palettes), `Chart`
  (bar/line), and `Animation` with a hand-rolled LZW `GIF` encoder. `image.draw(picture)` composites.
- **`scalacv-vision`** — the vision applications, each an extension layer over `core`:
  - **Faces & recognition**: YuNet detection with landmarks; SFace embeddings (`FaceRecognizer`,
    `FaceEmbedding` with cosine/L2 metrics) and an immutable `Gallery` for "who is this?".
  - **Pose**: `PoseEstimator` (MoveNet/OpenPose layouts, `PoseTopology`), `HeadPose` via `solvePnP`,
    `Gesture` recognition, and `drawSkeleton`.
  - **Markers/AR**: `Ar` marker pose (`Pose3D`/`MarkerPose`), axis/cube overlays.
  - **Tracking**: a constant-velocity `Kalman` point filter and `ObjectTracker` (SORT-lite
    tracking-by-detection with stable ids).
  - **Motion & video-conferencing**: `MotionDetector`; background blur / virtual backgrounds
    (`Segmenter` + `blurBackground`/`replaceBackground`).
  - **OCR** preprocessing (`forOcr`, deskew) with a pluggable engine; `Screen` analysis (template
    matching, change detection).
  - **Navigation / visual SLAM front end**: `OpticalFlow`, ORB `Features`, `StereoDepth` and obstacle
    detection, `VisualOdometry`/`Odometry`, `Localizer`, `Navigator`, `OccupancyGrid`, `LoopDetector`.
- `Camera`/`Recorder` (high-level capture) — including `Camera.taking`, a scoped batch that closes its
  frames for you, and a borrowing `Recorder.write(Mat)` so `Video.frames` records with no per-frame copy —
  `Video` interop, and `BufferedImage` interop (`Image.fromBufferedImage`/`toBufferedImage`, for AWT/Swing
  and notebook display).
- `Contour` geometry beyond area/perimeter/boundingRect: `centroid` (image moments), `convexHull`, and
  `approx` (Ramer–Douglas–Peucker polygon simplification).
- A `Models` registry + verifying downloader (`Models.fetch`); model specs live with their detectors
  (`FaceDetect.modelSpec`, `FaceRecognizer.modelSpec`).
- `scalacv-zio`: native ownership as ZIO `Scope`, plus a non-memoizing frame `ZStream`, typed-`CvError`
  boundary helpers (`fromCv`, `readImage`), and a scope-managed `imageScoped`.
- Ergonomics: `Color.toScalar`/`Scalar.toColor` bridges between the palette and OpenCV colours;
  one-call model verbs `image.estimatePose(net, …)` and `image.segment(net, …)`; `ObjectTracker.create`.
- Camera calibration: `Calibration` / `ChessboardPattern`, `Calibration.findCorners` and
  `Calibration.fromChessboard` (chessboard intrinsics + lens distortion, with the RMS reprojection
  error reported), a `CvError.CalibrationFailed` value for under-constrained captures, and
  `Image.undistort` / `Mat.undistort`. The recovered `Intrinsics` feed the existing pose stack
  (`Ar`, `HeadPose`, `Localizer`), turning its field-of-view guess into a measurement.
- A golden public-API signature test, so accidental API changes fail CI.
- `faces(Managed[FaceDetectorYN])` — a detector overload that keeps the spent-handle guard with the
  argument instead of discarding it through a bare `.get`.
- Opt-in ownership tracing (`-Dscalacv.trackOwnership=true`): a use-after-move `IllegalStateException`
  now carries the transform/terminal that consumed the handle as its cause.
- `Point.distanceTo` — the straight-line distance between two points, via `math.hypot` so it neither
  overflows nor underflows while squaring.
- `Releasable.nativeHandle` — `Releasable.handle` without the accessor argument, reading the address
  from the binding's own `nativeObj` field. `handle` is generic, so passing one type's
  `_.getNativeObjAddr` for another compiles cleanly and would free the wrong pointer; this form cannot
  be given the wrong accessor. `handle` remains for bindings that keep their address elsewhere.
- `Intrinsics` now rejects a distortion vector whose length is not one OpenCV accepts (0, 4, 5, 8, 12
  or 14), instead of passing it to native code that returns a silently wrong undistortion or pose. The
  accepted counts are public as `Intrinsics.ValidDistortionSizes`.

### Changed
- **Split the published artifact into three**: `scalacv` (core OpenCV wrapping), `scalacv-vision`
  (detectors/DNN/pose/tracking/OCR/calibration/SLAM), and `scalacv-graphs` (the `Picture`/chart/GIF
  layer). `vision` and `graphs` depend only on `core`; a consumer who only wants
  `Image.read(…).gray.canny(…)` no longer pulls a SLAM detector or a GIF encoder into their jar. Done
  before the first tag so MiMa (armed at `0.2.0`) guards a small, stable core rather than the whole
  surface. The golden API dump now covers the core module only (~1,600 lines, down from ~3,300).
- Slimmed `Image` to a lean core type. The domain verbs that only *start* from an image — `faces`,
  `detectHaar`, `qrCodes`, `arucoMarkers`, `arMarkers`, `drawSkeleton`, `markFaces`, `drawMarkerAxes`,
  `drawMarkerCube`, `drawTracks`, `forOcr`, `blurBackground`, `replaceBackground`, and `draw(Picture)` —
  are now **extension methods** in their domain files rather than members of `Image`. Call sites are
  unchanged under `import scalacv.*` (e.g. `image.faces(detector)` still reads the same).
- `Image.reading` now runs its body inside `Cv.attempt`, so a `CvError.NativeCall` from a transform in
  the chain returns as `Left` instead of escaping past the `Either`.
- `Intrinsics` is now a core type (was in `Ar`); `Image.undistort` takes `Intrinsics` directly, with the
  `Calibration` overload provided as a vision extension. The mid-level `Mat.undistort` is now
  `Mat.undistorted` (participle convention, and to free the `undistort` name for the `Image` overload).
- The use-after-move error now names the fix (`.copy`) and the tracing flag.

### Documentation
- `Image` scaladoc now documents the throwing surface (a transform throws `CvError.NativeCall`, an
  unchecked throw, on OpenCV rejection) and the library's Scala-first stance; `CLAUDE.md` records the
  two-tier (managed high-level / borrowed mid-level) API contract and corrects two stale notes.

The first released version.
