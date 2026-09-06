#import "../lib/book.typ": *

#chapter("Troubleshooting", subtitle: [What each failure actually means, indexed by the words you would use to describe it.])

A vision program fails in two registers. In the JVM register a mistake produces a stack trace with your
own file names in it. In the native register it produces a signal, a file called `hs_err_pid31474.log`,
and no indication of which line of Scala was running. Most of this book has been about staying in the
first register; this chapter is for the days you end up in the second, and for the days the message in
front of you is telling the truth about something you have not read carefully.

The failures below are not evenly distributed. The overwhelming majority are one of three: the platform
classifier is absent or wrong, so nothing runs; a handle was reused after a transform consumed it, so
`IllegalStateException` fires on a line that looks innocent; or native memory is climbing while the heap
graph stays flat, so nothing alerts until the kernel kills the process.

These messages are written to be read rather than grepped. Read all of one before searching for it.

#figure-table("The index. Find the symptom, read the section.")[
#tbl(
  columns: (2fr, 1.6fr),
  [*What you saw*], [*Section*],
  [`UnsatisfiedLinkError`, or `NativesMissing` at load], [The natives are not there],
  [The kernel killed the process; heap was flat], [RSS climbs, heap does not],
  [`IllegalStateException: … already been released or consumed`], [A spent handle],
  [`SIGSEGV`, an `hs_err_pid*.log`, no Java frames], [A hard crash],
  [`InaccessibleObjectException`, a `--add-opens` in the text], [The module system said no],
  [`Left(DecodeFailed)` on a file you can see], [The image will not read],
  [A video file of zero bytes, or one nothing will play], [The recording is empty],
  [Everything is orange where it should be blue], [The colours are swapped],
  [A detector that returns an empty `Seq`, always], [The detector finds nothing],
  [The same input, two machines, two answers], [Results differ between machines],
  [The first frames take ten times as long as the rest], [The first frames are slow],
  [A 24-megapixel photograph takes seconds], [Everything is slow on a big image],
  [The camera opened; every frame is black or absent], [The camera opens but delivers nothing],
  [`CalibrationFailed`, or an RMS error above 2 px], [Calibration will not converge],
  [It compiles, it resolves, and it will not run], [The build resolves but nothing runs],
)
]

#sect("Start-up and the build")

#subsect("The natives are not there")

#minor("What it means")

The `scalacv` jar contains no native code. `OpenCv.load()` extracts the OpenCV libraries out of the
per-platform classifier jars and loads them by absolute path; if those jars are absent, the extraction finds
nothing and the JNI shim will not link. The failure surfaces as `CvError.NativesMissing` rather than a bare
`UnsatisfiedLinkError`: the loader catches the link error and replaces it.

The message is the fix, computed at the moment of failure from `Loader.getPlatform`, so the classifier
it names is the one you are actually on rather than the one somebody wrote into a document once:

#example("The diagnostic. The versions come from the build, the classifier from `Loader.getPlatform`.")[
```text
OpenCV natives are not on the classpath (no opencv_java in java.library.path).

scalacv depends on the classifier-less OpenCV Java API only, because a build tool cannot
express a per-platform classifier in a published POM. Add the natives for your platform:

  "org.bytedeco" % "opencv"   % "4.13.0-1.5.13" classifier "linux-x86_64"
  "org.bytedeco" % "openblas" % "0.3.31-1.5.13" classifier "linux-x86_64"

Both lines are needed: libopencv_core links libopenblas. If you would rather not pick a
platform, "org.bytedeco" % "opencv-platform" % "4.13.0-1.5.13" bundles every one, at a
cost of about 408 MB.
```
]

#minor("What to do")

Add both lines, with the classifier for the machine that #emph[runs] the code rather than the one that
builds it. `libopencv_core` links `libopenblas`, so omitting the second line leaves the first unresolvable
--- which looks identical to having added nothing.

#figure-table("The five classifiers. Pick the one for the target, or take the 408 MB fat artifact.")[
#tbl(
  columns: (1.4fr, 1fr),
  [*Target*], [*Classifier*],
  [Linux x86-64], [`linux-x86_64`],
  [Linux ARM64], [`linux-arm64`],
  [macOS Intel], [`macosx-x86_64`],
  [macOS Apple silicon], [`macosx-arm64`],
  [Windows x86-64], [`windows-x86_64`],
)
]

