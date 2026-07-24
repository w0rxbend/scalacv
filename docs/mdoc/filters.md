# Photo filters & colormaps

This page is about *looks*: the tone, colour and stylisation effects you reach for to finish an image
— a warm cast, a sepia wash, a pencil sketch, a false-colour heatmap of a depth map — and the
[`Filter`](/image-api) type that names and composes them. Every effect is a high-level
[`Image`](/image-api) verb that consumes the image it is called on and frees every intermediate, with
the mid-level `Mat` op underneath it when you want the raw knob.

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

`sepia` and `emboss` are the two matrix effects: `sepia` is the classic warm-brown wash (a fixed
colour matrix via `Core.transform`), `emboss` is a directional relief from a 3×3 convolution.

```scala mdoc:silent
scene().sepia.close()
scene().emboss.close()
```

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

Because a filter is just a named transform, your own are first-class, and any two compose into a
third:

```scala mdoc:silent
val myLook = Filter("myLook")(_.temperature(0.3).saturate(1.2).gamma(0.9))
scene().filter(myLook).close()

val warmSketch = Filter.warm.andThen(Filter.sketch)
scene().filter(warmSketch).close()
```

`Filter.all` is every built-in look — handy for a contact sheet or a picker:

```scala mdoc:silent
{
  Filter.all.map(_.name).mkString(", ")
}
```

## The mid-level ops

Each `Image` verb is a thin cover over a `Mat` extension op that returns an owned `Managed[Mat]`, for
when you want to stay on the low-level surface:

```scala mdoc:silent
val src = sceneMat()
val warmed: Either[CvError, Array[Byte]] =
  src.temperature(0.5).use(Images.encode(_, ".png"))
val heat: Either[CvError, Array[Byte]] =
  src.colorMap(Colormap.Inferno).use(Images.encode(_, ".png"))
src.release()
```
