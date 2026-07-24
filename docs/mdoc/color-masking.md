# Colour, masking & compositing

This page is about turning colour into something you can *act on*: brightening and stretching
contrast, moving into a colour space you can reason about, carving a region out by its colour, and
compositing images together. It leads with the high-level [`Image`](/image-api) verbs — each consumes
the image it is called on and frees every intermediate — and shows the mid-level `Mat` op underneath
each one for when you need a knob `Image` does not surface.

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
OpenCv.load()
```

Everything runs against a synthetic scene, so no image file is read:

```scala mdoc:invisible
import org.opencv.imgproc.Imgproc
import org.opencv.core.{Scalar => CvScalar, Point => CvPoint}

// A raw BGR scene: grey background, a solid green disc, a solid red bar. BGR order, so green is
// (0, 200, 0) and red is (0, 0, 200). The caller owns the Mat and must release it.
def colourMat(): Mat =
  val m = Mat(120, 160, CvType.CV_8UC3, CvScalar(60, 60, 60))
  Imgproc.circle(m, CvPoint(45, 60), 30, CvScalar(0, 200, 0), -1)
  Imgproc.rectangle(m, CvPoint(95, 30), CvPoint(150, 90), CvScalar(0, 0, 200), -1)
  m

// The same scene as an Image. A transform consumes it, so build a fresh one each call.
def colourScene(): Image = Image.wrap(Managed(colourMat()))
```

## Intensity & contrast

Four transforms cover the everyday tone work. `adjust` is brightness and contrast in one step —
`contrast` scales each pixel (1.0 leaves it), `brightness` shifts it — saturating to 8-bit. `invert`
is the photographic negative (`255 - v`). `normalize` is a min-max contrast stretch: it rescales the
darkest pixel to `min` and the brightest to `max`, filling the range. `sharpen` is an unsharp mask —
firm at `amount` ~1, and a source of ugly haloes at the edges if you push it far past that.

```scala mdoc:silent
colourScene().adjust(brightness = 30, contrast = 1.2).close() // brighter, punchier
colourScene().invert.close()                                  // negative
colourScene().normalize().close()                             // stretch to fill [0, 255]
colourScene().sharpen(0.8).close()                            // firm unsharp mask
```

One step down, `adjust` is `convertScaleAbs(alpha = contrast, beta = brightness)` on a `Mat` —
`self * alpha + beta`, saturating to 8-bit — and `invert`, `normalize` and `sharpen` are the
identically named mid-level ops, each returning an owned `Managed[Mat]`:

```scala mdoc:silent
val src = colourMat()
val punchy: Either[CvError, Array[Byte]] =
  src.convertScaleAbs(alpha = 1.2, beta = 30).use(Images.encode(_, ".png"))
val stretched: Either[CvError, Array[Byte]] =
  src.normalize(0, 255).use(Images.encode(_, ".png"))
src.release()
```

## Colour spaces

OpenCV Mats are **BGR** by default, not RGB — which is why [`Scalar`](/geometry) is ordered
blue-green-red and `Scalar.Red` is `Scalar(0, 0, 255)`. That ordering matters the moment you write a
threshold by hand, because you are threshold-ing the channels in the order the Mat stores them.

BGR is a poor space to *segment* in: a single real-world colour smears across all three channels as
lighting changes, so no fixed box in BGR captures "green" robustly. HSV separates hue (the colour
itself) from saturation and value (how vivid, how bright), so "green, at any brightness" is a simple
range on one channel. `toHsv` is the move; `convert` is the general form.

```scala mdoc:silent
colourScene().toHsv.close()
colourScene().convert(ColorConversion.BgrToHsv).close()
```

The conversion changes what each channel *means*, not how many there are — a 3-channel BGR image
becomes a 3-channel HSV one. In OpenCV's 8-bit HSV, **hue runs 0–179** (degrees halved to fit a
byte), while saturation and value run the full 0–255. A `Scalar` you pass to `inRange` below is
therefore `Scalar(hue, sat, val)` in exactly that scale.

## Colour segmentation

The flagship recipe, end to end: convert to HSV, threshold a colour range into a binary mask with
`inRange`, then composite the original image through that mask with `applyMask`. `inRange` yields a
single-channel `CV_8UC1` mask (255 where every channel is in range, 0 elsewhere) regardless of the
source's channel count:

```scala mdoc
val hsv = colourScene().toHsv
val greenMask = hsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
greenMask.channels // 1 — a binary mask, whatever the source had
```

```scala mdoc:invisible
greenMask.close()
```

Now the whole pipeline. `inRange` and `applyMask` are high-level transforms, so each chain must end
in a terminal or a `close`; and `applyMask` **borrows** its `mask` argument — that `Image` stays
alive, so you close it yourself. Branch the scene with `copy` because one path builds the mask while
the other supplies the pixels to keep:

```scala mdoc:silent
val scene = colourScene()
val mask = scene.copy.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
val justGreen: Either[CvError, Array[Byte]] =
  scene.applyMask(mask).bytes(".png") // keep the original pixels only where the mask is white