One failure wears this exception's clothes without being it. A JVM maps a given native library file into
exactly one classloader, so a second classloader loading scalacv gets `UnsatisfiedLinkError: … already
loaded in another classloader` with the jars demonstrably present --- and that message gets its own text,
because telling you to add dependencies you already have is a dead end. Loading a second copy is not the
alternative: two copies of `libopencv_java` each keep their own globals, and a `Mat` allocated by one is
meaningless to the other. Load scalacv from a classloader both sides share --- in a servlet container, the
container's shared library directory rather than each application's `WEB-INF/lib`; in OSGi, one bundle that
exports `scalacv` rather than a copy per bundle.

#subsect("The build resolves but nothing runs")

#minor("What it means")

`scalacv` compiles fine with no native line at all: the classifier-less OpenCV Java API contains the
classes but none of the symbols behind them. A build that resolves, a file that compiles, and a program
that dies on the first `OpenCv.load()` is therefore the expected state until you add the platform jars
yourself --- the two-line dependency problem from Chapter 2, and the most common first-day failure there
is.

Its compile-time cousin, `value faces is not a member of scalacv.Image`, is a different problem with a
similar feel: the symbol lives in `scalacv-vision` or `scalacv-graphs`, and an import can bring into scope
only what the classpath contains.

#minor("What to do")

Add the natives, or add the module. Keep the wildcard `import scalacv.*` either way --- a single-symbol
import brings in the type and none of the extension methods defined beside it.

#sect("Memory and lifetimes")

#subsect("RSS climbs, heap does not")

#minor("What it means")

The process was killed by the OOM killer, or by a container limit, while every heap metric you have was
healthy. That is the shape of a native leak, and it is the reason this library exists. A `Mat`
is about forty bytes of Java header in front of a multi-megabyte off-heap buffer. Heap pressure is
the only thing that triggers a collection, and heap pressure is uncorrelated with native pressure, so
nothing fails --- memory grows, and then the kernel intervenes.

#memory[
The measurement on this project's test machine: 2000 × `Mat(1000, 1000, CV_8UC3)`, references dropped,
no explicit `System.gc()`. Unreleased, RSS reached 5 865 MB. Released, 144 MB. Same 41× on JDK 21 and
JDK 25. Reclamation is not impossible; nothing makes it happen in time.
]

#minor("What to do")

Put a ceiling on #emph[physical] bytes and the leak fails fast, in a test rather than in production:

#example("A leak that took an hour to notice now takes a minute.")[
```bash
java -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar
```
]

`maxBytes` will not catch it: javacpp's own accounting is blind to buffers OpenCV allocated inside
`cv::Mat`. Once it fails fast, bisect by moving suspects onto a scoped entry point.

#figure-table("The four substitutions that close nine leaks out of ten.")[
#tbl(
  columns: (1.5fr, 1.3fr, 1.2fr),
  [*Instead of*], [*Use*], [*It closes*],
  [`Image.read(p)`, then forgetting], [`Image.reading(p)(use)`], [the image, even if the body consumed it],
  [`Camera.open(i)` in a loop], [`Camera.using(i)(use)`], [the camera and its capture],
  [`Video.frames` with a retained `Mat`], [`Video.framesCopied`], [each owned copy, per iteration],
  [a raw `Mat`], [`Managed.use(mat)(f)`], [the Mat on the way out],
)
]

One case survives all four: the #emph[arguments] to a two-image operation are borrowed, not consumed.
The mask you hand `applyMask`, `inpaint`, `blurBackground` or `seamlessCloneInto`, the second image you
hand `blend`, and the background you hand `seamlessCloneInto` all come back yours to `close()`. Only the
receiver is spent. A slow leak in an otherwise disciplined codebase is usually a borrowed mask.

#subsect("A spent handle")

#minor("What it means")

`IllegalStateException: this Mat has already been released or consumed`. You used a value that a
transform already spent. The message names `Mat` rather than `Image` because an `Image` wraps a
`Managed[Mat]`, and the handle reports the class of the thing it actually owns; Chapter 5 spells that
out. `Image` has move semantics --- queries borrow, transforms and terminals
consume --- so the receiver of `gray`, `blur`, `canny`, any `draw*`, `write`, `bytes` or `close` is
dead the moment the call returns.

