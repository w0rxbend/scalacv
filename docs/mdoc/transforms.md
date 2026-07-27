# Geometric transforms & morphology

Some operations change *what colour a pixel is*; the ones on this page change *where the pixels go* —
resizing, cropping, mirroring, rotating, padding — plus the morphology operators that grow, shrink
and clean up binary masks. If you have ever made a thumbnail, straightened a photo, or tidied the
speckled output of a colour mask, this is the toolbox.

Like the rest of the library each op comes at **two levels**. Reach for the high-level
[`Image`](/image-api) verbs first — they free every intermediate for you and read as a fluent chain.
Drop to the mid-level `Mat` extension ops from [Image processing](/image-processing) only when you
want a knob `Image` does not surface (an `iterations`, a border colour on a rotation) or you are
already holding a raw `Mat` handed to you by a detector or a video frame.

:::note Transforms consume the image
Every verb here is a **transform**: it spends the `Image` it is called on and returns a fresh one.
Reusing the old handle throws `IllegalStateException`. To branch — build a mask from one copy and
keep the pixels from another — take [`.copy`](/image-api) first. Queries like `width`/`height` only
*borrow*, so they leave the image alive. See [Mat lifecycle](/mat-lifecycle) for the full ownership
story.
:::

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc
OpenCv.load()

// A synthetic binary mask so the morphology ops have something real to change: a solid blob, a
// stray speck, and a pinhole. The caller owns it and must release it.
def mask(): Mat =
  val m = Mat(120, 160, CvType.CV_8UC1, org.opencv.core.Scalar(0))
  Imgproc.rectangle(m, org.opencv.core.Point(30, 30), org.opencv.core.Point(120, 90),
    org.opencv.core.Scalar(255), -1)
  Imgproc.circle(m, org.opencv.core.Point(140, 20), 2, org.opencv.core.Scalar(255), -1) // a speck
  Imgproc.circle(m, org.opencv.core.Point(70, 60), 3, org.opencv.core.Scalar(0), -1)    // a pinhole
  m
```

## The transforms at a glance

| High-level `Image` verb | What it does | Mid-level `Mat` op |
| --- | --- | --- |
| `resize(w, h)` / `resizeTo(size)` / `scale(f)` | Change pixel dimensions | `resize`, `scaled` |
| `crop(rect)` | Cut out a rectangular region (an independent copy) | `submat` + clone |
| `flip(how)` | Mirror left↔right, top↔bottom, or both | `flip` |
| `rotate(Rotation.…)` | Lossless 90°/180° turn | `rotate` |
| `rotate(degrees, scale)` | Turn by any angle, growing the canvas | `rotated` |
| `pad(size)` / `border(t, b, l, r)` | Add a margin on some or all sides | `border` |
| `erode` / `dilate` | Shrink / grow bright regions | `erode`, `dilate` |
| `morphology(op)` | Open, close, gradient, top-hat, black-hat | `morphology` |

The rest of the page walks these in the order you usually meet them.

## Resize & scale

The most common geometric op. `resize(width, height)` sets an absolute pixel size; `scale(factor)`
multiplies both sides by one number (`0.5` halves, `2.0` doubles); `resizeTo(size, interpolation)`
takes a [`Size`](/geometry) and lets you pick the resampling filter:

```scala mdoc
val thumb = Image.blank(160, 120).resize(80, 60)
thumb.width // 80
```

```scala mdoc:invisible
thumb.close()
```

```scala mdoc:silent
Image.blank(160, 120).scale(0.5).close()                              // 80×60
Image.blank(160, 120).resizeTo(Size(320, 240), Interpolation.Cubic).close()
```

### Choosing an interpolation

Every resize resamples: it invents pixel values that were not there before. [`Interpolation`](/geometry)
picks how, and the right choice depends on whether you are growing or shrinking the image.

| `Interpolation` | Best for | Notes |
| --- | --- | --- |
| `Nearest` | Label maps, masks | Blocky; copies the closest pixel, so exact values (0/255) survive |
| `Linear` | General up/downscale | The default — a good, fast all-rounder |
| `Cubic` | Upscaling | Smoother than `Linear`, slower |
| `Area` | Downscaling | Averages the shrunk-away pixels; avoids moiré and aliasing |
| `Lanczos4` | Highest-quality upscale | Sharpest, slowest |

:::tip Downscale with `Area`, upscale with `Cubic`
The default `Linear` is fine most of the time, but the two ends of the range have better tools. When
you make an image smaller, `Area` avoids the shimmer a linear downscale leaves in fine textures; when
you make it larger, `Cubic` (or `Lanczos4`) keeps edges crisp.
:::

```scala mdoc:silent
Image.blank(320, 240).resizeTo(Size(80, 60), Interpolation.Area).close()   // shrink
Image.blank(80, 60).resizeTo(Size(320, 240), Interpolation.Lanczos4).close() // enlarge
```

## Crop

`crop(rect)` cuts out an axis-aligned region as an **independent copy** — not an aliasing view onto
the parent — so the crop outlives the image it came from. The [`Rect`](/geometry) is
`Rect(x, y, width, height)` and must lie fully inside the image, or the call throws
`IllegalArgumentException` before touching native memory:

```scala mdoc
val region = Image.blank(160, 120).crop(Rect(20, 15, 60, 40))
region.width // 60
```

```scala mdoc:invisible
region.close()
```

## Flip

[`Flip`](/geometry) is named by the visible effect, not OpenCV's axis-centric flip code:
`Flip.Horizontal` mirrors left↔right, `Flip.Vertical` mirrors top↔bottom, and `Flip.Both` does both
at once (a 180° point reflection). High-level, it is a transform — it consumes the image and hands
back a fresh one:

```scala mdoc:silent
Image.blank(160, 120).flip(Flip.Horizontal).close()
```

:::tip Mirroring a webcam preview
A front-facing camera feels natural only when the preview is mirrored, so `frame.flip(Flip.Horizontal)`
is the standard first step in a selfie or [conferencing](/conferencing) pipeline.
:::

Mid-level, the same op on a borrowed `Mat` returns an owned `Managed[Mat]`:

```scala mdoc:silent
val toMirror = mask()
val mirrored: Either[CvError, Array[Byte]] =
  toMirror.flip(Flip.Vertical).use(Images.encode(_, ".png"))
