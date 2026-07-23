# The error model

Every snippet here is compiled by mdoc against the real library, so it cannot drift out of date.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

scalacv draws one line, and draws it deliberately. OpenCV's Java API reports failure three
incompatible ways — a `false` return, an *empty* `Mat`, or a thrown `org.opencv.core.CvException`
several call frames from the mistake. The job of the wrapper is to flatten that into a policy you can
reason about:

- **`Either[CvError, A]`** for failures that are **data-dependent and expected** — a file that is not
  there, bytes that do not decode, a model download that 404s. These are values you handle, not bugs.
- **A thrown `IllegalArgumentException`** for **programmer errors** — an even Gaussian kernel, an empty
  `Mat` handed to an op that needs pixels. These come from `require`, are not part of the `Either`, and
  should never be pattern-matched.
- **`CvError.NativeCall`** for the residual `CvException` that OpenCV throws from an ordinary in-memory
  op that we did not anticipate. It is wrapped so the operation is *named*, never swallowed.

## Why an exception hierarchy, not a pure ADT

`CvError` is a `sealed abstract class` that extends `RuntimeException`. That looks unusual for an error
ADT, and it is a considered choice: the core cannot be made total. `CvException` escapes from ordinary
`Imgproc` calls — including on the empty `Mat` a failed `imread` hands back — and no wrapper can prevent
that. Because `CvError` *is* a `Throwable`, it interoperates with that JNI boundary: it can be the `cause`
of a wrapped native throw, it can be rethrown by [`Cv.orThrow`](#the-escape-hatch-cv-attempt), and it can
cross a `try`/`catch` unchanged. You still get exhaustive `match` on the sealed hierarchy where you want
it; you also get a type that behaves correctly at the one place the language cannot help you.

## The five cases

`CvError` has exactly five shapes. Each names *when you see it*.

### `NativesMissing`

The native OpenCV libraries are not on the classpath, so nothing can run. It carries the exact dependency
line to add for your OS — the message is meant to be copy-pasted into your build:

```scala mdoc:compile-only
def report(e: CvError): String = e match
  case CvError.NativesMissing(details) => details // the dependency line to add
  case other                           => other.getMessage
```

You normally hit this once, at `OpenCv.load()`, before any real work — not deep inside a pipeline.

### `DecodeFailed`

An image could not be **read or decoded**. This is the subtle one: `imread` and `imdecode` do *not* throw
for a missing file, a directory, or non-image bytes — they return a `Mat` with `empty() == true`. scalacv
makes the check for you and turns it into a `Left`, so the failure surfaces here instead of as a
`CvException` from some later op that had nothing to do with the mistake:

```scala mdoc
Images.read("/does/not/exist.png").left.map(_.getMessage)
```

```scala mdoc
Images.read("/does/not/exist.png") match
  case Left(CvError.DecodeFailed(path, _)) => s"could not decode: $path"
  case Left(other)                         => other.getMessage
  case Right(_)                            => "decoded"
```

`DecodeFailed` is specifically about image *bytes*. See [Image I/O](/image-io) for the full read/decode
surface.

### `LoadFailed`

A **non-image** resource could not be resolved, loaded, or verified — a model file, a Haar cascade, an
ONNX network, a downloaded artifact, a video source. It is kept distinct from `DecodeFailed` on purpose:
an HTTP 404 for a model download, a missing `.onnx`, or a checksum mismatch is *not* an image-decode
failure and should not read like one.

```scala mdoc:compile-only
def explain(e: CvError): String = e match
  case CvError.DecodeFailed(path, _)     => s"$path holds no image"
  case CvError.LoadFailed(resource, why) => s"$resource did not load: $why"
  case other                             => other.getMessage
```

This is the failure you handle when loading detectors and networks — see [Object detection](/object-detection).

### `EncodeFailed`

An image could not be **written or encoded** — an unwritable destination (`imwrite` returns `false`), or
an encode that otherwise fails:

```scala mdoc:compile-only
import org.opencv.core.{CvType, Mat}

Managed.use(Mat(8, 8, CvType.CV_8UC3)): m =>
  Images.write("/no/such/dir/out.png", m) // Left(CvError.EncodeFailed(...))
```

### `NativeCall`

The catch-all for a `CvException` thrown by an ordinary op — a size mismatch, a channel-count violation,
anything OpenCV signals by throwing rather than by an empty `Mat`. The wrapper *names the operation* and
preserves OpenCV's message verbatim (it is deliberately not parsed for error codes — that text is not a
stable interface). Here a `BGR→GRAY` conversion is asked of an image that is already single-channel:

```scala mdoc
import org.opencv.imgproc.Imgproc
import org.opencv.core.{CvType, Mat}

val bad: Either[CvError, Unit] =
  Managed.use(Mat(4, 4, CvType.CV_8UC1)): gray =>
    Managed.use(Mat()): out =>
      Cv.attempt("cvtColor(BGR2GRAY)"):
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_BGR2GRAY)

bad.left.map(_.getMessage)
```

## The escape hatch: `Cv.attempt`

Every built-in like `Images.read` already returns an `Either`. When you go *off the beaten path* — a raw
`org.opencv.*` call scalacv does not wrap — `Cv.attempt` is the single tool that lifts it into the same
policy. Its whole contract is: run the block; if OpenCV throws a `CvException`, return
`Left(CvError.NativeCall(operation, e))` with the operation you named; if the block itself already
produced a `CvError`, pass it through. That is the entire body — there is no hidden magic:

```scala mdoc:compile-only
import org.opencv.core.{Core, Mat}

// A raw op scalacv does not wrap; attempt names it and keeps the Either policy intact.
def meanBrightness(m: Mat): Either[CvError, Double] =
  Cv.attempt("Core.mean")(Core.mean(m).`val`(0))
```

When a native failure genuinely *is* a bug at your call site — not a value to handle — reach for
`Cv.orThrow(op)(...)` instead: it is `attempt` that rethrows the `CvError` rather than returning it.

## Composing

Because every fallible step is an `Either[CvError, A]`, they thread together with `flatMap` and
for-comprehensions, and the **first** failure short-circuits the rest — you never run the encode when the
decode already failed:

```scala mdoc
Images.read("/does/not/exist.png").flatMap(_.use(Images.encode(_, ".png"))).isLeft
```

A whole read → transform → write pipeline is one comprehension, and its type says precisely what can go
wrong: nothing but a `CvError`.

```scala mdoc:compile-only
val pipeline: Either[CvError, Array[Byte]] =
  for
    src   <- Image.read("photo.jpg")            // DecodeFailed if it is not an image
    edges <- src.gray.canny(80, 160).bytes(".png") // EncodeFailed / NativeCall from here on
  yield edges
```

The high-level [`Image`](/image-api) chain uses exactly this: reads and terminals return `Either`,
transforms move the image along, and the errors compose the same way. Programmer errors — an even kernel,
a spent handle — stay *outside* this `Either` as thrown `IllegalArgumentException`/`IllegalStateException`,
because they are bugs to fix, not outcomes to branch on. To take a native call all the way to the raw API
yourself, see [Working with the raw OpenCV API](/low-level).