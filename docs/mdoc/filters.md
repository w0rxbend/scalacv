# Photo filters & colormaps

This page is about *looks*: the tone, colour and stylisation effects you reach for to finish an image
— a warm cast, a sepia wash, a pencil sketch, a false-colour heatmap of a depth map — and the
[`Filter`](/image-api) type that names and composes them. Every effect is a high-level
[`Image`](/image-api) verb that consumes the image it is called on and frees every intermediate, with
the mid-level `Mat` op underneath it when you want the raw knob.

:::note What this page is *not*
These are finishing effects — colour grades and stylisations. The structural, analysis-oriented
operations (blur families, edges, thresholds, morphology, colour-space conversions) live in
[Image processing](/image-processing) and [Filters as `Mat` ops](/low-level). A few overlap
(`blur`, `sharpen`, `invert` show up in both worlds) because a named "look" is free to reuse them.
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

// A raw BGR scene: dim background, a green square, a red disc. The caller owns the Mat.
def sceneMat(): Mat =
  val m = Mat(120, 160, CvType.CV_8UC3, CvScalar(60, 90, 140))
  Imgproc.rectangle(m, CvPoint(20, 20), CvPoint(70, 70), CvScalar(40, 200, 60), -1)
  Imgproc.circle(m, CvPoint(115, 60), 30, CvScalar(220, 80, 60), -1)
  m