#example("The mistake, then the fix. `img` is spent by `.gray`, so the second line has nothing to work on.")[
```scala
val img = Image.blank(8, 8)
val a = img.gray
val b = img.blur(2)          // throws: `img` was consumed by `.gray`

val original = Image.blank(8, 8)
val edges = original.copy.gray.canny(50, 150)  // `.copy` borrows; `original` survives
val small = original.resize(4, 4)              // now `original` is consumed
```
]

#minor("What to do")

Take a `.copy` before the first use when you need the image twice. The harder part is finding the
consuming call, since the exception fires at the reuse. Start the JVM with
`-Dscalacv.trackOwnership=true` and it carries, as its `cause`, the stack of the transform or terminal
that spent the handle:

#example("Ownership tracking: off by default, and free until something already went wrong.")[
```bash
java -Dscalacv.trackOwnership=true -jar your-app.jar
```
]

It is off by default because it allocates a `Throwable` every time a handle is spent; the read happens
only on the already-failing path, so a correct program pays nothing for the check.

#subsect("A hard crash")

#minor("What it means")

The JVM died with `SIGSEGV`, wrote an `hs_err_pid*.log`, and produced no Java stack trace, because
there was no Java frame to produce one from. Three causes account for nearly all of these.

The first is a double free. Every one of the 185 `org.opencv.*` types without a public `release()`
carries `protected void finalize() throws Throwable { delete(this.nativeObj); }`, unconditionally --- so
a raw handle you freed yourself is freed a second time by the finalizer thread whenever the collector next
runs. That is heap corruption, and it surfaces somewhere else entirely, later:

#example("The frame that names the culprit. Read the `C` line and the thread name together.")[
```text
SIGSEGV (0xb)  C  [libopencv_java.so+0x163155]  Java_org_opencv_objdetect_FaceDetectorYN_delete
Current thread: JavaThread "Finalizer"
```
]

#memory[
`Managed` prevents this by zeroing `nativeObj` before deleting, so the finalizer's `delete(0)` becomes
`delete nullptr`, a no-op in C++. A crash of this shape means a raw handle escaped `Managed` somewhere.
]

The second is a handle shared across threads. Native handles are one-owner-per-thread, exactly as in raw
OpenCV: detector #emph[results] are immutable data and safe to share, the detectors are not, and a
`FaceDetectorYN` is mutated by every `detect` call.

The third is ABI mixing, which does not look like your bug at all. The bundled `libopencv_highgui.so`
carries unversioned dependency names, so on a machine with a system OpenCV installed a bulk `dlopen` maps
`libopencv_*.so.5.0.0` into the global namespace, where it interposes on the 4.13.0 symbols; the next call
crossing between the two ABIs dies inside `cv::Mat::release()`. `OpenCv.load()` resolves dependencies on
demand rather than speculatively, for exactly that reason.

#minor("What to do")

Read the `hs_err` frame list from the top. The first `C` frame names the native symbol, and
`Java_org_opencv_*_delete` in that position means a lifetime bug rather than a computation bug.
`Current thread` narrows it further: `"Finalizer"` means a double free. Then check whether anything calls
`Loader.load` directly instead of `OpenCv.load()`, and whether any raw `org.opencv.*` handle is constructed
outside a `Managed` or a `Managed.scope`. Keep the file: it is usually the only evidence a native crash
leaves.

#subsect("The module system said no")

#minor("What it means")

An `InaccessibleObjectException` reported as `CvError.NativesMissing`, in the middle of a pipeline that
has been working for twenty minutes. Note when it fires: as a handle is #emph[freed], typically at the end
of a `Managed.use` block, not at `OpenCv.load()`. OpenCV was loaded from a named module rather than the
classpath, so reflection cannot reach the private `delete(long)` that frees the 185 detector types, or
cannot write the `nativeObj` the disarm step needs.

The library refuses to degrade here: falling back to the collector is an unbounded leak that looks like
success, and deleting without disarming is the double free of the previous section.

#minor("What to do")

Add the flag the message prints. It is computed from the offending class's own module and package at the
moment of failure, so it is right for the type that failed.

#example("The flag for a `CascadeClassifier` or a `QRCodeDetector`. A `Net` asks for `org.opencv.dnn` instead.")[
```bash
--add-opens org.bytedeco.opencv/org.opencv.objdetect=ALL-UNNAMED
```
]

