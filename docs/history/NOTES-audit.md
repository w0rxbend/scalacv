# NOTES-audit.md — legacy scalacv repo audit (Phase R, Agent R1)

Synthesis of four parallel archaeology reports (R1a core API, R1b example apps, R1c dead weight/license, R1d build/natives) over `/home/worxbend/Projects/scalacv` @ `master` (`a2b2fe9`).

Scope of the port: Scala 2.11 / sbt 0.13 / vendored OpenCV 3.0.0-rc1 JNI stubs → Scala 3 / Mill / `org.bytedeco:opencv-platform:4.13.0-1.5.13`.

Every claim below is carried over from a report that backed it with a command or a fetched URL. Claims marked **UNVERIFIED** were explicitly flagged as unverified by the originating report. Claims marked **CONFLICT** are disagreements between reports and are enumerated in §9.

---

## 1. Repo at a glance

Working tree: 21 tracked files + `CLAUDE.md` and `PLAN.md`. ~1.3 MB working tree, ~1.5 MB `.git`.

| Path | Purpose | Verdict |
|---|---|---|
| `build.sbt` | 11 lines. `organization := "it.callisto"`, `version := "0.1.0"`, `scalaVersion := "2.11.6"`. No `libraryDependencies`, no resolvers, no publish config. | **DELETE** → `build.mill` |
| `project/build.properties` | `sbt.version=0.13.8` (2015; does not run on JDK 25) | **DELETE** |
| `project/site.sbt` | Misnamed; contains only `addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "3.0.0")` | **DELETE** |
| `project/` | Empty once the above two go | **DELETE** |
| `lib/opencv-300.jar` | 292 KB, 180 entries: 83 `.class` + 82 `.java` under `org/opencv/**`, built 2015-05-10 by Ant 1.9.3 / JDK 1.7.0_79. **Zero natives** (`unzip -l \| grep -Ei '\.(so\|dylib\|dll\|jnilib)'` → exit 1). `Core.java:19-24` → `getVersion() = "3.0.0-rc1"`, `getNativeLibraryName() = "opencv_java300"`. Bare MANIFEST, **no LICENSE entry**. | **DELETE** (also a standing BSD-notice violation, §7) |
| `src/main/scala-2.11/it/callisto/scalacv/` (10 `.scala`) | All source. Version-suffixed source root invalid under Scala 3 / Mill. No copyright headers on any file (`head -3` = `package` + imports). | **REWRITE** → `src/main/scala/`, new package. Read + port before `git rm`; only in-tree evidence of lineage. |
| `src/main/resources/Lena.png` | 512×512 RGB PNG, 431,096 B, `sha256 cf3aefcd…`. 7 call sites (verified: `grep -rn "Lena.png" src/` → `Laplace:14`, `CannySlider:41`, `Sobel:14`, `BrightnessContrastDemo:39`, `FaceDetect:15`, `HoughSlider:39`, `ThresholdDemo:42`). | **DELETE + REPLACE** (§8.2) |
| `src/main/resources/lbpcascade_frontalface.xml` | 52 KB, `sha256 6f5f2009…`, byte-identical to OpenCV `4.x` upstream. No embedded license header. | **KEEP** (or drop in favour of the copies inside the bytedeco jar) |
| `src/main/resources/haarcascade_lefteye_2splits.xml` | 192 KB, `sha256 74c323c7…`, byte-identical to OpenCV `4.x` upstream. Embedded Intel License Agreement + Shiqi Yu credit. | **KEEP + NOTICE** |
| `src/main/resources/haarcascade_righteye_2splits.xml` | 192 KB, `sha256 4cf0d72b…`, byte-identical to OpenCV `4.x` upstream. Same embedded notice. | **KEEP + NOTICE** |
| `README.md` | Only attribution-bearing file: credits `chimpler/blog-scala-javacv` and `rladstaetter/isight-java`, documents FaceDetect/CamFaceDetect/CannySlider provenance. Last touched `47bf51b`. | **REWRITE, never delete** — the three credit links must survive |
| `.gitignore` | sbt/Scala-IDE specific (`lib_managed/`, `project/boot/`, `.scala_dependencies`, `.worksheet`) | **DELETE + REWRITE** for Mill (`out/`, `.bloop/`, `.bsp/`, `.metals/`, `.scala-build/`) |
| `.github/unicorns` | 572 B, **byte-identical to `.mergify.yml`** (`diff` → no output). Misfired commit `0a90c77` (2020-07-27 20:56); corrected by `83e484d` two minutes later, never cleaned up. | **DELETE** |
| `.mergify.yml` | 572 B. Keyed on `dependencies` / `block-merge` labels from a bot setup that no longer exists. Deprecated `strict`/`strict_method` schema. | **DELETE** (rewrite from scratch later if wanted) |
| `.whitesource` | WhiteSource Bolt config; product is now Mend, GitHub app sunset. | **DELETE** |
| `PLAN.md` | Untracked working document; superseded by `ROADMAP.md` per its own §2. §6 ("keep original license") is currently unsatisfiable — see §7. | **DELETE once ROADMAP lands; AMEND §6 first** |
| `CLAUDE.md` | This project's own agent instructions | **KEEP** |
| `.git/` | Now the **sole surviving record** of upstream authorship (`mcallisto/scalacv` is 404) | **KEEP — do not squash or re-init** |
| `origin/renovate/configure` (remote branch) | Stale 2022 bot branch adding `renovate.json`; PR never merged | **DELETE remote branch** |

---

## 2. Legacy public API surface

**Structural facts** (`grep -nE "^(object|trait|class|case class|  case class|  type|  val)" OpenCV.scala`): the entire API is **5 cake-pattern traits, zero `object`s** — `OpenCVUtils` (27), `OpenCVImg` (38), `OpenCVCombos` (194), `OpenCVDetect` (222), `OpenCVVideo` (302). There is no importable namespace; consumers do `object FaceDetect extends App with OpenCVImg with OpenCVDetect`. `grep "implicit class\|implicit def"` → **no output**: no extension methods anywhere. `grep "release\|\.empty()"` → **no output**: `Mat.release()` is never called and `imread` results are never checked. One type alias total (`type CC = CascadeClassifier`). `OpenCV.scala:25` imports `javafx.scene.image.Image` — the "core" already depends on JavaFX. **21 of 24 methods return `Future[_]`** on `ExecutionContext.Implicits.global`.

All paths below are `/home/worxbend/Projects/scalacv/src/main/scala-2.11/it/callisto/scalacv/OpenCV.scala` unless noted.

### `trait OpenCVUtils` (27–36)

| Signature | OpenCV call | Verdict | Why |
|---|---|---|---|
| `def loadNativeLibs(): Unit` | `System.load("/home/mario/dev/tools/opencv-3.0.0-rc1/build/lib/libopencv_java300.so")` (line 30) | **DIES** | Absolute path into the original author's home dir; Linux-only; non-idempotent (2nd call → `UnsatisfiedLinkError`). Under javacpp the concept disappears entirely — `Loader` extracts and loads natives at class-init. |
| `def resourcePath(path: String): String` | `getClass().getResource(path).getPath()` | **DIES; intent SURVIVES** | NPEs on missing resource; URL-encoded (a space → `%20`, `imread` then silently fails); returns a `jar:file:…!/…` path that does not exist on disk once packaged. Need is real (cascade XMLs + fixtures; native APIs take `String` paths). Rewrite: extract resource → temp file → `java.nio.file.Path`, resource-scoped. |

### `trait OpenCVImg extends OpenCVUtils` (38–192)

Private helper — the structural heart:
```scala
private def toDst(src: Mat, f: (Mat, Mat) ⇒ Unit): Mat = { val dst = new Mat(); f(src, dst); dst }
```
**SURVIVES-AS-INTENT — the single best idea in the library.** 11 call sites. Rewrite as an ownership-taking, resource-safe combinator (§4.1).

