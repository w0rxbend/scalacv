# AUDIT — scalacv

Audit date: 2026-07-24 · Branch `api-ergonomics` @ `f846d52` **plus the uncommitted working tree**
(the core→vision/graphs split is on disk, not committed) · Mill 1.1.7, Scala 3.3.8 LTS, JDK 25 build /
JDK 17 target. Scope: `core`, `vision`, `graphs`, `zio`, `examples`, build, CI, published metadata.
**No source was modified.**

> This document consolidates two independent review passes. Every finding carried over from the first pass
> was re-verified against the jar, the generated POM, or a running JVM before being included here; two of its
> conclusions are corrected (§0.2 and M11). Findings are renumbered.

---

## 1. Verdict

The foundation is sound and the API does not need a redesign — this is a *designed* Scala 3 API, not a
transliteration: move semantics on `Image`, a single `Mats.produce` allocation choke point, OpenCV constants
sourced symbolically rather than as literals, a committed 1605-line public-API golden file, and a
`Releasable` layer that correctly refuses to trust the bindings' `finalize()`. The brief's central
hypotheses — hallucinated preset calls, raw int constants, a build-host classifier baked into the published
POM — are all **false here**, verified against the jar and the generated POM rather than by inspection.
What is actually wrong is narrower but includes two release blockers the first pass missed: **`ImreadFlags`
composes `imread` flags as if they were independent bits when they are not, so a caller asking for
greyscale silently receives a 3-channel image — and the shipped documentation teaches the broken
combination**; and **the `publish-shape` CI gate still asserts two publishable modules when the tree has
four**, so this branch cannot go green and three of four published POMs are now ungated. Fix those two, the
unbounded `FrameDiff` leak, the ZIO blocking-executor issue, and the `Cv.attempt` gap, and this is safe for
strangers to depend on.

---

## 2. Phase 0 — Ground truth (and corrections to the brief)

### 2.1 The binding layer is not what the brief assumes

The brief specifies JavaCPP presets (`org.bytedeco.opencv.*`, `Pointer` subclasses, `PointerScope`,
`*Indexer`, `MatVector`, `UMat`, `maxBytes`). **scalacv does not use that API.** It binds the **official
OpenCV Java API** (`org.opencv.*`, hand-written JNI via `libopencv_java.so`), which the bytedeco jar also
ships:

```
$ grep -rhoE "org\.(bytedeco|opencv)[a-zA-Z0-9_.]*" --include='*.scala' . | sort | uniq -c | sort -rn
    102 org.opencv.core.        57 org.opencv.imgproc.Imgproc    28 org.opencv.core.Mat
     18 org.bytedeco             6 org.bytedeco.javacpp.Loader    4 org.bytedeco.opencv.opencv_java
```

All 18 `org.bytedeco` references are loader/diagnostic code (`OpenCv.scala:5,39,44,195,196`,
`Cascades.scala:5,84`, `PublishedPomTest`). `grep -rn "PointerScope|Indexer|MatVector|UMat|deallocate"` over
all Scala sources returns **zero hits**. The classifier-less `opencv-4.13.0-1.5.13.jar` (1.9 MB) carries
both halves — 1146 `org/bytedeco/opencv/**` preset classes (unused) and 354 `org/opencv/**` JNI bindings
(used).

| Brief item | Applies? | Ground truth |
|---|---|---|
| `PointerScope` thread-locality across `Future`/fiber | **No** | No JavaCPP `Pointer` in the API. |
| `*Indexer` lifetime, `Buffer`- vs `Raw`-backed | **No** | No indexers. Pixel access is bulk `Mat.get/put`. |
| GC dealloc via `maxBytes` + `System.gc()`, `nopointergc` | **No** | JavaCPP's model. `org.opencv.*` uses `finalize()`. |
| `Mat` vs `GpuMat` vs `UMat` overload confusion | **No** | The Java API exposes neither `GpuMat` nor `UMat`. |
| `Mat.deallocate()` | **No** | Not in this API. |
| `close()` vs `release()` distinction | **Yes, reshaped** | See M6 — real, different mechanism. |
| View aliasing escaping to user code | **Yes** | Checked and **clean** — §2.6. |
| `javacpp.cachedir`, native extraction | **Yes** | `Loader.cacheResources` is used. Documented. |

### 2.2 "Hallucinated method names" — structurally impossible, but not the whole story

The build compiles against the same jar that ships to consumers, so a nonexistent method is a compile error
and `./mill __.compile` is green (478 tasks). I disassembled all 309 top-level `org.opencv.*` classes
(`xargs -a … javap -p -cp …`) and confirmed **no hallucinated method and no wrong overload**. The
overload-confusion risk the brief anticipates is structurally absent: with no `GpuMat`/`UMat`, the
near-identical-overload family does not exist.

That leaves the two things the compiler cannot catch:

- **Wrong constant value** — `Enums.scala` sources every value symbolically (`Imgproc.COLOR_BGR2GRAY`,
  `Core.BORDER_REFLECT_101`, …). The only integer literals are `Flip.Horizontal = 1` / `Vertical = 0` /
  `Both = -1` (`Enums.scala:82-88`), which match OpenCV's `flipCode` convention and are documented inline.
  **No finding.**
- **Wrong constant *composition*** — **this is a finding, and the first pass recorded "no finding" here.**
  Symbolic sourcing guarantees each constant is right; it says nothing about whether OR-ing two of them
  together is meaningful. It is not, for `ImreadFlags`. See **C1**.

### 2.3 The lifetime model is `finalize()`-based, and the library knows it

Measured over the 309 disassembled classes:

```
public release():                              3   → org.opencv.core.Mat, VideoCapture, VideoWriter
classes declaring private static delete(long): 244
nativeObj holders without a finalize():        0

public abstract class org.opencv.core.CleanableMat {   // Mat extends this
  public final long nativeObj;
  protected void finalize() throws java.lang.Throwable;   // ← not a Cleaner, despite the name
  private static native void n_delete(long);
}
```

`Releasable.scala:12-14`'s claim *"exactly three have a public `release()`"* is **verified correct**, as is
the unconditional-`finalize()` double-free hazard on the other 244. The mitigation — zero `nativeObj`
*before* deleting so the finalizer's `delete(0)` becomes a defined no-op (`Releasable.scala:41-44`) — is
correct, and `DoubleFreeTest:58-64` asserts the mechanism directly rather than inferring it from survival.
This is the strongest code in the repository; I have no alternative to propose.

