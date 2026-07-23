# Image processing

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
OpenCv.load()
```

This is the operation catalogue: colour conversion, blurring, edges, histogram equalisation,
thresholding, resizing and blending. Each one comes at **two levels**, and the whole page is really
about knowing which to reach for:

- The **high-level** [`Image`](/image-api) API — verbs that chain (`gray`, `blur`, `canny`, …). Reach
  for it first; it frees every intermediate for you.
- The **mid-level** `Mat` extension ops in `Ops` — the same operations one step down, returning a
  caller-owned [`Managed[Mat]`](/mat-lifecycle). Reach for it when you need a knob `Image` does not
  surface, or when you are already working with raw `Mat`s.

Everything runs against a synthetic scene so no image file is needed:

```scala mdoc:invisible
import org.opencv.imgproc.Imgproc
import org.opencv.core.{Scalar => CvScalar, Point => CvPoint}

// A raw BGR Mat with a filled rectangle and circle — the caller owns it and must release it.
def bgr(): Mat =
  val m = Mat(120, 160, CvType.CV_8UC3, CvScalar(40, 40, 40))
  Imgproc.rectangle(m, CvPoint(20, 20), CvPoint(90, 100), CvScalar(220, 220, 220), -1)
  Imgproc.circle(m, CvPoint(125, 60), 24, CvScalar(255, 255, 255), -1)
  m

// The same scene as an Image (a transform consumes it, so build a fresh one each call).
def scene(): Image = Image.wrap(Managed(bgr()))
```

## The ownership contract, in one paragraph

Every mid-level op is **pure with respect to its receiver**: it allocates a fresh destination `Mat`,
writes the result there, and hands that back as a `Managed[Mat]` that **you now own and must
release**. The receiver is never written to, never freed, never aliased into the result — so an op is
safe on a borrowed `Mat` (a video frame, a detector's input) with no transfer-of-ownership ceremony.
There are no in-place variants. Because each stage returns its own `Managed`, a naive two-step
pipeline strands the intermediate; the combinators below exist to stop that.

## Colour conversion

Mid-level `cvtColor` takes a typed [`ColorConversion`](/geometry) — no raw `int` constants — and the
result's channel count follows the conversion, not the source:

```scala mdoc:silent
val srcCol = bgr()
val grayBytes: Either[CvError, Array[Byte]] =
  srcCol.cvtColor(ColorConversion.BgrToGray).use(Images.encode(_, ".png"))
srcCol.release()
```

High-level, `convert` is the same thing, and `gray` is the common `BgrToGray` case named:

```scala mdoc:silent
scene().convert(ColorConversion.BgrToHsv).close()
scene().gray.close()
```

## Blurring

`gaussianBlur` takes an odd, positive [`Size`](/geometry) kernel (or `Size(0, 0)` to derive it from
sigma); `blur` is the normalised box filter. Both return an owned `Managed[Mat]`:

```scala mdoc:silent
val srcBlur = bgr()
val soft: Either[CvError, Array[Byte]] =
  srcBlur.gaussianBlur(Size(5, 5), sigmaX = 1.5).use(Images.encode(_, ".png"))
val boxed: Either[CvError, Array[Byte]] =
  srcBlur.blur(Size(3, 3)).use(Images.encode(_, ".png"))
srcBlur.release()
```

High-level `blur(radius)` is the quick radius form — `radius` 2 is a 5×5 kernel, `radius` 0 is the
identity — with `gaussianBlur(kernel, sigmaX, sigmaY)` available when you want the full control:

```scala mdoc:silent
scene().blur(2).close()
scene().gaussianBlur(Size(5, 5), sigmaX = 1.5).close()
```

## Edges

`canny` always produces a `CV_8UC1` result regardless of the source type:

```scala mdoc:silent
val srcEdge = bgr()
val edges: Either[CvError, Array[Byte]] =
  srcEdge.cvtColor(ColorConversion.BgrToGray)
     .pipe(_.canny(60, 180))
     .use(Images.encode(_, ".png"))
srcEdge.release()
```

`scene().gray.canny(60, 180)` is the high-level equivalent.

### The Sobel depth trap

`sobel` takes a derivative order (`dx`, `dy`) and an [`OutputDepth`](/geometry). The default,
`SameAsSource`, is a trap on the commonest input: on an 8-bit unsigned image it clips every *negative*
derivative to zero, so half of each edge silently disappears. The fix is to compute into
`Signed16`, then bring it back to a displayable 8-bit image with `convertScaleAbs` (which scales,
takes the absolute value, and saturating-casts). `Mats.chain` threads that through cleanly:

```scala mdoc:silent
val srcSobel = bgr()
val gradientX: Either[CvError, Array[Byte]] =
  Mats.chain(srcSobel)(
    _.cvtColor(ColorConversion.BgrToGray),
    _.sobel(dx = 1, dy = 0, depth = OutputDepth.Signed16),
    _.convertScaleAbs()
  ).use(Images.encode(_, ".png"))
srcSobel.release()
```

`laplacian` has the same depth consideration and the same `Signed16` → `convertScaleAbs` remedy:

```scala mdoc:silent
val srcLap = bgr()
val lap: Either[CvError, Array[Byte]] =
  Mats.chain(srcLap)(
    _.cvtColor(ColorConversion.BgrToGray),
    _.laplacian(depth = OutputDepth.Signed16),
    _.convertScaleAbs()
  ).use(Images.encode(_, ".png"))