| Signature | OpenCV call | Verdict | Why |
|---|---|---|---|
| `readImg(path: String): Future[Mat]` | `Imgcodecs.imread(resourcePath(path))` | SURVIVES-AS-INTENT | Async dies. `println("reading image")`/`"done image"`. Empty-Mat failure never checked. |
| `toGray(mat: Mat): Future[Mat]` | `Imgproc.cvtColor(s,d,COLOR_BGR2GRAY,1)` (`1` = local `val dstCn`) | SURVIVES-AS-INTENT | `dstCn` semantics under 4.x **NEEDS-VERIFICATION**; **CONFLICT-3** on the bytedeco arity. |
| `equalize(mat: Mat): Future[Mat]` | `Imgproc.equalizeHist(s,d)` | SURVIVES-AS-INTENT | — |
| `writeImg(mat: Mat, filename: String): Future[Unit]` | `Imgcodecs.imwrite(filename, mat)` | SURVIVES-AS-INTENT | Returned `boolean` discarded → a failed write is indistinguishable from success. |
| `mat2Image(mat: Mat): Future[javafx.scene.image.Image]` | `Imgcodecs.imencode(".png", mat, memory)` → `new Image(new ByteArrayInputStream(...))` | **DIES from core** | Returns a JavaFX type; sole reason core imports JavaFX. Split: `encode(mat, ".png"): Array[Byte]` in core, `Image` construction in `scalacv-javafx`. Leaks the `MatOfByte`. |
| `gaussianBlur(mat: Mat): Future[Mat]` | `Imgproc.GaussianBlur(s,d,new Size(3,3),0,0,border_default)` | SURVIVES-AS-INTENT | `border_default = 4` is a magic literal, not `Core.BORDER_DEFAULT`. Kernel hardcoded → must be parameters. |
| `blur(mat: Mat): Future[Mat]` | `Imgproc.blur(s,d,new Size(3,3))` | SURVIVES-AS-INTENT | Same. |
| `sobel(mat: Mat, x_order: Int, y_order: Int): Future[Mat]` | `Imgproc.Sobel(s,d,CvType.CV_16S,x,y,3,1,0,border_default)` | SURVIVES-AS-INTENT | ddepth/ksize/scale/delta all pinned. |
| `laplace(mat: Mat): Future[Mat]` | `Imgproc.Laplacian(s,d,CvType.CV_16S,3,1,0,border_default)` | SURVIVES-AS-INTENT | Same. |
| `convertScaleAbs(mat: Mat): Future[Mat]` | `Core.convertScaleAbs(s,d)` | SURVIVES-AS-INTENT | — |
| `addWeighted(mat1: Mat, mat2: Mat): Future[Mat]` | `Core.addWeighted(mat1,0.5,mat2,0.5,0,weighted)` | SURVIVES-AS-INTENT | Weights hardcoded; hand-rolls `new Mat()` instead of using `toDst`. |
| `canny(mat, low_threshold: Int, ratio: Double = 3.0, l2gradient: Boolean = false): Future[Mat]` | `Imgproc.Canny(s,d,low,low*ratio,3,l2gradient)` | SURVIVES-AS-INTENT | Best-designed signature in the file: named defaults, derived high threshold. |
| `houghLines(mat: Mat, threshold: Int): Future[Mat]` | `Imgproc.HoughLines(s,d,1,Math.PI/180,threshold)` | SURVIVES-AS-INTENT; **signature DIES** | Returns an untyped `Mat` that is secretly Nx1 `CV_32FC2` `(rho,theta)`, documented only in a comment. Must return `Seq[PolarLine]`. |
| `houghLinesP(mat, threshold: Int, minLinLength: Int = 50, maxLineGap: Int = 10): Future[Mat]` | `Imgproc.HoughLinesP(s,d,1,Math.PI/180,threshold,minLinLength,maxLineGap)` | same | Secretly `CV_32SC4`. Typo `minLinLength`. |
| `addVec2fLines(mat: Mat, lines: Mat): Future[Mat]` | `lines.get(i,0)` loop → `Imgproc.line(mat,pt1,pt2,new Scalar(0,0,255),2)` | SURVIVES-AS-INTENT; **semantics DIE** | **Mutates `mat` in place and returns it** — the return value aliases the argument. Colour/thickness/extension-length `1000` hardcoded. |
| `addVec4iLines(mat: Mat, lines: Mat): Future[Mat]` | same, `Point(vec(0),vec(1))`→`Point(vec(2),vec(3))` | same | Same in-place lie. |
| `longestVec4iLines(mat, lines)(implicit cmp: Ordering[Double]): Future[Mat]` | `vecs.maxBy{…}` + one `Imgproc.line` | **DIES** | The `implicit Ordering[Double]` param is decorative (Scala-2 mannerism); `maxBy` on empty `lines` throws. Intent survives as `def longest(lines: Seq[Segment]): Option[Segment]`. |
| `findContours(mat: Mat): Future[(Buffer[MatOfPoint], Mat)]` | `Imgproc.findContours(mat,contours,hierarchy,RETR_TREE,CHAIN_APPROX_SIMPLE,new Point(0,0))` | **DIES; intent survives** | Java signature is `(Mat, java.util.List<MatOfPoint>, Mat, int, int, Point)`; a `mutable.Buffer` compiles only via `scala.collection.JavaConversions._` (`OpenCV.scala:4`), **removed in 2.13/Scala 3**. Retrieval mode hardcoded. Tuple leaks N+1 unreleased native objects. |
| `addContours(mat, contours: Buffer[MatOfPoint], hierarchy: Mat): Future[Mat]` | `Imgproc.drawContours(mat,contours,i,color,2,8,hierarchy,0,new Point())` | **DIES** | `JavaConversions` again; `def rnd = Math.floor(Math.random*256).toInt` → nondeterministic colours (untestable); magic `8` = `LINE_8`; in-place mutation returned as a value. |
| `case class ImgprocThresh(id: String, value: Int)` (declared **inside** the trait → path-dependent `OpenCVImg#ImgprocThresh`) | — | **DIES; replace with `enum`** | `toString` overridden purely so a JavaFX `ListView` renders the name — GUI concern in a core data type. |
| `val threshConstants` (no ascribed type; `Map[String, ImgprocThresh]`) | `Imgproc.THRESH_{BINARY,BINARY_INV,TRUNC,TOZERO,TOZERO_INV}` | **DIES** | Stringly-typed map keyed by the constant's own name. Consumed only by `ThresholdDemo.scala:74`. Scala 3 `enum ThresholdType(val cvValue: Int)` gives ordering, exhaustivity and `values` free. |
| `threshold(mat: Mat, thresh: Int, mode: Int): Future[Mat]` | `Imgproc.threshold(s,d,thresh,255,mode)` | **DIES** | `mode: Int` — the `ImgprocThresh` wrapper is not used in the one signature it exists for. maxval pinned to 255. Returned `double` (the computed Otsu threshold) discarded. |
| `brightnessContrast(mat: Mat, α: Double, β: Double): Future[Mat]` | `s.convertTo(d, -1, α, β)` | SURVIVES-AS-INTENT | **Rename the params** — `α`/`β` are literal Unicode Greek identifiers; legal in Scala 3, hostile to callers. |

### `trait OpenCVCombos extends OpenCVImg` (194–220)

| Signature | Verdict | Why |
|---|---|---|
| `reduceNoise(mat): Future[Mat]` = `for { b ← gaussianBlur(mat); g ← toGray(b) } yield g` | SURVIVES-AS-INTENT | The *pipeline* is the point; the `Future` for-comprehension is the mechanism that dies. As `Mat => Mat` this is `andThen`. Leaks `blurred`. |
| `approxGradient(mat): Future[Mat]` (sobel 1,0 → convertScaleAbs; sobel 0,1 → convertScaleAbs; `addWeighted`) | SURVIVES-AS-INTENT | **The only site in the library where `Future` buys anything** — both gradient chains are constructed before either is flat-mapped, so they genuinely run in parallel. Leaks 4 intermediates per call. |

