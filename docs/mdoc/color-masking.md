# Colour, masking & compositing

This page is about turning colour into something you can *act on*: brightening and stretching
contrast, restyling a photo, moving into a colour space you can reason about, carving a region out by
its colour, and compositing images together. Three ideas run through all of it:

1. **Tone** — nudging brightness, contrast, saturation and hue (`adjust`, `sepia`, `saturate`, …).
2. **Segmentation** — deciding which pixels you care about and building a binary *mask* (`toHsv` →
   `inRange`).
3. **Compositing** — combining images, either blended together (`blend`) or one seen *through* a mask
   (`applyMask`).

It leads with the high-level [`Image`](/image-api) verbs — each consumes the image it is called on
and frees every intermediate — and shows the mid-level `Mat` op underneath each one for when you need
a knob `Image` does not surface.

:::note Transforms consume, masks are borrowed
A colour verb spends the `Image` it is called on and returns a new one. But `applyMask`, `blend` and
friends take a second image (the mask, or the other layer) that is **borrowed** — it stays alive, so
you close it yourself. Getting this right is the whole subject of [Mat lifecycle](/mat-lifecycle);
each snippet below is careful about it.
:::

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

## First taste

The everyday tone verbs read exactly the way you would say them. Each consumes the scene and returns
a new image; `.close()` releases it because these snippets actually run:

```scala mdoc:silent
colourScene().adjust(brightness = 30).close() // lighter
colourScene().invert.close()                  // photographic negative
colourScene().gray.close()                    // drop to greyscale
```

Everything else on the page is a richer version of one of those three moves.

## Intensity & contrast

Four transforms cover the everyday tone work.

| Verb | What it does |
| --- | --- |
| `adjust(brightness, contrast)` | Shift *and* scale each pixel in one step (saturating to 8-bit) |
| `invert` | Photographic negative, `255 - v` |
| `normalize(min, max)` | Min-max contrast stretch — rescale so the darkest pixel is `min`, brightest `max` |
| `sharpen(amount)` | Unsharp mask; ~1 is firm, higher haloes the edges |

`contrast` scales each pixel (1.0 leaves it), `brightness` shifts it — both saturate to 8-bit.
`normalize` fills the range so a flat, low-contrast image gains punch. `sharpen` is firm at
`amount` ~1 and a source of ugly haloes at the edges if you push it far past that.

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

## Stylistic colour

Beyond plain tone there is a family of look-and-feel transforms — the ones that make a photo warm,
vintage, or posterised. They are all transforms, so each consumes the scene:

| Verb | Effect | Range guide |
| --- | --- | --- |
| `sepia` | Warm brown monochrome | — |
| `saturate(factor)` | Vividness; `0` is grey (still 3-channel) | `≥ 0`, `>1` vivid, `<1` muted |
| `gamma(g)` | Mid-tone brightness | `>0`, `<1` darkens, `>1` lifts |
| `temperature(shift)` | Warm/cool white balance | `[-1, 1]`, `>0` warm, `<0` cool |
| `posterize(levels)` | Flatten to `levels` tones per channel | `[2, 256]` |
| `emboss` | Directional relief | — |
| `colorMap(map)` | False-colour a single channel into a heatmap | see [`Colormap`](/geometry) |

```scala mdoc:silent
colourScene().sepia.close()
colourScene().saturate(1.4).close()      // more vivid
colourScene().gamma(0.8).close()         // darken mid-tones
colourScene().temperature(0.4).close()   // warmer
colourScene().posterize(6).close()       // 6 tones per channel
colourScene().emboss.close()
```

`colorMap` expects a single channel, so it usually follows `gray` — it turns a scalar field (grey
intensity, a depth map, a [motion](/motion-detection) field) into a colour heatmap:

```scala mdoc:silent
colourScene().gray.colorMap(Colormap.Turbo).close()
```

:::tip Reach for a named filter first
Most of these looks are already bundled as composable [`Filter`](/filters) values — `Filter.vintage`,
`Filter.noir`, `Filter.warm`, `Filter.dramatic` — applied with `image.filter(...)`. Build your own
only when none fits.
:::