toMirror.release()
```

## Rotation

### Lossless quarter-turns

For the three right-angle turns, [`Rotation`](/geometry) — `Clockwise`, `CounterClockwise`, `Half` —
does an exact pixel shuffle: no interpolation, nothing resampled. A quarter-turn swaps width and
height, which you can see in the result:

```scala mdoc
val turned = Image.blank(160, 120).rotate(Rotation.Clockwise)
(turned.width, turned.height) // 160×120 comes back 120×160
```

```scala mdoc:invisible
turned.close()
```

| `Rotation` | Angle | Size change |
| --- | --- | --- |
| `Clockwise` | 90° CW | w↔h swap |
| `CounterClockwise` | 90° CCW | w↔h swap |
| `Half` | 180° | unchanged |

### Arbitrary angle — the canvas expands

`rotate(degrees)` turns by any angle (counter-clockwise). The nice part: rather than spin the image
inside its old frame and clip the corners off, it **grows the canvas to the rotated bounding box** so
every corner still lands inside. So the output is larger than the input, and the exposed border is
filled in (black by default):

```scala mdoc
val tilted = Image.blank(160, 120).rotate(30)
(tilted.width, tilted.height) // wider and taller than 160×120 — no corner is clipped
```

```scala mdoc:invisible
tilted.close()
```

The second argument zooms at the same time — `rotate(30, scale = 0.5)` turns and halves in one step.
The mid-level `rotated` exposes the rest: the [`Interpolation`](/geometry), and the
[`BorderType`](/geometry) plus colour used to fill the newly exposed corners:

```scala mdoc:silent
val toTurn = mask()
val spun: Either[CvError, Array[Byte]] =
  toTurn.rotated(15, scale = 1.0, border = BorderType.Replicate).use(Images.encode(_, ".png"))
toTurn.release()
```

:::note Straightening scanned text
When the tilt you want to remove is *text* skew rather than a known angle, reach for
[`deskew`](/ocr) instead — it finds the dominant text angle itself and rotates upright. `rotate` is
for when you already know the angle.
:::

## Padding & borders

`pad(size)` adds a uniform margin of `size` pixels on all four sides; `border(top, bottom, left,
right)` sizes each side independently. Both take a [`BorderType`](/geometry) and a fill colour — the
default is a constant black, but `Replicate`, `Reflect` and the others extend the edge pixels instead.
Each side grows the canvas by its own width:

```scala mdoc
val padded = Image.blank(160, 120).pad(12, color = Scalar.White)
(padded.width, padded.height) // 12px added on every side: 184×144
```

```scala mdoc:invisible
padded.close()
```

```scala mdoc
val framed = Image.blank(160, 120).border(top = 4, bottom = 4, left = 20, right = 20)
(framed.width, framed.height) // 200×128
```

```scala mdoc:invisible
framed.close()
```

The `borderType` decides what fills the new margin — a fixed colour or an extension of the existing
edge:

| `BorderType` | Fills the margin with | Typical use |
| --- | --- | --- |
| `Constant` | The given `color` (default black) | A visible frame or letterbox |
| `Replicate` | The nearest edge pixel, repeated | Padding before a filter, no fake edge |
| `Reflect` | A mirror of the edge, **including** the edge pixel | Seamless tiling |
| `Reflect101` | A mirror **excluding** the edge pixel | OpenCV's own default for filter borders |
| `Wrap` | Pixels from the opposite side | Periodic / tiling images |

```scala mdoc:silent
Image.blank(160, 120).pad(8, borderType = BorderType.Replicate).close()
```

The mid-level `border` has the identical `(top, bottom, left, right, borderType, color)` signature and
returns an owned `Managed[Mat]`.

## Morphology

Morphology reshapes the *bright* regions of an image (conventionally a `CV_8UC1` mask where the
foreground is 255) by probing it with a small **structuring element**. It is the standard clean-up
crew after any segmentation — thresholding, colour masking, motion differencing — where the raw mask
is right in outline but grainy at the pixel level.

### The structuring element

[`MorphShape`](/geometry) picks the probe's shape and `radius` sizes it: the kernel is
`radius * 2 + 1` pixels on a side, so `radius` 1 is a 3×3, `radius` 2 a 5×5. A larger radius reaches
further, and so removes or fills larger features. The wrapper builds and frees the kernel for you.

| `MorphShape` | Shape | When |
| --- | --- | --- |
| `Rect` | Filled square | The fast default; fine for most masks |
| `Ellipse` | Filled disc | Round blobs — avoids the corner artefacts a square leaves |
| `Cross` | Plus sign | Thin structures; touches fewer diagonal neighbours |

### Erode & dilate

**Erode** shrinks bright regions and clears specks smaller than the kernel; **dilate** grows bright
regions and fills small dark gaps. They are exact opposites:

```scala mdoc:silent
val blob = mask()
val eroded: Either[CvError, Array[Byte]] =
  blob.erode(radius = 2, shape = MorphShape.Ellipse).use(Images.encode(_, ".png"))