I also verified the riskiest interop detail, since a wrong descriptor here is a runtime
`WrongMethodTypeException` no compiler catches:

```
$ javap -p -c scalacv.Releasable$ | grep invokeExact
  35: invokevirtual #214  // Method java/lang/invoke/MethodHandle.invokeExact:(J)V
```

Scala 3 emits the correct signature-polymorphic `(J)V`. **No bug.** Likewise `Field.setLong` on
`CleanableMat`'s **`public final`** `nativeObj` is permitted for a non-static final field after
`setAccessible(true)`, so the `IllegalAccessException` handler is defensive, not dead.

### 2.4 Build, tests, packaging

| Item | Result |
|---|---|
| `./mill __.compile` | SUCCESS, 478 tasks |
| `./mill core.test` | SUCCESS — **36 suites, ~372 tests, 0 failed, 0 ignored, 3 skipped**, 7 s |
| `./mill zio.test examples.test` | SUCCESS |
| Test density | **No zero-assertion suite**; every suite has ≥ 1 assertion per `test(` block |
| `TODO`/`FIXME`/`XXX`/`???` in published source | **zero** |
| `asInstanceOf` in published source | 5, all justified (3 × `A \| Null` narrowing in `Managed`, 2 × `DataBufferByte` mandated by AWT) |
| Compiler warnings today | 1 (`Releasable.scala:60`) |

The three skips are legitimate `munit.assume` gates (`SCALACV_CAMERA`, `SCALACV_SFACE_MODEL`, a
platform-dependent cascade path), matching the CLAUDE.md convention. Nothing flaky across three runs.

### 2.5 Module graph and LOC

`core` → nothing; `vision`, `graphs`, `zio` → `core`. Acyclic, verified.

| Module | Published as | src LOC | Contents |
|---|---|---|---|
| `core` | `com.worxbend:scalacv_3` | 3,427 | `Image`, `Managed`, `Ops`, `Enums`, `Video`, `Draw` |
| `vision` | `scalacv-vision_3` | 3,337 | detectors, DNN, pose/tracking, OCR, calibration, SLAM |
| `graphs` | `scalacv-graphs_3` | 902 | `Picture` scene graph, charts, GIF |
| `zio` | `scalacv-zio_3` | 105 | `Scope` bindings, `ZStream` frames |
| `examples` / `examples-gui` / `docs` | *(never published)* | 970 / 63 | CI builds `examples` only |
| tests | — | 4,990 | `core/test` covers core + vision + graphs |

### 2.6 Verified negatives

Things the brief predicts that I looked for and did **not** find:

- **No aliasing view can escape.** All three call sites are scope-bound and none returns the view:
  ```
  $ grep -rnE "\.(submat|rowRange|colRange|row|col)\(" --include='*.scala' core/src vision/src graphs/src zio/src
  vision/src/scalacv/Depth.scala:80      Managed.use(disparity.submat(...))(patch => Core.mean(patch)…)
  core/src/scalacv/Image.scala:171       Managed.use(handle.get.submat(rect.toCv))(_.clone())
  vision/src/scalacv/Navigator.scala:52  Managed.use(mat.submat(...))(band => Core.mean(band)…)
  ```
  `Image.crop` clones deliberately and documents why. **No use-after-free is reachable.**
- **No shared/cached native handle** in any `object`, `lazy val`, or global map — so the OpenCV
  thread-safety hazard does not arise.
- **No global thread-state mutation**: `grep -rn "setNumThreads|OPENBLAS|useOptimized|System.setProperty"`
  over all sources returns nothing. Correct behaviour for a library.
- **Double-close is safe.** `Managed.release` is a `getAndSet(null)` CAS (`Managed.scala:88-93`); a second
  call is a no-op, and use-after-close throws before crossing JNI (`Managed.scala:65-67`). The per-call
  liveness check is one volatile read — **keep it**; the alternative is a SIGSEGV with no stack trace.
- **Hand-rolled pixel math is correct.** Every non-delegating transform in `Ops.scala` checked:
  `sepia` (`:414-417`, canonical kernel correctly transposed for BGR — easy to get wrong),
  `temperature` (`:444-448`, less blue / more red for `shift > 0` in BGR = warmer, matches its doc),
  `gamma` (`:420-422`), `posterize` (`:425-428`, `levels = 4` → exactly {0, 85, 170, 255}),
  `emboss` (`:431-434`), `deskew` (`:461-484`, `normalizeSkew` folds `minAreaRect`'s OpenCV-4.5+ `(0, 90]`
  angle into `(−45, 45]` and feeds `getRotationMatrix2D` unnegated — the correct recipe).
- **`Ops.masked` is *not* buggy.** I hypothesised that `Core.bitwise_and(self, self, dst, mask)`
  (`Ops.scala:348-349`) leaves masked-out destination pixels uninitialised, since the masked path does not
  write them. Tested over 6 trials with a deliberately dirtied allocator: OpenCV zeroes the destination
  every time. **Not a finding**, recorded so nobody re-derives it.

### 2.7 Packaging — the predicted release blocker is already handled

The brief anticipates a build-host `classifier=$platform` leaking into published metadata. It does not. The
generated `core` POM's dependency section, in full:

```xml
<dependency><groupId>org.bytedeco</groupId><artifactId>opencv</artifactId><version>4.13.0-1.5.13</version></dependency>
<dependency><groupId>org.scala-lang</groupId><artifactId>scala3-library_3</artifactId><version>3.3.8</version></dependency>
```

No classifier, no openblas, no host leakage. `Deps.platform` (`build.mill:19-41`) is confined to
test/example module deps.

**I endorse the current strategy.** The options are (a) `-platform` meta-artifacts (~408 MB on every
consumer), (b) classifier propagation (Mill 1.1.7 cannot express a classifier in a POM at all — the binding
constraint, documented at `build.mill:43-46`), (c) classifier-less + documented per-platform instructions.
**(c) is the only one that both resolves and stays small**, and `OpenCv.load()` already fails with a
copy-pasteable two-line fix naming the consumer's actual platform (`OpenCv.scala:186-200`). Keep it. The
residual cost — `scalacv` alone compiles but does not run — is correctly mitigated by the error message
rather than by bloating the POM. Native footprint for the record: opencv linux-x86_64 jar **31 MB**,
openblas **20 MB**, first-load extraction to `~/.javacpp` **~196 MB**, no dedup between the two.

