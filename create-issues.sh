#!/usr/bin/env bash
#
# Files the Critical, High and Medium findings from AUDIT-scalacv.md as GitHub issues.
#
#   ./create-issues.sh --dry-run    # print what would be created (default)
#   ./create-issues.sh --create     # actually create the 16 issues
#
# Requires: gh, authenticated, with the repo as origin.

set -euo pipefail

MODE="${1:---dry-run}"
REPO="${GH_REPO:-w0rxbend/scalacv}"

if [[ "$MODE" != "--create" && "$MODE" != "--dry-run" ]]; then
  echo "usage: $0 [--dry-run|--create]" >&2
  exit 2
fi

# Labels are created up front; gh issue create fails on an unknown label.
ensure_label() {
  [[ "$MODE" == "--create" ]] || return 0
  gh label create "$1" --repo "$REPO" --color "$2" --description "$3" 2>/dev/null || true
}

ensure_label "bug"               "d73a4a" "Something is wrong"
ensure_label "severity:critical" "8b0000" "Release blocker"
ensure_label "severity:high"     "b60205" "Affects running consumer programs"
ensure_label "severity:medium"   "fbca04" "Should be fixed before 0.2.0"
ensure_label "breaking-change"   "e99695" "Changes the public API or observable behaviour"
ensure_label "lifetime"          "5319e7" "Native memory ownership and release"
ensure_label "zio"               "0e8a16" "scalacv-zio module"
ensure_label "vision"            "0e8a16" "scalacv-vision module"
ensure_label "build"             "1d76db" "Build, CI, packaging"
ensure_label "ci"                "1d76db" "Continuous integration"
ensure_label "api"               "c2e0c6" "Public API surface"
ensure_label "security"          "d93f0b" "Security or supply chain"
ensure_label "docs"              "0075ca" "Documentation"
ensure_label "test-coverage"     "bfd4f2" "Missing or weak tests"
ensure_label "good-first-issue"  "7057ff" "Good for newcomers"

COUNT=0
file_issue() {
  local title="$1" labels="$2" body="$3"
  COUNT=$((COUNT + 1))
  if [[ "$MODE" == "--create" ]]; then
    gh issue create --repo "$REPO" --title "$title" --label "$labels" --body "$body"
  else
    echo "─────────────────────────────────────────────────────────────"
    echo "TITLE:  $title"
    echo "LABELS: $labels"
    echo
    echo "$body"
    echo
  fi
}

# ══ CRITICAL ══════════════════════════════════════════════════════════════════

# ── C1 ────────────────────────────────────────────────────────────────────────
file_issue \
  "ImreadFlags silently returns colour images for greyscale reads" \
  "bug,severity:critical,breaking-change,api" \
'## Evidence

`core/src/scalacv/Enums.scala:177-190`

```scala
final case class ImreadFlags(mode: ImreadFlags.Mode, modifiers: Set[ImreadFlags.Modifier] = Set.empty):
  def cvValue: Int = modifiers.foldLeft(mode.cvValue)(_ | _.cvValue)   // line 178
```

`Mode` and `Modifier` are treated as orthogonal bit sets. They are not.

```
$ javap -constants -cp opencv-4.13.0-1.5.13.jar org.opencv.imgcodecs.Imgcodecs | grep IMREAD
IMREAD_UNCHANGED = -1   IMREAD_GRAYSCALE = 0   IMREAD_COLOR = 1   IMREAD_ANYDEPTH = 2
IMREAD_REDUCED_GRAYSCALE_2 = 16   IMREAD_REDUCED_COLOR_2 = 17
IMREAD_REDUCED_GRAYSCALE_4 = 32   IMREAD_REDUCED_COLOR_4 = 33   IMREAD_IGNORE_ORIENTATION = 128
```

`IMREAD_REDUCED_COLOR_2` (17) **is** `REDUCED_GRAYSCALE_2 | COLOR` — the colour bit is baked into the
modifier. And `IMREAD_UNCHANGED` is `-1`, which ORs every other bit away.

