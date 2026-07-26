# Cookbook

Every snippet here is compiled by mdoc against the real library, so it cannot drift out of date. The
recipes lead with the high-level [`Image`](/image-api) API; the [lower-level recipes](#lower-level-recipes)
at the end show the same work on a raw `Mat` for when you need the extra control.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

## Edge-detect a photo

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

## Make a thumbnail

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.scale(0.25).write("thumb.jpg"))
```

## Annotate a scene and encode it

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

`Image.reading` closes the source for you even if the body already consumed it:

```scala mdoc:compile-only
Image.reading("photo.jpg")(_.gray.equalizeHist.threshold(128).write("mask.png"))
```

## Segment by colour (HSV keying)

Threshold in HSV — where "greenish" is a hue range, not a fragile RGB box — then keep only those pixels:

```scala mdoc:silent
val photo =
  Image.blank(120, 90, Scalar(0, 0, 255)).drawCircle(Point(60, 45), 22, Scalar.Green, Thickness.Filled)
val hueMask = photo.copy.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255)) // green hues
val greenOnly: Either[CvError, Array[Byte]] = photo.applyMask(hueMask).bytes(".png")
hueMask.close()
```

## Count the shapes in a frame

Binarise, then count contours — the outermost outline of each blob:

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

## Apply a named filter

The built-in [filters](/filters) are composable `Image => Image` looks:

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.filter(Filter.vintage).write("vintage.jpg"))
```

## Blend two images

`blend` is an alpha composite — `weight` is how much of the receiver survives:

```scala mdoc:silent
val base = Image.blank(100, 100, Scalar(255, 0, 0)) // blue
val over = Image.blank(100, 100, Scalar(0, 0, 255)) // red
val blended: Either[CvError, Array[Byte]] = base.blend(over, weight = 0.5).bytes(".png")
over.close() // `over` is borrowed by blend; `base` was consumed by it
```

## Erase an object (inpaint)

Mark the region to remove with a non-zero mask; inpaint fills it from its surroundings:

```scala mdoc:silent
val scratched =
  Image.blank(100, 100, Scalar(80, 120, 160)).drawRect(Rect(46, 10, 6, 80), Scalar.White, Thickness.Filled)
val repairMask =
  Image.blank(100, 100, Scalar.Black, channels = 1).drawRect(Rect(46, 10, 6, 80), Scalar.White, Thickness.Filled)
val repaired: Either[CvError, Array[Byte]] = scratched.inpaint(repairMask).bytes(".png")
repairMask.close()
```

## Blur the background behind a person

Given a foreground mask (from a segmentation model, or any keying), keep the person sharp and blur the rest — the compositing behind a virtual background:

```scala mdoc:silent
val frame =
  Image.blank(160, 120, Scalar(60, 60, 60)).drawCircle(Point(80, 60), 30, Scalar(200, 180, 160), Thickness.Filled)
val personMask =
  Image.blank(160, 120, Scalar.Black, channels = 1).drawCircle(Point(80, 60), 30, Scalar.White, Thickness.Filled)
val composited: Either[CvError, Array[Byte]] = frame.blurBackground(personMask, strength = 15).bytes(".png")
personMask.close()
```

See [Video conferencing](/conferencing) for producing the mask with a segmentation network.

## Raise a motion alarm on a video

Feed frames in order to a stateful detector; it reports whether — and where — something moved:

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

## Straighten and clean a scanned page (OCR prep)

Deskew the text, then adaptive-threshold so uneven lighting doesn't swallow the letters:

```scala mdoc:compile-only
Image.reading("scan.jpg")(_.gray.deskew().adaptiveThreshold(blockSize = 25, c = 10).write("clean.png"))
```

See [OCR](/ocr) to hand the cleaned page to Tesseract.

## Lower-level recipes

The same tasks on a raw `Mat`, for when you want the mid-level [ownership contract](/image-processing)
and the `Managed[Mat]` chain directly.

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

### Read an image safely

`imread` never throws — it returns an empty Mat for a missing or unreadable file. scalacv turns that
into an `Either` so you cannot forget to check:

```scala mdoc
Images.read("/does/not/exist.png").isLeft
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

The cascade is resolved from the platform payload — nothing to download or vendor. The classifier is
one of the types with no public `release()`, so it is freed through the safe bridge:

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