val dilated: Either[CvError, Array[Byte]] =
  blob.dilate(radius = 2).use(Images.encode(_, ".png"))
blob.release()
```

The mid-level ops also take `iterations` to apply the same kernel repeatedly — often cleaner than one
big radius:

```scala mdoc:silent
val blob2 = mask()
val worn: Either[CvError, Array[Byte]] =
  blob2.erode(radius = 1, iterations = 3).use(Images.encode(_, ".png"))
blob2.release()
```

High-level, `erode` and `dilate` are transforms with the same `radius`/`shape` knobs:

```scala mdoc:silent
Image.blank(160, 120, Scalar.White).gray.threshold(127).erode(radius = 2).close()
```

### Compound operations

The compound operators from [`MorphOp`](/geometry) pair an erode and a dilate, and the pairing is the
whole point:

| `MorphOp` | Definition | Net effect |
| --- | --- | --- |
| `Open` | erode → dilate | **Specks vanish, real shapes stay put** — the de-speckle |
| `Close` | dilate → erode | **Holes fill, shapes keep their size** |
| `Gradient` | dilation − erosion | A one-pixel outline of the shapes |
| `TopHat` | source − opening | The bright detail smaller than the kernel |
| `BlackHat` | closing − source | The dark detail smaller than the kernel |

`Open` erodes first, so anything smaller than the kernel disappears; the following dilate restores
what survived to its original size. `Close` is the mirror image — the dilate bridges gaps and fills
pinholes, and the erode shrinks the shapes back. `TopHat` and `BlackHat` isolate the fine detail an
`Open`/`Close` throws away, which makes them handy for pulling text or specks off an uneven
background.

```scala mdoc:silent
val m = mask()
val despeckled: Either[CvError, Array[Byte]] =
  m.morphology(MorphOp.Open, radius = 2).use(Images.encode(_, ".png"))  // drop the speck
val holesFilled: Either[CvError, Array[Byte]] =
  m.morphology(MorphOp.Close, radius = 3).use(Images.encode(_, ".png")) // fill the pinhole
val outline: Either[CvError, Array[Byte]] =
  m.morphology(MorphOp.Gradient).use(Images.encode(_, ".png"))
val brightDetail: Either[CvError, Array[Byte]] =
  m.morphology(MorphOp.TopHat, radius = 3).use(Images.encode(_, ".png"))
m.release()
```

The mid-level `morphology` takes `iterations` too; the high-level `Image.morphology(op, radius,
shape)` is the same op as a transform.

## Recipe: clean up a mask

The common pipeline after any segmentation — [thresholding](/image-processing) or
[colour masking](/color-masking) — is **threshold → open → close**: binarise, drop the specks the
threshold left behind, then fill the pinholes it punched. `Mats.chain` threads it leak-free, and the
tuple from `threshold` yields its `Mat` with `._1`:

```scala mdoc:silent
val raw = mask()
val cleaned: Either[CvError, Array[Byte]] =
  Mats.chain(raw)(
    _.threshold(127, 255)._1,
    _.morphology(MorphOp.Open, radius = 2),  // remove specks
    _.morphology(MorphOp.Close, radius = 3)  // fill holes
  ).use(Images.encode(_, ".png"))
raw.release()
```

High-level, move semantics give the same guarantee with no combinator — each step consumes the last:

```scala mdoc:silent
Image.blank(160, 120, Scalar.White)
  .gray
  .threshold(127)
  .morphology(MorphOp.Open, radius = 2)
  .morphology(MorphOp.Close, radius = 3)
  .close()
```

A mask cleaned this way is exactly what [`contours`](/contours) wants next.

## Next

- [Contours](/contours) — turn a cleaned mask into shapes you can measure and label.
- [Colour, masking & compositing](/color-masking) — where most masks come from before this clean-up.
- [Image processing](/image-processing) — the full mid-level op catalogue and the ownership contract.