| Expression | cvValue | Asked for | Actually returned |
|---|---|---|---|
| `ImreadFlags(Mode.Grayscale, Set(Modifier.ReducedHalf))` | 0\|17 = **17** | 1-channel, half | **3-channel** BGR |
| `ImreadFlags(Mode.Unchanged, Set(Modifier.ReducedHalf))` | -1\|17 = **-1** | half size | **full size** |
| `ImreadFlags(Mode.AnyDepth, Set(Modifier.ReducedHalf))` | 2\|17 = **19** | 16-bit thumb | **CV_16UC3** |

Reproduced on linux-x86_64, opencv 4.13.0-1.5.13, against a 16-bit single-channel TIFF:

```
cvValue = 19  (ANYDEPTH=2 | REDUCED_COLOR_2=17)
-> 20x20 channels=3 depth=CV_16UC3      <<< asked for 1 channel, got 3
```

The third row is the example the docs ship, `docs/mdoc/image-io.md:145`:

```scala
val hdrThumbnail = ImreadFlags(Mode.AnyDepth, Set(Modifier.ReducedHalf))
```

`mdoc` type-checks that snippet and cannot see it is semantically wrong.
`docs/mdoc/geometry.md:141` teaches the same pattern.

## Why it matters

Code that greys an image by reading it greyscale gets a 3-channel Mat. The next `equalizeHist`/`Canny`
either throws `CvException` several frames later, or succeeds on the wrong data. Reachable with no user
error.

## Proposed fix

`Reduced*` is not a modifier; it is part of the mode. Make the mapping total.

```scala
enum ImreadColor:  case Grayscale, Color, ColorRgb, Unchanged, AnyDepth
enum ImreadScale(val denom: Int):
  case Full extends ImreadScale(1);    case Half   extends ImreadScale(2)
  case Quarter extends ImreadScale(4); case Eighth extends ImreadScale(8)

final case class ImreadFlags(color: ImreadColor, scale: ImreadScale = ImreadScale.Full,
                             ignoreOrientation: Boolean = false):
  require(!(color == ImreadColor.Unchanged && (scale != ImreadScale.Full || ignoreOrientation)),
          "IMREAD_UNCHANGED (-1) cannot carry a reduction or an orientation flag")
  def cvValue: Int = ...   // a total `match` over (color, scale), NOT an OR
```

## Acceptance criteria

- [ ] Exhaustive test over `ImreadColor x ImreadScale x Boolean`, each `cvValue` asserted equal to a
      named `Imgcodecs.IMREAD_*` constant.
- [ ] `Image.read(p, ImreadFlags(Grayscale, Half)).channels == 1` holds.
- [ ] Illegal `Unchanged` combinations throw `IllegalArgumentException` rather than silently dropping.
- [ ] `docs/mdoc/image-io.md` and `docs/mdoc/geometry.md` snippets corrected.
- [ ] `core/api.golden` regenerated and the diff reviewed.

**BREAKING**: `ImreadFlags(Mode, Set[Modifier])` is removed. Land before 0.2.0 arms MiMa.'

# ── C2 ────────────────────────────────────────────────────────────────────────
file_issue \
  "publish-shape CI gate asserts 2 publishable modules; there are 4" \
  "bug,severity:critical,ci,build" \
'## Evidence

`.github/workflows/ci.yml:100-106`

```yaml
- name: Only core and zio may publish
  run: |
    modules=$(./mill resolve "__:PublishModule.publishArtifacts" | grep -cE "...")
    test "$modules" -eq 2
```

Reality on `api-ergonomics`:

```
$ ./mill resolve "__:PublishModule.publishArtifacts"
core.publishArtifacts   graphs.publishArtifacts   vision.publishArtifacts   zio.publishArtifacts
count: 4  <-- CI asserts -eq 2
```

## Why it matters

Two problems, the second worse than the first.

1. **The branch cannot go green.** The gate fails as written.
2. **Three of four published POMs are now ungated.** `PublishedPomTest` asserts only on `core`s POM — it
   is wired in via `core.test`s `forkArgs` (`-Dscalacv.pom=${core.pom().path}` in `build.mill`). The
   failure it exists to catch — *Mill 1.1.7 has no classifier field, so a `;classifier=` dependency is
   silently stripped and consumers resolve an artifact with no natives* — is unguarded for `vision`,
   `graphs` and `zio`.