The pair is what people get wrong by hand: the module is `org.bytedeco.opencv`, the package is
`org.opencv.objdetect`, and the two do not share a prefix. It is never `java.base/java.lang` --- the field
being opened is OpenCV's own. On the classpath there is no module to open, so the message says so and asks
for a bug report rather than printing a flag with a `null` in it. Where you have the choice, running OpenCV
on the classpath removes the problem instead of papering over it.

#sidebar("Messages that carry their own remedy")[
Three diagnostics here are generated rather than written: the missing-natives text, which fills in
`Loader.getPlatform` and the artifact versions from the build; the `--add-opens` line, which reads the
class's own `Module` and package; and the Windows cascade failure, which names the platform's jar and the
empty `share/` directory inside it.
All three exist because a static sentence and a documentation link would make the reader do work the
process has already done.

The consequence: when a scalacv message runs past one line, the rest is not boilerplate.
]

#sect("Wrong output")

#subsect("The image will not read")

#minor("What it means")

`Image.read` returned `Left(DecodeFailed)` for a file you can see in your editor. `imread` does not
throw on a missing file, a directory, or bytes it cannot decode --- it returns an empty `Mat`, which
would run a whole pipeline on nothing --- so scalacv makes the check explicit and turns it into a
`Left`.

#minor("What to do")

Read `details`. `Images.read` fetches the bytes with the JVM's own file I/O and leaves only the decoding to
OpenCV, so the message distinguishes `there is no file at this path`, `this path is a directory, not a
file`, `the file is empty`, `this is not a usable filesystem path` and `the bytes are not an image in a
format OpenCV can decode`. The first two are almost always a relative path resolved against a working
directory that is not the one your editor shows you --- print `java.io.File(path).getAbsolutePath` and the
argument ends. `the file is empty` is a truncated download or a half-written file. The last is a codec this
build does not carry. And OpenCV understands neither URLs nor
`classpath:` resources: fetch the bytes yourself and use `Image.decode`.

`DecodeFailed` is specifically about image bytes; a model, cascade or video source that will not load
reports `LoadFailed`, a distinction Chapter 6 draws in full.

#subsect("The recording is empty")

#minor("What it means")

`Recorder.open` returned a `Left`, or opened and produced a file nothing will play. `VideoWriter` reports a
missing codec by leaving `isOpened` false rather than by throwing, so scalacv turns that into
`CvError.LoadFailed` whose message names the fallback.

The codec and the container move together, and that is the trap. `Codec.Mjpg` opens only in an `.avi`;
`Codec.Mp4v` and `Codec.Avc1` only in an `.mp4`. Worse, the `linux-x86_64` and `windows-x86_64` payloads this
project builds against ship no FFmpeg plugin at all, so `Mp4v` and `Avc1` do not open there --- which is why
`Codec.Mjpg` is the default for `Recorder.open`, `Recorder.using`, `Camera.recordTo` and
`Animation.record`.

#minor("What to do")

#example("Prefer the compact codec, fall back to the one that always opens. Note the extension moving with it.")[
```scala
def openRecorder(base: String, size: Size, fps: Double): Either[CvError, Recorder] =
  Recorder.open(s"$base.mp4", size, fps, Codec.Mp4v)
    .orElse(Recorder.open(s"$base.avi", size, fps, Codec.Mjpg))
```
]

If even `Codec.Mjpg` in an `.avi` fails, the path is unwritable --- check the parent directory exists.
If the file exists but is tiny, the recorder was never closed: `close()` finalises the container, and
`Recorder.using` does it on every path. A frame whose size does not match the recorder's is not silent
corruption --- `write` requires the match and throws `IllegalArgumentException` naming both sizes, as it
does for a frame that is not 8-bit.

#subsect("The colours are swapped")

#minor("What it means")

Skies are orange, skin has a cyan cast, a red annotation came out blue. OpenCV stores three-channel images as
#emph[BGR], not RGB, for historical reasons that are now nobody's fault. Anything that hands pixels to
a library expecting RGB --- a web encoder, a tensor, another imaging toolkit --- swaps two channels
unless something converts.

#minor("What to do")

