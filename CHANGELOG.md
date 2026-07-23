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
- A golden public-API signature test, so accidental API changes fail CI.

_Nothing has been released yet; `0.1.0` will be the first tag._