`consumer-smoke` (ci.yml:129-160), the highest-value publishing gate in the repo, likewise resolves only
`com.worxbend:scalacv_3` from a clean cache.

## Proposed fix

- Assert the 4 publishable module **names**, not just the count, so a fifth module is a deliberate edit.
- Parameterise `PublishedPomTest` over `-Dscalacv.poms=<colon-separated>` covering all four POMs.
- Extend `consumer-smoke` to `cs fetch` all four coordinates.

## Acceptance criteria

- [ ] CI green on `api-ergonomics`.
- [ ] Removing `Deps.opencvApi` from `vision`s `mvnDeps` fails `PublishedPomTest`.
- [ ] The clean-cache consumer resolves and loads natives for all four published artifacts.'

# ══ HIGH ══════════════════════════════════════════════════════════════════════

# ── H1 ────────────────────────────────────────────────────────────────────────
file_issue \
  "FrameDiff.detect leaks a full frame on every failed comparison" \
  "bug,severity:high,lifetime,vision" \
'## Evidence

`vision/src/scalacv/Motion.scala:169-186`

```scala
def detect(image: Image): Motion =
  val current = prepare(image.mat, blurRadius)      // allocates a Managed[Mat]
  previous match
    case null => previous = current; Motion.still
    case prev =>
      val motion = prev.get.absdiff(current.get).use { ... }   // throws here => current never released
      prev.release()
      previous = current
      motion
```

`current` is allocated before a `try`-less body and released only on the success path.

## Why it matters

`Core.absdiff` throws `CvException` whenever the two frames differ in size or type — which happens in
production when a camera renegotiates resolution mid-stream, or when a caller feeds a differently-sized
image to a detector that has already seen one. Each such call leaks one blurred grayscale frame
**permanently**, and `previous` stays pointing at the stale baseline, so the *next* call fails
identically. A 1080p stream leaks ~2 MB per failed frame with no ceiling.

This is the only leak in the repo that is unbounded, silent, and reachable without user error.

## Proposed fix

```scala
case prev =>
  val motion =
    try prev.get.absdiff(current.get).use { ... }
    catch case e: Throwable => current.release(); throw e
  prev.release(); previous = current; motion
```

## Acceptance criteria

- [ ] A test feeds two differently-sized `Image`s to a `FrameDiff` and asserts `CvException` propagates.
- [ ] The same test then asserts a subsequent `detect` on correctly-sized frames still works.
- [ ] Decide and document explicitly whether the detector re-baselines or stays wedged after a failure.'

# ── H2 ────────────────────────────────────────────────────────────────────────
file_issue \
  "ZIO module runs blocking native calls on the compute executor" \
  "bug,severity:high,zio" \
'## Evidence

`zio/src/scalacv/zio/package.scala:34, 39, 66, 91` — and `grep -rn "blocking" zio/src` returns **nothing**.

```scala
package.scala:91   ZIO.attempt(capture.read(buffer))       // blocks a fiber-pool thread indefinitely
package.scala:39   ZIO.attempt(OpenCv.load())              // extracts ~196 MB + dlopen — blocking I/O
package.scala:66   ZIO.acquireRelease(readImage(path, ...))// filesystem decode — blocking I/O
package.scala:34   ZIO.acquireRelease(ZIO.attempt(make))   // may open a model file from disk
```

`core` documents the hazard at length — `core/src/scalacv/Video.scala:46-63`:

> blocks in native code, so a stream that stops delivering hangs the calling thread with nothing scalacv
> can do about it

## Why it matters

ZIOs default executor is sized to the CPU count. One RTSP stream that stops delivering pins one of those
threads forever; a handful pins the runtime and unrelated fibers stop scheduling. This is the most likely
way a consumers application dies from depending on `scalacv-zio`.

## Proposed fix

`ZIO.attemptBlocking` for all four; `ZIO.attemptBlockingInterrupt` for the `capture.read` in
`frameStream`, so an interrupted stream does not wedge on a dead camera.

## Acceptance criteria

- [ ] A `zio-test` runs `frameStream` over a fixture clip concurrently with a CPU-bound `ZIO.foreachPar`
      and asserts the latter still makes progress.
