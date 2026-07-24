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
- `scalacv-zio`: native ownership as ZIO `Scope`, plus a non-memoizing frame `ZStream`.
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
- Slimmed `Image` to a lean core type. The domain verbs that only *start* from an image — `faces`,
  `detectHaar`, `qrCodes`, `arucoMarkers`, `arMarkers`, `drawSkeleton`, `markFaces`, `drawMarkerAxes`,
  `drawMarkerCube`, `drawTracks`, `forOcr`, `blurBackground`, `replaceBackground`, and `draw(Picture)` —
  are now **extension methods** in their domain files rather than members of `Image`. Call sites are
  unchanged under `import scalacv.*` (e.g. `image.faces(detector)` still reads the same).
- The use-after-move error now names the fix (`.copy`) and the tracing flag.

### Documentation
- `Image` scaladoc now documents the throwing surface (a transform throws `CvError.NativeCall`, an
  unchecked throw, on OpenCV rejection) and the library's Scala-first stance; `CLAUDE.md` records the
  two-tier (managed high-level / borrowed mid-level) API contract and corrects two stale notes.

_Nothing has been released yet; `0.1.0` will be the first tag._
