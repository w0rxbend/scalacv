# Phase 3 — Correctness & robustness audit

*Each Phase-3 topic against the real code, with citations. Verdicts **OK** / **finding** / **N/A**. Read-only.*

Commit `fcf898d`.

---

## Findings introduced in Phase 3

| ID | Title | Sev | Conf | Location |
|---|---|---|---|---|
| **P3-1** | Int-overflow in dimension arithmetic on pathological sizes | LOW | SUSPECTED | `Interop.scala:58`; `OccupancyGrid.scala:20,60` |

Plus two **design notes** (not defects): structured error codes are deliberately not exposed (§3), and bit-exact pixel-hash tests are a determinism risk under a *future* multi-platform matrix (§7).

---

## 1. Type/shape handling (CV_8U..CV_64F × channels)

**OK — no unsupported combination reaches native code as a segfault; it arrives as a caught, named `CvError`.** The policy is explicit (`Cv.scala:5–17`): argument mistakes the library can *cheaply foresee* throw `IllegalArgumentException` up front; everything OpenCV itself rejects arrives as `cv::Exception` (a `CV_Assert` failure — a thrown C++ exception, **not** a wild pointer) and is caught by `Mats.produce`/`Cv.orThrow` and rethrown as `CvError.NativeCall(operation, cause)` naming the op (`Ops.scala:573–581`, `Cv.scala:25–37`).

Foreseeable type/shape guards that fire before native code:
- `Hough.requireEdgeImage` — non-empty `CV_8UC1` or a typed message (`Hough.scala:172–177`).
- `Interop.toBufferedImage` — `depth == CV_8U` and channels ∈ {1,3,4}, else `IllegalArgumentException` with a remediation hint (`Interop.scala:18–27`).
- `extractChannel` bounds-checks the channel index (`Ops.scala:363–368`); `blend`/`inpaint`/`seamlessClone` document the size/type contract.
- `Depth.disparity` requires matching stereo-pair size + valid `numDisparities`/`blockSize` (`Depth.scala:24–33`) and greys internally, so `StereoSGBM`'s 8-bit-single-channel requirement is met by construction.

Where the library does **not** pre-check (e.g. `equalizeHist` is `CV_8UC1`-only but has no guard, `Ops.scala:154`), a wrong type produces `CvError.NativeCall("equalizeHist", …)` — named, catchable, no crash. This is the deliberate B0 line, and it is consistent. **The one thing the library relies on is that OpenCV's type mismatches are `CV_Assert` throws, not segfaults — which is true for the operations wrapped here.**

## 2. Degenerate inputs (empty, 0×0, 1×1, single row/col, huge, overflow)

**OK for empty/tiny; P3-1 for pathological dimensions.**

- **Empty** is guarded broadly and early: `findContours` (`Contours.scala:143`), all `Draw` ops via `requireDrawable` (`Draw.scala:270–271`), `Interop` (`:23`), `Qr`/`Aruco`/`FaceDetect`/`Dnn`/`Navigator` detectors (`:48,119,212,113,47`). `imread` empty results become `Left(DecodeFailed)` centrally (`Images.scala:115–119`).
- **1×1 / single row/col / small** pass through to OpenCV, which handles or `CV_Assert`-rejects them → `CvError.NativeCall`. `Contour` special-cases the empty contour for `boundingRect`/`area`/`centroid` to avoid a native throw on `m00==0` etc. (`Contours.scala:35–72`).
- **`Image.blank`** rejects non-positive size (`Image.scala:457`); `Size` rejects negative extents (`Geometry.scala:29`); `Rect` rejects negative extent (`:36`).
- **Overflow — OK where it matters:** `Rect.area` uses `Long` explicitly *because* `width*height` overflows `Int` past ~46340 px (`Geometry.scala:38–41`) — the one place the authors clearly reasoned about it.
- **P3-1 (LOW, SUSPECTED):** two `Int` products are unguarded against overflow on pathological dimensions: `Interop.toMat` compares `d.length == w * h * 3` (`Interop.scala:58`) and `OccupancyGrid` sizes arrays with `cols * rows` (`:20,60`). For a `w*h*3` above `Int.MaxValue` the comparison silently mis-evaluates (wrong branch), and a negative `cols*rows` throws `NegativeArraySizeException` rather than a typed error. In practice both are unreachable through normal use — a BufferedImage that large OOMs first, and `OccupancyGrid` is private-constructed via a factory with small grid dimensions — so this is theoretical. **Repro would require a multi-gigapixel input;** downgrade/close if unreproducible. Cheap fix: widen to `Long` and/or `require` a sane bound in `OccupancyGrid.apply`.

## 3. Error translation (typed hierarchy, code + function name)

**OK, with a deliberate design boundary.** `CvError` is a sealed `RuntimeException` hierarchy (`CvError.scala:10–52`): `NativesMissing`, `DecodeFailed`, `LoadFailed`, `EncodeFailed`, `CalibrationFailed`, `NativeCall`. `NativeCall` wraps the original `CvException` as its `cause` and puts the *named operation* in the message. The OpenCV message (which contains the error code `(-215:Assertion failed)` and the C++ function name) is **preserved verbatim** in the cause but **not parsed into structured fields** — a stated choice (`Cv.scala:20–23`, `CvError.scala:48–52`): OpenCV's text is not a stable interface, so extracting codes would invent structure upstream does not promise.

**Design note (not a defect):** a consumer that wants to *branch on* an OpenCV error code or function name must string-match the cause's message — there is no typed accessor. That is defensible, but worth surfacing in the report as an API-ergonomics decision the audit noticed. No change recommended without a concrete need.

## 4. File/stream I/O (imread-empty, VideoCapture/Writer release)