- [ ] No `ZIO.attempt` in the module wraps a native or filesystem call (review-level check).'

# ── H3 ────────────────────────────────────────────────────────────────────────
file_issue \
  "frameStream omits the exception-mode guard Video.frames documents" \
  "bug,severity:high,zio" \
'## Evidence

`zio/src/scalacv/zio/package.scala:88-96` vs `core/src/scalacv/Video.scala:247-259`.

`Video.frames` sets `setExceptionMode(false)` for the traversal and restores it after, with a nine-line
comment explaining why (`Video.scala:156-165`): with exception mode **on**, OpenCV reports plain
end-of-file through the *identical* `CvException` it uses for a broken stream, so the loop cannot
distinguish "the video ended" from "the camera was unplugged".

`frameStream` has no such guard.

## Why it matters

It usually works today, because `Video.openCapture` clears exception mode before the capture escapes
(`Video.scala:308`). But `frameStream`s signature takes *any* `VideoCapture`, including one the caller
constructed or re-armed. On such a capture, normal end-of-file surfaces as a **stream failure** rather
than stream end, and every downstream `ZStream` consumer sees an error where the synchronous API sees
success.

This is copy-paste drift between parallel implementations; `core` already contains the correct code.

## Proposed fix

```scala
ZStream
  .acquireReleaseWith(ZIO.succeed(capture.getExceptionMode))(m => ZIO.succeed(capture.setExceptionMode(m)))
  .tap(_ => ZIO.succeed(capture.setExceptionMode(false)))
  .flatMap { _ => ... }
```

## Acceptance criteria

- [ ] A `zio-test` calls `capture.setExceptionMode(true)` before `frameStream` over a fixture clip and
      asserts the stream completes normally rather than failing.'

# ── H4 ────────────────────────────────────────────────────────────────────────
file_issue \
  "Cv.attempt misses java.lang.Exception thrown by the OpenCV JNI shim" \
  "bug,severity:high,api" \
'## Evidence

`core/src/scalacv/Cv.scala:25-29`

```scala
def attempt[A](operation: String)(a: => A): Either[CvError, A] =
  try Right(a)
  catch
    case e: CvException => Left(CvError.NativeCall(operation, e))
    case e: CvError     => Left(e)
```

OpenCVs `throwJavaException` falls back to `java.lang.Exception` for any failure that is not a
`cv::Exception` (`std::bad_alloc`, `std::out_of_range`, unknown). Confirmed in the shipped shim:

```
$ strings ~/.javacpp/cache/.../libopencv_java.so | grep -E "^(org/opencv/core/CvException|java/lang/Exception)$"
java/lang/Exception
org/opencv/core/CvException
```

## Why it matters

`Images.read`, `Image.write`, `Image.bytes`, `Image.reading` and every `fromCv` in the ZIO module can
throw a bare `java.lang.Exception` past a signature that says `Either[CvError, A]`. Rare in practice — an
out-of-range `submat` correctly yields `CvException`, and I could not trigger the fallback on demand — but
it defeats the one abstraction the entire error policy rests on.

## Proposed fix

Match the exact class, so `IllegalArgumentException` and friends still propagate as programmer errors:

```scala
case e: Exception if e.getClass == classOf[Exception] => Left(CvError.NativeCall(operation, e))
```

## Acceptance criteria

- [ ] A unit test throws `new java.lang.Exception("boom")` inside `Cv.attempt` and asserts
      `Left(CvError.NativeCall)`.
- [ ] A unit test throws `IllegalArgumentException` inside `Cv.attempt` and asserts it still propagates.'

# ── H5 ────────────────────────────────────────────────────────────────────────
file_issue \
  "Image.blur(0) aliases instead of moving, breaking the documented invariant" \
  "bug,severity:high,api,breaking-change" \
'## Evidence

`core/src/scalacv/Image.scala:113-118`

```scala
def blur(radius: Int): Image =
  require(radius >= 0, ...)
  if radius == 0 then this          // returns the receiver; does NOT spend it
  else transform(...)               // every other branch spends it
```