srcLap.release()
```

## Histogram equalisation

`equalizeHist` accepts `CV_8UC1` only, so it is normally preceded by a `gray` step:

```scala mdoc:silent
val flat = Mat(120, 160, CvType.CV_8UC1, CvScalar(90))
val stretched: Either[CvError, Array[Byte]] =
  flat.equalizeHist().use(Images.encode(_, ".png"))
flat.release()
```

High-level: `scene().gray.equalizeHist`.

## Thresholding

Mid-level `threshold` returns **both** the mask and a `ThresholdResult` carrying the `double` OpenCV
computed. For a fixed threshold that number is just the value you passed back; for the automatic
methods it is the threshold OpenCV *chose* — often the reason you called it. Select a method with
[`Threshold`](/geometry): a plain `Threshold.Mode`, or `Threshold.otsu(...)` / `Threshold.triangle(...)`:

```scala mdoc:silent
val gray = Mat(120, 160, CvType.CV_8UC1, CvScalar(90))
Imgproc.rectangle(gray, CvPoint(20, 20), CvPoint(90, 100), CvScalar(220), -1)
```

```scala mdoc
val (otsuMask, otsuResult) = gray.threshold(0, 255, Threshold.otsu())
otsuResult.value // the level Otsu picked
```

A fixed threshold is the same shape with the default `Binary` mode:

```scala mdoc:silent
val (mask, _) = gray.threshold(127, 255, Threshold(Threshold.Mode.BinaryInv))
mask.release()
```

```scala mdoc:invisible
otsuMask.release(); gray.release()
```

High-level `threshold` drops the computed value (the common "binarise" case). Reach for the mid-level
op above when you need the number an `Auto` method chose:

```scala mdoc:silent
scene().gray.threshold(127).close()
```

## Resize and scale

Mid-level distinguishes an absolute target `Size` (`resize`) from independent x/y factors (`scaled`),
each with a typed [`Interpolation`](/geometry):

```scala mdoc:silent
val srcResize = bgr()
val small: Either[CvError, Array[Byte]] =
  srcResize.resize(Size(80, 60)).use(Images.encode(_, ".png"))
val half: Either[CvError, Array[Byte]] =
  srcResize.scaled(0.5, 0.5, Interpolation.Area).use(Images.encode(_, ".png"))
srcResize.release()
```

High-level offers `resize(width, height)`, `resizeTo(size)` and `scale(factor)`:

```scala mdoc:silent
scene().resize(80, 60).close()
scene().scale(0.5).close()
```

## Blending: convertScaleAbs and addWeighted

`convertScaleAbs` (seen above with Sobel) also stands alone for brightness/contrast:
`self * alpha + beta`, saturating to 8-bit. `addWeighted` is the weighted sum of two images —
`self * alpha + other * beta + gamma` — with `other` borrowed, exactly like the receiver:

```scala mdoc:silent
val a = bgr()
val b = Mat(120, 160, CvType.CV_8UC3, CvScalar(10, 60, 10))
val brighter: Either[CvError, Array[Byte]] =
  a.convertScaleAbs(alpha = 1.2, beta = 20).use(Images.encode(_, ".png"))
val blended: Either[CvError, Array[Byte]] =
  a.addWeighted(0.7, b, 0.3).use(Images.encode(_, ".png"))
a.release(); b.release()
```

## Chaining without leaks

The single most important thing about the mid-level ops: because each returns its own owned
`Managed[Mat]`, writing a chain naively strands every intermediate. Two combinators make the
intermediates free themselves.

`pipe` is the two-stage form — it feeds the intermediate to the next stage and releases it once that
stage has produced its own output, so it can neither leak nor be used after the chain moves on
(touching it afterwards throws, rather than reading freed memory):

```scala mdoc:silent
val srcPipe = bgr()
val cannyBytes: Either[CvError, Array[Byte]] =
  srcPipe.gaussianBlur(Size(5, 5), 1.5)
     .pipe(_.canny(50, 150))
     .use(Images.encode(_, ".png"))
srcPipe.release()
```

`Mats.chain` is the n-stage form — a list of stages reads better than nested `pipe`s, and it releases
each intermediate as soon as the next stage consumes it (even if a stage throws). The source is
borrowed and never released; it belongs to whoever created it:

```scala mdoc:silent
val frame = bgr()
val result: Either[CvError, Array[Byte]] =
  Mats.chain(frame)(
    _.cvtColor(ColorConversion.BgrToGray),
    _.gaussianBlur(Size(5, 5), 1.5),
    _.canny(50, 150)
  ).use(Images.encode(_, ".png"))
frame.release()
```

`use` (or `Managed.use`) is the terminal when the last stage yields something other than a `Mat` — a
count, a `Seq`, an encoded byte array — with the same release guarantee.

The high-level chain gives you the identical property as move semantics rather than a combinator: each
transform consumes the `Image` it was called on, so a pipeline holds exactly one live `Mat` at a time
and never a pile of intermediates:

```scala mdoc:silent
scene().gray.equalizeHist.canny(80, 160).close()
```

That is the same guarantee as `Mats.chain`, surfaced as a type. See [Mat lifecycle](/mat-lifecycle)
for how `Managed` enforces release-exactly-once underneath both, the [Image API](/image-api) for the
full high-level surface, and the [Cookbook](/cookbook) for end-to-end recipes.