### `trait OpenCVDetect extends OpenCVUtils` (222–300)

| Signature | OpenCV call | Verdict | Why |
|---|---|---|---|
| `type CC = CascadeClassifier` | — | **DIES** | 2-char alias for a 17-char name; hides the type in `findEyes(image, faces, lEye: CC, rEye: CC)`. |
| `getClassifier(path: String): CC` | `new CascadeClassifier(resourcePath(path))` | SURVIVES-AS-INTENT; **error model DIES** | `CascadeClassifier` **does not throw** on a missing XML — it silently constructs an empty classifier that detects nothing. No `.empty()` check. Prints to stdout. Native handle never released. |
| `findFaces(image: Mat, faceDetector: CC): Future[Vector[Rect]]` | `detectMultiScale(image, MatOfRect)` → `.toArray().toVector` | **SURVIVES-AS-INTENT — best pattern in the file** | Copies native results out into immutable JVM data at the boundary. In OpenCV 3.0 Java `Rect` is a POJO so this is safe; **under bytedeco `Rect` is a `Pointer`, so the copy-out must become a copy into a Scala 3 `final case class Rect(x,y,w,h)`** — load-bearing port change. Prints `"Detected %s faces"`. |
| `findEyes(image, faces: Vector[Rect], lEye: CC, rEye: CC): Future[Vector[(Vector[Rect], Vector[Rect])]]` | 2× `new Mat(image, new Rect(...))` ROI + 2× `detectMultiScale` per face | SURVIVES-AS-INTENT; **type DIES** | Return type is unreadable and positionally index-coupled to `faces` (`eyes(i)._1`). Leaks 4 native objects per detected face. Eye coords are relative to the quadrant ROI and the caller re-adds offsets — a latent correctness trap. Should be `Vector[FaceEyes(left: Option[Rect], right: Option[Rect])]`. |
| `frameFaces(image: Mat, faces: Vector[Rect]): Future[Unit]` | `Imgproc.rectangle` + `Imgproc.putText(..., Core.FONT_HERSHEY_PLAIN, ...)` | **DIES from core** → `scalacv-draw` | Drawing/annotation is presentation. Mutates in place, returns `Future[Unit]` for no reason. **`Core.FONT_HERSHEY_PLAIN` is removed in 4.13** — see §4.6. |
| `frameEyes(image, faces, eyes): Future[Unit]` | inner `coords(face, eye, isLeft)` → `Imgproc.rectangle` | **DIES from core** → `scalacv-draw` | Indexes `eyes(i)` in lockstep with `faces(i)` → `IndexOutOfBoundsException` on desync. Contains the **only `Option` use in the entire library** (`headOption`). |

### `trait OpenCVVideo` (302–320) — note: does **not** extend `OpenCVUtils`

| Signature | Verdict | Why |
|---|---|---|
| `def videoCapture: VideoCapture` (abstract) | SURVIVES-AS-INTENT | Injecting the capture device via an abstract member is fine; the cake delivery mechanism dies. |
| `def takeImage: Mat` — `val image = new Mat(); while (videoCapture.read(image) == false) {}; image` | **DIES** | Unbounded busy-wait spin, no timeout, no exit condition → pins a core forever if the camera is unplugged. Synchronous inside an otherwise-async trait. Fresh unreleased `Mat` per frame = the highest-frequency leak in the library. |
| `def sourceMat: Future[Mat]` — `assert(videoCapture.isOpened()); if (videoCapture.grab) takeImage else throw new RuntimeException("Couldn't grab image!")` | **DIES; intent survives** | `assert` is elidable with `-Xdisable-assertions`, so the open-check can vanish in a production build; the `RuntimeException` inside a `Future` becomes a silently-dropped failed Future. Intent survives as an `Iterator`/stream of frames with explicit close. |

### `JavaFxUtils.scala` (79 lines) — all DIES from core

| Member | Verdict |
|---|---|
| `object JfxExecutionContext` — `ExecutionContext.fromExecutor(new Executor { def execute(r: Runnable) = Platform.runLater(r) })` (lines 19–25) | **The architectural keystone of the old design and the entire reason `Future` exists.** Makes `future.map(updateUI)` FX-thread-safe by construction. Used by `CamFaceDetect:36`, `HoughSlider:29`, `BrightnessContrastDemo:29`, `CannySlider:31`, `ThresholdDemo:32`. Genuinely elegant — **keep it, in the JavaFX adapter module only**. |
| `trait JfxUtils` — `mkCellFactoryCallback`, `mkEventHandler`, `mkTask`, `mkTop`, `mkSlider`, `sliderValue`, `sliderText` | Pure JavaFX widget factory, zero OpenCV content. Moves to the examples/GUI module. |

---

## 3. Design intent worth preserving

1. **`toDst(src, f)` — the out-parameter adapter.** OpenCV's C-style `(src, dst)` convention is adapted once, in one private helper, into a value-returning function; every image op is then a one-liner. This is the best idea in the library and should be the backbone of the Scala 3 core, re-expressed as an ownership-taking, resource-safe combinator.

2. **Layered traits: primitives → compositions.** `OpenCVUtils` (native loading) → `OpenCVImg` (primitives) → `OpenCVCombos` (compositions) → `OpenCVDetect` / `OpenCVVideo` (domains). The design point is that `Combos` is *derived from* the primitives layer by composition rather than being another wrapper around the Java API. Preserve the layering; drop the cake-pattern mechanism (Scala 3: given instances / modules).

3. **Composable pipelines as the headline feature.** `FaceDetect.scala:16-24`, `Sobel`, `Laplace` all carry the comment `// instantiate all independent futures before the for comprehension`; `approxGradient` (`OpenCV.scala:202-218`) is the cleanest demonstration. Whatever the effect type ends up being, *pipelines composed from named primitives* is the library's reason to exist and must survive in shape.

4. **Copy-out at the native boundary.** `findFaces` returns `Vector[Rect]`, not a `MatOfRect` — callers hold JVM data, never native handles. This instinct is right and becomes *more* important under bytedeco, where `Rect` is itself a `Pointer`.

5. **Thread-confinement as an `ExecutionContext`.** `JfxExecutionContext` turns "run on the FX Application Thread" into a first-class EC. Reusable idea; belongs in the adapter module.

6. **`canny`'s signature.** Named parameters with defaults and a *derived* high threshold (`low * ratio`) instead of two unrelated ints — the one place the original chose ergonomics over a literal Java transcription. Use it as the template for every other op.

7. **Attribution discipline in the README.** Provenance for `FaceDetect` (OpenCV *Introduction to Java Development* tutorial + `chimpler/blog-scala-javacv`), `CamFaceDetect` ("heavily indebted with `rladstaetter/isight-java`") and `CannySlider` (*Canny Edge Detector* tutorial). Carry these forward verbatim.

---

## 4. Structural problems

### 4.1 Mat lifecycle & native leaks — the library leaks native memory everywhere
`grep -n "release" *.scala` → **no output**. There are **zero release sites** against these allocation sites: `toDst` (`new Mat()`, ×11), `addWeighted` (`new Mat()`), `findContours` (`new Mat()` hierarchy + N native `MatOfPoint`), `mat2Image` (`new MatOfByte`), `findFaces` (`new MatOfRect`), `findEyes` (2 ROI `new Mat(image, rect)` + 2 `new MatOfRect` **per face**), `takeImage` (`new Mat()` **per video frame**), `readImg` (`imread`).

`org.opencv.core.Mat` has `public void release()` (verified via `javap`); the JNI wrapper's `finalize()` is the only thing reclaiming these today — i.e. reclamation is at the mercy of a GC with no visibility into the off-heap bytes, and finalization is removed/degraded on modern JDKs. Worst offenders: `takeImage` (per-frame, unbounded), `findEyes` (4 native objects per face), `approxGradient` (4 intermediates unreachable after the final `addWeighted`). A 1080p webcam loop through `takeImage` + `reduceNoise` allocates ~6 unreleased Mats per frame.