**OK.**
- **imread/imdecode empty is checked at every call site** — because there is exactly one: all reads route through `Images.read`/`decode`, which call `own(...)` to convert an empty Mat into `Left(DecodeFailed)` and release the empty handle (`Images.scala:42–46, 101–119`). Grep confirms **no** `Imgcodecs.imread`/`imdecode` anywhere outside `Images.scala`. `imwrite`/`imencode`'s three failure shapes are flattened to `EncodeFailed` (`Images.scala:48–93`), including the `haveImageWriter` pre-check that avoids the `CvException` an unknown extension would throw.
- **VideoCapture release on failure:** `Video.openCapture` releases the capture on the exception path (`Video.scala:310–313`) *and* on a `Left` outcome (`:314`). The timeout-parameter fallback (`:297–301`) is also release-safe.
- **VideoWriter release on failure:** `Recorder.open` releases `vw` when `open` reports `!isOpened` (`Camera.scala:247`) and reports a typed `LoadFailed` with a codec hint. `Camera.recordTo` closes the recorder in `finally` (`Camera.scala:154`).

## 5. Thread safety (what is safe to share)

**OK — documented, not assumed; here is the consolidated matrix.**

| Type | Concurrent use | Basis |
|---|---|---|
| `Managed[A]` release/`get` | **Safe** — `AtomicReference` CAS (`Managed.scala:27,88–95`) | release is idempotent & atomic across threads |
| `Image`/`Camera`/`Recorder` handle lifecycle | **release safe**; concurrent *transforms* on one instance are not (move semantics + shared Mat) | inherits `Managed` for release only |
| A shared `Mat`'s pixel buffer | **Reads safe; concurrent writes not synchronised** | OpenCV refcount ops are atomic; pixel writes are not |
| `MotionDetector`, `Odometry`, `LoopDetector`, `ObjectTracker` | **Not thread-safe** (documented `Motion.scala:33`, `Odometry.scala:19`, `LoopDetector.scala:20`) | mutable `var`/buffer state |
| `Dnn.Net`, `FaceDetectorYN`, `FaceRecognizerSF` | **One per thread** (documented `Dnn.scala:134`, `BackgroundEffect.scala:122`, `FaceDetect.scala`) | stateful native objects |
| Value types (`Contour`, `Geometry`, `Pose`, results) | **Safe** — immutable, no pointer | copied-out data |

The stateful wrappers' `var`-swap-and-release (e.g. `FrameDiff.previous`, Phase 1 §4) would be a use-after-free under concurrent `detect` — but that is the explicitly documented single-thread contract, not a defect. **No wrapper claims thread safety it does not have.**

## 6. Native library loading

**OK — robust, single-classloader assumption.** `OpenCv.load()` is idempotent, double-checked-locked, `@volatile` flag (`OpenCv.scala:23–34`). Failure modes are all mapped to `CvError.NativesMissing` with the exact dependency lines for the *actual* platform: `UnsatisfiedLinkError`/`NoClassDefFoundError` at load (`:56–61`, `:186–201`), a missing soname not in the payload (`:104–114`), an un-openable `delete`/`nativeObj` for reflection (`Releasable.scala:81–98, 129–161`). Demand-driven `System.load`/`loadGlobal` avoids the highgui-ABI-interposition crash (`OpenCv.scala:63–128`, with the reproduced-crash rationale). Windows uses a bounded bulk-load fallback (`:136–146`).
- **First-call latency:** the first `load()` extracts ~196 MB and `dlopen`s it (noted in `zio` docs `:22–23,46`); on the blocking pool in `zio`. Not a correctness bug, a documented startup cost.
- **Multiple classloaders (edge):** `loaded` is a per-`OpenCv`-object flag; if the class is loaded by two classloaders, each attempts `System.load` of the same `.so`, which the JVM rejects with `UnsatisfiedLinkError: already loaded in another classloader`. No guard for this; it surfaces as `NativesMissing`. An uncommon deployment (some app servers / SBT-in-SBT), worth a documented caveat, not a code fix.

## 7. Determinism across platforms / OpenCV builds

**Design note (determinism risk under a future matrix).** The project's own rule — "no optimization without a bit-identical output hash" — is enforced by **bit-exact** FNV-1a pixel hashes in `PixelHashTest` (`core/test/.../PixelHashTest.scala:31–48`) and `GraphicsAlphaRoiTest`. Those are correct and valuable **on the current single-platform CI** (Phase 0: one classifier). But bit-exact equality across SIMD paths / OpenCV builds / thread counts is exactly what the plan warns will make tests flaky. So:
- **Today:** safe — CI is single-platform, and these hashes catch real regressions cheaply.
- **When Phase 8's multi-OS × arch matrix lands:** bit-exact hashes will break on SIMD/rounding differences. The mitigation is the plan's own Phase-4 recommendation — **tolerance-based golden tests** (max-abs-diff / PSNR) for the *cross-platform* golden set, keeping bit-exact hashes only as *same-platform* regression keys. Flagged for Phase 4/8, not a current bug.

---

## Topic summary

| Topic | Verdict |
|---|---|
| Type/shape → typed error, never segfault | **OK** (relies on OpenCV asserts being throws — true here) |
| Degenerate inputs | **OK** (empty/tiny guarded) + **P3-1** (pathological-dim int overflow, LOW/SUSPECTED) |
| Error translation | **OK**; structured codes deliberately not exposed (design note) |
| imread-empty at every call site | **OK** (single call site, centrally checked) |
| VideoCapture/Writer release on failure | **OK** |
| Thread safety documented | **OK** (matrix above; no false safety claims) |
| Native loading failure modes | **OK**; single-classloader caveat |
| Cross-platform determinism | **OK today**; bit-exact hashes are a matrix-era risk → Phase 4/8 |