// The same scene as an Image. A transform consumes it, so build a fresh one each call.
def scene(): Image = Image.wrap(Managed(sceneMat()))
```

## First example — one filter, one line

The shortest possible taste: take an image, apply a named look, write it out. `filter` consumes the
`Image` and hands back a new one; `write` is a terminal that encodes and releases.

```scala mdoc:compile-only
Image.reading("photo.jpg") { img =>
  img.filter(Filter.vintage).write("vintage.jpg")
}
```

Or, without a named filter, call the verbs directly and chain them — each one consumes the receiver and
returns the next `Image`:

```scala mdoc:silent
scene().temperature(0.4).saturate(1.2).gamma(0.9).close()
```

:::warning Move semantics still apply
A filter is a transform, so it **consumes** the image. `val warm = scene(); warm.saturate(1.2); warm.gamma(0.9)`
throws on the second call — `warm` was spent by `saturate`. Chain the calls, or take a
[`.copy`](/mat-lifecycle) first if you need to branch. In these runnable snippets every image is either
chained to a terminal or `.close()`d so nothing leaks.
:::

## Tone & colour

The everyday colour grade. `saturate` scales colourfulness — `0` gives a (still three-channel) grey,
`1.0` leaves it, above `1.0` is more vivid. `temperature` shifts the white balance: positive warms
(toward red), negative cools (toward blue), on a `-1..1` scale. `gamma` bends the mid-tones —
below `1.0` darkens, above brightens — without clipping the ends. `posterize` collapses each channel
to a handful of levels for a flat, screen-printed look.

```scala mdoc:silent
scene().saturate(1.4).close()     // more vivid
scene().saturate(0).close()       // grey, still 3-channel
scene().temperature(0.5).close()  // warm
scene().temperature(-0.5).close() // cool
scene().gamma(0.8).close()        // darker mids
scene().posterize(4).close()      // 4 levels per channel
```

The full tone-and-colour toolbox, all consuming transforms on `Image`:

| Verb | Effect | Argument | Notes |
| --- | --- | --- | --- |
| `saturate(factor)` | colourfulness | `factor >= 0` | `0` grey, `1` unchanged, `>1` vivid |
| `temperature(shift)` | white balance | `shift` in `[-1, 1]` | `>0` warm (red), `<0` cool (blue) |
| `gamma(g)` | mid-tones | `g > 0` | `<1` darkens, `>1` lifts; ends unclipped |
| `posterize(levels)` | flatten tones | `levels` in `[2, 256]` | per-channel banding |
| `adjust(brightness, contrast)` | linear level | `contrast` scales (1.0), `brightness` shifts (0) | saturating `convertScaleAbs` |
| `invert` | photographic negative | — | `255 − v` per channel |
| `sepia` | warm-brown wash | — | fixed 3×3 colour matrix |
| `emboss` | directional relief | — | 3×3 convolution |

`sepia` and `emboss` are the two matrix effects: `sepia` is the classic warm-brown wash (a fixed
colour matrix via `Core.transform`), `emboss` is a directional relief from a 3×3 convolution. Neither
takes an argument.

```scala mdoc:silent
scene().sepia.close()
scene().emboss.close()
```

`invert` and `adjust` round out the linear grades — `adjust` is one call for brightness *and* contrast,
where `contrast` multiplies and `brightness` adds:

```scala mdoc:silent
scene().invert.close()                            // negative
scene().adjust(brightness = 20, contrast = 1.3).close() // punchier
```

:::tip gamma vs adjust
`adjust` is a *linear* level change — it multiplies and adds, so it clips at both ends once it hits
`0` or `255`. `gamma` is a *curve*: it moves the mid-tones while leaving pure black and pure white
where they are, so it rarely clips. Reach for `gamma` when you want to open up shadows without blowing
the highlights.
:::

## Stylisation (the photo module)

These come from OpenCV's `photo` module — non-photorealistic rendering that treats the image as a
whole rather than pixel-by-pixel. `stylize` gives a smooth, saturated "cartoon" cast; `sketch` is a
pencil drawing; `enhance` (detail enhancement) sharpens local contrast; `edgePreserving` smooths
flat regions while keeping edges crisp.

```scala mdoc:silent
scene().stylize().close()        // painterly
scene().sketch().close()         // pencil drawing
scene().enhance().close()        // local detail boost
scene().edgePreserving().close() // smooth, edges kept
```

Each takes tuning parameters with sensible defaults. All four require an **8-bit, 3-channel** input
(the ordinary BGR image you get from `Image.read`); hand them a greyscale Mat and OpenCV throws.

| Verb | Effect | Signature (defaults) |
| --- | --- | --- |
| `stylize` | painterly cartoon | `stylize(strength: Float = 60, detail: Float = 0.45f)` |
| `sketch` | pencil drawing | `sketch(strength: Float = 60, detail: Float = 0.07f, shade: Float = 0.02f)` |
| `enhance` | local detail boost | `enhance(strength: Float = 10, detail: Float = 0.15f)` |
| `edgePreserving` | smooth flats, keep edges | `edgePreserving(strength: Float = 60, detail: Float = 0.4f)` |

`edgePreserving` is the quiet workhorse: it is the smoothing step underneath the painterly filters, and
on its own it is a gentle, edge-respecting denoise — useful before thresholding or contour finding when
you want to kill texture without softening boundaries.

```scala mdoc:silent
scene().stylize(strength = 80, detail = 0.3f).close() // more abstract
scene().enhance(strength = 30, detail = 0.2f).close() // stronger clarity
```

:::warning These are the slow ones
The `photo`-module effects (`stylize`, `sketch`, `enhance`, `edgePreserving`) run an edge-aware
solver over the whole image and are far heavier than the tone grades — easily tens of milliseconds on a
mid-size frame. They are fine for stills but think twice before putting one on a per-frame video path.
Measure with a [benchmark](/performance), don't guess.
:::

## Colormaps — data as colour

`colorMap` false-colours a single-channel image (a depth map, a motion field, a mask, any scalar
field) into a heatmap. The [`Colormap`](/image-api) enum names the choices; the perceptually-uniform
ones — `Viridis`, `Magma`, `Inferno`, `Plasma`, `Turbo` — are the honest pick for data, while `Jet`
is the classic-but-misleading rainbow.

```scala mdoc:silent
// A horizontal ramp stands in for a depth map: 0 on the left, 255 on the right.
def ramp(): Image =
  val m = Mat(60, 200, CvType.CV_8UC1)
  for x <- 0 until 200 do
    Imgproc.line(m, CvPoint(x, 0), CvPoint(x, 60), CvScalar(x * 255.0 / 200), 1)
  Image.wrap(Managed(m))

