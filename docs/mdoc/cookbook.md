# Cookbook

Task-first recipes you can copy, paste, and adapt. If you know *what* you want to do ("blur the background",
"count the shapes", "raise an alarm when something moves") but not yet *which* method does it, start here and
follow the cross-links into the reference pages for the details.

Every snippet on this page is compiled by **mdoc** against the real library, so it cannot drift out of date:
if a recipe here stopped compiling, the docs build would fail. The recipes lead with the high-level
[`Image`](/image-api) API — the friendly, chainable layer most code should use — and the
[lower-level recipes](#lower-level-recipes) at the end show the same kind of work on a raw `Mat`, for when you
want the extra control the mid-level [ownership contract](/image-processing) gives you.

:::note New to scalacv?
Read [Getting started](/getting-started) first for install and your very first program, then skim
[The Image API](/image-api) for how the chain works. This page assumes you have `OpenCv.load()` behind you.
:::

## Recipe index

| I want to…                                   | Recipe                                                        | Reference          |
| -------------------------------------------- | ------------------------------------------------------------- | ------------------ |
| Find edges in a photo                        | [Edge-detect a photo](#edge-detect-a-photo)                   | [Image processing](/image-processing) |
| Shrink an image                              | [Make a thumbnail](#make-a-thumbnail)                         | [Transforms](/transforms) |
| Rotate / mirror / crop                       | [Rotate, flip, crop](#rotate-flip-crop)                       | [Transforms](/transforms) |
| Apply a photo "look"                          | [Apply a named filter](#apply-a-named-filter)                 | [Filters](/filters) |
| Adjust brightness / contrast / sharpness     | [Tune tone and sharpness](#tune-tone-and-sharpness)          | [Filters](/filters) |
| Keep only one colour                         | [Segment by colour](#segment-by-colour-hsv-keying)           | [Colour masking](/color-masking) |
| Blend / composite two images                 | [Blend two images](#blend-two-images)                        | [Image processing](/image-processing) |
| Remove an object                             | [Erase an object](#erase-an-object-inpaint)                  | [Image processing](/image-processing) |
| Paste a patch invisibly                      | [Seamlessly paste a patch](#seamlessly-paste-a-patch)        | [Conferencing](/conferencing) |
| Blur behind a person                         | [Blur the background](#blur-the-background-behind-a-person)  | [Conferencing](/conferencing) |
| Count / measure shapes                       | [Count the shapes](#count-the-shapes-in-a-frame)             | [Contours](/contours) |
| Find the biggest blob                        | [Find the biggest blob](#find-the-biggest-blob)              | [Contours](/contours) |
| Find straight lines                          | [Find straight lines](#find-straight-lines-hough)            | [Hough](/hough) |
| False-colour data as a heatmap               | [Colour a heatmap](#colour-a-heatmap)                        | [Image processing](/image-processing) |
| Label an image                               | [Draw a text badge](#draw-a-text-badge)                      | [Drawing](/drawing) |
| Alarm on motion                              | [Raise a motion alarm](#raise-a-motion-alarm-on-a-video)     | [Motion detection](/motion-detection) |
| Process a whole video                        | [Re-encode a video](#re-encode-a-video)                      | [Video](/video) |
| Grab a webcam frame                          | [Snapshot the webcam](#snapshot-the-webcam)                  | [Video](/video) |
| Clean a scan for OCR                         | [Clean a scanned page](#straighten-and-clean-a-scanned-page-ocr-prep) | [OCR](/ocr) |
| Read a QR code                               | [Read a QR code](#read-a-qr-code)                            | [Object detection](/object-detection) |
| Find faces                                   | [Find faces](#find-faces-with-a-haar-cascade)               | [Object detection](/object-detection) |
| Go to/from `BufferedImage`                   | [Swing / AWT interop](#swing--awt-interop)                   | [Image I/O](/image-io) |

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

## How the chains work

Everything below leans on one rule worth internalising before you copy a recipe: an [`Image`](/image-api) has
**move semantics**. A transform (`gray`, `blur`, `crop`, a `draw*`, …) *consumes* the image it was called on
and returns a new one, and a terminal (`write`, `bytes`, `close`) consumes it for good. So a chain is
leak-free — exactly one live Mat flows through it — but you can never use one `Image` twice:

```scala mdoc:crash
val once = Image.blank(10, 10)
val edges = once.gray         // `once` is now spent
val blurred = once.blur(1)    // reusing it throws IllegalStateException
```

To branch — to feed the *same* source into two pipelines — take a [`copy`](/image-api) first. That is the
single most common shape in this cookbook:

```scala mdoc:silent
val source = Image.blank(120, 80, Scalar(80, 120, 160))
val forEdges = source.copy.gray.canny(80, 160) // works on a copy
val forThumb = source.scale(0.5)               // consumes the original
forEdges.close()
forThumb.close()
```

:::tip
Running under `-Dscalacv.trackOwnership=true` makes a use-after-move error point at the *consuming* call, not
just the reuse. See [Mat lifecycle](/mat-lifecycle) for the full ownership story.
:::

## Edge-detect a photo

The canonical pipeline: greyscale, a light blur to quiet the noise, then [Canny](/image-processing).

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

`canny`'s two thresholds are both `Double` and silently swappable — name them (`canny(threshold1 = 80,
threshold2 = 160)`) when the values are not obviously ordered.

## Make a thumbnail

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.scale(0.25).write("thumb.jpg"))
```

For a *large* source, decoding at reduced size is cheaper than a full read followed by a resize — the codec
skips the detail it is about to throw away. Ask [`imread`](/image-io) for it with an [`ImreadFlags`](/image-io):

```scala mdoc:compile-only
Image.read("huge.jpg", ImreadFlags(ImreadColor.Color, ImreadScale.Quarter)).flatMap(_.write("thumb.jpg"))
```

## Rotate, flip, crop

Quarter-turns are lossless (no interpolation); arbitrary angles expand the canvas so no corner is clipped.
See [Transforms](/transforms) for the full set.

```scala mdoc:silent
val portrait = Image.blank(60, 100, Scalar(30, 30, 30)).drawRect(Rect(10, 10, 40, 30), Scalar.Green, Thickness.Filled)
val landscape = portrait.rotate(Rotation.Clockwise) // a 60x100 image becomes 100x60
val landscapeWidth = landscape.width
landscape.close()
```

```scala mdoc
landscapeWidth
```

`crop` returns an independent copy (not an aliasing view), and rejects a rectangle that does not fit:

```scala mdoc:silent
val scene = Image.blank(200, 200, Scalar.Black).drawCircle(Point(100, 100), 40, Scalar.Red, Thickness.Filled)
val centre = scene.crop(Rect(60, 60, 80, 80))
val centreWidth = centre.width
centre.close()
```

```scala mdoc
centreWidth
```

Mirroring is named by the visible effect, not OpenCV's axis code:

```scala mdoc:silent
val mirrored: Either[CvError, Array[Byte]] =
  Image.blank(64, 48, Scalar.Blue).drawRect(Rect(4, 4, 20, 10), Scalar.White).flip(Flip.Horizontal).bytes(".png")
```

## Apply a named filter

The built-in [filters](/filters) are composable `Image => Image` looks — apply one with `filter`, or compose
your own with `andThen`:

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.filter(Filter.vintage).write("vintage.jpg"))
```

`Filter.all` is the whole catalogue, handy for a contact sheet or a picker:

```scala mdoc
Filter.all.map(_.name)
```

A running example with a filter built from pure tone ops (no photo needed):

```scala mdoc:silent
val warmed: Either[CvError, Array[Byte]] =
  Image.blank(80, 80, Scalar(120, 90, 60)).filter(Filter.warm.andThen(Filter.sharpen)).bytes(".png")
```

## Tune tone and sharpness

`adjust` does brightness and contrast in one step; `sharpen` is an unsharp mask; `saturate`, `gamma` and
`temperature` are the individual tone knobs the named filters are built from.

```scala mdoc:silent
val punchier: Either[CvError, Array[Byte]] =
  Image
    .blank(100, 100, Scalar(60, 90, 120))
    .adjust(brightness = 10, contrast = 1.2)
    .saturate(1.3)
    .sharpen(0.8)
    .bytes(".png")
```

## Segment by colour (HSV keying)

Threshold in HSV — where "greenish" is a hue *range*, not a fragile RGB box — then keep only those pixels.
See [Colour masking](/color-masking) for choosing the bounds.

```scala mdoc:silent
val photo =
  Image.blank(120, 90, Scalar(0, 0, 255)).drawCircle(Point(60, 45), 22, Scalar.Green, Thickness.Filled)
val hueMask = photo.copy.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255)) // green hues
val greenOnly: Either[CvError, Array[Byte]] = photo.applyMask(hueMask).bytes(".png")
hueMask.close()
```

:::warning
`applyMask` **borrows** the mask — it does not consume it, so you must `.close()` the mask yourself. The
receiver (`photo`) *is* consumed. The same borrowing rule holds for `inpaint`, `blend`, `blurBackground` and
`seamlessCloneInto`.
:::

## Blend two images

`blend` is an alpha composite — `weight` is how much of the receiver survives. Both images must match in size
and type.

```scala mdoc:silent
val base = Image.blank(100, 100, Scalar(255, 0, 0)) // blue
val over = Image.blank(100, 100, Scalar(0, 0, 255)) // red
val blended: Either[CvError, Array[Byte]] = base.blend(over, weight = 0.5).bytes(".png")
over.close() // `over` is borrowed by blend; `base` was consumed by it
```

## Erase an object (inpaint)

Mark the region to remove with a non-zero (single-channel) mask; inpaint fills it from its surroundings.

```scala mdoc:silent
val scratched =
  Image.blank(100, 100, Scalar(80, 120, 160)).drawRect(Rect(46, 10, 6, 80), Scalar.White, Thickness.Filled)
val repairMask =
  Image.blank(100, 100, Scalar.Black, channels = 1).drawRect(Rect(46, 10, 6, 80), Scalar.White, Thickness.Filled)
val repaired: Either[CvError, Array[Byte]] = scratched.inpaint(repairMask).bytes(".png")
repairMask.close()
```

## Seamlessly paste a patch

Poisson blending (`seamlessCloneInto`) pastes an object into a background so the seam disappears — it matches
gradients, not just pixels. The receiver (the patch) is consumed; `background` and `mask` are borrowed and the
result is `background`-sized.

```scala mdoc:silent
val patch = Image.blank(60, 60, Scalar(60, 160, 60))
val canvas = Image.blank(200, 200, Scalar(160, 160, 160))
val patchMask =
  Image.blank(60, 60, Scalar.Black, channels = 1).drawCircle(Point(30, 30), 24, Scalar.White, Thickness.Filled)
val cloned: Either[CvError, Array[Byte]] = patch.seamlessCloneInto(canvas, patchMask, Point(100, 100)).bytes(".png")
canvas.close()
patchMask.close()
```

## Blur the background behind a person

Given a foreground mask (from a segmentation model, or any keying), keep the person sharp and blur the rest —
the compositing behind a virtual background.

```scala mdoc:silent
val frame =
  Image.blank(160, 120, Scalar(60, 60, 60)).drawCircle(Point(80, 60), 30, Scalar(200, 180, 160), Thickness.Filled)
val personMask =
  Image.blank(160, 120, Scalar.Black, channels = 1).drawCircle(Point(80, 60), 30, Scalar.White, Thickness.Filled)
val composited: Either[CvError, Array[Byte]] = frame.blurBackground(personMask, strength = 15).bytes(".png")
personMask.close()
```

See [Video conferencing](/conferencing) for producing the mask with a segmentation network, and
`replaceBackground` for swapping in a different scene entirely.

## Count the shapes in a frame

Binarise, then count [contours](/contours) — the outermost outline of each blob:

```scala mdoc:silent
val shapes = Image
  .blank(200, 120, Scalar.Black)
  .drawCircle(Point(40, 60), 18, Scalar.White, Thickness.Filled)
  .drawCircle(Point(100, 60), 18, Scalar.White, Thickness.Filled)
  .drawRect(Rect(150, 40, 40, 40), Scalar.White, Thickness.Filled)
val binary = shapes.gray.threshold(128)
val shapeCount = binary.contours().size
binary.close()
```

```scala mdoc
shapeCount
```

A [`Contour`](/contours) is plain immutable data (it survives the Mat it came from), so you can `approx` it to
count corners — a filled rectangle simplifies back to four:

```scala mdoc:silent
val poly = Image.blank(120, 120, Scalar.Black).drawRect(Rect(20, 20, 80, 80), Scalar.White, Thickness.Filled)
val polyBin = poly.gray.threshold(128)
val corners =
  polyBin.contours().headOption match
    case Some(c) => c.approx(0.02 * c.perimeter).points.size
    case None    => 0
polyBin.close()
```

```scala mdoc
corners
```

## Find the biggest blob

`contours()` returns plain data, so ordinary Scala — `sortBy`, `maxByOption`, `filter` — does the rest. Here,
the largest blob's bounding box, drawn back over a copy of the source:

```scala mdoc:silent
val src2 = Image
  .blank(200, 120, Scalar.Black)
  .drawCircle(Point(50, 60), 12, Scalar.White, Thickness.Filled)
  .drawRect(Rect(120, 40, 50, 50), Scalar.White, Thickness.Filled)
val bin2 = src2.copy.gray.threshold(128)
val boxes = bin2.contours().sortBy(-_.area).map(_.boundingRect)
val biggestArea = boxes.headOption.map(_.area).getOrElse(0L)
bin2.close()
val annotated2: Either[CvError, Array[Byte]] = src2.drawRects(boxes.take(1), Scalar.Green).bytes(".png")
```

```scala mdoc
biggestArea
```

## Find straight lines (Hough)

Canny produces the edge image the [Hough transform](/hough) needs; `houghLinesP` returns finite
[`Segment`](/hough)s you can filter by length or draw with `drawSegments`.

```scala mdoc:silent
val boxed = Image.blank(120, 120, Scalar.Black).drawRect(Rect(20, 20, 80, 80), Scalar.White, Thickness.Stroke(2))
val boxEdges = boxed.gray.canny(50, 150)
val segments = boxEdges.mat.houghLinesP(threshold = 20, minLineLength = 15, maxLineGap = 5)
boxEdges.close()
```

```scala mdoc
segments.nonEmpty
```

## Colour a heatmap

A single-channel image (a depth map, a motion field, any measurement) is invisible until you false-colour it.
`colorMap` turns `CV_8UC1` into a colour heatmap — the perceptually-uniform maps (`Viridis`, `Magma`,
`Inferno`, `Plasma`, `Turbo`) are the honest choice for data.

```scala mdoc:silent
val field =
  Image.blank(120, 120, Scalar(40), channels = 1).drawCircle(Point(60, 60), 40, Scalar(200), Thickness.Filled)
val heat: Either[CvError, Array[Byte]] = field.colorMap(Colormap.Inferno).bytes(".png")
```

## Draw a text badge

OpenCV anchors text on the *baseline*, so measure first with `Draw.textSize`, then size a filled background
rectangle to enclose the glyphs (including descenders). See [Drawing](/drawing) for the baseline caveat.

```scala mdoc:silent
val label = "scalacv"
val metrics = Draw.textSize(label, scale = 0.8)
val boxW = metrics.size.width.toInt + 12
val boxH = metrics.size.height.toInt + metrics.baseline + 12
val badge: Either[CvError, Array[Byte]] =
  Image
    .blank(boxW + 8, boxH + 8, Scalar.Black)
    .drawRect(Rect(4, 4, boxW, boxH), Scalar(50, 50, 50), Thickness.Filled)
    .drawText(label, Point(10, 4 + metrics.size.height.toInt + 6), Scalar.White, scale = 0.8)
    .bytes(".png")
```

## Annotate a scene and encode it

Chaining `draw*` calls builds an annotated frame; `bytes` encodes it to an in-memory PNG/JPEG without touching
the filesystem — the shape you want when the result is going into an HTTP response or a notebook.

```scala mdoc:silent
val annotated: Either[CvError, Array[Byte]] =
  Image
    .blank(220, 140, Scalar.White)
    .drawRect(Rect(20, 20, 90, 70), Scalar.Green)
    .drawCircle(Point(150, 70), 30, Scalar.Red)
    .drawText("scalacv", Point(20, 125), Scalar.Black)
    .bytes(".png")
```

## Read → process → write, scoped

`Image.reading` closes the source for you even if the body already consumed it (release is idempotent), and
folds any transform failure in the chain into the `Either`:

```scala mdoc:compile-only
Image.reading("photo.jpg")(_.gray.equalizeHist.threshold(128).write("mask.png"))
```

## Raise a motion alarm on a video

Feed frames in order to a stateful [detector](/motion-detection); it reports whether — and where — something
moved. `Camera.foreach` hands you an owned [`Image`](/image-api) per frame and closes it for you.

```scala mdoc:compile-only
val detector = MotionDetector.frameDifference()
Camera.usingFile("clip.mp4") { cam =>
  cam.foreach() { frame =>
    val motion = detector.detect(frame)
    if motion.moving then println(s"motion in ${motion.regionCount} region(s)")
  }
}
detector.close()
```

The same detector drives an MJPEG stream (an ESP32-CAM, a trail cam) directly from the encoded bytes —
`detect(Array[Byte])` decodes each JPEG for you:

```scala mdoc:compile-only
val detector = MotionDetector.backgroundSubtraction()
for jpeg <- Iterator.continually(Array.empty[Byte]).takeWhile(_.nonEmpty) do
  detector.detect(jpeg).foreach(m => if m.moving then println("alert"))
detector.close()
```

## Re-encode a video

`Camera.recordTo` reads every frame, applies your transform, and writes the result — the transform must
preserve the frame size (colour-convert, filter, annotate: yes; resize: size a [`Recorder`](/video) yourself).

```scala mdoc:compile-only
Camera.usingFile("clip.mp4") { cam =>
  cam.recordTo("edges.mp4")(_.gray.canny(80, 160).convert(ColorConversion.GrayToBgr))
}
```

## Snapshot the webcam

`Camera.using(0)` opens device 0 and closes it afterwards; `snapshot` retries a few reads before giving up, so
a single dropped frame does not look like a dead camera.

```scala mdoc:compile-only
Camera.using(0)(_.snapshot().flatMap(_.write("shot.png")))
```

## Straighten and clean a scanned page (OCR prep)

Deskew the text, then adaptive-threshold so uneven lighting doesn't swallow the letters:

```scala mdoc:compile-only
Image.reading("scan.jpg")(_.gray.deskew().adaptiveThreshold(blockSize = 25, c = 10).write("clean.png"))
```

See [OCR](/ocr) to hand the cleaned page to Tesseract.

## Read a QR code

At the high level, `qrCodes` is a query on an [`Image`](/image-api) (see [Object detection](/object-detection));
it borrows the image and returns plain data:

```scala mdoc:compile-only
Image.reading("qr.png")(_.qrCodes.map(_.text))
```

The [lower-level version](#decode-a-qr-code) below builds a code to decode, so it needs no fixture file.

## Find faces

See the [lower-level recipe](#find-faces-with-a-haar-cascade) for a Haar cascade, and
[Object detection](/object-detection) for the more accurate DNN face detector (`faces`).

## Swing / AWT interop

`toBufferedImage` copies out to a `java.awt.image.BufferedImage` (which is also what a notebook renders), and
`Image.fromBufferedImage` brings one back in — always as a 3-channel BGR image. See [Image I/O](/image-io) and
[Notebooks](/notebooks).

```scala mdoc:silent
val awt: java.awt.image.BufferedImage = Image.blank(48, 24, Scalar.Blue).toBufferedImage
val back: Image = Image.fromBufferedImage(awt)
val backChannels = back.channels
back.close()
```

```scala mdoc
backChannels
```

You can round-trip through encoded bytes the same way — encode an image, hand the bytes off (an HTTP body, a
BLOB), and `decode` them back:

```scala mdoc:silent
val encodedRed: Either[CvError, Array[Byte]] = Image.blank(32, 32, Scalar.Red).bytes(".png")
val decodedWidth: Either[CvError, Int] =
  encodedRed.flatMap(Image.decode(_)).map { img =>
    try img.width
    finally img.close()
  }
```

```scala mdoc
decodedWidth.getOrElse(-1)
```

## Lower-level recipes

The same tasks on a raw `Mat`, for when you want the mid-level [ownership contract](/image-processing) and the
`Managed[Mat]` chain directly. Every mid-level op is *pure with respect to its receiver* — it allocates a fresh
result you own and must release — so `pipe` (release the intermediate once the next stage consumes it) and
`Managed.use` (release when the block returns) are how you keep a chain leak-free. See [Low-level](/low-level).

### Detect edges

```scala mdoc:silent
import org.opencv.core.{CvType, Mat}

val png: Either[CvError, Array[Byte]] =
  Managed.use(Mat(120, 120, CvType.CV_8UC3)) { image =>
    image
      .cvtColor(ColorConversion.BgrToGray)
      .pipe(_.canny(60, 160))
      .use(Images.encode(_, ".png"))
  }
```

### Chain many stages

`Mats.chain` reads as a list of stages rather than nested `pipe` lambdas — it borrows the source and releases
every intermediate:

```scala mdoc:silent
val chained: Either[CvError, Array[Byte]] =
  Managed.use(Mat(120, 120, CvType.CV_8UC3)) { image =>
    Mats
      .chain(image)(
        _.cvtColor(ColorConversion.BgrToGray),
        _.gaussianBlur(Size(5, 5), 1.5),
        _.canny(50, 150)
      )
      .use(Images.encode(_, ".png"))
  }
```

### Read an image safely

`imread` never throws — it returns an empty Mat for a missing or unreadable file. scalacv turns that into an
`Either` so you cannot forget to check:

```scala mdoc
Images.read("/does/not/exist.png").isLeft
```

### Let Otsu pick the threshold

The high-level [`Image.threshold`](/image-api) drops the value OpenCV computes; the mid-level `threshold`
returns it in a [`ThresholdResult`](/image-processing), which for `Threshold.otsu()` is the threshold chosen —
often the reason you called it.

```scala mdoc:silent
import org.opencv.core.Scalar as CvScalar

val otsuValue: Double =
  Managed.use(Mat(50, 50, CvType.CV_8UC1, CvScalar(0.0))) { m =>
    m.drawRect(Rect(10, 10, 30, 30), Scalar(200), Thickness.Filled) // two populations for Otsu to split
    val (out, result) = m.threshold(0, 255, Threshold.otsu())
    out.release()
    result.value
  }
```

```scala mdoc
otsuValue > 0
```

### Decode a QR code

```scala mdoc:silent
import org.opencv.objdetect.QRCodeEncoder
import org.opencv.imgproc.Imgproc
import org.opencv.core.{Mat, Size => CvSize}

// Encode one to decode it back — no fixture file needed.
val qr = Mat()
QRCodeEncoder.create().encode("https://github.com/w0rxbend/scalacv", qr)
val big = Mat()
Imgproc.resize(qr, big, CvSize(qr.cols * 10, qr.rows * 10), 0, 0, Imgproc.INTER_NEAREST)
val bgr = Mat()
Imgproc.cvtColor(big, bgr, Imgproc.COLOR_GRAY2BGR)
```

```scala mdoc
Qr.detectAndDecode(bgr).map(_.text)
```

```scala mdoc:invisible
qr.release(); big.release(); bgr.release()
```

### Find faces with a Haar cascade

The cascade is resolved from the platform payload — nothing to download or vendor. The classifier is one of
the types with no public `release()`, so it is freed through the safe bridge:

```scala mdoc:silent
given Releasable[org.opencv.objdetect.CascadeClassifier] =
  Releasable.handle(_.getNativeObjAddr)

val faces: Either[CvError, Seq[Rect]] =
  Cascades.load(CascadeName.FrontalFaceAlt).map { c =>
    c.use { classifier =>
      Managed.use(org.opencv.core.Mat(200, 200, org.opencv.core.CvType.CV_8UC1)) { img =>
        img.detect(classifier)
      }
    }
  }
```

## Next

- [The Image API](/image-api) — the chainable high-level layer these recipes lead with.
- [Mat lifecycle](/mat-lifecycle) — move semantics, borrowing vs. consuming, and diagnosing use-after-move.
- [Image processing](/image-processing) — the mid-level `Mat` operations and the ownership contract behind them.