Convert with `ColorConversion.BgrToRgb`, or read straight into RGB with
`ImreadFlags(ImreadColor.ColorRgb)`, which skips the conversion --- the object's own shorthands are
`ImreadFlags.Color`, `.Grayscale` and `.Unchanged`, and RGB is not among them. For AWT, `toBufferedImage`
and `Image.fromBufferedImage` handle the ordering already; a hand-rolled raster copy is where this creeps
in. Inside scalacv, use `Scalar.Red`,
`Scalar.Green` and `Scalar.Blue` and channel order stops being something to remember. Chapter 7 has the
round-trip.

#subsect("The detector finds nothing")

#minor("What it means")

An empty `Seq`, every frame, with no error anywhere. A detector that finds nothing is
indistinguishable from a scene containing nothing, so nothing complains.

#minor("What to do")

Work down four things in order. First, is the model loaded? `Cascades.loadFrom` verifies the classifier
is non-empty precisely so a typo comes back as a `Left` rather than as a detector that finds nothing
forever --- and on Windows the bytedeco jar ships an empty `share/` directory with no cascade XML, so
`Cascades.load` by name #emph[cannot] succeed there. Second, the input: Haar detection wants a
single-channel, histogram-equalised image --- `img.gray.equalizeHist` --- and a colour Mat works but is
slower. An object smaller than the window the cascade was trained on is not found at any pyramid level,
which is why a subject that occupies a few dozen pixels of a 4000 px photograph needs the photograph
cropped rather than the parameters loosened. Third, the parameters: `detectHaar` defaults to
`scaleFactor = 1.1`, `minNeighbors = 3` and `minSize = None`, and raising `minNeighbors` to suppress
false positives is how
people accidentally suppress everything. `FaceDetect.create` defaults to `scoreThreshold = 0.9f` and
`nmsThreshold = 0.3f`; 0.9 is strict, and 0.6 is a fair first experiment. Fourth, the colour space: a
mask built in BGR when the threshold was designed for HSV returns empty for the reason above.

#subsect("Results differ between machines")

#minor("What it means")

Three independent causes, and it is worth deciding which one you have before chasing any of them.

The OpenCV build differs. The classifier jars do not all carry the same videoio backends --- no FFmpeg
plugin in the `linux-x86_64` and `windows-x86_64` payloads --- and forcing a `CaptureBackend` that is not
compiled in turns a working `open` into a failing one. The Windows jar ships no Haar cascades. Those are
properties of the artifact, not of your code.

The model differs. `ModelSpec.unverified` disables cache invalidation as well as integrity: whatever file
is on disk under that name is served forever and the URL is never contacted again, so two machines that
downloaded at different times can run different weights with no signal that they do.

And floating point varies. OpenCV dispatches on the SIMD the CPU reports and parallelises kernels across a
thread pool sized from the core count, so a bilateral filter or a forward pass can differ in the last bits
between machines. That is not a defect to fix.

#minor("What to do")

Pin a SHA-256 in every `ModelSpec` and let a mismatch re-download. Leave `CaptureBackend.Any` unless you
have a concrete reason. And compare images with a tolerance --- `org.opencv.core.Core.PSNR`, or a bounded
maximum absolute difference --- rather than byte equality, the assertion that makes a portable pipeline
look broken.
Chapter 40 has the shape of those tests.

#sect("Performance and hardware")

#subsect("The first frames are slow")

#minor("What it means")

Two warm-ups, stacked. The first `OpenCv.load()` in a fresh environment extracts about 196 MB of native
libraries into `~/.javacpp`; every later run reuses that cache, so it is a cold-start cost rather than a
per-run one. On top of it the JIT needs many iterations before the pixel loops reach steady state, which
is why this project's benchmark harness runs 2000 untimed iterations by default, in `Bench.measure`,
before it times anything.

#minor("What to do")

Relocate the cache with `-Dorg.bytedeco.javacpp.cachedir=/var/cache/javacpp`, or pre-warm it at container
build time by running any main that calls `OpenCv.load()` --- the extraction is content-addressed and
idempotent, so a baked layer makes every cold start instant. For the JIT, push a few frames through the
real pipeline before reporting any timing, and never quote a first-frame number as a benchmark.

#subsect("Everything is slow on a big image")

#minor("What it means")

Work scales with pixel count, and pixel count with the square of the linear dimension: a 24-megapixel
photograph is roughly twelve times the work of a 1080p frame for the same operation. A 1080p BGR frame is
about 6 MB, so a copying loop at 30 fps moves 180 MB/s before doing anything useful.

#minor("What to do")

