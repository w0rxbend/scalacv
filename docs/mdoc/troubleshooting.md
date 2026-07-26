# Troubleshooting

The errors people actually hit, and the one-line fix for each.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## `OpenCv.load()` fails / `UnsatisfiedLinkError` / natives missing

The `scalacv` jar contains no native code — the OpenCV symbols live in per-platform classifier jars you add yourself. If they're absent, `OpenCv.load()` throws `CvError.NativesMissing` with a **copy-pasteable fix naming your actual platform**. Add the two lines it prints:

```scala
mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64",
mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64"
```

Both are needed — `libopencv_core` links `libopenblas`. Pick the classifier for where the code *runs*, not where you build it. See [Getting Started](/getting-started) for the full table.

## It fails on a headless server / CI runner (no GTK)

Call `OpenCv.load()`, **not** `Loader.load(classOf[opencv_java])`. The latter eagerly initialises OpenCV's `highgui` module, which is GTK2-linked on Linux and drags `objdetect`/`calib3d`/`features2d` down with it on a box with no GTK. `OpenCv.load()` brings the natives up through a GUI-free path and needs no `apt-get install libgtk2.0-0`. This is the whole reason the loader exists.

## `IllegalStateException: this Image has already been released or consumed`

You reused a value a transform already consumed — `Image` has **move semantics**, so `gray`/`blur`/`canny`/a terminal *spends* the receiver:

```scala mdoc:crash
val img = Image.blank(8, 8)
val a = img.gray
val b = img.blur(2) // throws: `img` was already spent by `.gray`
```

Take a `.copy` before the first use if you need it twice. The exception fires at the *reuse*, which is rarely the interesting line — start the JVM with `-Dscalacv.trackOwnership=true` and the error carries, as its cause, the stack of the call that actually consumed the handle:

```sh
java -Dscalacv.trackOwnership=true -jar your-app.jar
```

## `Image.read` returns `Left(DecodeFailed)` — but the file looks fine

`imread` does not throw on a missing file, a directory, or bytes it can't decode — it returns an empty `Mat`, and scalacv turns that into a `Left` so you can't accidentally run a pipeline on nothing. Check the `Either`:

```scala mdoc:silent
Image.read("does-not-exist.png") match
  case Right(img) => img.close()
  case Left(err)  => () // err names the path and the likely cause
```

If a real image reports `DecodeFailed`, the usual culprits are a relative path resolved against the wrong working directory, or a format this OpenCV build has no decoder for.

## `cannot open …/delete(long)` / `add-opens` in the error

OpenCV was loaded from a **named module** rather than the classpath, so reflection can't reach the private `delete(long)` scalacv uses to free the 185 detector types. The error prints the exact flag; add it:

```sh
--add-opens org.opencv/org.opencv.objdetect=ALL-UNNAMED
```

On the classpath (the normal case) this never happens.

## `Recorder.open` returns `Left` — "codec may be unavailable"

`VideoWriter` reports a missing codec by refusing to open, not by throwing. Whether a codec works depends on what your platform's OpenCV build links (FFmpeg, the OS frameworks). The portable fallback encodes with the always-built-in codecs:

```scala mdoc:compile-only
Recorder.open("out.avi", Size(640, 480), fps = 30, codec = Codec.Mjpg)
```

`Codec.Mjpg` with an `.avi` extension works anywhere `Mp4v`/`Avc1` don't.

## RSS keeps climbing but the heap is small / no test fails

That's the classic native leak: a `Mat` frees its off-heap buffer only when you release it, and the GC never sees the pressure. Put a ceiling on **physical** bytes (RSS-based — `maxBytes` alone would *not* catch it, see [Performance](/performance#measuring-memory-do-it-right)) and a leak fails fast:

```sh
java -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar
```

Then bisect by wrapping suspects in `Managed.use`. Reach for `Image.reading` / `Camera.using` / `Video.framesCopied`, which close for you.

## `drawText` drew nothing / text is in the wrong place

OpenCV anchors text on the **baseline's left end**, not the top-left corner — a `y` of 0 puts almost the whole string above the image. Use [`Draw.textSize`](/drawing) to measure and place it, and remember only the Hershey vector fonts exist: non-ASCII characters render as `?`.

## A transform threw `CvError.NativeCall` mid-chain

OpenCV rejected the pixels for a reason scalacv can't foresee (a wrong type/channel combination for that operation, say). It's an unchecked throw naming the operation. To fold it into an `Either`, wrap the chain in `Cv.attempt` — or use `Image.reading`, which does that for you:

```scala mdoc:silent
val r: Either[CvError, Int] =
  Cv.attempt("measure")(Image.blank(16, 16).gray.canny(50, 150).mat.rows)
```

## GraalVM native-image doesn't work

Known limitation — the reflective release layer and the runtime native extraction are exactly what a static image forbids. See [Mat lifecycle](/mat-lifecycle#graalvm-native-image-is-not-supported-today) for the three concrete blockers.

## Still stuck?

The [API reference](/api/core/index.html) documents every method, and most pages here end with a "Next" trail. If a `CvError` message or an assertion isn't clear, it's a doc bug worth reporting.