**GraalVM native-image does not work today.** Three concrete blockers: (1) `Releasable.handle` reflects a
private `delete(long)` per class (`Releasable.scala:56-58`) — needs `reflect-config.json` for every binding
class used; (2) `NativeFinalizer.disarm` writes a `final` field via `Field.setLong` (`Releasable.scala:106`);
(3) `OpenCv.satisfy` extracts shared libraries from a jar **at runtime** and `System.load`s them by absolute
path (`OpenCv.scala:44,128`), which a static image has no jar to read. (1) and (2) are certain from the
code; (3) is the known JavaCPP limitation — *unverified*, I did not attempt a build. State it in the README
rather than fixing it.

---

## 3. Critical findings

### C1 · `ImreadFlags` silently returns the wrong image
`core/src/scalacv/Enums.scala:177-190` · category: correctness / silently-wrong output

```scala
final case class ImreadFlags(mode: ImreadFlags.Mode, modifiers: Set[ImreadFlags.Modifier] = Set.empty):
  def cvValue: Int = modifiers.foldLeft(mode.cvValue)(_ | _.cvValue)   // ← line 178
```

`Mode` and `Modifier` are treated as orthogonal bit sets. They are not. Actual constants
(`javap -constants org.opencv.imgcodecs.Imgcodecs`, opencv 4.13.0-1.5.13):

```
IMREAD_UNCHANGED = -1   IMREAD_GRAYSCALE = 0    IMREAD_COLOR = 1     IMREAD_ANYDEPTH = 2
IMREAD_REDUCED_GRAYSCALE_2 = 16   IMREAD_REDUCED_COLOR_2 = 17
IMREAD_REDUCED_GRAYSCALE_4 = 32   IMREAD_REDUCED_COLOR_4 = 33   IMREAD_IGNORE_ORIENTATION = 128
```

`IMREAD_REDUCED_COLOR_2` (17) **is** `REDUCED_GRAYSCALE_2 | COLOR` — the colour bit is baked into the
modifier. And `IMREAD_UNCHANGED` is `-1`, which ORs every other bit away.

Three failures, all silent, all reproduced on this machine:

| Expression | `cvValue` | Asked for | Actually returned |
|---|---|---|---|
| `ImreadFlags(Mode.Grayscale, Set(Modifier.ReducedHalf))` | `0 \| 17` = **17** | 1-channel, half size | **3-channel** BGR, half size |
| `ImreadFlags(Mode.Unchanged, Set(Modifier.ReducedHalf))` | `-1 \| 17` = **-1** | half size | **full size** — modifier dropped |
| `ImreadFlags(Mode.AnyDepth, Set(Modifier.ReducedHalf))` | `2 \| 17` = **19** | 16-bit thumbnail | **`CV_16UC3`** — 3 channels |

Probe output (16-bit single-channel TIFF, half-size read):

```
=== docs/mdoc/image-io.md:145 — hdrThumbnail ===
  cvValue = 19  (ANYDEPTH=2 | REDUCED_COLOR_2=17)
  -> 20x20 channels=3 depth=CV_16UC3
  intended: 20x20, 1 channel, 16-bit.  <<< got COLOUR
```

The third row is **the example the documentation ships**, `docs/mdoc/image-io.md:145`:

```scala
val hdrThumbnail = ImreadFlags(Mode.AnyDepth, Set(Modifier.ReducedHalf))
```

`mdoc` type-checks that snippet and cannot see that it is semantically wrong.
`docs/mdoc/geometry.md:141` teaches `Set(IgnoreOrientation, ReducedHalf)` the same way.

**Why it matters.** Code that greys an image by reading it greyscale gets a 3-channel Mat; the next
`equalizeHist`/`Canny` either throws `CvException` several frames later or — worse — succeeds on the wrong
data. This is exactly the "silently-wrong image output" class the brief targets, and it is reachable
without any user error.

**Fix.** `Reduced*` is not a modifier; it is part of the mode. Model the real value space and make the
mapping total:

```scala
enum ImreadColor:              case Grayscale, Color, ColorRgb, Unchanged, AnyDepth
enum ImreadScale(val denom: Int):
  case Full extends ImreadScale(1); case Half    extends ImreadScale(2)
  case Quarter extends ImreadScale(4); case Eighth extends ImreadScale(8)

final case class ImreadFlags(
    color: ImreadColor,
    scale: ImreadScale = ImreadScale.Full,
    ignoreOrientation: Boolean = false):
  require(!(color == ImreadColor.Unchanged && (scale != ImreadScale.Full || ignoreOrientation)),
          "IMREAD_UNCHANGED (-1) cannot carry a reduction or an orientation flag")
  def cvValue: Int = ...   // a total `match` over (color, scale), NOT an OR
```

**Acceptance:** an exhaustive test over `ImreadColor × ImreadScale × Boolean` asserting each `cvValue`
equals a named `Imgcodecs.IMREAD_*` constant; a round-trip test that
`Image.read(p, ImreadFlags(Grayscale, Half)).channels == 1`; docs snippets updated; `core/api.golden`
regenerated.

---

### C2 · The `publish-shape` CI gate is stale and three of four published POMs are ungated
`.github/workflows/ci.yml:100-106`, `core/test/src/scalacv/PublishedPomTest.scala` · category: release safety

```yaml
- name: Only core and zio may publish
  run: |
    modules=$(./mill resolve "__:PublishModule.publishArtifacts" | grep -cE "...")
    test "$modules" -eq 2
```

Reality on this working tree:

```
$ ./mill resolve "__:PublishModule.publishArtifacts"
core.publishArtifacts   graphs.publishArtifacts   vision.publishArtifacts   zio.publishArtifacts
count: 4  <-- CI asserts -eq 2
```

Two distinct problems:

1. **The branch cannot go green.** The gate fails as written.
2. **The more dangerous half:** `PublishedPomTest` asserts only on `core`'s POM — it is wired in via
   `core.test`'s `forkArgs` (`-Dscalacv.pom=${core.pom().path}`). `vision`, `graphs` and `zio` POMs get no
   assertions at all. The failure the test exists to catch — *"Mill 1.1.7 has no classifier field, so a
   `;classifier=` dependency is silently stripped and consumers resolve an artifact with no natives"* — is
   now unguarded for three of the four artifacts you intend to publish.

