# The Image API

`Image` is the high-level face of scalacv and the layer to reach for first. It wraps a single native
image and lets you express the common OpenCV shape — **read → transform → detect → annotate → write** — as
one readable chain, with every intermediate freed for you.

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat, Scalar => CvScalar, Point => CvPoint}
import org.opencv.imgproc.Imgproc
OpenCv.load()

// A synthetic scene, so every example below runs against real pixels without shipping an image file.
// Returns a fresh Image each call because a transform *consumes* the one it is given.
def scene(): Image =
  val m = Mat(120, 160, CvType.CV_8UC3, CvScalar(40, 40, 40))
  Imgproc.rectangle(m, CvPoint(20, 20), CvPoint(90, 100), CvScalar(220, 220, 220), -1)
  Imgproc.circle(m, CvPoint(125, 60), 24, CvScalar(255, 255, 255), -1)
  Image.wrap(Managed(m))

// Placeholders for the compile-only snippets below (those type-check but do not run):
val bytesFromSomewhere: Array[Byte] = Array.emptyByteArray
lazy val detector: org.opencv.objdetect.FaceDetectorYN = ??? // built with FaceDetect.create(model, size)
```

## The whole idea, in one line

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

`read` gives you an `Either[CvError, Image]`; the chain transforms it; `write` encodes it and releases the
native memory. No `Mat`, no manual `release`, no leaked intermediates.

## Three kinds of method

Everything on `Image` is one of three shapes, and knowing which is which is the whole mental model:

| Kind | Examples | Effect on the image |
|---|---|---|
| **Transform** | `gray`, `blur`, `canny`, `resize`, `crop`, every `draw*` | **consumes** it, returns a new `Image` |
| **Query** | `width`, `size`, `channels`, `faces`, `qrCodes`, `contours` | **borrows** it, returns plain data, image stays alive |
| **Terminal** | `write`, `bytes`, `close` | **consumes** it and releases the native memory |

### A transform consumes the image

Each transform hands the underlying image on to the next step and spends the handle it was called on. That
is what makes a chain leak-free without a scope: it holds exactly one live image at a time.

```scala mdoc:silent
val edges: Either[CvError, Array[Byte]] =
  scene().gray.blur(1).canny(60, 180).bytes(".png")
```

Using a handle after a transform has spent it throws — in Scala, before anything can reach native code:

```scala mdoc:crash
val img = scene()
val g = img.gray   // consumes img
img.width          // img is spent: this throws IllegalStateException, it does not read freed memory
```

To feed one image into two chains, take a [`copy`](#branching-with-copy) first.

### A query borrows

Queries only read, so the image is still yours afterwards:

```scala mdoc
val img = scene()
(img.width, img.height, img.channels)
```

```scala mdoc:invisible
img.close()
```

### A terminal releases

`write` and `bytes` encode and then release; `close` just releases. After any of them the handle is spent,
the same as after a transform.

```scala mdoc
scene().gray.bytes(".png").map(_.length)
```

## Getting an Image

```scala mdoc:compile-only
Image.read("photo.jpg")            // Either[CvError, Image] from a file
Image.decode(bytesFromSomewhere)   // from an in-memory image file (HTTP body, BLOB)
```

```scala mdoc:silent
Image.blank(width = 320, height = 240)                 // a black canvas
Image.blank(64, 64, color = Scalar.White, channels = 1) // a white 1-channel canvas
```

`Image.wrap(managed)` adopts an existing `Managed[Mat]` you already hold.

## Transforming

The common image-processing steps read as verbs:

```scala mdoc:silent
scene()
  .gray                         // BGR -> single-channel grey
  .equalizeHist                 // stretch the histogram
  .blur(2)                      // quick radius-based Gaussian blur (radius 2 = 5x5)
  .canny(80, 160)               // edges, always CV_8UC1
  .close()
