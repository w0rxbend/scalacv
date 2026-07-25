# Phase 1 — The ownership model

*How native memory is owned in `scalacv`, reconstructed from the code, plus every place the code departs from its own stated contract.*

Commit: `fcf898d`. Evidence is first-hand (every `core` file holding a pointer was read in full; `vision`, `graphs`, `zio` were inventoried and the sharpest cases re-read directly). Citations are `file.scala:LINE`.

---

## 1. The intended contract (prose)

scalacv does **not** use JavaCPP `PointerScope` anywhere (Phase 0 established: 0 hits). It replaces the thread-local-scope model with an explicit, deterministic one built on three primitives:

### 1.1 `Managed[A]` — deterministic single release

`Managed[A]` (`core/src/scalacv/Managed.scala:25`) wraps one native object behind an `AtomicReference`.

- `release()`/`close()` is an idempotent compare-and-set (`Managed.scala:88–95`): a second release is a no-op, so a double free cannot originate here.
- `get` throws `IllegalStateException` if already released/consumed (`Managed.scala:65–67`) — the use-after-free guard, on the Scala side, *before* JNI.
- `take()` (`Managed.scala:80–85`) transfers ownership out and leaves the source spent **without freeing** — the primitive behind `Image`'s move semantics.
- `use[B](f)` (`Managed.scala:100–102`) and `Managed.use(a)(f)` (`Managed.scala:125`) are the scoped forms (try/finally release).

### 1.2 `Releasable[A]` — how a type is freed (the two-level truth)

`Releasable[-A]` (`core/src/scalacv/Releasable.scala:20`) encodes the fact that *only 3 of ~188* `org.opencv.*` pointer types have a public `release()`:

- **`Mat` / `VideoCapture` / `VideoWriter`** → `release()` (`Releasable.scala:34–36`).
- **The other 185** (every detector: `CascadeClassifier`, `FaceDetectorYN`, `Net`, `ORB`, `BFMatcher`, `QRCodeDetector`, `ArucoDetector`, `StereoSGBM`, `BackgroundSubtractorMOG2`, `KalmanFilter`, `CvTracker`, `FaceRecognizerSF`, …) → a cached `MethodHandle` onto the binding's private `delete(long)` (`Releasable.scala:46–53`, `NativeDelete` `:68–98`), **and** the finalizer is disarmed first (`NativeFinalizer.disarm`, `Releasable.scala:100–166`) by zeroing `nativeObj` so the binding's unconditional `finalize()→delete(this.nativeObj)` becomes `delete(0)` (a C++ no-op). Disarm-before-delete order is explicit (`Releasable.scala:51–53`); if the field can't be written it **throws rather than deletes** (`Releasable.scala:129–133`) — a leak over a double-free, deliberately.

**Two-level ownership, and how the code treats it.** `cv::Mat` has an atomic refcount over the pixel buffer; the JavaCPP `Pointer` separately owns the `cv::Mat` header. scalacv **deliberately frees only the buffer** for `Mat` (`Releasable[Mat] = _.release()`) and lets the ~100 B header be reclaimed by `CleanableMat.finalize()` at the next GC — documented with a measurement (`Releasable.scala:25–33`: 2000 unreleased 1000×1000 Mats → 5.8 GB RSS vs 144 MB released). This is **not** a conflation bug: the expensive allocation is freed deterministically; only the tiny header waits for GC, on the JVM heap where GC is the right owner. For the other 185 types there is no such split — `delete(long)` frees the whole object.

### 1.3 The three ownership dispositions of a returned Mat

Every method that returns something native falls into exactly one of these, and the codebase is remarkably disciplined about which:

| Disposition | Meaning | Canonical site |
|---|---|---|
| **Owned (transferred)** | caller must release/close it | `Ops` extensions return `Managed[Mat]` via `Mats.produce` (`Ops.scala:573–581`); `Image` transforms return a fresh `Image` |
| **Borrowed** | caller must *not* release; owner outlives the call | `Image.mat` (`Image.scala:81`); `Video.frames` iterator Mat (`Video.scala:240–259`); `Recorder.write` frame (`Camera.scala:199,206`) |
| **Copied-out to plain data** | no pointer survives the call at all | `Contour` (`Contours.scala:20`), `Geometry` value types (`Geometry.scala`), Hough results (`Hough.scala`) |

The `Ops` contract is stated in-file (`Ops.scala:8–39`): every op is *pure w.r.t. its receiver* — allocates a fresh dst, never writes/releases/aliases the receiver — which is exactly what lets an op run on a *borrowed* Mat (a video frame) with no transfer ceremony. `pipe` (`Ops.scala:536–538`) and `Mats.chain` (`Ops.scala:558–564`) release each intermediate as the next stage consumes it. `Image`'s move semantics (`Image.scala:413–429`) make a long chain hold exactly one live Mat.

The **one deliberate inversion** is `Draw.scala` / `Graphics.scala`: drawing mutates the receiver in place (OpenCV has no out-of-place draw), signalled by `drawX`/`fillX` names returning `Unit` (`Draw.scala:8–24`).

---

## 2. Ownership inventory

Every construct that transitively holds a `Pointer`. "Scoped?" = released by a deterministic construct (`Managed.use`/`.use`/try-finally/`pipe`/`Using`/ZIO scope) rather than GC.

### 2.1 core — wrapper types with a native field

| Wrapper | Underlying native | Allocates | Frees | Escapes? | Aliases? | Scoped? |
|---|---|---|---|---|---|---|
| `Managed[A]` `Managed.scala:25` | any `A` | `Managed.apply` | `release`/`close` (CAS) | yes (it *is* the handle) | no | it **is** the scope primitive |
| `Image` `Image.scala:58` | `Managed[Mat]` | `Image.apply`/`read`/`blank`/`wrap` | `close` `:399` (AutoCloseable) | yes, owned | no (owns one Mat; `crop` clones off the submat) | via `Image.reading` `:480`, `Using`, or move-chain |
| `Camera` `Camera.scala:56` | `Managed[VideoCapture]` | `Camera.open`/`openFile` | `close` `:158` | yes, owned | no | `Camera.using`/`usingFile` `:173,181` |
| `Recorder` `Camera.scala:194` | `Managed[VideoWriter]` | `Recorder.open` | `close` `:217` | yes, owned | no | `Recorder.using` `:258` |
| `Filter` `Filter.scala:12` | none (stores `Image=>Image`) | — | — | no | no | N/A — pure |

### 2.2 core — transient native allocations (no field; released in-scope)

| Site | Native | Frees | Scoped? |
|---|---|---|---|
| `Mats.produce` `Ops.scala:573` | dst `Mat()` | returns owned, or `dst.release()` on throw `:580` | ✅ single allocation point for all ops |
| `Image.crop` `Image.scala:175` | `submat(rect)` (aliasing view) → `clone()` | `Managed.use` releases the view header; `finally handle.release()` `:177` | ✅ view never escapes; clone is independent |
| `Contours.findContours` `Contours.scala:145–157` | `ArrayList[MatOfPoint]` + `hierarchy Mat` | `finally` releases every `MatOfPoint` + hierarchy `:156–157` | ✅ |
| `Contour` lazy metrics `Contours.scala:35–109` | `MatOfPoint`/`MatOfPoint2f`/`MatOfInt` | `Managed(...).use` / `withPointMat` `:108–109` | ✅ (lazy vals store *plain data*, not the Mat) |
| `Hough.decoding` `Hough.scala:154–157` | out `Mat()` | `finally out.release()` | ✅ deliberately not `Managed` (nothing escapes) |
| `Intrinsics.cameraMatrix`/`distCoeffs` `Intrinsics.scala:26–33` | fresh `Mat`/`MatOfDouble` per call | **caller's job** | ⚠️ owned-return; every caller must free (they do — `Ops.undistorted:288–294`, `Ar`, `Calibration`) |
| `Interop.toMat` `Interop.scala:42–73` | `Mat(h,w,CV_8UC3)` | returns owned; `mat.release()` on throw `:72` | ✅ exception-guarded |
| `Ops.undistorted` `Ops.scala:288–294` | `intrinsics.cameraMatrix`,`distCoeffs` | nested `finally` releases both `:292–294` | ✅ |
| `Video.timeoutParams` `Video.scala:320–324` | `MatOfInt` | `params.use` at call site `:300` | ✅ |
| `DrawOps.withPolygons` `Draw.scala:288–293` | one `MatOfPoint` per polygon | `finally mats.foreach(_.release())` | ⚠️ see §3.1 (upstream residue) |