`Image`s contract, from its own scaladoc (`Image.scala:20-24`):

> Every **transform** returns a *new* `Image` and **spends the one it was called on** — using the old
> handle afterwards throws `IllegalStateException`.

Reproduced:

```
out eq img            : true   (every other transform returns a NEW Image)
source still usable   : true   (a spent Image must throw)
after img.gray, out.width -> IllegalStateException
```

## Why it matters

Whether `img` is still usable after `img.blur(n)` depends on a **runtime value**. No crash follows —
release is idempotent — but it makes the one invariant the whole design rests on conditionally true. A
user discovers it via a test that passes locally with `radius = 1` and fails in production with a
configured `0`, at a line that looks correct.

## Proposed fix

Spend the handle without copying:

```scala
if radius == 0 then Image(Managed(handle.take()))
```

(Rejecting `0` with `require(radius >= 1)` also works and is arguably cleaner, but it is a louder break for
callers who legitimately pass a configured zero.)

## Acceptance criteria

- [ ] For every non-negative radius, `{ val i = img.blur(r); img.width }` throws `IllegalStateException`.
- [ ] Identity-argument cases added to `ImageTest` (`blur(0)`, `scale(1.0)`, and any others).

**BREAKING** (observable behaviour). Land before 0.2.0 arms MiMa.'

# ══ MEDIUM ════════════════════════════════════════════════════════════════════

# ── M1 ────────────────────────────────────────────────────────────────────────
file_issue \
  "The --add-opens advice in Releasable error messages is wrong" \
  "bug,severity:medium,docs" \
'## Evidence

`core/src/scalacv/Releasable.scala:73, 135` both tell the user to add:

```
--add-opens java.base/java.lang=ALL-UNNAMED
```

Verified against a running JVM with the classifier-less opencv jar on the classpath:

```
org.opencv.objdetect.CascadeClassifier     module=null  package=org.opencv.objdetect
org.opencv.core.Mat                        module=null  package=org.opencv.core
org.opencv.dnn.Net                         module=null  package=org.opencv.dnn
```

These classes are in `org.opencv.*` packages, not `java.lang`, and not in `java.base`. Opening
`java.base/java.lang` has **no effect** on reflective access to them.

## Why it matters

The messages fire on exactly the path where the user is stuck with no other information, and the
instruction they are given cannot work. The scaladoc above them (`Releasable.scala:32-33, 96-98`)
diagnoses the situation correctly — "OpenCV is on the module path" — so only the remedy string is wrong.

## Proposed fix

Compute the module at throw time, and handle the unnamed case honestly (the output above shows unnamed is
the *normal* state, in which `setAccessible` would not have failed for this reason — so that branch needs
different text, not a `null` interpolated into a flag):

```scala
val remedy = Option(cls.getModule.getName) match
  case Some(m) => s"  --add-opens $m/${cls.getPackageName}=ALL-UNNAMED"
  case None    => "  (OpenCV is on the classpath, so this is not a module-access failure — " +
                  "please report it with the cause below.)"
```

## Acceptance criteria

- [ ] A unit test asserts the emitted string contains `cls.getPackageName` for a named module.
- [ ] A unit test asserts the string never interpolates the literal `null`.
- [ ] Both handlers chain the original exception as the cause (see the -Werror issue).'

# ── M2 ────────────────────────────────────────────────────────────────────────
file_issue \
  "vision and graphs ship with no public-API golden" \
  "severity:medium,test-coverage,build" \
'## Evidence

`core/test/src/scalacv/PublicApiTest.scala:323` explicitly requires the code source to be `core`s output
directory, and:

```
$ find . -name api.golden -not -path "./.claude/*"
./core/api.golden
```

`vision` (`scalacv-vision`, 23 files, 3,337 LOC) and `graphs` (`scalacv-graphs`, 4 files, 902 LOC) are both
`ScalacvPublishModule` — they ship to Maven Central with the same compatibility promise and no golden file.

## Why it matters

`PublicApiTest` is an unusually good gate: it renders the compiled public surface and diffs it against a
committed 1605-line file, so accidental API churn is a failing test rather than a silent break. That gate
covers 45 % of the published source. `vision` and `graphs` hold the entire detector/DNN/SLAM surface and
the `Picture` scene graph — where churn is most likely.

