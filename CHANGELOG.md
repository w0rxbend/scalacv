# Changelog

All notable changes to scalacv are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
`early-semver`: while the library is on `0.x`, a minor bump may break compatibility.

## [Unreleased]

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

_Nothing has been released yet; `0.1.0` will be the first tag._