### 2.3 vision — wrapper types with a native field

| Wrapper | Native field | Frees | Retention shape |
|---|---|---|---|
| `Descriptors` `Features.scala:14–24` | `Managed[Mat] descriptors` | `close` `:24` | one Mat, escapes `detect`; ownership-transfer guarded by catch `:57` |
| `FaceRecognizer` `FaceRecognizer.scala:92` | `Managed[FaceRecognizerSF]` | `close` `:113` | one handle |
| `Tracker` `Tracking.scala:32` | `Managed[CvTracker]` | `close` `:49` | one handle |
| `Kalman` `Tracking.scala:78` | `Managed[KalmanFilter]` | `close` `:90` | one handle; see §4.1 |
| `ObjectTracker` `Tracking.scala:142` | `ArrayBuffer[Trk]`, each `Trk.kalman: Kalman` | `close` `:230` closes all | **bounded** by `maxAge` retirement `:199–201` |
| `MotionDetector.FrameDiff` `Motion.scala:167` | `var previous: Managed[Mat]|Null` | `close`→`reset` `:201,206` | one frame; swap-and-release each frame `:192–193` |
| `MotionDetector.BgSubtract` `Motion.scala:208` | `Managed[BackgroundSubtractorMOG2]` | `close` `:227` | one handle |
| `Odometry` `Odometry.scala:21` | `var previous: Image|Null` | `close` `:71` | one Image; swap-and-close each `update` `:49` |
| `LoopDetector` `LoopDetector.scala:22` | `ArrayBuffer[Descriptors]` | `close` `:68` | **unbounded** — see §4.2 |

All detector/algorithm objects (`CascadeClassifier`, `FaceDetectorYN`, `Net`, `ORB`, `BFMatcher`, `QRCodeDetector`, `ArucoDetector`/`Dictionary`, `StereoSGBM`) are build-use-free within a single call via `Managed.use`, or returned as caller-owned `Managed` with the null/empty path freed before wrapping (`Cascades.scala:116`, `FaceDetect.scala:188`, `Dnn.scala:66`, `Tracker.create:63`). No aliasing view (`submat`/`reshape`) is stored in a field; the two `submat` (`Depth.scala:80`, `Navigator.scala:56`) and three `reshape` (`BackgroundEffect.scala:98`, `Pose.scala:168,191`) sites are all `Managed.use`-scoped with the parent outliving the view.

### 2.4 graphs / zio

- **graphs** `Color`, `Chart`, `Picture`/`Graphics` scene graph store **no** native pointer — all pure values. Rendering (`Graphics.renderTo`/`draw`) draws into a **borrowed** Mat from `Image.paint`; every intermediate (`submat`+`clone` alpha-blend `Graphics.scala:483–488`, `MatOfPoint` `:515`) is `Managed.use`-scoped. `Animation.record`/`gif` allocate one owned `Image` per frame; `record` closes each in `finally` `:54`, `gif` in `finally` `:111` — **but see §3.2.**
- **zio** stores no native field; all resources are `ZIO.acquireRelease`/scoped effects that release on scope close incl. interruption (`acquireRelease:40`, `imageScoped:76`, `frameStream` buffer `:111`). `frameStream` emits the shared borrowed buffer (documented `:86–98`); `framesCopied:127` clones into an escaping caller-owned `Managed[Mat]` (documented `:121–125`).

