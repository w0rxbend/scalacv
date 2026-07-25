# Phase 2 — Memory-safety audit

*The Phase-2 checklist worked against the real code. Each item: **OK** / **VIOLATION** / **N/A**, with citations. New defects found here are added to the findings register; Phase-1 findings are cross-referenced, not repeated.*

Commit `fcf898d`. Phase 1's ownership model was approved before this phase began. Read-only: no source modified.

---

## Findings introduced in Phase 2

| ID | Title | Sev | Conf | Location |
|---|---|---|---|---|
| **P2-1** | `blurBackground`/`replaceBackground` leak the receiver `Image` on a throw from compositing | MEDIUM | CONFIRMED (static) | `BackgroundEffect.scala:148–150, 156–158` |
| **P2-2** | `alphaBlend` escaping `out` Mat allocated+filled outside `Using.Manager` protection | LOW | SUSPECTED | `BackgroundEffect.scala:57–59` |

Both detailed under [Lifetime & scoping](#lifetime--scoping).

---

## Lifetime & scoping

### Every allocation path ends in a deterministic free (not the GC deallocator) — **OK, with one deliberate exception and two new gaps**

- **OK (core):** the single allocation point `Mats.produce` (`Ops.scala:573–581`) wraps allocate→fill→wrap in try/catch and `dst.release()` on throw. `Hough.decoding` (`Hough.scala:154–157`), `Contours.findContours` (`Contours.scala:145–157`), `DrawOps.withPolygons` (`Draw.scala:288–293`), `Video.openCapture` (`Camera.scala`/`Video.scala:286–315`) all release in `finally`.
- **OK (vision, manual multi-Mat):** every site that allocates several bare Mats outside `Managed` releases all of them in a `finally` — `Ar.estimatePose` (`Ar.scala:60–68`), `Ar.project` (`:74–84`), `FaceRecognizer.embed` (`:99–111`), `Calibration.calibrate` (`:153–174`), `OpticalFlow.track` (`:52–69`), `Detectors`/Aruco (`Detectors.scala:125–134`). Verified line-by-line.
- **Deliberate exception (documented):** `Mat` frees only its buffer; the ~100 B header waits for GC (`Releasable.scala:25–33`). Accepted as design in Phase 1 §1.2; you asked it be tracked (see [REPORT tracking](#carried-to-later-phases)).
- **VIOLATION — P2-1:** `BackgroundEffect.blurBackground` (`:147–150`) and `replaceBackground` (`:155–158`) run the compositing **before** the `try`:
  ```scala
  val out = BackgroundEffect.blur(img.mat, mask.mat, strength, feather)  // outside try
  try Image(out) finally img.close()
  ```
  These extension methods consume the receiver `img` (that is what the `finally img.close()` is for), matching `Image.transform`'s consume-and-release-on-throw contract (`Image.scala:413–415`). But if `BackgroundEffect.blur`/`replace` throws — reachable from user code as `image.blurBackground(maskOfWrongSize)`, which trips `alphaBlend`'s `require` at `:28` with `IllegalArgumentException`, or a `CvException` from `gaussianBlur` — the `val out = …` line throws first and `img.close()` never runs. The source `Image`'s Mat leaks. (The intermediate `bg` is correctly released by the inner `.use`; only `img` leaks.) Contrast `Image.transform`, which puts the op **inside** the `try`. **Fix:** move the call inside the `try` (`try Image(BackgroundEffect.blur(...)) finally img.close()`). **Repro (Phase 5):** `image.blurBackground(Image.blank(2,2))` on a larger image; assert the source Mat is released.
- **VIOLATION — P2-2 (minor):** `alphaBlend` (`:57–59`) allocates the escaping result outside the `Using.Manager`:
  ```scala
  val out = Mat()
  sumF.convertTo(out, CvType.CV_8U)   // if this throws, `out` is not registered with `use` → leaks
  Managed(out)
  ```
  Every other Mat is `use(...)`-registered and released on unwind; `out` is intentionally unregistered so it escapes alive, but the fill at `:58` sits between allocation and the `Managed` wrap with no catch. A throw there (OOM, or a degenerate `sumF`) strands one Mat. Bounded, unlikely — **SUSPECTED** pending a forced-throw repro. **Fix:** allocate via a `Mats.produce`-style guarded wrap, or `use(Managed(out))` then `.take()`.

### PointerScope is thread-local; any async close-on-another-thread is a bug — **N/A (library) / one SUSPECTED (zio)**

No `PointerScope` and no async boundary exist in `core`/`vision`/`graphs` (Phase 0). The only concurrency is munit suite-parallelism against `AtomicReference`-guarded `Managed` (safe). The single async surface is `zio`, whose resources release on `Scope` close (incl. interruption) — **OK** — except the interrupt-window clone in `framesCopied` (Phase 1 §3.3, **SUSPECTED**, ID carried).

### Values outliving their scope use `retainReference`/`extend` with a matching release — **N/A**

No `PointerScope`, so nothing to extend. Cross-scope survival is expressed by ownership transfer (`Managed.take`, returning a `Managed`/`Image`), not scope extension.

### Class-filtered `PointerScope` not swallowing unintended types — **N/A**

No `PointerScope` construction anywhere.

### No `Pointer` crosses a try/catch unfreed; mid-expression exception safety — **OK, except P2-1/P2-2**

`cv::Exception` surfaces as a JVM exception mid-expression, and the codebase is built for it: `Mats.produce` releases the half-built dst on throw (`Ops.scala:579–581`); `Image.transform`/`paint` release the source/half-built Mat on throw (`Image.scala:413–429`); every manual multi-Mat block uses `finally`. The two exceptions are P2-1 and P2-2 above. **No expression allocates two owned Mats and loses the first when the second throws** other than P2-2.

---

## Aliasing & mutation

### Operations documented as copies actually copy; views documented as views — **OK**

- `Image.crop` (`:166–177`) takes a `submat` **view** then `clone()`s it to an independent buffer — documented "returning an independent copy (not an aliasing view)" (`:163`), and the code matches.
- `Image.copy` (`:402`) = `handle.get.clone()` — a real deep copy.
- Borrowed accessors are labelled: `Image.mat` "borrowed" (`:77–81`), `Video.frames` iterator "borrowed … valid only until the next interaction" (`Video.scala:131–145, 218–229`), `Recorder.write` "borrowed, not consumed" (`Camera.scala:196–206`).

### In-place calls where `src == dst` are OpenCV-supported or rejected — **OK**

Every pure op in `Ops.scala` passes the **freshly allocated** `produce` dst (`_`) as the destination, so `src ≠ dst` universally — including the functions with undefined aliased behaviour (`Core.flip` `:245`, `Imgproc.warpAffine` `:274,496`, `Imgproc.filter2D` `:441`, `Core.transform` `:424,455`, `resize` `:180,190`). `masked` (`:354`) is `bitwise_and(self, self, dst, mask)` — `self` appears as both inputs, but element-wise and `dst` is fresh: safe. The **only** genuine in-place `src==dst` call in the codebase is `Graphics.scala:488` `Core.addWeighted(view, t, backup, 1-t, 0, view)` — `addWeighted` is a per-element affine combination, defined in-place, and `backup` is a distinct `clone()`: **safe**. Drawing ops (`Draw.scala`) mutate the single receiver with no separate `src`.

### Non-continuous Mats (submats/ROIs) handled where raw buffers are read — **OK**

Raw bulk-buffer access (`Mat.get/put(0,0,array)`) occurs only in `Interop`, which checks `isContinuous` and clones the rare non-continuous case (`Interop.scala:36–38`, `55–68`). `Segmenter.decodeMask` reads a row via `flat.get(channel,0,plane)` after `output.reshape(1,c)` (`BackgroundEffect.scala:98–100`) — `reshape` itself requires continuous data, so a non-continuous tensor throws there rather than silently misreading; DNN `forward` output is continuous in practice. Hough decodes element-by-element via `out.get(i,0)` (`Hough.scala:167`), which makes no contiguity assumption. No other code indexes a raw buffer.

---

## Scala-3-specific hazards

### `case class` wrapping a `Pointer` (copy()/equals on address) — **OK (N/A by construction)**

No `case class` holds a native pointer. Every native-holding wrapper is a `final class` — `Managed`, `Image`, `Camera`, `Recorder`, `Descriptors`, `FaceRecognizer`, `Tracker`, `Kalman`, `ObjectTracker`, `LoopDetector`, `Filter`. The case classes (`CaptureOptions`, `CaptureInfo`, `TemplateMatch`, `FaceMatch`, `FeatureMatch`, `LoopClosure`, `QrCode`, `ArucoMarker`, all of `Geometry`/`Hough`/`Contour`) hold only primitives and `Seq[Point]`-style plain data. So no auto-derived `copy()` can alias a buffer and no `equals`/`hashCode` compares addresses.

### `lazy val`/`given` holding a native handle → permanent retention — **OK**

`Contour`'s lazy vals cache **plain data** (`Rect`, `Double`, `Option[Point]`, `Contour`), not Mats — the native Mats they compute through are `Managed.use`-scoped (`Contours.scala:35–109`). Every `given Releasable[...]` is a stateless strategy — either `_.release()` or a `Releasable.handle(fn)` lambda capturing only an address-getter (`Releasable.scala:34–36`, `Features.scala:35–36`, `Cascades/Depth/Detectors/Dnn/FaceDetect/Motion/Tracking/Animation`). `Models.httpClient` is a `lazy val HttpClient` — a JVM object, not native (`Models.scala:55–60`). No given/lazy retains a native handle.

### Extension methods / `inline` returning borrowed views without documentation — **OK**

Extension methods returning native either return an **owned** `Managed`/`Image` (the `Ops` extensions, `segment`, `blurBackground`) or a documented **borrow** (`Image.mat`). No extension returns an undocumented aliasing view.

### Collection pipelines producing intermediate Mats never freed — **OK**

`Filter.all` and the `Picture`/`Chart` folds are pure value pipelines (no Mats). `Camera.take` returns `Seq[Image]` of **owned** frames, explicitly "each is yours to close" with the scoped `taking` alternative (`Camera.scala:104–121`). `.map(Image.wrap)`/`.map(_.clone())` sites wrap each element in an owner; none drops a Mat on the floor. `ObjectTracker.update` folds over tracks without allocating stray Mats.

### Iterator/LazyList over native escaping its owning scope — **OK, with two documented footguns**

- `Video.FrameIterator` (`Video.scala:340–379`) owns exactly one Mat, is `retire()`d when its scope exits, and refuses to decode into a released Mat — deliberately **not** a `LazyList` (memoisation rationale, `Video.scala:114–145`). Correct.
- **Footgun (documented):** `Animation.frames` returns a memoising `LazyList[Image]` (`Animation.scala:71–74`); holding the head retains realised frames' Mats. Documented "each image is yours to close" — a caller-visible cost, tied to Phase-1 §3.2 (`Animation.gif`, the *bug* variant, ID §3.2 in scope).
- **Footgun (documented):** `zio.frameStream` emits a shared borrowed buffer (`package.scala:106–119`) — the borrowing contract is spelled out (`:86–98`).

---

## API-level safety

### Closed/disposed guard (throw `IllegalStateException`, not segfault) — **OK (top-item satisfied)**

`Managed.get` throws `IllegalStateException` with a diagnostic message **before** anything crosses JNI (`Managed.scala:60–67`), and every wrapper dereferences through `handle.get` (`Image.width` `:63`, `Camera.info` `:61`, `Recorder.write` `:211`, `Kalman.predict` `:82`, …). Optional `-Dscalacv.trackOwnership=true` attaches the consuming call site as the cause (`Managed.scala:29–58`). This is the guard the checklist flags as top-severity-if-absent; it is present and used uniformly.

### Does the API make leaking possible by default? — **PARTIAL (resource-safe surface exists but owned-returns can still leak)**

Leaking is possible where an owned handle escapes and the caller forgets to close: `Features.detect` → `Descriptors` (`Features.scala:41–54`), `Camera.take` → `Seq[Image]` (`Camera.scala:110`), `zio.framesCopied` → `Managed[Mat]` per element, `Intrinsics.cameraMatrix`/`distCoeffs` per call (`Intrinsics.scala:26–33`), and every `Managed`-returning `Ops` op. **Each has a scoped alternative** already: `Managed.use`, `Image.reading`, `Camera.taking`/`foreach`, `Recorder.using`, `zio` scopes, `.use`/`.pipe`/`Mats.chain`, and all wrappers are `AutoCloseable` (so `scala.util.Using` works). So the *safe* path is one method away, but the default owned-return is unguarded by the type system. This is inherent to a zero-cost native wrapper; **the gap is test coverage of the release contracts** (Phase 4), not a missing API. `Intrinsics.cameraMatrix`/`distCoeffs` are the sharpest — private, caller-must-free, no scoped variant (Phase 1 §3.4).

### Library sets global JVM properties at load time — **OK (does not)**

No `maxBytes`/`maxPhysicalBytes`/`setNumThreads`/`System.setProperty` anywhere (Phase 0, 0 hits). `OpenCv.load()` only `Loader.load`/`System.load`/`loadGlobal`s libraries (`OpenCv.scala:36–128`) and reads `-Dscalacv.trackOwnership` / `getBoolean` (read-only). The library correctly leaves resource-limit and thread-count properties to the application.

---

## Checklist summary

| Item | Verdict |
|---|---|
| Deterministic free, not GC deallocator | **OK** + P2-1/P2-2 gaps + documented Mat-header split |
| PointerScope thread-local async | **N/A** (library) / 1 SUSPECTED (zio) |
| retainReference/extend | **N/A** |
| Class-filtered PointerScope | **N/A** |
| No Pointer crosses try/catch unfreed | **OK** except P2-1/P2-2 |
| Copies copy / views documented | **OK** |
| In-place src==dst supported-or-rejected | **OK** |
| Non-continuous Mats handled | **OK** |
| case class over Pointer | **OK (N/A)** |
| lazy val / given retention | **OK** |
| Extension/inline borrowed views | **OK** |
| Collection pipelines strand Mats | **OK** |
| Iterator/LazyList escape | **OK** + 2 documented footguns |
| Closed/disposed guard | **OK (present, uniform)** |
| Leak-possible-by-default | **PARTIAL** (scoped surface exists; coverage gap → Phase 4) |
| Global JVM properties | **OK (not set)** |

## Carried to later phases

- **P2-1** (BackgroundEffect receiver leak) → remediation PR + repro test (Phases 5/remediation).
- **P2-2** (alphaBlend escaping-out) → repro attempt (Phase 5); may downgrade if unreproducible.
- Mat-header GC split → tracked item per your Phase-1 request.
- Owned-return release contracts (`Descriptors`, `Camera.take`, `Intrinsics.*`) → contract tests (Phase 4).
