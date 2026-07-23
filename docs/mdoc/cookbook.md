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