**Mandate.** bytedeco's `Mat extends AbstractMat extends … Pointer`, and `org.bytedeco.javacpp.Pointer implements java.lang.AutoCloseable` (verified by `javap`). Make `toDst` the *only* way to allocate a destination Mat, taking ownership inside a scope (`scala.util.Using` / `Using.Manager`, or javacpp `PointerScope`), so `reduceNoise` / `approxGradient` release intermediates deterministically. **UNVERIFIED:** `PointerScope` semantics under virtual threads on JDK 25.

### 4.2 Error model — there isn't one
Four incompatible strategies coexist, none documented:
- **Silent success on failure (dominant).** `imread` returns an empty Mat for a missing file — never checked. `imwrite` returns `boolean` — discarded. `imencode` returns `boolean` — discarded. `Imgproc.threshold` returns the computed `double` — discarded. `new CascadeClassifier(badPath)` yields a classifier that detects nothing.
- **Unchecked exceptions.** `resourcePath` NPEs; `sourceMat` throws bare `RuntimeException`; `longestVec4iLines` throws on empty input; `frameEyes` can throw `IndexOutOfBoundsException`; native `CvException` can surface anywhere. All of these live inside `Future { … }` → **failed Futures dropped on the floor**, and the demos never `recover`.
- **`Option`** — used exactly once (`frameEyes`'s `headOption`).
- **`assert`** — used once (`sourceMat`), compiler-elidable.

Aggravating factor: `Await.ready(p, 5 seconds)` in `FaceDetect` / `Sobel` / `Laplace` — `ready`, not `result`, so a failed pipeline **exits with status 0**. These are exactly the demos intended for CI. Diagnostics are `println` — 6 stdout writes in a library (`readImg`, `writeImg`, `getClassifier`, `findFaces`).

**Mandate.** Pick one: `Either[CvError, A]` (or `Try`) at the I/O boundary (decode, encode, classifier load, frame grab), unchecked exceptions for programmer errors only, a real logging facade instead of `println`, and `Await.result` (or no `Await` at all) in demos.

### 4.3 Raw int constants — exposed unwrapped, everywhere
Passed through in signatures (`threshold(mat, thresh, mode: Int)`); hardcoded as unnamed literals otherwise: `border_default = 4` (3 sites — should be `Core.BORDER_DEFAULT`), `8` in `drawContours` (`LINE_8`), `3` for every kernel size, `255` for threshold maxval, `1` / `Math.PI/180` for Hough resolution, `1000` for line-extension length, `2` for line thickness, `new Scalar(0,0,255)` / `(0,255,0)` for colours. `toGray`'s `dstCn`, `sobel`'s `x_order` and `threshold`'s `mode` are all `Int` and mutually interchangeable at the call site. The only mitigation, `threshConstants`, is stringly-typed and unused by `threshold` itself.

**Mandate.** Scala 3 `enum`s with a `cvValue: Int` member for `ThresholdType`, `BorderType`, `ColorConversion`, `LineType`, `ContourRetrieval`, `ContourApproximation`, `HersheyFont`; opaque types / case classes for geometry. **Verified port hazard:** in `org.bytedeco.opencv.global.opencv_imgproc` the constants exist in both un-prefixed (`THRESH_BINARY`, `FONT_HERSHEY_PLAIN`, `RETR_TREE`) and legacy `CV_`-prefixed forms — **use the un-prefixed set**; the `CV_`-prefixed set is C-API legacy and its numeric values are not guaranteed to match.

### 4.4 `Future` misuse — cargo cult, with one real exception
21/24 methods return `Future[_]` on `import ExecutionContext.Implicits.global` (`OpenCV.scala:6`) — a fixed global pool, never caller-supplied. Actual concurrency gained: **exactly one site** (`approxGradient`). Everything else is a strictly sequential `flatMap` chain buying nothing but thread hops, allocation and lost stack traces. The real motivation is `JfxExecutionContext`: async lets the GUI demos update the FX thread without blocking it — **a GUI concern, not a library concern**.

Hazards introduced: `Mat` is not thread-safe, yet `addVec2fLines` / `addContours` / `frameFaces` mutate a shared `Mat` from pool threads; `approxGradient` runs two `sobel` calls concurrently reading the same `mat`, safe only because Sobel is read-only on `src` — an undocumented invariant one refactor from a data race. Combined with §4.1, off-heap memory is allocated on threads that never release it.

**R1a verdict:** core should be **synchronous and total** (`Mat => Mat` + `Either` at boundaries), with async offered as a separate opt-in adapter where the caller supplies the EC; native ops are CPU-bound and already internally parallelised by OpenCV's `parallel_for`. **R1b verdict:** the `Future`-pipeline shape is the library's identity and should survive "essentially unchanged in shape (as `IO`/`ZIO`/`Future`)". **CONFLICT-1 — see §9.**

### 4.5 JavaFX coupling in core
Five distinct leaks: (1) `OpenCV.scala:25` imports `javafx.scene.image.Image` and `mat2Image` returns it; (2) `ImgprocThresh.toString` is overridden for `ListView` rendering; (3) `frameFaces` / `frameEyes` / `addVec*Lines` / `addContours` are presentation logic living in the detection and image traits; (4) `Future` + `JfxExecutionContext` exist to service the FX thread; (5) the cake pattern makes GUI and CV traits siblings on one object (`class CamFaceDetect extends … with OpenCVImg with OpenCVDetect with JfxUtils`, `WebcamService extends Service[Future[Mat]] with OpenCVVideo with JfxUtils`), which is structurally what prevents separation.

Additional blocker: **JavaFX is not in the JDK.** `java --list-modules | grep -i javafx` → empty on 25.0.3-graal; `javap javafx.scene.image.Image` → `Error: class not found`. Explicit `org.openjfx` deps are required for anything FX. **Exact OpenJFX version: UNVERIFIED.**

**Mandate.** `scalacv-core` (zero GUI deps) / `scalacv-draw` (OpenCV drawing only, optional) / `scalacv-javafx` (`Image` conversion + `JfxExecutionContext` + widgets).

### 4.6 Vendored natives & the legacy build
`lib/opencv-300.jar` is a pure-Java JNI **stub** jar (every method `private static native`); the ~40 MB of actual natives were never in the repo. `grep -rn "loadLibrary\|NATIVE_LIBRARY_NAME\|java.library.path"` → nothing: the code does not use the documented `System.loadLibrary(Core.NATIVE_LIBRARY_NAME)` idiom, it hardcodes `System.load("/home/mario/dev/tools/opencv-3.0.0-rc1/build/lib/libopencv_java300.so")`. Every entry point calls it (`Sobel:11`, `FaceDetect:11`, `Laplace:11`, and `override def init(): Unit = loadNativeLibs` in `CannySlider:33`, `BrightnessContrastDemo:31`, `ThresholdDemo:34`, `CamFaceDetect:38`). Consequences: unreproducible on any machine including the author's current one; Linux-x86_64 only; ABI pinning invisible; `lib/` jars are non-transitive so the artifact is unpublishable by construction.

**Replacement — coordinate verified.** `https://repo1.maven.org/maven2/org/bytedeco/opencv-platform/maven-metadata.xml` → `<latest>4.13.0-1.5.13</latest>`, `<lastUpdated>20260222024936</lastUpdated>`. Version string is `<opencv-version>-<javacpp-presets-version>`; **there is no bare `4.13.0`**. `cs resolve org.bytedeco:opencv-platform:4.13.0-1.5.13` resolves to `javacpp:1.5.13`, `javacpp-platform:1.5.13`, `openblas:0.3.31-1.5.13`, `openblas-platform:0.3.31-1.5.13`, `opencv:4.13.0-1.5.13`, `opencv-platform:4.13.0-1.5.13`. `opencv-4.13.0-1.5.13-linux-x86_64.jar` is 31 MB containing 83 `.so` files under `org/bytedeco/opencv/linux-x86_64/`. Platform classifiers in the `-platform` POM: `android-arm, android-arm64, android-x86, android-x86_64, ios-arm64, ios-x86_64, linux-x86, linux-x86_64, linux-armhf, linux-arm64, linux-ppc64le, macosx-arm64, macosx-x86_64, windows-x86, windows-x86_64` (some commented out). Set `-Djavacpp.platform=linux-x86_64` or depend on the classified base jar to avoid pulling ~15 native jars.

**This is a rewrite of every call site, not a rename.** Legacy imports `org.opencv.{core,imgproc,imgcodecs,objdetect,videoio}`; bytedeco puts classes under `org.bytedeco.opencv.opencv_core.*` etc. and free functions in `org.bytedeco.opencv.global.*` (verified present: `global/opencv_core.class` 240 KB, `opencv_imgproc.class` 221 KB, `opencv_imgcodecs.class` 55 KB, `opencv_objdetect.class`, `opencv_videoio.class`).

**Verified bytedeco 4.13.0-1.5.13 port map** (all by `javap` against `/home/worxbend/.cache/coursier/v1/https/repo1.maven.org/maven2/org/bytedeco/opencv/4.13.0-1.5.13/opencv-4.13.0-1.5.13.jar`):

| Legacy (`org.opencv`) | bytedeco 4.13 |
|---|---|
| `Mat` | `opencv_core.Mat extends AbstractMat`; `public native void release()`; `AutoCloseable` via `Pointer` |
| `Imgcodecs.imread(String)` / `imwrite(String,Mat)` | `opencv_imgcodecs.imread(String): Mat` / `imwrite(String, Mat): boolean` |
| `Imgcodecs.imencode(String,Mat,MatOfByte)` | `opencv_imgcodecs.imencode(String, Mat, java.nio.ByteBuffer): boolean` (+ `BytePointer`/`byte[]` overloads) — **no `MatOfByte` in bytedeco** |
| `Imgproc.cvtColor(s,d,code,dstCn)` | `opencv_imgproc.cvtColor(Mat,Mat,int,int,int)` / `cvtColor(Mat,Mat,int)`; **the 4-arg form does not exist**. **UNVERIFIED** which trailing param of the 5-arg form is `dstCn` — confirm against the C++ header before porting `dstCn = 1`. |
| `Imgproc.equalizeHist` | `opencv_imgproc.equalizeHist(Mat,Mat)` |
| `Imgproc.GaussianBlur` | `opencv_imgproc.GaussianBlur(Mat,Mat,Size,double,double,int,int)` — **7 args, one more than 3.0's 6** |
| `Imgproc.blur` | `opencv_imgproc.blur(Mat,Mat,Size,Point,int)` / `blur(Mat,Mat,Size)` |
| `Imgproc.Sobel` / `Laplacian` / `Canny` | `opencv_imgproc.Sobel(Mat,Mat,int,int,int,int,double,double,int)` / `Laplacian(Mat,Mat,int,int,double,double,int)` / `Canny(Mat,Mat,double,double,int,boolean)` |
| `Imgproc.HoughLines(s,d,…) → Mat` | `opencv_imgproc.HoughLines(Mat,Vec2fVector,double,double,int,double,double,double,double)` — **typed `Vec2fVector` output**, which makes the `Seq[PolarLine]` rewrite natural |
| `Imgproc.HoughLinesP` | `opencv_imgproc.HoughLinesP(Mat,Vec4iVector,double,double,int,double,double)` |
| `Imgproc.threshold` | `opencv_imgproc.threshold(Mat,Mat,double,double,int): double` |
| `Imgproc.findContours(Mat, java.util.List<MatOfPoint>, …)` | `opencv_imgproc.findContours(Mat,MatVector,Mat,int,int,Point)` — **`MatVector`; the `JavaConversions` hack is not merely removed, it is unnecessary** |
| `Imgproc.drawContours` | `opencv_imgproc.drawContours(Mat,MatVector,int,Scalar,int,int,Mat,int,Point)` |
| `Imgproc.line`/`rectangle`/`putText` | `opencv_imgproc.line(Mat,Point,Point,Scalar,int,int,int)`, `rectangle(Mat,Point,Point,Scalar,…)` **plus a new `rectangle(Mat,Rect,Scalar,…)` overload**, `putText(Mat,String,Point,int,double,Scalar,…)` |
| `Core.convertScaleAbs` / `Core.addWeighted` | `opencv_core.convertScaleAbs(Mat,Mat)` / `opencv_core.addWeighted(Mat,double,Mat,double,double,Mat)` |
| `Core.FONT_HERSHEY_PLAIN` | **moved** → `opencv_imgproc.FONT_HERSHEY_PLAIN` |
| `MatOfRect` + `.toArray()` | `opencv_core.RectVector`; `CascadeClassifier.detectMultiScale(Mat, RectVector)`. `Rect` is a `Pointer` → **must be copied into a Scala case class** |
| `new CascadeClassifier(String)` | `org.bytedeco.opencv.opencv_objdetect.CascadeClassifier(String)` |
| `VideoCapture.read/grab/isOpened` | `org.bytedeco.opencv.opencv_videoio.VideoCapture` — `public native boolean read(Mat)`, `grab()`, `isOpened()` |
| `System.load("…libopencv_java300.so")` | `org.bytedeco.javacpp.Loader` extracts + loads bundled natives at class-init; no `java.library.path`, no user action. **UNVERIFIED:** `opencv-platform`'s POM/classifier layout was not fetched by R1a (but was by R1d — see §9 CONFLICT-4). |

**Independently confirmed break in the `org.opencv` API itself** (R1b, `javap` on both jars): `Core.FONT_HERSHEY_PLAIN` count 1 in 3.0 `Core`, **0** in 4.13 `Core`, and present (+7 siblings) in 4.13 `Imgproc`. Used at `OpenCV.scala:263` — on the `FaceDetect` *and* `CamFaceDetect` paths. Also removed in 4.13: `VideoCapture.getSupportedPreviewSizes()` (unused here).

**Non-OpenCV compile blockers, verified:** `scala/collection/JavaConversions.class` present in scala-library 2.11.6, **absent** in 2.13.18 — affects `OpenCV.scala:4` and `ThresholdDemo.scala:19`; replace with `scala.jdk.CollectionConverters`. Scala-2-only syntax in the demos: procedure syntax `override def changed(…) { … }` (all four slider demos), `Application.launch(classOf[X], args: _*)` → `args*`, `App` trait (`FaceDetect`/`Sobel`/`Laplace`) → `@main`, `_ <: Number` → `? <: Number`. `src/main/scala-2.11` is not a source root under Scala 3.

---

## 5. Example applications

8 demos. **Critical structural fact:** no demo calls OpenCV directly except `Imgcodecs.imread` (the 5 GUI demos, in `start()`); every other OpenCV call is inside `OpenCV.scala`. **The port risk is concentrated in one 320-line file, not in the demos.**

| App | Demonstrates | OpenCV calls used | Camera? | GUI? | Heritage? | Rewrite target |
|---|---|---|---|---|---|---|
| `FaceDetect.scala` | Batch face detection on `/Lena.png` → `faceDetection.png`; `Future` for-comprehension pipeline | `imread`; `cvtColor(…,COLOR_BGR2GRAY,1)`; `equalizeHist`; `new CascadeClassifier(String)`; `detectMultiScale(Mat,MatOfRect)`; `MatOfRect.toArray()`; `rectangle`; `putText`; **`Core.FONT_HERSHEY_PLAIN`**; `imwrite` | no | no | **YES** — README: rewrites OpenCV *Introduction to Java Development* tutorial + ideas from `chimpler/blog-scala-javacv` | `FaceDetectHaar` (headless examples; runs in CI). Add a `FaceDetectorYN` companion — `org/opencv/objdetect/FaceDetectorYN.class` confirmed present in the 4.13 jar — **alongside**, not replacing |
| `Sobel.scala` | Sobel x/y gradient approximation via `OpenCVCombos` → `sobel.png` | `imread`; `GaussianBlur(…,Size(3,3),0,0,4)`; `cvtColor`; `Sobel(…,CV_16S,x,y,3,1,0,4)` ×2; `convertScaleAbs` ×2; `addWeighted(m1,0.5,m2,0.5,0,dst)`; `imwrite` | no | no | no (undocumented; landed after last README commit) | headless example; **the natural regression test for the Combos layer** |
| `Laplace.scala` | Laplacian edge detection → `laplace.png` | `imread`; `GaussianBlur(…,4)`; `cvtColor`; `Laplacian(…,CV_16S,3,1,0,4)`; `convertScaleAbs`; `imwrite` | no | no | no (undocumented) | headless example; CI |
| `CannySlider.scala` | Live Canny, 2 sliders (low threshold, hi:lo ratio) + L2-gradient checkbox | `imread`; `cvtColor`; `blur(…,Size(3,3))`; `Canny(…,low,low*ratio,3,l2gradient)`; `imencode` | no | **yes** | **YES** — README: rewrites *Canny Edge Detector* tutorial; **ratio + L2-gradient controls are the original contribution** | GUI example, tag `Gui` |
| `HoughSlider.scala` | Probabilistic Hough transform, 2 sliders, red line overlay | `imread`; `Mat.clone()`; `cvtColor`; `blur`; `Canny(…,50,200.0,3,false)`; `HoughLinesP(…,1,PI/180,thr,minLen,10)`; `Mat.height()` + `Mat.get(i,0)`; `line(…,Scalar,2)`; `imencode` | no | **yes** | no (undocumented) | GUI example, tag `Gui`. **Carries the two riskiest unverified 4.x behaviours (§9 Q6/Q7)** |
| `ThresholdDemo.scala` | 5 threshold modes via `ComboBox` + threshold slider | `imread`; `Mat.clone()`; `cvtColor`; `threshold(…,255,mode)`; `THRESH_BINARY{,_INV}`, `THRESH_TRUNC`, `THRESH_TOZERO{,_INV}`; `imencode` | no | **yes** | no (undocumented) | GUI example, tag `Gui`. Thinnest of the set — candidate to fold into a single "adjustments" demo |
| `BrightnessContrastDemo.scala` | α gain / β bias via `Mat.convertTo` | `imread`; `Mat.clone()`; `convertTo(dst,-1,α,β)`; `imencode`. **No OpenCV algorithm at all** | no | **yes** | no (undocumented) | GUI example, tag `Gui`. Safest to drop or fold |
| `CamFaceDetect.scala` | Webcam face **+ left/right eye** detection via `javafx.concurrent.Service` restart loop | all of FaceDetect, plus `new VideoCapture(0)`, `isOpened/grab/read`; `new Mat(Mat,Rect)` (eye ROIs); `imencode(".png",Mat,MatOfByte)`; `MatOfByte.toArray()` | **yes** | **yes** | **YES** — README: "heavily indebted with `rladstaetter/isight-java`", "adds eyes detection too". Evolved from now-deleted `ladstatt.scala` (commit `bd92664` "Start skinning ladstatt") | GUI example, tags `Gui` + **`Hardware`** — never run in CI. **Clean-room required** (§7) |

**Tagging.** Only `CamFaceDetect` truly needs `Hardware`. The other four GUI demos need only a display — a separate `Gui` tag lets them run under `xvfb`/Monocle while `Hardware` is unconditionally excluded.

**Module shape.** `core` (zero JavaFX) / `examples-headless` (FaceDetect, Sobel, Laplace — CI) / `examples-gui` (the five FX demos). The three heritage demos are the ones that go into the docs microsite.

**Latent bugs to fix rather than port:** `Await.ready` → silent success on failure (§4.2); `resourcePath` (§2); `VideoCapture` never `release()`d; the busy-spin at `OpenCV.scala:308`; unnecessary `im.clone` at `ThresholdDemo:46` and `BrightnessContrastDemo:43` (those ops write to a fresh dst) but **necessary** at `HoughSlider:43` (`addVec4iLines` mutates in place) — a typed "mutates-in-place vs returns-new" distinction is the single clearest API improvement available; dead commented-out `cb.visibleProperty.bind(colorToggle.selectedProperty)` at `ThresholdDemo:78` referencing a control that does not exist.

---

## 6. Delete list

- [ ] `lib/opencv-300.jar` — 292 KB of OpenCV 3.0.0-**rc1** JNI stubs + sources, no natives, no LICENSE entry in the jar. Replaced by `org.bytedeco:opencv:4.13.0-1.5.13`. Also a standing BSD-notice violation (§7).
- [ ] `lib/` — empty afterwards.
- [ ] `build.sbt` — sbt 0.13 / Scala 2.11.6 / `organization := "it.callisto"`. Target is Mill.
- [ ] `project/build.properties` — `sbt.version=0.13.8`; does not run on JDK 25.
- [ ] `project/site.sbt` — `sbteclipse-plugin` 3.0.0, dead IDE tooling.
- [ ] `project/` — empty afterwards.
- [ ] `.github/unicorns` — byte-identical duplicate of `.mergify.yml` from a misfired commit (`0a90c77`); pure garbage.
- [ ] `.mergify.yml` — rules keyed on labels from a bot setup that no longer exists; deprecated 2020-era `strict`/`strict_method` schema.
- [ ] `.whitesource` — WhiteSource Bolt config; product renamed to Mend, GitHub app sunset. Superseded by Scala Steward / Dependabot.
- [ ] `.gitignore` — sbt/Scala-IDE entries only. Delete and rewrite for Mill; do not edit in place.
- [ ] `src/main/resources/Lena.png` — 424 KB redistributed without rights, subject withdrawn consent, IEEE-banned, source of record no longer distributes it (§8.2). Update all **7** call sites.
- [ ] `src/main/scala-2.11/` (10 files) — invalid source root, EOL language version, package rename. **Do not `git rm` before the port lands**: these files are the design-intent inventory and the only in-tree evidence of the mcallisto/ladstatt lineage.
- [ ] `PLAN.md` — untracked working doc, superseded by `ROADMAP.md` per its own §2. **Amend §6 before deleting** (§7).
- [ ] `origin/renovate/configure` — stale 2022 bot branch; PR never merged.

Total tracked binary dead weight removable: **~716 KB** (`lib/opencv-300.jar` 292 KB + `Lena.png` 424 KB) out of a 1.3 MB working tree.

**Explicitly NOT deleted:** `README.md` (attribution-bearing — rewrite, never remove; the three credit links must survive), `.git/` history (now the sole record of upstream authorship — do not squash, do not re-init), the three cascade XMLs, `CLAUDE.md`.

---

## 7. License & attribution

### What upstream carries: nothing
`mcallisto/scalacv` is **gone** — `curl https://github.com/mcallisto/scalacv` → HTTP 404; `api.github.com/repos/mcallisto/scalacv` → `{"message":"Not Found","status":"404"}`. The user `github.com/mcallisto` is alive (HTTP 200, ~52 repos) but `scalacv` is not among them. A Wayback snapshot exists (`web.archive.org/web/20180611035138/…`, `available: true`) but archive.org was blocked from the audit environment → **snapshot contents UNVERIFIED**.

This repo nonetheless holds the **complete unbroken history from mcallisto's own initial commit** `c57696d` (2015-05-09, `mario.callisto@gmail.com`), whose tree is `.gitignore` + `README.md` only — i.e. the repo was created via the GitHub UI *without* the "add a license" option. **Conclusion: upstream was unlicensed — all rights reserved by Mario Càllisto.** Confidence high (history-complete), not absolute: a LICENSE added upstream *after* the last commit we hold would be invisible.

### What this fork currently has: nothing
```
$ find . -path ./.git -prune -o -iname '*licen*' -print -o -iname '*copying*' -print -o -iname '*notice*' -print
(no output)
$ git log --all --diff-filter=A --name-only | grep -iE 'licen|copying|notice'
(no output)
```
**No LICENSE file has ever existed in this repository, on any ref, in any commit, since 2015.** `gh api repos/w0rxbend/scalacv` → `{"fork": false, "license": null, "parent": null}` — this is also **not registered as a GitHub fork**, so GitHub renders no "forked from" link and the lineage exists only in commit history and in `README.md`. Zero copyright headers in any `.scala` file. The only `grep -ri "license\|copyright"` hits in the tree are the embedded Intel notice in two cascade XMLs and this project's own planning prose.

### This is a blocker, not a nit
The 2015 code is **not licensed to you**. Absent a grant, default copyright applies: no right to copy, modify, or redistribute. Publishing to Maven Central while the artifact contains derivative 2015 code is infringement. Two clean paths:
1. **Preferred** — request a relicense grant (Apache-2.0 or MIT) from `mario.callisto@gmail.com` / GitHub `@mcallisto`; record the grant verbatim in `NOTICE` and reference it in `ROADMAP.md`.
2. **Fallback if no reply** — the Scala 3 rewrite must be a genuine clean-room reimplementation against the OpenCV 4.13 API: no copied `.scala` bodies, no copied structure-of-expression. The `Future`-based API and the cake pattern die anyway, which makes this practical. Retain a courtesy lineage credit regardless (uncopyrightable facts: "originally forked from mcallisto/scalacv").

`PLAN.md` §6's "keep original license" / "do not drop attribution to mcallisto/scalacv" is **currently unsatisfiable — there is no original license to keep.** Amend it.

### Third-party obligations currently unmet
- **`lib/opencv-300.jar`** — OpenCV 3.0.0 is **3-clause BSD** (fetched `raw.githubusercontent.com/opencv/opencv/3.0.0/LICENSE`: *"(3-clause BSD License) Copyright (C) 2000-2015, Intel Corporation … Willow Garage … NVIDIA … AMD … OpenCV Foundation … Itseez Inc."*), requiring binary redistributions to reproduce the notice. The vendored jar's MANIFEST is 3 lines and there is **no LICENSE entry**. Redistributing it in a public git repo with the notice stripped is a **standing BSD violation**. Deleting `lib/` fixes it going forward; `git rm` does not purge history, but this is a notice-only breach of a permissive licence — history rewrite is not warranted. (Note: the jar self-reports `3.0.0-rc1`; the licence text fetched was the `3.0.0` tag.)
- **`rladstaetter/isight-java`** — `api.github.com/repos/rladstaetter/isight-java` → `"license": None`; root listing `['.gitignore','Readme.md','pom.xml','src']`, **no LICENSE file**. Commit `bd92664` is literally "Start skinning ladstatt". So `CamFaceDetect.scala` / `JavaFxUtils.scala` descend from **unlicensed** third-party code → **second clean-room requirement**.
- **`chimpler/blog-scala-javacv`** — `"license": None`; root listing `['.gitignore','README.md','build.sbt','skyfall.jpg','src']`, **no LICENSE file**. Ideas-level borrowing per README; lower risk, but the credit must be preserved.
- **`org.bytedeco:opencv-platform` (incoming)** — javacpp-presets `LICENSE.txt` (fetched): *"You may use this work under the terms of either the Apache License, Version 2.0, or the GNU General Public License (GPL), either version 2, or any later version, with 'Classpath' exception … as long as the copyright header is left intact."* Compatible with an Apache-2.0 library. OpenCV `4.x` upstream `LICENSE` is now **Apache-2.0** (fetched); the exact 3.x→4.x relicensing version is **UNVERIFIED**.

### Exactly what MUST be added
1. **`LICENSE`** at repo root — Apache-2.0 recommended (matches OpenCV 4.x and javacpp-presets; provides a patent grant).
2. **`NOTICE`** at repo root, containing: the mcallisto lineage statement + the relicense grant text (or the clean-room declaration if no grant was obtained); the Intel/OpenCV notice for the bundled cascade XMLs + credit to **Shiqi Yu**; credits to `rladstaetter/isight-java` and `chimpler/blog-scala-javacv`; the OpenCV 3-clause BSD notice **only if** anything OpenCV-3-derived remains (it should not, after `lib/` deletion).
3. **`THIRD-PARTY.md`** (or `src/main/resources/README.md`) — per-file resource provenance: upstream URL, branch, SHA-256, fetch date.
4. **License metadata in `build.mill`** publish settings (`PomSettings.licenses`) — nothing declares a license anywhere today.
5. **Amend `PLAN.md` §6** — "keep original license" → "obtain a license grant, or clean-room".

---

## 8. Third-party assets

### 8.1 Cascade XMLs — keepable, notice required
All three under `/home/worxbend/Projects/scalacv/src/main/resources/` are **byte-identical to current OpenCV `4.x` upstream** (fetched from `raw.githubusercontent.com/opencv/opencv/4.x/data/{haarcascades,lbpcascades}/…`; `diff` → IDENTICAL, SHA-256 match on all three). Provenance is therefore certain, and they remain valid for a 4.13 `CascadeClassifier` — modulo runtime confirmation (§9 Q9).

- `haarcascade_lefteye_2splits.xml` (192 KB, `74c323c7…`) and `haarcascade_righteye_2splits.xml` (192 KB, `4cf0d72b…`) carry their **own embedded licence header**, verbatim: *"Tree-based 20x20 left eye detector. … trained by 6665 positive samples from FERET, VALID and BioID face databases. Created by Shiqi Yu (http://yushiqi.cn/research/eyedetection)."* followed by the **Intel License Agreement For Open Source Computer Vision Library, Copyright (C) 2000, Intel Corporation**, with a binary-redistribution notice clause and an Intel no-endorsement clause. A published jar containing these XMLs **must reproduce the notice in accompanying documentation**, and Shiqi Yu deserves a `NOTICE` credit. Training-data lineage (FERET / VALID / BioID) has its own distribution restrictions — **UNVERIFIED**; redistributing the *trained* cascade under the Intel notice is what OpenCV itself does.
- `lbpcascade_frontalface.xml` (52 KB, `6f5f2009…`) has **no embedded licence header** (`head -20` = sample counts only, identical to upstream) → covered by the OpenCV repo licence (Apache-2.0 on `4.x`).

OpenCV's own `data/readme.txt` (fetched) confirms the split: *"haarcascades — the folder contains trained classifiers… Some of the classifiers have a special license - please, look into the files for details."*

**Action:** keep all three; add the Intel notice + Shiqi Yu credit to `NOTICE`; add `src/main/resources/README.md` with upstream URL, branch, SHA-256 and fetch date. **Alternative worth considering:** the same cascades ship inside the bytedeco `opencv` jar, so vendoring them is optional.

### 8.2 `Lena.png` — remove and replace
Local facts: 512×512 8-bit RGB non-interlaced PNG, 431,096 B, `sha256 cf3aefcd…` — the canonical USC-SIPI `misc/4.2.04` crop. Added by `9d0879b` (mario, 2015-05-11), re-encoded in place by `33327a5 [ImgBot] Optimize images` (2019-01-02) from 494,405 B; the only surviving PNG text chunks are ImgBot's `date:create`/`date:modify`. **No copyright or attribution metadata is embedded.** 7 call sites (verified above).

- **Copyright is held by Playboy Enterprises** (photograph by Dwight Hooker, Nov/Dec 1972 centrefold). Playboy chose not to enforce — *"We decided we should exploit this, because it is a phenomenon"* (Eileen Kent, VP new media) — but **non-enforcement is not a licence**. No grant permits redistribution in a Maven Central artifact. (Wikipedia: Lenna)
- **USC-SIPI explicitly disclaims the ability to grant rights**: *"USC-SIPI does not hold the copyright status on many of the images in the database… will not be able to provide any documents granting permission for their use."* (`sipi.usc.edu/database/copyright.php`)
- **USC-SIPI has withdrawn the file** — verified firsthand: `GET https://sipi.usc.edu/database/download.php?vol=misc&img=4.2.04` returns HTTP 200 `text/html` with body *"Image download error: File not found - \"misc/4.2.04.tiff\""*. The source of record no longer distributes it.
- **IEEE Computer Society banned it** — no papers containing Lenna accepted from 2024-04-01.
- **The subject asked to be retired** — Lena Forsén, 2019 *Losing Lena*: *"I retired from modeling a long time ago. It's time I retired from tech, too… Let's commit to losing me."*
- Context, not exculpation: OpenCV upstream **still ships** `samples/data/lena.jpg` and `lena_tmpl.jpg` (verified via GitHub contents API on `4.x`). A new 2026 library has no reason to inherit it.

**Action:** delete; replace with (a) OpenCV-shipped `samples/data/*` with clean provenance, (b) a CC0/public-domain photo recorded in `THIRD-PARTY.md`, or (c) **programmatically generated synthetic fixtures** — which PLAN.md Track B already mandates for tests and which removes the resource-licensing question entirely. Update all 7 call sites.

---

## 9. Open questions for the orchestrator

**CONFLICT-1 (design, must be decided before any core code is written) — does `Future`/an effect type survive into the core API?**
R1a: `Future` is cargo cult (21/24 methods, exactly one site gains real concurrency); core should be **synchronous and total** (`Mat => Mat` + `Either`), with async as an opt-in adapter. R1b: the `Future[Mat]` pipeline *is* the library's identity and should survive "essentially unchanged in shape (as `IO`/`ZIO`/`Future`)". Both agree the *composable pipeline* is the point; they disagree on whether the effect wrapper is intrinsic to it. **Orchestrator must choose.** A synthesis exists — pure synchronous `Mat => Mat` core + a thin `scalacv-effect` adapter that reproduces the for-comprehension ergonomics — but it is a decision, not a finding.

**CONFLICT-2 — which OpenCV 4.13 Java API is the target: bytedeco `org.bytedeco.opencv.*`, or the official `org.opencv.*` JNI bindings that the bytedeco jar apparently also ships?**
R1b performed all its 4.13 signature checks by running `javap` on `org.opencv.*` classes *inside* `opencv-4.13.0-1.5.13.jar` and reports `org/opencv/objdetect/FaceDetectorYN.class` present there. R1d found `org/bytedeco/opencv/opencv_java.class` in the same jar and marked "you could keep the old `org.opencv.*` API" as **UNVERIFIED**. R1b's evidence suggests those classes *are* present. This must be settled: it decides whether the port is a rename (R1b's "everything unchanged except `FONT_HERSHEY_PLAIN`") or a total call-site rewrite (R1a/R1d). **The task brief specifies the bytedeco binding, so `org.bytedeco.opencv.*` is presumed correct — but confirm, because R1b's entire "CONFIRMED UNCHANGED" table is about the other API and does not transfer.**