This is also the prerequisite for arming MiMa at 0.2.0 (`build.mill:150-152`).

## Proposed fix

Parameterise `PublicApiTest` over module name + golden path; add `vision/api.golden` and
`graphs/api.golden`.

## Acceptance criteria

- [ ] `vision/api.golden` and `graphs/api.golden` exist and are asserted in CI.
- [ ] Adding a public method to either module fails the test.'

# ── M3 ────────────────────────────────────────────────────────────────────────
file_issue \
  "Image.crop leaks the source Mat on the exception path" \
  "bug,severity:medium,lifetime" \
'## Evidence

`core/src/scalacv/Image.scala:171-173`

```scala
val out = Managed.use(handle.get.submat(rect.toCv))(_.clone())   // OUTSIDE the try
try Image(Managed(out))
finally handle.release()
```

If `submat` or `clone` throws, `handle.release()` never runs. Every sibling transform uses
`try ... finally handle.release()` (`Image.scala:402-404`), so this is an inconsistency as well as a leak.

## Proposed fix

```scala
try
  val out = Managed.use(handle.get.submat(rect.toCv))(_.clone())
  Image(Managed(out))
finally handle.release()
```

## Acceptance criteria

- [ ] A test forces the allocation to fail (e.g. a crop on a released parent) and asserts the source Mat
      is released.
- [ ] Same treatment applied to the sibling leaks in `Ops.deskew` (`Ops.scala:473-476`) and
      `Features.detect` (`Features.scala:49-53`).'

# ── M4 ────────────────────────────────────────────────────────────────────────
file_issue \
  "Models.fetch has no HTTP timeout and ships unpinned checksums" \
  "bug,severity:medium,security" \
'## Evidence

`core/src/scalacv/Models.scala:65-67`

```scala
Using.resource(URI.create(url).toURL.openStream())(in =>
  Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING))
```

No connect timeout, no read timeout. A mirror that accepts the TCP connection then stalls hangs the calling
thread forever, and `fetchFirst` (`:45`) never advances to the next mirror. Model downloads are on the
critical path of `FaceDetect` and `FaceRecognizer`.

Separately, `Models.scala:13`

```scala
final case class ModelSpec(fileName: String, urls: Seq[String], sha256: Option[String] = None)
```

An unpinned spec is downloaded and loaded as a DNN model with **no integrity check**, and
`verifies(target, None)` (`:82`) returns `true` via `Option.forall`, so a cached unpinned model is never
re-checked either.

## Why it matters

The default is the unsafe one on both counts: a hung mirror is a hung application, and an unverified model
file fetched over the network is executed by OpenCVs DNN loader.

## Proposed fix

- `HttpClient.newBuilder().connectTimeout(...)` with a per-request `timeout(...)`.
- Pin SHA-256 on every shipped `ModelSpec` (`FaceDetect.modelSpec`, `FaceRecognizer.modelSpec`).
- Make `sha256` non-optional in the public constructor; add `ModelSpec.unverified(...)` as the loud opt-out.

## Acceptance criteria

- [ ] A test against a deliberately stalling local server asserts `fetch` fails within the timeout and
      falls through to the next mirror.
- [ ] Every `ModelSpec` in the codebase carries a pinned SHA-256.
- [ ] A test asserts a corrupted cached file is re-downloaded rather than accepted.

**BREAKING** if `sha256` becomes non-optional.'

# ── M5 ────────────────────────────────────────────────────────────────────────
file_issue \
  "Document that Releasable[Mat] frees the buffer, not the header" \
  "docs,severity:medium,lifetime" \
'## Evidence

`core/src/scalacv/Releasable.scala:25`

```scala
given Releasable[Mat] = _.release()
```

`Mat.release()` calls `n_release` -> `cv::Mat::release()`, which frees the **pixel buffer**. The `cv::Mat`
**header** is reclaimed only by the finalizer inherited from `CleanableMat`:

```
$ javap -p -cp opencv-4.13.0-1.5.13.jar org.opencv.core.CleanableMat
public abstract class org.opencv.core.CleanableMat {
  public final long nativeObj;
  protected void finalize() throws java.lang.Throwable;
  private static native void n_delete(long);
}
```