Likewise `consumer-smoke` (`ci.yml:129-160`), the highest-value publishing gate you have, resolves only
`com.worxbend:scalacv_3` from a clean cache. It never touches `scalacv-vision`, `scalacv-graphs` or
`scalacv-zio`.

**Fix.** Assert the 4 publishable module *names* (not just the count, so a fifth module is a deliberate
edit); parameterise `PublishedPomTest` over a `-Dscalacv.poms=<colon-separated>` property covering all four;
extend `consumer-smoke` to `cs fetch` all four coordinates.

**Acceptance:** CI green; removing `opencvApi` from `vision`'s `mvnDeps` fails `PublishedPomTest`; the
clean-cache consumer resolves and loads natives for all four artifacts.

---

## 4. High findings

### H1 · `FrameDiff.detect` leaks a full frame on every failed comparison
`vision/src/scalacv/Motion.scala:169-186` · category: lifetime

```scala
def detect(image: Image): Motion =
  val current = prepare(image.mat, blurRadius)      // allocates a Managed[Mat]
  previous match
    case null => previous = current; Motion.still
    case prev =>
      val motion = prev.get.absdiff(current.get).use { … }   // ← throws here ⇒ `current` never released
      prev.release()
      previous = current
      motion
```

`current` is allocated before a `try`-less body and released only on the success path. `Core.absdiff` throws
`CvException` whenever the two frames differ in size or type — which happens in production when a camera
renegotiates resolution mid-stream, or when a caller feeds a differently-sized image to a detector that has
already seen one. Each such call leaks one blurred grayscale frame **permanently**, and `previous` stays
pointing at the stale baseline, so the *next* call fails identically. A 1080p stream leaks ~2 MB per failed
frame with no ceiling.

This is the one leak in the repo that is unbounded, silent, and reachable without user error.

**Fix:**

```scala
case prev =>
  val motion =
    try prev.get.absdiff(current.get).use { … }
    catch case e: Throwable => current.release(); throw e
  prev.release(); previous = current; motion
```

**Acceptance:** a test that feeds two differently-sized `Image`s to a `FrameDiff`, asserts the
`CvException` propagates, and asserts a subsequent `detect` on correctly-sized frames still works.

---

### H2 · The ZIO module runs indefinitely-blocking native calls on ZIO's default executor
`zio/src/scalacv/zio/package.scala:34, 39, 66, 91` · category: threading

