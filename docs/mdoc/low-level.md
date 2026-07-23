# Working with the raw OpenCV API

scalacv is a wrapper, never a wall. Underneath every convenience is the ordinary OpenCV 4.13 Java
API — `org.opencv.core.Mat`, `org.opencv.imgproc.Imgproc`, the detectors — and scalacv is built so
you can reach it at any point, use the exact call you need, and come back up without ceremony. This
page is the map of how to move between levels.

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
OpenCv.load()
```

## Three levels, one library

There are three altitudes, and picking the right one per step is the whole skill:

| Level | What you hold | Frees itself? | Reach for it when |
|---|---|---|---|
| **High** — [`Image`](/image-api) | an owned image, chained by verbs | yes, per chain | the common `read → transform → detect → write` path |
| **Mid** — `Managed[Mat]` + extension ops | one owned Mat at a time | yes, via `use`/`pipe` | you need a Mat-level knob or to hold intermediates |
| **Low** — raw `org.opencv.*` | whatever the Java binding hands back | **you decide** | OpenCV exposes a call the higher levels don't wrap |

The levels are not sealed tiers you commit to at the top of a file. A single pipeline can start on
`Image`, drop to a raw `Imgproc` call for one operator, and rise back to `Image` for the write — and
the rest of this page is exactly those moves.

## Escaping from an `Image`

An `Image` owns a single `Managed[Mat]`. Three methods let you get at it, and which one you pick
depends on whether you are *borrowing* the Mat or *taking ownership* of it.

### `mat` borrows — do not release it

`img.mat` hands you the underlying `org.opencv.core.Mat` for any raw call. The `Image` still owns it:
read from it, pass it to a detector, run an `Imgproc` function against it — but do **not** release it,
because the `Image` will.

```scala mdoc:silent
val img = Image.blank(64, 64)

// A raw org.opencv.core.Core call on the borrowed Mat. The Image still owns `img.mat`.
val average = org.opencv.core.Core.mean(img.mat)

img.close() // this frees the Mat — `average` is already a plain value, safe to keep
```

### `managed` hands the whole thing over

When you want to stop being an `Image` and manage the Mat's lifetime yourself, `img.managed` transfers
ownership out. The `Image` is spent afterwards; the returned `Managed[Mat]` is now yours to release.

```scala mdoc:silent
val owned: Managed[Mat] = Image.blank(32, 32).managed // the Image is spent; the Managed owns the Mat now
val pixels = owned.use(_.total)                       // ordinary Managed[Mat] from here on
```

### `Image.wrap` goes the other way

`Image.wrap(managed)` adopts a `Managed[Mat]` you already hold and gives you back the high-level API.
Ownership transfers *into* the `Image`, so do not also release the `Managed` yourself.

```scala mdoc:silent
val back: Image = Image.wrap(owned) // adopts the Managed above; `owned` must not be released separately
back.close()
```

`mat` / `managed` / `wrap` are the doorways between the top level and everything below it — a borrow, a
handover down, and a handover back up.

## Calling raw `org.opencv.*`, then coming back up

The common shape is: you have an owned image, OpenCV has a function scalacv doesn't wrap, you call it
directly on the borrowed Mat, and you adopt the result back into managed ownership.

```scala mdoc:silent
import org.opencv.imgproc.Imgproc

val src = Image.blank(120, 160)

// Drop to raw org.opencv.* for a knob the higher levels don't expose (here, a CV_16S Laplacian):
val rawOut = new Mat()
Imgproc.Laplacian(src.mat, rawOut, CvType.CV_16S) // borrowed Mat in, our own Mat out
src.close()                                       // done borrowing; the source Image frees its Mat

// Come back up. Wrap the raw result so it is released exactly once...
val result: Managed[Mat] = Managed(rawOut)

// ...or lift it straight into the high-level API and keep chaining:
val encoded: Either[CvError, Array[Byte]] =
  Image.wrap(result).bytes(".png") // `result`'s Mat is now owned by the Image, released on bytes()