`org.opencv.core.Mat extends CleanableMat`.

## Why it matters

The scaladoc explains release-vs-delete carefully for the other 244 binding types but not for `Mat` — the
one users touch constantly. As written it implies full determinism for `Mat`, which is not quite true.

Bounded (~100 B per Mat, GC-reclaimable), so this is a documentation fix, not a behaviour fix.

## Explicitly NOT the fix

Do **not** switch `Mat` to the `delete(long)` bridge. `Mat` has no `delete(long)` — only
`CleanableMat.n_delete`, private to the superclass — and the header cost does not justify the reflection.

## Acceptance criteria

- [ ] `Releasable`s scaladoc states that `Mat.release()` frees the buffer deterministically and the
      header is reclaimed by the inherited finalizer.'

# ── M6 ────────────────────────────────────────────────────────────────────────
file_issue \
  "CI does not lint vision/graphs and has no leak-detection run" \
  "ci,severity:medium,test-coverage" \
'## Evidence

`.github/workflows/ci.yml:70`

```yaml
run: ./mill core.fix --check zio.fix --check examples.fix --check
```

`vision.fix` and `graphs.fix` are absent, leaving 3,337 + 902 = **4,239 LOC unlinted** — about 55 % of the
published source. Same omission at `:35` for `compile` (harmless today only because `examples` pulls them
in transitively).

Separately, there is **no leak-detection run** anywhere in CI. `DoubleFreeTest` proves the *disarm*, but
nothing proves a long pipeline does not accumulate Mats — and because `Mat` reclaims its header only via
`finalize()`, an unreleased-Mat regression shows up as RSS growth, not as a test failure.

## Why it matters

A leak gate is the one thing that would have caught the `FrameDiff.detect`, `Image.crop`, `Ops.deskew` and
`Features.detect` leaks filed alongside this issue — all four are exception-path leaks that no existing
test exercises.

## Proposed fix

- Add `vision.fix --check graphs.fix --check` at `:70` and `vision.compile graphs.compile` at `:35`.
- Add a CI step looping ~2000 `Image.reading(...)(_.gray.canny(...).bytes())` under a low `-Xmx`, with an
  RSS assertion.

## Acceptance criteria

- [ ] `scalafix`/`scalafmt` cover all four published modules.
- [ ] A leak step exists and fails when a deliberately introduced `Mat` leak is added.'

# ── M7 ────────────────────────────────────────────────────────────────────────
file_issue \
  "EnumsTest covers only the one valid ImreadFlags combination" \
  "test-coverage,severity:medium" \
'## Evidence

`core/test/src/scalacv/EnumsTest.scala:29-35`

```scala
test("ImreadFlags ORs its modifiers"):
  val f = ImreadFlags(ImreadFlags.Mode.Color, Set(ImreadFlags.Modifier.IgnoreOrientation))
  assertEquals(f.cvValue, Imgcodecs.IMREAD_COLOR | Imgcodecs.IMREAD_IGNORE_ORIENTATION)
```

`IMREAD_COLOR | IMREAD_IGNORE_ORIENTATION` = `1 | 128` = 129, which is genuinely valid. Every broken
combination — `Grayscale`/`AnyDepth`/`Unchanged` x `Reduced*` — is untested.

## Why it matters

This is the test shape that let the `ImreadFlags` correctness bug through: it asserts the happy path of a
composition rule that does not hold in general. The suites own docstring says it exists so *"a version bump
that renumbers anything fails here instead of silently changing behaviour"* — but renumbering is not the
failure mode that actually occurred.

## Proposed fix

Replace with an exhaustive property test over the full `Mode x PowerSet(Modifier)` space (or, after the
`ImreadFlags` redesign, over `ImreadColor x ImreadScale x Boolean`), asserting each `cvValue` equals a
**named** `Imgcodecs.IMREAD_*` constant rather than an expression built with the same operator under test.

## Acceptance criteria

- [ ] The test fails against the current `ImreadFlags` implementation.
- [ ] The test passes against the redesigned one.