`core` documents at length that `VideoCapture.read` blocks in native code with no timeout the JVM can
interrupt (`Video.scala:46-63`: *"blocks in native code, so a stream that stops delivering hangs the calling
thread with nothing scalacv can do about it"*). The ZIO module then calls exactly that on the **default**
executor. `grep -rn "blocking" zio/src` → **no matches**.

```scala
package.scala:91   ZIO.attempt(capture.read(buffer))       // blocks a fiber-pool thread indefinitely
package.scala:39   ZIO.attempt(OpenCv.load())              // extracts ~196 MB + dlopen — blocking I/O
package.scala:66   ZIO.acquireRelease(readImage(path, …))  // filesystem decode — blocking I/O
package.scala:34   ZIO.acquireRelease(ZIO.attempt(make))   // may open a model file from disk
```

ZIO's default executor is sized to the CPU count. One RTSP stream that stops delivering pins one of those
threads forever; a handful pins the runtime and unrelated fibers stop scheduling. This is the most likely
way a consumer's application dies from depending on `scalacv-zio`.

**Fix:** `ZIO.attemptBlocking` for all four, and `ZIO.attemptBlockingInterrupt` for the `capture.read` in
`frameStream` so an interrupted stream does not wedge on a dead camera.

**Acceptance:** a `zio-test` running `frameStream` over a fixture clip concurrently with a CPU-bound
`ZIO.foreachPar`, asserting the latter still makes progress; plus a review check that no `ZIO.attempt` in
the module wraps a native or filesystem call.

---

### H3 · `frameStream` omits the exception-mode guard the synchronous path documents at length
`zio/src/scalacv/zio/package.scala:88-96` vs `core/src/scalacv/Video.scala:247-259` · category: parity drift

`Video.frames` sets `setExceptionMode(false)` for the duration of the traversal and restores it after, with
a nine-line comment explaining why: with exception mode **on**, OpenCV reports plain end-of-file through the
*identical* `CvException` it uses for a broken stream, so the loop cannot distinguish "the video ended" from
"the camera was unplugged" (`Video.scala:156-165`).

`frameStream` has no such guard. Today it usually works, because `Video.openCapture` clears exception mode
before the capture escapes (`Video.scala:308`) — but the function's signature takes *any* `VideoCapture`,
including one the caller constructed or re-armed. On such a capture, normal end-of-file surfaces as a
**stream failure** rather than stream end, and every downstream `ZStream` consumer sees an error where the
synchronous API sees success.

This is exactly the copy-paste drift between parallel implementations the brief predicts, and `core`
already contains the correct code to copy.

**Fix:** bracket the stream with the same save/clear/restore via `ZStream.acquireReleaseWith`.

**Acceptance:** a `zio-test` that calls `capture.setExceptionMode(true)` before `frameStream` over a
fixture clip and asserts the stream completes normally rather than failing.

---

### H4 · `Cv.attempt` misses `java.lang.Exception` thrown by the OpenCV JNI shim
`core/src/scalacv/Cv.scala:25-29` · category: error handling

```scala
def attempt[A](operation: String)(a: => A): Either[CvError, A] =
  try Right(a)
  catch
    case e: CvException => Left(CvError.NativeCall(operation, e))
    case e: CvError     => Left(e)
```

OpenCV's `throwJavaException` falls back to `java.lang.Exception` for any failure that is not a
`cv::Exception` (`std::bad_alloc`, `std::out_of_range`, unknown). Confirmed in the shipped shim:

```
$ strings ~/.javacpp/cache/…/libopencv_java.so | grep -E "^(org/opencv/core/CvException|java/lang/Exception)$"
java/lang/Exception
org/opencv/core/CvException
```

So `Images.read`, `Image.write`, `Image.bytes`, `Image.reading` and every `fromCv` in the ZIO module can
throw a bare `java.lang.Exception` past a signature that says `Either[CvError, A]`. Rare in practice — I
could not trigger it on demand; an out-of-range `submat` correctly yields `CvException` — but it defeats
the one abstraction the entire error policy rests on.

**Fix:** match the exact class, so `IllegalArgumentException` and friends still propagate as programmer
errors:

```scala
case e: Exception if e.getClass == classOf[Exception] => Left(CvError.NativeCall(operation, e))
```

**Acceptance:** a unit test that throws `new java.lang.Exception("…")` from inside `Cv.attempt` and asserts
a `Left(CvError.NativeCall)`.

---

### H5 · `Image.blur(0)` silently breaks the move-semantics invariant
`core/src/scalacv/Image.scala:113-118` · category: API consistency

```scala
def blur(radius: Int): Image =
  require(radius >= 0, …)
  if radius == 0 then this          // ← returns the receiver; does NOT spend it
  else transform(…)                 //    every other branch spends it
```

`Image`'s contract is stated unambiguously in its own scaladoc (`Image.scala:20-24`): *"Every transform
returns a new `Image` and spends the one it was called on — using the old handle afterwards throws
`IllegalStateException`."* With `radius == 0` that is false. Reproduced:

```
out eq img            : true   (every other transform returns a NEW Image)
source still usable   : true   (a spent Image must throw)
after img.gray, out.width -> IllegalStateException
```

Whether `img` is still usable after `img.blur(n)` now depends on a **runtime value**. No crash follows —
release is idempotent — but it makes the one invariant the whole design rests on conditionally true, and a
user discovers it via a test that passes locally with `radius = 1` and fails in production with a configured
`0`, at a line that looks correct.

**Fix:** spend the handle without copying — `if radius == 0 then Image(Managed(handle.take()))`. (Rejecting
`0` with `require(radius >= 1)` also works and is arguably cleaner, but it is a louder breaking change for
callers who legitimately pass a configured zero.)

**Acceptance:** for every non-negative radius, `{ val i = img.blur(r); img.width }` throws
`IllegalStateException`; add identity-argument cases to `ImageTest`.

---

## 5. Medium findings

### M1 · The `--add-opens` advice in the two most important error messages is wrong
`core/src/scalacv/Releasable.scala:73, 135` · category: diagnostics

Both messages tell the user to add `--add-opens java.base/java.lang=ALL-UNNAMED`. Verified against a running
JVM:

```
org.opencv.objdetect.CascadeClassifier     module=null  package=org.opencv.objdetect
org.opencv.core.Mat                        module=null  package=org.opencv.core
org.opencv.dnn.Net                         module=null  package=org.opencv.dnn
```

These classes are in `org.opencv.*` packages, not `java.lang`, and not in `java.base`. Opening
`java.base/java.lang` has **no effect** on reflective access to them. The messages fire on exactly the path
where the user is stuck with no other information, and the instruction cannot work.

The scaladoc above them (`Releasable.scala:32-33, 96-98`) diagnoses the situation correctly — "OpenCV is on
the module path" — so the analysis is right and only the remedy string is wrong.

**Fix (refined).** Compute the module at throw time, and handle the unnamed case honestly — `module=null`
above shows unnamed is the *normal* state, in which `setAccessible` would not have failed for this reason,
so that branch needs different text rather than a `null` interpolated into a flag:

```scala
val remedy = Option(cls.getModule.getName) match
  case Some(m) => s"  --add-opens $m/${cls.getPackageName}=ALL-UNNAMED"
  case None    => "  (OpenCV is on the classpath, so this is not a module-access failure — " +
                  "please report it with the exception below.)"
```

**Acceptance:** a unit test asserting the emitted string contains `cls.getPackageName` when the class is in
a named module, and does not interpolate `null` otherwise.

---

### M2 · `vision` and `graphs` are published with no public-API gate
`core/api.golden`, `core/test/src/scalacv/PublicApiTest.scala:323` · category: release safety

`PublicApiTest` renders the compiled public surface and diffs it against a committed 1605-line golden file —
an unusually good gate. It covers **`core` only** (`:323` explicitly requires the code source to be core's
output directory), and `find . -name 'api.golden'` returns exactly one file.

`vision` (`scalacv-vision`, 23 files, 3,337 LOC) and `graphs` (`scalacv-graphs`, 4 files, 902 LOC) are both
`ScalacvPublishModule` — they ship with the same compatibility promise and no golden. They hold the entire
detector/DNN/SLAM surface and the `Picture` scene graph, where accidental churn is most likely.

**Fix:** parameterise `PublicApiTest` over module name + golden path; add `vision/api.golden` and
`graphs/api.golden`. Prerequisite for arming MiMa at 0.2.0 (`build.mill:150-152`).

---

### M3 · `Image.crop` leaks the source Mat on the exception path
`core/src/scalacv/Image.scala:171-173` · category: lifetime

```scala
val out = Managed.use(handle.get.submat(rect.toCv))(_.clone())   // ← outside the try
try Image(Managed(out))
finally handle.release()
```

If `submat` or `clone` throws, `handle.release()` never runs. Every sibling transform uses
`try … finally handle.release()` (`Image.scala:402-404`).

**Fix:** move the allocation inside the `try`.

---

### M4 · `Models.fetch` has no HTTP timeout and ships unpinned checksums
`core/src/scalacv/Models.scala:13, 35, 65-67, 82` · category: robustness / security

`URI.create(url).toURL.openStream()` (`:65`) has **no connect or read timeout**. A mirror that accepts the
TCP connection then stalls hangs the calling thread forever, and `fetchFirst` (`:45`) never advances to the
next mirror. Model downloads are on the critical path of `FaceDetect` and `FaceRecognizer`.

Separately, `sha256: Option[String] = None` (`:13`) means an unpinned spec is downloaded and loaded as a DNN
model with **no integrity check**, and `verifies(target, None)` (`:82`) returns `true`, so a cached unpinned
model is never re-checked. Documented as a trade-off, but the default is the unsafe one.

**Fix:** `HttpClient.newBuilder().connectTimeout(…)` with a per-request `timeout(…)`; pin SHA-256 on every
shipped `ModelSpec` and make `sha256` non-optional in the public constructor, with
`ModelSpec.unverified(…)` as the loud opt-out.

---

### M5 · `Releasable[Mat]` frees the buffer but not the header, and does not say so
`core/src/scalacv/Releasable.scala:25` · category: documentation / lifetime

`given Releasable[Mat] = _.release()` calls `n_release` → `cv::Mat::release()`, which frees the **pixel
buffer**. The `cv::Mat` **header** is reclaimed only by the inherited `CleanableMat.finalize()`. The
scaladoc explains release-vs-delete carefully for the other 244 types but not for `Mat` — the one users
touch constantly, and the one the brief specifically asks about.

Bounded (~100 B/Mat, GC-reclaimable), so not a leak in the damaging sense. **Do not** switch `Mat` to the
`delete(long)` bridge: `Mat` has no `delete(long)` (only `CleanableMat.n_delete`, private to the superclass),
and the header cost does not justify the reflection.

**Fix:** document it in `Releasable`'s scaladoc.

---

### M6 · CI does not lint `vision`/`graphs`, and there is no leak-detection run
`.github/workflows/ci.yml:35, 70` · category: CI

`scalafix` runs `core.fix zio.fix examples.fix` — **`vision.fix` and `graphs.fix` are absent**, leaving
3,337 + 902 = **4,239 LOC unlinted**, ~55 % of the published source. Same omission at `:35` for `compile`
(harmless today because `examples` pulls them in transitively; that will not stay true).

Separately, **no leak-detection run exists**. `DoubleFreeTest` proves the *disarm*, but nothing proves that
a long pipeline does not accumulate Mats — and because `Mat` reclaims its header only via `finalize()`, an
unreleased-Mat regression shows up as RSS growth, not as a test failure. This is the one gate that would
have caught H1, M3 and L2/L3.

**Fix:** add `vision.fix --check graphs.fix --check` at `:70` and `vision.compile graphs.compile` at `:35`;
add a CI step looping ~2000 `Image.reading(…)(_.gray.canny(…).bytes())` under a low `-Xmx` with an RSS
assertion.

---

### M7 · The test that should have caught C1 tests only the combination that happens to be valid
`core/test/src/scalacv/EnumsTest.scala:29-35` · category: test quality

```scala
test("ImreadFlags ORs its modifiers"):
  val f = ImreadFlags(ImreadFlags.Mode.Color, Set(ImreadFlags.Modifier.IgnoreOrientation))
  assertEquals(f.cvValue, Imgcodecs.IMREAD_COLOR | Imgcodecs.IMREAD_IGNORE_ORIENTATION)
```

`1 | 128` = 129 is genuinely valid. Every broken combination (`Grayscale`/`AnyDepth`/`Unchanged` ×
`Reduced*`) is untested. **Fix:** replace with the exhaustive mapping test from C1's acceptance criteria.

---

### M8 · The Windows native-loading branch has zero test coverage
`core/src/scalacv/OpenCv.scala:121-146`, `.github/workflows/ci.yml:76-82` · category: CI

`OpenCv.satisfy` has two structurally different strategies: demand-driven soname resolution (Linux/macOS)
and a **bulk retry-load** (Windows), reached only when `missingSoname` returns `None`. `bulkLoad` swallows
every `Throwable` and loops until a pass makes no progress (`:136-146`). It is the most fragile code in the
loader and nothing exercises it. `linux-arm64` is likewise uncovered.

The comment at `ci.yml:76-82` is honest about why Windows is absent (`./mill` is a Unix-only shell launcher,
Mill 1.1.7 ships no Windows launcher). I record this anyway, because that rationale explains why the leg is
*hard*, not why shipping an untested platform branch is *safe*.

**Fix (cheapest first):** unit-test `missingSoname` and `isNativeLib` against captured
Windows/macOS/Linux linker-error strings and filename lists — that covers the *decision* logic with no
Windows runner. Then a `windows-latest` leg when a working Mill invocation exists.

---

### M9 · No `-Werror`, and the one live warning marks a real defect
`build.mill:113-121`, `core/src/scalacv/Releasable.scala:60, 65` · category: build hygiene

`./mill clean core.compile` emits one warning — `Releasable.scala:60`, unused pattern variable — and
`-Werror` is not set, so it ships. The warning is not cosmetic: both handlers discard the original
exception, and `CvError extends RuntimeException(String, Throwable)`, so a cause is available and free. On
the `InaccessibleObjectException` path (the one M1 is also about), the underlying exception's message names
the module and package that actually failed to open — exactly the information the user needs, currently
thrown away.

**Correction to the first pass**, which declined to evaluate the stricter flags: I measured them. Compiling
`core/src vision/src graphs/src` with `-Wvalue-discard` and `-source:future` **succeeds**, with exactly
three warnings total:

```
Models.scala:76      discarded non-Unit value of type Boolean   (Files.deleteIfExists)
Calibration.scala:178 discarded non-Unit value of type Int      (dist.get(0, 0, d))
Releasable.scala:60  unused pattern variable
```

(`-Wsafe-init` does not exist in 3.3.8; the flag is `-Ysafe-init`.)

**Fix:** fix the three sites and chain the causes, then add `-Werror -Wvalue-discard -source:future`.
`-Wvalue-discard` is the flag most likely to catch a future ignored-return-code bug in a JNI wrapper.

---

## 6. Low findings

| # | File:line | What | Fix |
|---|---|---|---|
| L1 | `core/src/scalacv/Ops.scala:286-289` | `finally { camera.release(); dist.release() }` — if the first throws, the second never runs | Nest, or add a `releaseAll` helper |
| L2 | `core/src/scalacv/Ops.scala:473-476` | `deskew`'s `pts: MatOfPoint2f` is released on the success path only; `Imgproc.minAreaRect` throwing leaks it | Wrap in `Managed.use(MatOfPoint2f())` |
| L3 | `vision/src/scalacv/Features.scala:49-53` | `detect` leaks `descriptors` if `orb.detectAndCompute` throws. The comment *"transferred to the returned Descriptors"* holds on the happy path only | `try … catch { case e: Throwable => descriptors.release(); throw e }` |
| L4 | `vision/src/scalacv/Odometry.scala:42-45` | Two-step state mutation with no rollback. If `goodFeatures` throws after `previous = frame.copy`, `previousPoints` keeps the *previous* frame's points — silently wrong odometry, no error. Worse: if `frame.copy` throws after `prev.close()`, `previous` points at a **closed** handle and the next call throws `IllegalStateException` | Compute both into locals first, then assign both |
| L5 | `core/src/scalacv/Interop.scala:20-23` | 2-channel (or >4-channel) 8U input falls to `cvtColor(BgraToBgr)` and surfaces a raw `CvException` after the `require`s at `:17-18` already passed | `require(channels == 1 \|\| 3 \|\| 4, …)`; match `case 4 =>` explicitly |
| L6 | `vision/src/scalacv/Calibration.scala:150` | `Seq.fill(n)(pattern.objectPoints)` allocates *n* identical Mats; OpenCV accepts the same `Mat` repeated | `val o = pattern.objectPoints; Seq.fill(n)(o)`, release once |
| L7 | `core/test/src/scalacv/DoubleFreeTest.scala:42` | `assert(true, …)` in `churn` — a tautology. The four suites using it are crash canaries, not assertions | Keep the canaries (documented and defensible) but drop the fake assert; the real check is already at `:58-64` |
| L8 | `core/src/scalacv/OpenCv.scala:180` | `isNativeLib` allows one numeric suffix group (`\.so(\.\d+)?`); `libfoo.so.4.13.0` would be skipped. Bytedeco ships `.so.413`, so unreachable today — *unverified* for future preset versions | Widen to `(\.\d+)*` |
| L9 | `core/src/scalacv/OpenCv.scala:87-128` | `satisfy`'s `lastMissing` is a single `var` shared across the recursion while `loaded` is per-name. A graph where B needs A *after* A was loaded rethrows the raw `UnsatisfiedLinkError` (`:119`) instead of a `CvError`. Not reachable with the current payload; a latent sharp edge in safety-critical code | Thread `lastMissing` through as a parameter |
| L10 | `zio/src/scalacv/zio/package.scala:19` | `package object zio` — Scala 3 supersedes package objects with top-level definitions | `package scalacv.zio` with top-level `def`s |
| L11 | `core/src/scalacv/Image.scala:445-452` | `blank` mixes `require` (`:446`) with a bare `throw IllegalArgumentException` (`:451`) for the same error class | Use `require` for both |
| L12 | `.claude/` (2.0 MB, untracked, **not** in `.gitignore`) | A stale git worktree holding a full duplicate of the pre-split source tree. `grep`/`find` over the repo hits it, and it is one `git add .` from being committed | Add `.claude/` to `.gitignore` |

---

## 7. Performance

Nothing here is measurably broken. Three observations, each with the benchmark that would settle it — I am
not proposing changes without measurement.

| Location | Observation | JMH benchmark · claim to test |
|---|---|---|
| `core/src/scalacv/Interop.scala:24-31` | `toBufferedImage` does a bulk `src.get(0,0,bytes)` **plus** a `System.arraycopy` — a second full-image copy, removable by getting directly into the raster's backing array | 1080p, current vs direct-into-raster. **Claim: ~30 % faster, one fewer allocation.** |
| `core/src/scalacv/Ops.scala:368-370` | `sharpen` allocates a full-size blur then a full-size `addWeighted` destination — 2 extra full-frame Mats per call. Inherent to unsharp masking; noted so it is not mistaken for an oversight | `sharpen` at 1080p vs a fused `Imgproc.filter2D` with a precomputed kernel. **Claim: fusion wins by >20 %.** |
| `core/src/scalacv/Releasable.scala:37` | `handle[A](getNativeAddr: A => Long)` erases to `Function1[A, Object]`, boxing a `java.lang.Long` per release — the only boxing in the native layer | Not worth benchmarking; release is not a hot path. Listed for completeness. |

No per-pixel JVM loops over native data exist (there are no indexers to misuse); bulk `Mat.get`/`Mat.put` is
used throughout; JNI crossings are one per operation.

---

## 8. Remediation plan

Five tracks with disjoint file ownership.

| Track | Owns | Findings | Breaking? |
|---|---|---|---|
| **A — ZIO correctness** | `zio/src/**`, `zio/test/**` | H2, H3, L10 | No |
| **B — vision lifetime** | `vision/src/scalacv/{Motion,Odometry,Calibration,Features}.scala` | H1, L3, L4, L6 | No |
| **C — core correctness** | `core/src/scalacv/{Enums,Image,Cv,Releasable,Ops,Interop,Models,OpenCv}.scala`, `build.mill` | **C1**, H4, H5, M1, M3, M4, M5, M9, L1, L2, L5, L8, L9, L11 | **Yes — C1, H5, M4** |
| **D — gates & CI** | `.github/**`, `core/test/**`, `vision/api.golden`, `graphs/api.golden`, `.gitignore` | **C2**, M2, M6, M7, M8, L7, L12 | No |
| **E — docs** | `docs/mdoc/**`, `README.md`, `THIRD-PARTY.md` | C1 snippets, packaging/GraalVM/threading notes | No |

**Dependencies.**

1. **D's new goldens must land after C**, because C1/H5/M4 change public signatures and behaviour —
   otherwise D regenerates a golden that C immediately invalidates.
2. **E is blocked on C1**, since `docs/mdoc/image-io.md:145` and `docs/mdoc/geometry.md:141` cannot be
   rewritten until the replacement `ImreadFlags` shape exists.
3. **A, B and C are mutually independent** and touch no shared file. `Calibration.scala:178`
   (`-Wvalue-discard`) belongs to **B**, not C, even though C owns the flag change — land B first or accept
   one warning until it does.
4. C2's CI edit is independent of everything and should land **first**, since nothing else can go green
   until it does.

**Breaking API changes — three, all in Track C.** C1 replaces `ImreadFlags(Mode, Set[Modifier])`; H5 changes
`blur(0)`'s observable behaviour; M4 makes `ModelSpec.sha256` non-optional. Under early-semver 0.x these are
permitted (`build.mill:146`) and MiMa is not yet armed, so land them **before 0.2.0** or they become
expensive.

**Suggested order if serialised:** C2 → H1 → H2 → H3 → H4 → C1 → H5 → M1, M9 → M3, M4, M5 → M2, M6, M7, M8
→ Low → E.

---

## 9. Ready-to-file issues

`./create-issues.sh` is written alongside this file. It files the two Critical, five High and nine Medium
findings with labels, evidence and acceptance criteria. **Review before running — it creates 16 issues.**

| # | Title | Labels |
|---|---|---|
| 1 | `ImreadFlags` silently returns colour images for greyscale reads | `bug` `critical` `breaking-change` `core` |
| 2 | `publish-shape` CI gate asserts 2 publishable modules; there are 4 | `bug` `critical` `ci` `release` |
| 3 | `FrameDiff.detect` leaks a full frame on every failed comparison | `bug` `memory` `vision` |
| 4 | ZIO module runs blocking native calls on the compute executor | `bug` `zio` `performance` |
| 5 | `frameStream` omits the exception-mode guard `Video.frames` documents | `bug` `zio` |
| 6 | `Cv.attempt` misses `java.lang.Exception` from the OpenCV JNI shim | `bug` `core` `error-handling` |
| 7 | `Image.blur(0)` aliases instead of moving, breaking the documented invariant | `bug` `core` `api` `breaking-change` |
| 8 | `--add-opens` advice in `Releasable` error messages is wrong | `bug` `diagnostics` |
| 9 | `vision` and `graphs` ship with no public-API golden | `release` `test-coverage` |
| 10 | `Image.crop` leaks the source Mat on the exception path | `bug` `memory` `core` |
| 11 | `Models.fetch` has no HTTP timeout and ships unpinned checksums | `bug` `security` `core` |
| 12 | Document that `Releasable[Mat]` frees the buffer, not the header | `docs` `memory` |
| 13 | CI does not lint `vision`/`graphs` and has no leak-detection run | `ci` `test-coverage` |
| 14 | `EnumsTest` covers only the one valid `ImreadFlags` combination | `test-coverage` |
| 15 | Windows native-loading branch has zero test coverage | `ci` `test-coverage` |
| 16 | Enable `-Werror -Wvalue-discard -source:future` (3 warnings to fix) | `build` `good-first-issue` |

---

## 10. Target API sketch

The API does not need redesigning, so this is not an "after" rewrite — it is the call sites the findings
actually change, plus the two that are already right and should be left alone.

**1. The canonical pipeline — unchanged, and worth stating that it is already right.**

```scala
import scalacv.*
OpenCv.load()

Image.reading("photo.jpg") { img =>
  img.gray.blur(2).canny(threshold1 = 80, threshold2 = 160).write("edges.png")
}
```

This is the "read → cvtColor → GaussianBlur → detect → write" program the brief asks to see. Already one
line, already leak-free, already typed. **No change proposed** — only a docs change to promote
`Image.reading` from "also available" to the documented default, since it is the one entry point that
cannot leak.

**2. `ImreadFlags` — after C1 (`core/src/scalacv/Enums.scala`). BREAKING.**

```scala
// Before: silently returns a 3-channel image.
val thumb = ImreadFlags(ImreadFlags.Mode.Grayscale, Set(ImreadFlags.Modifier.ReducedHalf))

// After: (colour, scale) is a total mapping onto one OpenCV constant; illegal
// combinations are rejected by `require`, not silently ORed away.
val thumb = ImreadFlags(ImreadColor.Grayscale, ImreadScale.Half)
Image.reading("photo.jpg", thumb) { img =>
  assert(img.channels == 1)          // now actually holds
  img.canny(80, 160).write("edges.png")
}
```

**3. `frameStream` — after H2 + H3 (`zio/src/scalacv/zio/package.scala`).**

```scala
def frameStream(capture: VideoCapture): ZStream[Any, Throwable, Mat] =
  ZStream
    .acquireReleaseWith(ZIO.succeed(capture.getExceptionMode))(m => ZIO.succeed(capture.setExceptionMode(m)))
    .tap(_ => ZIO.succeed(capture.setExceptionMode(false)))   // EOF must not look like a broken stream
    .flatMap { _ =>
      ZStream.acquireReleaseWith(ZIO.succeed(Mat()))(m => ZIO.succeed(m.release())).flatMap { buffer =>
        ZStream.repeatZIOOption {
          ZIO.attemptBlockingInterrupt(capture.read(buffer)).mapError(Some(_)).flatMap { got =>
            if got && !buffer.empty() then ZIO.succeed(buffer) else ZIO.fail(None)
          }
        }
      }
    }
```

**4. `FrameDiff.detect` — after H1 (`vision/src/scalacv/Motion.scala`).**

```scala
def detect(image: Image): Motion =
  val current = prepare(image.mat, blurRadius)
  try
    previous match
      case null => previous = current; Motion.still
      case prev =>
        val motion = prev.get.absdiff(current.get).use { diff => … }
        prev.release(); previous = current; motion
  catch
    case e: Throwable =>
      if previous ne current then current.release()   // don't free what we just installed as the baseline
      throw e
```

**5. The `Releasable` diagnostic — after M1 + M9 (`core/src/scalacv/Releasable.scala`).**

```scala
case e: RuntimeException =>
  val remedy = Option(cls.getModule.getName) match
    case Some(m) => s"  --add-opens $m/${cls.getPackageName}=ALL-UNNAMED"
    case None    => "  (OpenCV is on the classpath, so this is not a module-access failure — " +
                    "please report it with the cause below.)"
  throw CvError.NativesMissing(
    s"""cannot open ${cls.getName}.delete(long) (${e.getClass.getSimpleName}).
       |
       |$remedy
       |
       |scalacv fails here rather than falling back to the garbage collector, because that
       |fallback does not reclaim native memory in any useful timeframe.""".stripMargin,
    e   // ← cause chained; its message names what actually failed to open
  )
```

---

## Appendix — reproduction artefacts

The two probe programs behind C1 and H5 are in the session scratchpad as `Probe.scala` / `Probe2.scala`,
runnable with:

```bash
CP=$(./mill show core.test.runClasspath | grep -oE '/[^"]*' | paste -sd:)
scala-cli run Probe.scala --classpath "$CP" --java-opt -Djava.awt.headless=true
```

Disassembly sweep behind §2.3 and C1:

```bash
J=~/.cache/coursier/v1/https/repo1.maven.org/maven2/org/bytedeco/opencv/4.13.0-1.5.13/opencv-4.13.0-1.5.13.jar
unzip -l "$J" | grep -oE 'org/opencv/[a-z0-9_]+/[A-Za-z0-9_$]+\.class' | sed 's#/#.#g; s#\.class##' \
  | grep -v '\$' | xargs -n 40 javap -p -cp "$J" > ocv-javap.txt
javap -constants -cp "$J" org.opencv.imgcodecs.Imgcodecs | grep IMREAD
```