mask.close()                          // applyMask borrowed it — close it ourselves
```

The green disc survives; the red bar and grey background go black. The same two ops exist mid-level
as `inRange(lo, hi)` and `masked(mask)` on a `Mat`, each an owned `Managed[Mat]`:

```scala mdoc:silent
val bgr = colourMat()
val segmented: Either[CvError, Array[Byte]] =
  bgr.cvtColor(ColorConversion.BgrToHsv)
    .use(hsvMat => hsvMat.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255)))
    .use(m => bgr.masked(m).use(Images.encode(_, ".png")))
bgr.release()
```

## Compositing

`blend` is alpha-over: `this * weight + other * (1 - weight)`, so `weight` 0.6 keeps 60% of this
image and 40% of `other`. Both images must match in size and type, and `other` is **borrowed** — the
`Image` you pass stays alive, so close it yourself:

```scala mdoc:silent
val base = colourScene()
val over = Image.blank(160, 120, Scalar.White)
val mixed: Either[CvError, Array[Byte]] =
  base.blend(over, weight = 0.6).bytes(".png")
over.close() // blend borrowed it
```

`applyMask` is the other compositing move — composite an image *through* a shape rather than over
another image. Build a single-channel mask (here by [drawing](/drawing) a filled white disc on a
black canvas) and everything outside it becomes black:

```scala mdoc:silent
val photo = colourScene()
val hole = Image
  .blank(160, 120, Scalar.Black, channels = 1)
  .drawCircle(Point(80, 60), 40, Scalar.White, Thickness.Filled)
val throughMask: Either[CvError, Array[Byte]] =
  photo.applyMask(hole).bytes(".png")
hole.close() // applyMask borrowed it
```

Underneath, `blend` is `addWeighted` and `applyMask` is `masked`, both on a `Mat` with `other` /
`mask` borrowed exactly like the receiver — see [Image processing](/image-processing) for the
`addWeighted` signature in full.

## Channels

`channel(index)` pulls one plane out as its own single-channel image — the hue plane of an HSV image,
say, or the blue plane of a BGR one:

```scala mdoc
val hue = colourScene().toHsv.channel(0)
hue.channels // 1 — one plane on its own
```

```scala mdoc:invisible
hue.close()
```

The mid-level op is `extractChannel(index)` on a `Mat`, returning an owned `Managed[Mat]`:

```scala mdoc:silent
val planeSrc = colourMat()
val bluePlane: Either[CvError, Array[Byte]] =
  planeSrc.extractChannel(0).use(Images.encode(_, ".png")) // BGR: channel 0 is blue
planeSrc.release()
```

## Smoothing & thresholding that pair with masks

A raw mask is rarely clean, and uneven lighting defeats a single global threshold — three ops earn
their place alongside the masking above.

`medianBlur` replaces each pixel with the median of its neighbourhood; on a binary mask that erases
stray speckle without smearing the edges the way a Gaussian would. Radius 2 is a 5×5 window:

```scala mdoc:silent
val speckled = colourScene().toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
val cleaned: Either[CvError, Array[Byte]] =
  speckled.medianBlur(2).bytes(".png") // knock out stray pixels in the mask
```

`bilateralFilter` smooths flat regions while keeping edges crisp — the tool when you want to denoise
before segmenting without blurring the colour boundaries you are about to threshold on (slower than a
Gaussian):

```scala mdoc:silent
colourScene().bilateralFilter().close()
```

`adaptiveThreshold` computes a threshold *per neighbourhood* instead of once for the whole image,
which is what makes it hold up under uneven lighting — document scans, OCR pre-processing. It needs a
single-channel input, so it follows `gray`:

```scala mdoc:silent
colourScene().gray.adaptiveThreshold(blockSize = 15, c = 4).close()
```

Each has the identically named mid-level op on a `Mat` — `medianBlur(ksize)` (an odd kernel size
rather than a radius), `bilateralFilter(diameter, sigmaColor, sigmaSpace)`, and `adaptiveThreshold`
with its [`AdaptiveMethod`](/geometry) (`Gaussian` or `Mean`) knob:

```scala mdoc:silent
val docSrc = colourMat()
val document: Either[CvError, Array[Byte]] =
  docSrc.cvtColor(ColorConversion.BgrToGray)
    .pipe(_.adaptiveThreshold(blockSize = 15, c = 4, method = AdaptiveMethod.Mean))
    .use(Images.encode(_, ".png"))
docSrc.release()
```

## See also

- [The Image API](/image-api) — the transform / query / terminal model these verbs follow.
- [Image processing](/image-processing) — the full mid-level op catalogue and the ownership contract.
- [Drawing](/drawing) — building masks and annotations by hand.
- [Geometry](/geometry) — `Scalar` and its BGR ordering, `AdaptiveMethod`, and the other typed enums.
- [Cookbook](/cookbook) — end-to-end recipes.