---

## 3. Where the code violates (or bends) its own contract

Findings are labelled **CONFIRMED** (defect visible by reading the code / statically certain) or **SUSPECTED** (needs a runtime repro, deferred to Phase 5/6). No source is modified in this phase.

### 3.1 `DrawOps.withPolygons` — upstream converter residue leak — MEDIUM, CONFIRMED (self-documented)

`Draw.scala:288–293`. scalacv frees the `MatOfPoint`s it allocates, **but** the generated Java binding for `polylines`/`fillPoly`/`drawContours` runs the input through `Converters.vector_vector_Point_to_Mat`, which allocates one `Mat` per polygon plus one outer-vector `Mat` and **releases none**. The file says so verbatim (`Draw.scala:280–286`): *"bounded per call but … unbounded across a video loop."* Reached by `Image.drawContours`/`drawSegments`/`drawPolyline` on every annotated frame. **Repro + byte/iteration deferred to Phase 5.** Fix is non-trivial (reimplement the converter or avoid the vector overload).

### 3.2 `Animation.gif` — error-path leak of already-rendered frames — HIGH-ish, CONFIRMED (static)

`graphs/src/scalacv/Animation.scala:96`. `val images = Vector.tabulate(frames)(i => frame(i).render(...))` renders **all** frame `Image`s (each an owned Mat) *before* the `try` at `:98` whose `finally images.foreach(_.close())` (`:111`) is the only thing that closes them. If `frame(i).render` throws for any `i > 0` — a user `frame` lambda that throws, or a `Picture` that renders fine early and fails later — frames `0..i-1` are already allocated, never assigned to `images`, and never closed → their native buffers leak (bounded to the frames rendered before the throw, one-shot per call). **Repro deferred to Phase 5** (a `frame` lambda throwing at index 3, assert `Pointer.totalBytes` returns to baseline). Fix is trivial (render inside the `try`, or a `Using.Manager`).

### 3.3 `frameStream` / `framesCopied` (zio) — interrupt-window leak/race — LOW/MEDIUM, SUSPECTED

`zio/src/scalacv/zio/package.scala:113,128`. Two narrow windows: (a) `framesCopied` `frame.clone()` (`:128`) creates an owned Mat inside `.map`; if the fiber is interrupted after the clone but before a downstream stage acquires it into a scope, the clone leaks — there is no bracket around the clone itself. (b) `frameStream`'s reused `buffer` release finalizer (`:111`) could in principle run concurrently with an interrupted-but-in-flight native `capture.read(buffer)` (`:113`). Both depend on ZIO interruption timing; **needs a runtime repro to confirm**, hence SUSPECTED. The escaping-clone-must-be-released contract is otherwise documented and correct.

### 3.4 Owned-return methods that put the release burden on the caller — NOT defects, flagged for test coverage

`Intrinsics.cameraMatrix`/`distCoeffs` (`Intrinsics.scala:26–33`) allocate a fresh Mat **per call** and rely on every caller to free it. Every current caller does (`Ops.undistorted`, `Ar.estimatePose:68`, `Ar.project:84`, `Calibration`). This is contract-correct but is a **latent trap for future callers** and has no test asserting the release — Phase 4 target.

---

## 4. Retention structures — correct-by-contract, but sharp

### 4.1 `Kalman` setup releases the filter's *own* internal matrices — OK, assumption-dependent