**CONFLICT-3 — `cvtColor` arity.** R1a (bytedeco): `cvtColor(Mat,Mat,int)` and `cvtColor(Mat,Mat,int,int,int)`; **the 4-arg form does not exist**, and which trailing param is `dstCn` is UNVERIFIED. R1b (`org.opencv`): `cvtColor(Mat,Mat,int,int)` unchanged from 3.0. Same root cause as CONFLICT-2. Resolve before porting `toGray`'s `dstCn = 1`.

**CONFLICT-4 — `opencv-platform` verification.** R1a marked the artifact's POM/classifier layout UNVERIFIED (did not fetch it). R1d fetched `maven-metadata.xml`, ran `cs resolve`, and enumerated the classifier list. **R1d's data supersedes R1a's UNVERIFIED flag** — no action beyond noting it.

**CONFLICT-5 — `HoughLines`/`HoughLinesP` output type.** R1a (bytedeco): output is `Vec2fVector` / `Vec4iVector`, so `addVec2fLines`/`addVec4iLines`'s `lines.get(i,0)` loop is structurally obsolete. R1b (`org.opencv`): output is still an untyped `Mat` and the layout is NEEDS-VERIFICATION. Same root cause as CONFLICT-2.

Numbered items still open:

1. **Licence grant from Mario Càllisto** — email `mario.callisto@gmail.com` / GitHub `@mcallisto` requesting Apache-2.0 or MIT relicensing. This gates publishing. If no reply within a chosen window, the fallback is a documented clean-room rewrite. **Decide the window and who sends it.**
2. **Clean-room scope.** If the fallback path is taken, `CamFaceDetect.scala` and `JavaFxUtils.scala` need a *second* clean-room pass because they descend from `rladstaetter/isight-java`, which is also unlicensed. Confirm whether `JfxExecutionContext` (a 6-line idiom) is copyrightable enough to matter.
3. **`PLAN.md` §6 amendment** — "keep original license" is impossible; needs rewriting before PLAN.md is retired in favour of ROADMAP.md.
4. **Wayback snapshot of `mcallisto/scalacv`** (`web.archive.org/web/20180611035138/…`) was unreachable from the audit environment. Fetching it would raise confidence on "upstream never had a LICENSE" from high to near-certain. Worth one manual check.
5. **Vendor the cascades or use the bytedeco copies?** Keeping them means reproducing the Intel notice in the published artifact's docs; using the jar's copies avoids the redistribution obligation entirely but adds a runtime extraction step.
6. **`HoughLinesP` output layout at runtime** — `addVec4iLines` (`OpenCV.scala:133-141`) iterates `0 until lines.height()` reading `lines.get(i,0)`. Whether 4.13 preserves the Nx1 orientation is unproven by `javap` and needs a runtime check.
7. **`HoughLines` Vec2f vs Vec3f** — 4.13 adds a `HoughLines(…,double,double,double,double,boolean)` overload; whether the default output gained a votes channel (which would break `addVec2fLines`, `OpenCV.scala:118-131`) is unproven.
8. **`BORDER_DEFAULT` numeric value across 3.0→4.13** — the code hardcodes `4` at `OpenCV.scala:76, 85, 90`. Unverified. Use the named constant regardless.
9. **Do the vendored 3.0-era cascade XMLs load in a 4.13 `CascadeClassifier`?** They are byte-identical to current 4.x upstream, which is strong evidence they do, but no code has been run. Needs one runtime smoke test.
10. **`imread` failure mode in 4.x** — never checked in the legacy code; 4.x added `VideoCapture.setExceptionMode` (confirmed present) and `imread` error behaviour may differ. Decide the error model (§4.2) against actual 4.13 behaviour.
11. **`Imgproc.threshold`'s discarded `double` return** (`OpenCV.scala:185`) — harmless today, but whether any binding marks it `@CheckReturnValue` is unconfirmed.
12. **Exact OpenJFX version for the GUI examples module** — UNVERIFIED. JavaFX is absent from JDK 25.0.3-graal (`java --list-modules | grep -i javafx` → empty), so an explicit `org.openjfx` dependency set is required.
13. **`PointerScope` semantics under virtual threads on JDK 25** — UNVERIFIED and load-bearing for §4.1's resource-safety design.
14. **OpenCV's 3.x→4.x relicensing point (BSD-3 → Apache-2.0)** — the exact version is UNVERIFIED. Only matters if any OpenCV-3-derived material survives, which it should not after `lib/` deletion.
15. **Trivial discrepancy, no action:** R1c wrote "Referenced by 6 of the 10 source files" for `Lena.png` and then listed 7. Re-verified in this pass: **7 files, 7 call sites** (`grep -rn "Lena.png" src/`).