```scala mdoc:silent
colourScene().filter(Filter.vintage).close()
```

See [Filters](/filters) for the full catalogue and how to chain them.

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

[`ColorConversion`](/geometry) covers the spaces you actually reach for:

| Conversion | From → to | Why |
| --- | --- | --- |
| `BgrToGray` / `GrayToBgr` | colour ↔ 1-channel grey | Feed grey into edges, thresholds, `colorMap` |
| `BgrToHsv` / `HsvToBgr` | BGR ↔ hue/sat/value | Segment by colour (below) |
| `BgrToLab` / `LabToBgr` | BGR ↔ CIELAB | Perceptual colour distance, white balance |
| `BgrToRgb` / `RgbToBgr` | swap channel order | Hand pixels to a library that expects RGB |
| `BgrToBgra` / `BgraToBgr` | add / drop alpha | Move between opaque and 4-channel images |

The conversion changes what each channel *means*, not how many there are (except when it adds or
drops the alpha channel) — a 3-channel BGR image becomes a 3-channel HSV one. In OpenCV's 8-bit HSV,
**hue runs 0–179** (degrees halved to fit a byte), while saturation and value run the full 0–255. A
`Scalar` you pass to `inRange` below is therefore `Scalar(hue, sat, val)` in exactly that scale.

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

Picking the hue bounds is the hard part. These centres (on OpenCV's 0–179 scale) are a starting
point; widen the `sat`/`val` floor to admit more washed-out or shadowed pixels:

| Colour | Hue (0–179) | Note |
| --- | --- | --- |
| Red | 0–10 **and** 170–179 | Wraps the seam — needs two ranges (see below) |
| Orange | ~10–20 | — |
| Yellow | ~25–35 | — |
| Green | ~35–85 | The disc in our scene |
| Cyan | ~85–95 | — |
| Blue | ~100–130 | — |
| Magenta | ~140–160 | — |

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

:::warning Red wraps around the hue wheel
Because hue is circular and red sits at the 0/179 seam, no single `inRange` captures it — you build
*two* masks (one at each end) and OR them together. `inRange` and `applyMask` are wrapped, but a raw
bitwise OR is not, so you drop to `org.opencv.core.Core` for that one step:

```scala
import org.opencv.core.{Core, Mat}

val hsv  = scene.toHsv                                   // scene held elsewhere
val low  = hsv.copy.inRange(Scalar(0, 80, 80), Scalar(10, 255, 255))
val high = hsv.copy.inRange(Scalar(170, 80, 80), Scalar(179, 255, 255))
val redMask = Mat()
Core.bitwise_or(low.mat, high.mat, redMask)              // both ends of the wheel
low.close(); high.close(); hsv.close()
// redMask is now an unmanaged Mat — wrap it (Image.wrap(Managed(redMask))) or release it yourself.
```

This block is illustrative (not type-checked), because it reaches past the wrapped API into raw
OpenCV; treat `redMask` as an unmanaged Mat you must release.
:::

A freshly-cut mask is usually grainy at the edges — the [Transforms](/transforms) page's
threshold → open → close clean-up is the natural next step before you [find its contours](/contours).

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

:::note Invisible pastes want `seamlessCloneInto`
`applyMask` composites with a hard edge. To paste an object into another image so the join is
*invisible* — Poisson blending that matches gradients across the seam — reach for
`seamlessCloneInto`, the compositing behind a good virtual background. See [Conferencing](/conferencing).
:::

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

The channel index follows the Mat's storage order — for a BGR image that is blue = 0, green = 1,
red = 2; for HSV it is hue = 0, saturation = 1, value = 2. The mid-level op is `extractChannel(index)`
on a `Mat`, returning an owned `Managed[Mat]`:

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

## Next

- [Transforms & morphology](/transforms) — clean up the masks you build here before measuring them.
- [Contours](/contours) — turn a colour mask into shapes you can count and outline.
- [Filters](/filters) — the named, composable looks built from the stylistic verbs above.
