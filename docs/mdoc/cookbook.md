# Cookbook

Every snippet here is compiled by mdoc against the real library, so it cannot drift out of date.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

## Detect edges

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

## Read an image safely

`imread` never throws — it returns an empty Mat for a missing or unreadable file. scalacv turns that
into an `Either` so you cannot forget to check:

```scala mdoc
Images.read("/does/not/exist.png").isLeft
```

## Decode a QR code

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

## Find faces with a Haar cascade

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