Depends on the `ImreadFlags` redesign issue.'

# ── M8 ────────────────────────────────────────────────────────────────────────
file_issue \
  "Windows native-loading branch has zero test coverage" \
  "ci,severity:medium,test-coverage" \
'## Evidence

`core/src/scalacv/OpenCv.scala:121-146`. `satisfy` has two structurally different strategies:
demand-driven soname resolution (Linux/macOS) and a **bulk retry-load** (Windows), reached only when
`missingSoname` returns `None`. `bulkLoad` swallows every `Throwable` and loops until a pass makes no
progress (`:136-146`).

CI covers `ubuntu-latest` (JDK matrix) and `macos-14` (arm64 natives). Windows and linux-arm64 are absent.

## Why it matters

`bulkLoad` is the most fragile code in the loader — a retry loop over `dlopen` with all errors swallowed —
and nothing exercises it. The comment at `ci.yml:76-82` is honest about why Windows is hard (`./mill` is a
Unix-only shell launcher; Mill 1.1.7 ships no Windows launcher), but that explains why the leg is *hard*,
not why shipping an untested platform branch is *safe*.

## Proposed fix (cheapest first)

1. Unit-test the **decision** logic without a Windows runner: assert `missingSoname` returns `None` for
   the literal Windows message (`Cant find dependent libraries`, with an apostrophe) and the correct soname for the Linux
   (`libopencv_xphoto.so.413: cannot open shared object file`) and macOS
   (`Library not loaded: @rpath/libopencv_highgui.413.dylib`) message forms. Assert `isNativeLib` against
   captured filename lists for all three platforms.
2. Add a `windows-latest` leg invoking Mill via `mill.bat` / `java -jar` once a working invocation exists.
3. Add `ubuntu-24.04-arm` to the natives job.

## Acceptance criteria

- [ ] An `OpenCvLoaderTest` covers `missingSoname` and `isNativeLib` for all three platform message
      formats and filename conventions.'

# ── M9 ────────────────────────────────────────────────────────────────────────
file_issue \
  "Enable -Werror -Wvalue-discard -source:future (3 warnings to fix)" \
  "build,severity:medium,good-first-issue" \
'## Evidence

`build.mill:113-121` sets `-Wunused:all` but not `-Werror`, so the one live warning ships:

```
[warn] core/src/scalacv/Releasable.scala:60:12
       case e: NoSuchMethodException =>
       unused pattern variable
```

That warning is not cosmetic. Both handlers (`Releasable.scala:60, 65`) discard the original exception,
and `CvError extends RuntimeException(String, Throwable)` — so a cause is available and free. On the
`InaccessibleObjectException` path the underlying message names the module and package that actually failed
to open, which is exactly the information the user needs and is currently thrown away.

I measured the stricter flags rather than guessing. Recompiling `core/src vision/src graphs/src` with
`-Wvalue-discard` and `-source:future` **succeeds**, with exactly three warnings total:

```
Models.scala:76        discarded non-Unit value of type Boolean   (Files.deleteIfExists)
Calibration.scala:178  discarded non-Unit value of type Int       (dist.get(0, 0, d))
Releasable.scala:60    unused pattern variable
```

Note: `-Wsafe-init` does not exist in 3.3.8 — the flag is `-Ysafe-init`.

## Why it matters

`-Wvalue-discard` is the flag most likely to catch a future ignored-return-code bug in a JNI wrapper, where
`Boolean`/`Int` returns carry the success signal. All three sites are trivial.

## Proposed fix

1. Chain the cause in both `Releasable` handlers.
2. Ascribe or handle the two discarded values.
3. Add `-Werror -Wvalue-discard -source:future` to `scalacOptions`.

## Acceptance criteria

- [ ] `./mill clean __.compile` is green with all three flags on.
- [ ] `CvError.NativesMissing` from `NativeDelete.open` carries the original exception as its cause.'

# ══════════════════════════════════════════════════════════════════════════════

echo "─────────────────────────────────────────────────────────────"
if [[ "$MODE" == "--create" ]]; then
  echo "Created $COUNT issues in $REPO."
else
  echo "$COUNT issues would be created in $REPO. Re-run with --create to file them."
fi