ramp().colorMap(Colormap.Viridis).close() // honest, perceptually uniform
ramp().colorMap(Colormap.Jet).close()     // the classic rainbow
```

The full palette — ten maps, matching OpenCV's `COLORMAP_*` set:

| Colormap | Family | Use for |
| --- | --- | --- |
| `Viridis` | perceptually uniform | the default honest choice for data |
| `Magma` | perceptually uniform | dark background, dramatic |
| `Inferno` | perceptually uniform | heat/energy fields (the `heatmap` filter uses it) |
| `Plasma` | perceptually uniform | bright, high-contrast data |
| `Turbo` | perceptually uniform | rainbow-like but corrected — safe |
| `Jet` | legacy rainbow | matching older tools; **misleading for data** |
| `Hot` | sequential | thermal imagery |
| `Bone` | sequential grey-blue | X-ray / medical look |
| `Ocean`, `Autumn` | sequential | stylistic |

```scala mdoc
Colormap.values.length
```

:::danger Jet lies
The classic `Jet` rainbow has bright bands (cyan, yellow) that read as *edges* your data doesn't have,
and it is unreadable in greyscale or to colour-blind viewers. Prefer `Viridis`/`Turbo` for anything a
person will draw a conclusion from; keep `Jet` for matching a legacy screenshot.
:::

`colorMap` expects a single channel. If you have a colour image, take it to grey first — that is
exactly what the built-in `heatmap` filter does (`_.gray.colorMap(Colormap.Inferno)`):

```scala mdoc:silent
scene().gray.colorMap(Colormap.Turbo).close()
```

## Repair & compositing

`inpaint` reconstructs a masked region from its surroundings — scratch removal, logo erasure. You
pass a single-channel mask that is white where the image should be repaired.

```scala mdoc:silent
val holed =
  Image.blank(80, 80, Scalar.White).drawRect(Rect(30, 30, 20, 20), Scalar.Black, Thickness.Filled)
val mask =
  Image.blank(80, 80, Scalar.Black, channels = 1).drawRect(Rect(30, 30, 20, 20), Scalar.White, Thickness.Filled)
holed.inpaint(mask).close()
mask.close()
```

:::warning The mask is borrowed; the receiver is consumed
`inpaint`, `seamlessCloneInto`, `applyMask` and `blend` all follow the same ownership rule: the
**receiver `Image` is consumed** (it becomes the result), but the mask / background you pass is
**borrowed** — scalacv does not close it for you. Close it yourself, as the snippets above and below do.
See [Mat lifecycle](/mat-lifecycle) for the full ownership model.
:::

`seamlessCloneInto` composites this image onto a background at a point, blending gradients so the
seam disappears (Poisson cloning). The result is background-sized.

```scala mdoc:silent
val patch = Image.blank(30, 30, Scalar(40, 60, 220))
val patchMask = Image.blank(30, 30, Scalar.White, channels = 1)
val background = Image.blank(120, 120, Scalar(180, 180, 180))
patch.seamlessCloneInto(background, patchMask, Point(60, 60)).close()
patchMask.close()
background.close()
```

`applyMask` keeps this image only where the mask is non-zero (the rest goes black), and `blend` is a
weighted average of two images — a cross-fade in one call:

```scala mdoc:silent
val base   = Image.blank(80, 80, Scalar(200, 120, 60))
val circle = Image.blank(80, 80, Scalar.Black, channels = 1).drawCircle(Point(40, 40), 30, Scalar.White, Thickness.Filled)
base.applyMask(circle).close() // keep a disc, black elsewhere
circle.close()

val white = Image.blank(160, 120, Scalar.White)
scene().blend(white, weight = 0.7).close() // 70% scene, 30% white — `white` is borrowed
white.close()
```

## Named, composable filters

A [`Filter`](/image-api) is a named `Image => Image` — a ready-made look you apply with
`image.filter(...)` or compose with `andThen`. The catalog is built from exactly the operations
above, so nothing here is magic; it is a curated set of starting points.

```scala mdoc:silent
scene().filter(Filter.vintage).close()
scene().filter(Filter.noir).close()
scene().filter(Filter.cartoon).close()
scene().filter(Filter.heatmap).close()
```

The full catalog, each spelled out as the recipe it actually runs:

| Filter | Recipe | Look |
| --- | --- | --- |
| `grayscale` | `saturate(0)` | grey, still 3-channel so it chains |
| `sepia` | `sepia` | warm brown |
| `invert` | `invert` | negative |
| `warm` | `temperature(0.5)` | warmer white balance |
| `cool` | `temperature(-0.5)` | cooler white balance |
| `vivid` | `saturate(1.5).enhance()` | punchy, high-clarity |
| `muted` | `saturate(0.6)` | desaturated, soft |
| `noir` | `saturate(0).adjust(contrast = 1.3).gamma(0.9)` | high-contrast black & white |
| `vintage` | `sepia.saturate(0.85).gamma(0.9)` | faded film |
| `cartoon` | `stylize()` | painterly |
| `sketch` | `sketch()` | pencil drawing |
| `posterize` | `posterize(6)` | screen-print banding |
| `emboss` | `emboss` | grey relief |
| `softBlur` | `blur(3)` | gentle Gaussian softening |
| `sharpen` | `sharpen()` | unsharp mask |
| `heatmap` | `gray.colorMap(Colormap.Inferno)` | false-colour heat |
| `dramatic` | `enhance(strength = 20).saturate(1.3)` | strong local contrast |

Because a filter is just a named transform, your own are first-class, and any two compose into a
third:

```scala mdoc:silent
val myLook = Filter("myLook")(_.temperature(0.3).saturate(1.2).gamma(0.9))
scene().filter(myLook).close()