Resize before you detect, not after: detect at a smaller scale and scale the boxes back up. Setting
`minSize` on a Haar detector is the cheapest speed-up available, because it truncates the pyramid. Then
check you are not fighting yourself --- OpenCV parallelises its own kernels, so a program that fans
pipelines across its own executor should cap `org.opencv.core.Core.setNumThreads` before the two pools
oversubscribe the machine. Chapter 35 is the whole story, with the harness that gates every claim in it.

#subsect("The camera opens but delivers nothing")

#minor("What it means")

A webcam is not ready the instant `open` returns. Auto-exposure, auto-white-balance and auto-gain are
closed loops on the device that need real frames to converge, so `open` then `snapshot` produces a black
or badly underexposed image and #emph[reports it as a success]. There is no property to poll for
"converged".

A stalled network stream looks similar and is not the same. `read` blocks in native code with no timeout
of its own, and the ones `CaptureOptions.withTimeout` sets are advisory: FFmpeg and GStreamer honour them,
V4L2, AVFoundation and the built-in MJPEG reader ignore them, and nothing reports which you got.

#minor("What to do")

For a dim room or a slow device, raise the warm-up: `CaptureOptions(warmupFrames = Some(20))`. The
default is `None`, meaning five frames for a camera index and zero for a file --- a file has no exposure
loop, and discarding frames there would silently skip content. For dropped frames on a live camera,
`attemptsPerFrame` is the bound that rides them out; it defaults to 3 on `Camera.foreach` and 1 on
`Video.frames`, where the first `false` is end-of-file. It is a bound and not a retry-forever, so a dead
camera ends the loop instead of hanging the thread.

#subsect("Calibration will not converge")

#minor("What it means")

`Left(CvError.CalibrationFailed)` names the count. With `minViews = 10` over a folder of forty captures
it reads `need at least 10 views showing the whole 9×6 inner grid, but only 3 of 40 did`.
`fromChessboard` silently skips views where the board is not fully visible, so an entire capture folder is
a fine argument; only when fewer than `minViews` survive does it fail. A large `reprojectionError` is the
same problem one step further on --- the solver converged, but not on much.

#minor("What to do")

The failure is almost always the capture, not the solver. The board must be #emph[wholly] in frame,
rigid --- a sheet of paper that flexes ruins the run --- and seen from a range of angles, which is what
makes the problem well-posed. Ten to fifteen good views is where the numbers become trustworthy;
`minViews` defaults to 3, a floor for seeing it work rather than a production setting. Judge the result
by its RMS: under 0.5 px excellent, 0.5--1 px fine to ship, 1--2 px marginal, above 2 px recapture. And
the intrinsics are in pixels at `imageSize` --- calibrate at 1920×1080, run at 960×540, and every number
is out by a factor of two. Chapter 31 has the capture procedure.

#sect("Filing a bug that can be fixed")

Most of what makes a vision bug reproducible is environment, and none of it is visible from the report
unless you write it down. The bug template asks for four lines of it: the scalacv version, `java -version`,
the platform and bytedeco classifier you depend on, and the OS. Those four decide which OpenCV build was
running, which is frequently the whole answer.

Then the reproduction, and generated inputs beat attached ones. `Image.blank`, a drawn rectangle and a
synthetic gradient reproduce on the maintainer's machine with no download --- the project ships no image
assets for the same reason. If the failure needs a specific photograph, say what is distinctive about it
(16-bit, CMYK, an exotic `.tiff` variant), because that property is usually the bug. If the JVM crashed,
attach the `hs_err_pid*.log`; `CONTRIBUTING.md` is blunt about why. And paste the whole scalacv message.

A feature request has its own template, with one question in it that is not decoration: does the OpenCV type
you want wrapped own native memory, and does it expose a public `release()`? Three of the 188 do, and the
answer decides whether the wrapper is a one-line `given` or a `Releasable.nativeHandle` bridge with a disarm
step --- which is most of the work.

#sect("Where the reference material lives")

This chapter closes Part VI, and with it the running narrative; what follows is reference. Appendix A,
#emph[Dropping to OpenCV Java], is the one to read next if any section above ended with "borrow the raw
`Mat` and call it yourself". It sets out that borrowing contract in full: which side of `image.mat` owns the
pixels, how to adopt a raw `Mat` back with `Image.wrap`, and which `org.opencv.*` calls need a
`Managed.scope` to hold their out-parameters.