```

The rule of thumb: the moment a raw call hands you a bare `Mat`, wrap it in `Managed` (or adopt it as an
`Image`). From then on it is freed exactly once, on success or on exception, like everything else.

The mid level is the same story without the `Image` wrapper — every extension op on a `Mat` already
returns an owned `Managed[Mat]`, so `pipe` threads them and frees each intermediate:

```scala mdoc:silent
val midLevel: Either[CvError, Array[Byte]] =
  Managed.use(Mat(80, 80, CvType.CV_8UC3)) { m =>
    m.cvtColor(ColorConversion.BgrToGray)
      .pipe(_.canny(50, 150))
      .use(Images.encode(_, ".png"))
  }
```

## Managing *any* native type yourself

A `Mat` is not the only native object with an off-heap footprint. Detectors, networks and classifiers
all own native memory too, and `Managed` frees any of them — but *how* it frees them splits into two
regimes, and the split is dictated by the generated Java binding, not by taste. (The full argument for
why this matters lives in [Mat lifecycle](/mat-lifecycle).)

### Regime 1: the three types with a public `release()`

`Mat`, `VideoCapture` and `VideoWriter` are the only `org.opencv.*` types that expose a public
`release()`. For them the `Releasable` instance is already in scope and `Managed` just works:

```scala mdoc:silent
val frameSize =
  Managed.use(Mat(48, 48, CvType.CV_8UC3)) { m => // Releasable[Mat] uses the public release()
    (m.rows, m.cols)
  }
```

### Regime 2: the other 185 — opt into the bridge

Every other native type — `CascadeClassifier`, `QRCodeDetector`, `ArucoDetector`, `Net`, `FaceDetectorYN`
and 180 more — has no public `release()`. All it exposes is a `private static native void delete(long)`
and an unconditional `finalize()`. To free one you opt into the bridge:

```scala mdoc:silent
import org.opencv.objdetect.QRCodeDetector

// Opt in: route release through the binding's private delete(long).
given Releasable[QRCodeDetector] = Releasable.handle(_.getNativeObjAddr)

val detectorClass =
  Managed(new QRCodeDetector()).use { detector =>
    // ...run `detector` against a Mat here; when the block returns, the bridge frees it...
    detector.getClass.getSimpleName
  }
```

`Releasable.handle(_.getNativeObjAddr)` does two things, in this order:

- it **disarms the binding's finalizer first** (zeroing `nativeObj`) so that after scalacv calls
  `delete`, the finalizer's later `delete(this.nativeObj)` becomes `delete nullptr` — a C++ no-op —
  instead of freeing the same pointer a second time and corrupting the heap;
- then it calls `delete(long)` through a cached `MethodHandle`.

It is deliberately **opt-in and loud**. `delete(long)` is private API with no compatibility promise, and
the reflection it needs stops working the moment OpenCV is loaded from a named module. So if the bridge
cannot be opened, `Releasable.handle` **throws** (a `CvError.NativesMissing`, usually asking for
`--add-opens java.base/java.lang=ALL-UNNAMED`) rather than falling back to the garbage collector — a
silent fallback would look like success while leaking native memory without bound. See
[the error model](/error-model) for how that surfaces.

Anything that needs a real model file or trained network follows the identical pattern — only the
construction changes:

```scala mdoc:compile-only
import org.opencv.dnn.{Dnn, Net}

given Releasable[Net] = Releasable.handle(_.getNativeObjAddr)

Managed.use(Dnn.readNetFromONNX("model.onnx")) { net =>
  // set inputs, forward, read outputs — raw org.opencv.dnn throughout
  net.empty
}
```

For the batteries-included detectors, scalacv already declares these instances for you and wraps the
construction — see [Object detection](/object-detection). The bridge is what you reach for when you want
a detector the library doesn't wrap yet.

## The rule of thumb

Stay high-level for the common path; drop exactly one level at exactly the step that needs a knob the
level above doesn't expose, then come straight back up. `mat` borrows, `Managed` adopts, `Image.wrap`
lifts — no ceremony to go down, no lock-in keeping you there. The low-level OpenCV API is always one
method call away, and reaching for it is a normal thing to do, not an escape from the library.