val warmSketch = Filter.warm.andThen(Filter.sketch)
scene().filter(warmSketch).close()
```

:::tip `andThen` names the composite
`Filter.warm.andThen(Filter.sketch)` builds a new `Filter` whose `name` is `"warm+sketch"` — handy
when you print a filter picker or log which look was applied. `Filter("x")(f)` names anything: a filter
is nothing but a `String` and an `Image => Image`.
:::

`Filter.all` is every built-in look — handy for a contact sheet or a picker:

```scala mdoc
Filter.all.map(_.name).mkString(", ")
```

A contact sheet is then just "apply each filter to a copy and encode it":

```scala mdoc:silent
val sheet: Seq[(String, Either[CvError, Array[Byte]])] =
  Filter.all.map(f => f.name -> scene().filter(f).bytes(".png"))
```

## The mid-level ops

Each `Image` verb is a thin cover over a `Mat` extension op that returns an owned `Managed[Mat]`, for
when you want to stay on the low-level surface. The verb and the op share a name (with two exceptions
noted below), and the op *borrows* its receiver and hands back a fresh owned `Mat`:

```scala mdoc:silent
val src = sceneMat()
val warmed: Either[CvError, Array[Byte]] =
  src.temperature(0.5).use(Images.encode(_, ".png"))
val heat: Either[CvError, Array[Byte]] =
  src.colorMap(Colormap.Inferno).use(Images.encode(_, ".png"))
src.release()
```

| High-level `Image` verb | Mid-level `Mat` op | Note |
| --- | --- | --- |
| `saturate` / `temperature` / `gamma` / `posterize` | same names | pure `Managed[Mat]` result |
| `sepia` / `emboss` / `invert` (as `bitwiseNot`) | `sepia` / `emboss` / `bitwiseNot` | matrix / convolution |
| `stylize` / `enhance` / `edgePreserving` | `stylize` / `detailEnhance` / `edgePreserving` | `enhance` → `detailEnhance` |
| `sketch` | `pencilSketch` | different name mid-level |
| `colorMap` / `inpaint` / `seamlessCloneInto` | same names | as above |

Chain mid-level ops with [`pipe`](/low-level) so each intermediate is released the moment the next
stage consumes it — the same ownership discipline the high-level verbs give you for free:

```scala mdoc:silent
val graded = sceneMat()
val out: Either[CvError, Array[Byte]] =
  graded.temperature(0.4).pipe(_.saturate(1.2)).pipe(_.gamma(0.9)).use(Images.encode(_, ".png"))
graded.release()
```

:::note Optimise only with a benchmark
The house rule in [`CLAUDE.md`](/architecture) is *no optimisation without a benchmark delta and a
bit-identical output hash*. If you drop from a high-level verb to a hand-rolled mid-level chain for
speed, prove it with the [benchmark harness](/performance) — micro-seconds are machine-specific,
deltas reproduce.
:::

## Next

- [Image processing](/image-processing) — the structural operations (blur families, edges, thresholds, morphology) these looks are built on.
- [Filters as `Mat` ops](/low-level) — the mid-level surface, `pipe`, and the ownership contract in full.
- [Drawing & annotation](/drawing) — burn labels and boxes onto the graded result before you save it.