`Tracking.scala:82,88,107–114`. `kf.get_transitionMatrix()`, `predict()`, `correct()` etc. return `Mat` headers that alias the `KalmanFilter`'s internal `cv::Mat` members, and each is wrapped in `Managed.use` → `release()` at scope end. This is **safe** — but only because the OpenCV Java binding's getter returns a *copy-constructed* header (`Mat _retval_ = me->member;` → shares the buffer, refcount++), so `release()` drops only the extra refcount and the filter's member survives. It is the **one** place in the library where a `Managed` wraps a Mat scalacv does not itself own, and its correctness rests on binding-internal refcount semantics rather than scalacv's own allocation. **Not a finding**, but the assumption deserves a locking test (many predict/correct iterations still produce sane output) in Phase 4.

### 4.2 `LoopDetector.keyframes` — unbounded native accumulation — MEDIUM design observation

`LoopDetector.scala:25`. `ArrayBuffer[Descriptors]` grows one native-descriptor-Mat **per keyframe** with no internal cap; freed only by `close()` (`:68`). This is the data structure's *purpose* (you match against stored keyframes) and it is documented "caller-owned … close it" (`:20`), so it is not a leak-by-bug. But it is the audit's "pointer retained in a growing collection" category in its purest form: a long-running SLAM front end holds unbounded native memory until `close()`, and the file's own note ("fine for hundreds of keyframes") concedes the ceiling. Phase 7 could add a bounded-index variant; for now it is a documented, caller-visible cost, not a defect.

---

## 5. What the audit checklist maps to here

| Phase-1 question | Answer |
|---|---|
| Conflate `Mat.release()` with header `delete`? | **No** — deliberate split: buffer freed deterministically, header GC'd; documented+measured (`Releasable.scala:25–33`) |
| `@ByRef`/view accessors escaping? | Only `submat`/`reshape`, all `Managed.use`-scoped, none stored in a field, none closed before parent (§2.2, §2.3) |
| Buffer-backed constructors / raw `data()`/indexers? | **None** — 0 `createIndexer`/`BytePointer`; only bulk `Mat.get/put` in `Interop`, `isContinuous`-guarded (`Interop.scala:36–38`) |
| `MatVector.get(i)` held across mutation? | **No such site** in the codebase |
| Retention in cache/lazy val/companion? | `Contour` lazy vals hold plain data; `LoopDetector.keyframes` §4.2 is the only accumulator; no companion/cache holds a pointer |
| Async close-on-other-thread? | **No async boundary in library code** (Phase 0); zio releases on scope, one interrupt-window SUSPECTED (§3.3) |
| Closed/disposed guard? | **Yes** — `Managed.get` throws `IllegalStateException` before JNI (`Managed.scala:65–67`); every wrapper reads through it |

---

## 6. Bottom line for review

The ownership model is **coherent, explicit, and unusually well-enforced**: one release primitive (`Managed`), one allocation point per concern (`Mats.produce`, `Hough.decoding`, `withPolygons`), a stated three-way disposition (owned / borrowed / copied-out), and a genuine use-after-free guard. The audit did **not** find a systemic ownership defect. It found:

- **2 confirmed leaks on error/loop paths** — `Animation.gif` eager-render (§3.2, easy fix) and the self-documented `withPolygons` upstream residue (§3.1, hard fix);
- **1 suspected interrupt-window issue** in zio (§3.3, needs repro);
- **2 sharp-but-contractual retentions** — `LoopDetector` unbounded (§4.2) and the `Kalman` binding-refcount assumption (§4.1);
- **coverage gaps** around owned-return release contracts (§3.4) — carried to Phase 4.

**Please confirm this ownership model is correct before I begin Phase 2 (memory-safety audit).** In particular I'd like your read on: (a) whether the deliberate Mat-header-GC split (§1.2) is acceptable as-is or should be a tracked item; (b) whether `LoopDetector`'s unbounded growth (§4.2) is in-scope for a fix or explicitly out; and (c) whether the `Kalman` refcount assumption (§4.1) warrants the locking test I propose.
