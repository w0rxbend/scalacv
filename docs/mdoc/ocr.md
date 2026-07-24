# Optical character recognition (OCR)

Whether OCR works is decided almost entirely *before* the recogniser runs — by how clean and how straight
the image is. That preprocessing is OpenCV's job, and scalacv's:

```
Image ─▶ grayscale ─▶ denoise ─▶ threshold ─▶ deskew ─▶ [ OCR engine ] ─▶ text
        └──────────────── scalacv (Image.forOcr) ─────────────┘   └ you bring this ┘
```

scalacv owns the pipeline up to the engine and defines the recognition **contract** ([[OcrEngine]]); the
engine itself — Tesseract, a cloud OCR — stays a dependency *you* add, because it is a heavy,
separately-licensed native library that has no place inside a thin OpenCV wrapper. Wiring one in is a few
lines (below).

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
def scan(): Image =
  val page = Image
    .blank(240, 160, Scalar.White)
    .drawRects(Seq(Rect(30, 30, 180, 10), Rect(30, 60, 180, 10), Rect(30, 90, 180, 10)), Scalar.Black, Thickness.Filled)
  val skewed = Image.wrap(page.mat.rotated(9.0, borderValue = Scalar.White))
  page.close()
  skewed
```

## Preprocessing

`Image.forOcr` runs the whole pre-pipeline — grayscale → denoise → adaptive threshold → deskew — and hands
back a clean, upright, single-channel binary image:

```scala mdoc
val prepared = scan().forOcr()
val shape = s"${prepared.channels} channel, ${prepared.width}x${prepared.height}, upright & binarised"
prepared.close()
shape
```

The knobs (`denoise`, `blockSize`, `c`) tune the median-blur radius and the adaptive threshold; the defaults
suit a typical document scan. The individual steps are all available on `Image` too —
[gray](/image-processing), [medianBlur](/color-masking), [adaptiveThreshold](/color-masking) — if you want
to build your own variant.

### Deskew

The one step unique to OCR is **deskew**: a scan is rarely perfectly straight, and even a few degrees of tilt
hurts recognition. `deskew` binarises the ink, fits a minimum-area rectangle to it, and rotates the page back
to level (the exposed corners fill white):

```scala mdoc:compile-only
Image.read("crooked-scan.jpg").map(_.deskew().write("straight.png"))
```

## The engine contract

An [[OcrEngine]] turns a prepared image into text. `Ocr.read` preprocesses (unless told not to) and delegates:

```scala mdoc:silent
// A stand-in engine; a real one calls Tesseract (see below).
val engine: OcrEngine = new OcrEngine:
  def recognize(image: Image): OcrResult =
    OcrResult(text = s"<${image.width}x${image.height} page>", words = Seq.empty)
```

```scala mdoc
Ocr.read(scan(), engine).text
```

The result is plain data — the full `text`, and per-word boxes and confidences when the engine provides them:

```scala mdoc
val result = OcrResult(
  "hello world",
  Seq(OcrWord("hello", 0.95f, Rect(0, 0, 40, 12)), OcrWord("world", 0.30f, Rect(45, 0, 40, 12)))
)
result.confident(0.5f).map(_.text).mkString(", ") // drops the low-confidence "world"
```

## Wiring in Tesseract

Add [tess4j](https://github.com/nguyenq/tess4j) (or bytedeco's `tesseract` preset) and its language data, then
implement `OcrEngine` over it. scalacv has already produced the clean, deskewed image; the adapter just hands
the pixels across:

```scala
// build: libraryDependencies += "net.sourceforge.tess4j" % "tess4j" % "5.x"
import net.sourceforge.tess4j.Tesseract
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

final class TesseractEngine(dataPath: String, language: String = "eng") extends scalacv.OcrEngine:
  private val tess = new Tesseract()
  tess.setDatapath(dataPath) // the folder holding eng.traineddata
  tess.setLanguage(language)

  def recognize(image: scalacv.Image): scalacv.OcrResult =
    // scalacv encodes the prepared image; tess4j reads a BufferedImage.
    val png = image.copy.bytes(".png").fold(e => throw e, identity)
    val buffered = ImageIO.read(new ByteArrayInputStream(png))
    scalacv.OcrResult(text = tess.doOCR(buffered).trim)
```

```scala
OpenCv.load()
val ocr = TesseractEngine(dataPath = "/usr/share/tessdata")

Image.read("receipt.jpg").map { img =>
  try Ocr.read(img, ocr).text   // forOcr + Tesseract
  finally img.close()
}
```

Why it is not bundled: Tesseract pulls its own native libraries and multi-megabyte `*.traineddata` files per
language, and is GPL-adjacent — exactly the kind of heavy, opinionated dependency a wrapper should let you
choose rather than impose. The same shape works for any recogniser (a cloud OCR, an ONNX text model through
[Dnn](/dnn)): implement `OcrEngine`, and the preprocessing is done for you.
