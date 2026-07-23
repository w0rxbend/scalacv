# Hough transforms

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc
OpenCv.load()
```

The Hough line transforms find straight lines in a **binary edge image** — the kind of thing
[Canny](/image-processing) produces. OpenCV returns them as an anonymous multi-channel `Mat` whose element
type differs per transform and whose channels have no names; scalacv decodes each into a small, immutable
Scala case class so you never touch that `Mat` or guess at its layout.

Every snippet below runs against a synthetic edge image, so no fixture file is needed. All three transforms
require a non-empty 8-bit single-channel image ([`CV_8UC1`](/geometry)); handing them anything else fails
with a named precondition rather than a JNI abort.

## Two flavours

| Method | Returns | A line is… | Use when |
|---|---|---|---|
| `houghLines` | `Seq[PolarLine]` | **infinite**, in polar form `(rho, theta)` | you want the line's orientation/position, and endpoints are irrelevant (lane direction, dominant angle) |
| `houghLinesP` | `Seq[Segment]` | **finite**, with pixel endpoints `(x1, y1, x2, y2)` | you want to draw or measure the actual runs of edge (table edges, bar-code bars, wireframe sides) |

The probabilistic variant (`houghLinesP`) is the one most code wants: it reports where each line actually
starts and stops, is cheaper, and its output renders directly with [`drawSegments`](/drawing).

## Building an edge image to work with

```scala mdoc:silent
val edges = Mat(200, 200, CvType.CV_8UC1, org.opencv.core.Scalar(0))
// One horizontal and one vertical bright line — this stands in for a real edge map.
Imgproc.line(edges, org.opencv.core.Point(20, 30), org.opencv.core.Point(180, 30), org.opencv.core.Scalar(255), 1)
Imgproc.line(edges, org.opencv.core.Point(100, 10), org.opencv.core.Point(100, 190), org.opencv.core.Scalar(255), 1)
```

## Standard: infinite lines

`houghLines` reports each line in Hesse normal form. `rho` is the signed distance in pixels from the
image origin to the line; `theta` is the angle of the *normal* to the line, in radians — `0` is a vertical
line, `Pi/2` a horizontal one. `threshold` is the minimum number of accumulator votes (roughly, collinear
edge pixels) a line needs to be reported, and is the only argument without a sensible default.

```scala mdoc
edges.houghLines(threshold = 120)
```

`PolarLine(rho, theta)` carries `Float` fields because the underlying `Mat` for this transform is
`CV_32FC2` — a two-channel float. Reading it any other way would misinterpret the bits.

## Probabilistic: finite segments

`houghLinesP` gives real endpoints. `minLineLength` drops short segments and `maxLineGap` is the largest
break, in pixels, that will still be bridged into one segment.

```scala mdoc
edges.houghLinesP(threshold = 50, minLineLength = 50, maxLineGap = 5)
```

`Segment(x1, y1, x2, y2)` fields are `Int`, not `Float`, and this is not a rounding choice: the raw `Mat`
here is `CV_32SC4`, genuine int32 pixel coordinates. A float-typed read of that `Mat` throws, so scalacv
decodes it as integers. `Segment` also gives you `start`/`end` [`Point`](/geometry)s and a `length`, which
makes the near-universal "keep only the long ones" filter a one-liner:

```scala mdoc
edges.houghLinesP(threshold = 50).filter(_.length > 100).map(_.length)
```

## Keeping the vote counts

The plain transform returns lines already sorted by strength but discards the magnitudes.
`houghLinesWithAccumulator` keeps them, which is the only way to rank or threshold results yourself:

```scala mdoc
edges.houghLinesWithAccumulator(threshold = 120).take(3)
```

`PolarLineWithVotes(rho, theta, votes)` comes from a third `Mat` shape again — `CV_32FC3`, where the
third channel is the vote count. Call `.line` on one to drop the votes and get a plain `PolarLine`.

## The pipeline: edges first

Hough needs *edges*, not a raw image — feed it a photo and it detects nothing. The normal first step is
[`canny`](/image-processing), whose output is always `CV_8UC1` and so drops straight into any of the three
transforms. Here a filled square stands in for a scene; Canny turns it into its four-sided outline, which
`houghLinesP` recovers as segments:

```scala mdoc:silent
val shape = Mat(200, 200, CvType.CV_8UC1, org.opencv.core.Scalar(0))
Imgproc.rectangle(shape, org.opencv.core.Point(50, 50), org.opencv.core.Point(150, 150), org.opencv.core.Scalar(255), -1)
```

```scala mdoc
shape.canny(50, 150).use { outline =>
  outline.houghLinesP(threshold = 40, minLineLength = 40, maxLineGap = 10).size
}
```

`canny` returns a `Managed[Mat]`; its `use` borrows the edge image for the transform and releases it
afterwards, so the intermediate never leaks. See [the Image API](/image-api) for the same pipeline as a
single `Image` chain.

## Rendering the segments

`houghLinesP` results are invisible until you draw them. [`drawSegments`](/drawing) is their renderer —
it strokes each `Segment` into a Mat you own (it **mutates** that Mat, like every `draw*` op):

```scala mdoc:silent
val segments = edges.houghLinesP(threshold = 50, minLineLength = 50, maxLineGap = 5)

val canvas = Mat(200, 200, CvType.CV_8UC3, org.opencv.core.Scalar(0))
canvas.drawSegments(segments, Scalar.Red, Thickness.Stroke(2))
```

```scala mdoc
Images.encode(canvas, ".png").map(_.length)
```

```scala mdoc:invisible
edges.release(); shape.release(); canvas.release()
```

## See also

- [The Image API](/image-api) — the high-level `read → canny → detect → draw` chain
- [Image processing](/image-processing) — `canny` and the rest of the edge/filter surface
- [Drawing](/drawing) — `drawSegments` and the other annotation primitives
- [Geometry & typed values](/geometry) — `Point`, `Scalar`, `Thickness` and the pixel types