```

Resizing and cropping:

```scala mdoc:silent
scene().resize(80, 60).close()                 // absolute size
scene().scale(0.5).close()                     // half on both axes
scene().crop(Rect(10, 10, 60, 60)).close()     // an independent copy of a region
```

### The full verb set

Beyond the basics above, `Image` covers the everyday OpenCV toolkit — each an ordinary transform that
consumes the image and hands on a new one:

| Group | Verbs |
|---|---|
| **Geometric** | `flip`, `rotate` (quarter-turns and arbitrary angle, auto-expanding), `pad`, `border`, `crop`, `resize`, `scale` |
| **Smoothing** | `blur`, `gaussianBlur`, `medianBlur`, `bilateralFilter` |
| **Edges & threshold** | `canny`, `threshold`, `adaptiveThreshold`, `equalizeHist` |
| **Morphology** | `erode`, `dilate`, `morphology(MorphOp.Open / Close / Gradient / …)` |
| **Intensity & colour** | `adjust` (brightness/contrast), `invert`, `normalize`, `sharpen`, `convert`, `gray`, `toHsv`, `channel` |
| **Masking & compositing** | `inRange` (→ mask), `applyMask`, `blend` |

```scala mdoc:silent
scene()
  .rotate(Rotation.Clockwise)     // lossless quarter-turn
  .medianBlur(1)                  // de-noise
  .adjust(brightness = 20)        // a touch brighter
  .morphology(MorphOp.Open)       // clean up small specks
  .bytes(".png")
```

The dedicated guides go deeper: [Geometric transforms & morphology](/transforms),
[Colour, masking & compositing](/color-masking), and [Image processing](/image-processing) for the
mid-level `Managed[Mat]` equivalents.

## Detecting

The self-contained detectors need nothing from you — they build and free their own machinery:

```scala mdoc
scene().qrCodes.size          // Seq[QrCode]
```

```scala mdoc:invisible
// aruco/contours borrow; keep the scene alive for the next example set
```

`arucoMarkers(dictionary)` and `contours(...)` work the same way. Faces need a model you supply, because
YuNet is a downloaded network — see [Object detection](/object-detection):

```scala mdoc:compile-only
Image.reading("crowd.jpg") { img =>
  img.faces(detector) // detector: FaceDetectorYN, from FaceDetect.create(...)
}
```

## Annotating

Draw methods are transforms — they mutate the image you own and hand it on:

```scala mdoc:silent
val annotated: Either[CvError, Array[Byte]] =
  scene()
    .drawRect(Rect(20, 20, 70, 80), Scalar.Green)
    .drawCircle(Point(125, 60), 24, Scalar.Red)
    .drawText("scene", Point(8, 16), Scalar.White)
    .bytes(".png")
```

`markFaces(faces)` is the one-call "show me what YuNet found" — a box per face and a dot per landmark.

## Scoping with `reading`

If the body of your work is a **query** (no terminal to release the image), `Image.reading` closes it for
you — even on an exception, and harmlessly even if the body already consumed it:

```scala mdoc:compile-only
val faceCount: Either[CvError, Int] =
  Image.reading("crowd.jpg")(_.faces(detector).size)
```

## Branching with `copy`

Move semantics forbid using one image twice — so when you genuinely need to, take an independent deep copy:

```scala mdoc:silent
val base = scene()
val branch = base.copy
val a = base.gray.bytes(".png")          // consumes base
val b = branch.canny(80, 160).bytes(".png") // consumes the copy
```

## Dropping to the low level

`Image` is a convenience, never a wall. `mat` borrows the raw `org.opencv.core.Mat` for any `org.opencv.*`
call or mid-level extension op that `Image` does not wrap; the image stays yours:

```scala mdoc:silent
val img2 = scene()

// A raw org.opencv.* call on the borrowed Mat — the image still owns it.
val mean = org.opencv.core.Core.mean(img2.mat)

// Or a mid-level extension op, which returns an owned Managed[Mat]:
val sharpenedBytes: Either[CvError, Array[Byte]] =
  img2.mat.gaussianBlur(Size(3, 3)).use(Images.encode(_, ".png"))

img2.close()
```

And `managed` hands the whole `Managed[Mat]` over when you want to manage the lifetime directly. See
[Working with the raw OpenCV API](/low-level) for the full story on moving between the two levels.
