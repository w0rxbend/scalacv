# Contours & shape analysis

A *contour* is the outline of a connected region in a binary image — the boundary curve that traces around
one solid blob of foreground. Once you have that outline as data you can ask the questions that matter for
most vision work: how big is this shape, where is its centre, how many corners does it have, does it enclose
a hole? `findContours` traces those outlines; scalacv hands them back as plain, immutable Scala data you can
measure, filter, and draw. Every snippet here is compiled by mdoc against the real library, so it cannot
drift out of date.

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc
OpenCv.load()
```

:::tip When you reach for contours
Contours are the workhorse of classical (non-neural) shape analysis: counting objects on a conveyor,
measuring blobs under a microscope, finding the paper in a document scan, isolating a coloured region after
[colour masking](/color-masking), turning a segmentation mask back into per-object boxes. If you can get your
subject to stand out as bright-on-dark (or dark-on-bright), contours turn that mask into countable,
measurable objects.
:::

## You need a binary image first

`findContours` works on a **single-channel 8-bit** image (`CV_8UC1`, or `CV_32SC1`): non-zero pixels are
foreground, zero is background. Anything else raises `CvError.NativeCall`. In practice you reach that shape
one of two ways:

- **Threshold** a greyscale image — everything brighter (or darker) than a cutoff becomes foreground.
- **Canny** it — the edge map is already `CV_8UC1`, so you can trace the edges directly.

At the high level both are one call, and `Image.contours` runs `findContours` for you:

```scala mdoc:compile-only
// Threshold to a mask, then trace the outlines.
Image.reading("coins.jpg")(_.gray.threshold(127).contours())

// Or trace Canny edges directly.
Image.reading("coins.jpg")(_.gray.canny(80, 160).contours())
```

:::note Threshold vs. Canny
Threshold gives you the outline of a **filled region** — one contour per blob, ready to measure area and
centroid. Canny gives you the outline of **edges** — you may get the outer boundary *and* an inner one for a
thick stroke, because both sides of the stroke are edges. For counting and measuring solid objects, threshold
first; for tracing thin structure, Canny. See [image processing](/image-processing) for both.
:::

The examples below skip the file and **draw their own binary images**, so they run against real pixels with
nothing to download. This helper paints two solid white shapes on black — a 100×50 rectangle and a circle:

```scala mdoc:silent
def shapes(): Mat =
  val m = Mat(120, 220, CvType.CV_8UC1, org.opencv.core.Scalar(0))
  Imgproc.rectangle(m, org.opencv.core.Point(20, 20), org.opencv.core.Point(119, 69),
    org.opencv.core.Scalar(255), -1) // thickness -1 = filled
  Imgproc.circle(m, org.opencv.core.Point(170, 60), 30, org.opencv.core.Scalar(255), -1)
  m
```

## Finding contours

`findContours` is a mid-level extension on `Mat`. It returns a `Seq[Contour]` — one entry per outline, in
OpenCV's order:

```scala mdoc
Managed.use(shapes())(_.findContours().size)
```

Two shapes, two contours. Note the `Managed.use`: the source `Mat` is a live native handle and must be
released. The `Seq[Contour]` it returns is **not** — read on.

### What comes back is safe to keep

`Contour` is ordinary immutable Scala data. `findContours` copies each outline's points across the native
boundary and frees the native buffers before it returns, so the result stays valid **after the source Mat is
released** — as here, where the `Managed.use` block has already freed the `Mat` by the time we measure:

```scala mdoc
val boxes: Seq[Rect] =
  Managed.use(shapes())(_.findContours().map(_.boundingRect))
boxes
```

This is deliberate, and it is the one place scalacv most visibly earns its keep. The raw OpenCV Java API
hands contours back as a `java.util.List[MatOfPoint]` — a list of **live native handles the caller is
expected to free individually**. Nothing in that signature says the list owns anything, and the handles
survive every reasonable-looking use of the result, so leaking them is the single most reliable leak in the
Java API. `findContours` releases every `MatOfPoint` (and the hierarchy `Mat`) in a `finally` block and gives
you copied-out data instead. There are no handles in a `Contour` to forget. (The same design runs through the
whole library — see [Mat lifecycle](/mat-lifecycle) for why.)

## Measuring a contour

Each `Contour` carries its `points: Seq[Point]` and computes its metrics lazily, delegating to OpenCV so the
numbers match what the rest of the ecosystem reports:

```scala mdoc
val rect: Contour =
  Managed.use(shapes())(_.findContours().head) // the rectangle
(rect.boundingRect, rect.area, rect.perimeter)
```

Here is the full surface of a `Contour`. Everything but `points` is lazy, so a contour you never measure never
pays for the native call:

| Member | Type | What it gives you |
|---|---|---|
| `points` | `Seq[Point]` | the outline vertices, sub-pixel `Double` (widened int32s from `findContours`) |
| `isEmpty` | `Boolean` | `true` only for a hand-built empty contour — `findContours` never emits one |
| `boundingRect` | `Rect` | the upright bounding box (`Imgproc.boundingRect`) |
| `area` | `Double` | enclosed area by the shoelace formula (`Imgproc.contourArea`), always ≥ 0 |
| `perimeter` | `Double` | closed arc length (`Imgproc.arcLength`, `closed = true`) |
| `centroid` | `Option[Point]` | centre of mass from image moments; `None` when it is undefined |
| `convexHull` | `Contour` | the tightest convex outline enclosing the points |
| `approx(epsilon, closed)` | `Contour` | Ramer–Douglas–Peucker simplification |

- **`boundingRect: Rect`** — the upright bounding box. OpenCV's box is *inclusive* of the extreme pixels, so a
  shape spanning x = 20..119 reports `width == 100`, not 99. (`Rect` itself is covered in
  [Geometry](/geometry).)
- **`area: Double`** — the enclosed area by the shoelace formula, **not** the filled pixel count. Area is
  measured between the *centres* of the boundary pixels, so our 100×50 filled rectangle reports 99 × 49 =
  4851, and never `boundingRect.area`. It is always non-negative.
- **`perimeter: Double`** — the closed arc length; contours from `findContours` are always closed curves.

`isEmpty` is `true` only for a `Contour` built by hand — `findContours` never emits one — and the metrics
short-circuit to zero rather than reaching native code in that case.

## More shape metrics: centroid, hull, simplification

Three further members answer the questions shape work most often asks after size.

### Centroid — where the shape *is*

`centroid` is the centre of mass, computed from image moments. It is an `Option` because it is undefined for
an empty contour or a zero-area one (a single point or a collinear run), where the division `m10/m00` would be
by zero:

```scala mdoc
Managed.use(shapes())(_.findContours().head.centroid).map(p => (p.x.round, p.y.round))
```

The rectangle spans x = 20..119 and y = 20..69, so its centre lands near `(70, 44)`. Centroids are how you
track an object frame to frame, sort blobs left-to-right, or place a label in the middle of a shape.

### Convex hull — the shape with its dents filled

`convexHull` returns a new `Contour`: the tightest convex outline enclosing the points. The vertices it
returns are *exactly* points of the original contour (OpenCV computes indices, which scalacv maps back), not
re-derived coordinates. A filled rectangle read with `None` approximation is a long chain of boundary pixels;
its hull is just the four corners:

```scala mdoc
Managed.use(shapes())(_.findContours(approximation = ContourApproximation.None).head.convexHull.points.size)
```

The hull is the standard way to measure *convexity* — compare `contour.area` to `contour.convexHull.area` and
a low ratio tells you the shape has deep concavities (fingers of a hand, teeth of a gear).

### `approx` — fewer vertices, same shape

`approx(epsilon)` runs Ramer–Douglas–Peucker simplification: it drops vertices, keeping none more than
`epsilon` pixels off the original outline. `epsilon` is usually a small fraction of the `perimeter`. This is
the classic "how many sides does this polygon have?" tool — a noisy quadrilateral collapses back to four
corners:

```scala mdoc:silent
val quad = Managed.use(shapes())(_.findContours(approximation = ContourApproximation.None).head)
```

```scala mdoc
quad.approx(0.02 * quad.perimeter).points.size
```

:::tip Counting sides
`approx(0.02 * perimeter).points.size` is the idiom behind shape classifiers: 3 → triangle, 4 → quad, and a
count that stays high as you shrink `epsilon` → a circle. Tune the `0.02` up to tolerate more noise, down to
keep more detail.
:::

## Retrieval: which contours you get

The first knob is `retrieval`, a `ContourRetrieval`. The default, `External`, returns only the *outermost*
outline of each region — what callers who ignore nesting almost always mean. The others also return the
boundaries of holes. Consider a square with a square hole punched out of it:

```scala mdoc:silent
def ring(): Mat =
  val m = Mat(120, 120, CvType.CV_8UC1, org.opencv.core.Scalar(0))
  Imgproc.rectangle(m, org.opencv.core.Point(20, 20), org.opencv.core.Point(99, 99),
    org.opencv.core.Scalar(255), -1)
  Imgproc.rectangle(m, org.opencv.core.Point(45, 45), org.opencv.core.Point(74, 74),
    org.opencv.core.Scalar(0), -1) // punch a hole
  m
```

```scala mdoc
Managed.use(ring())(_.findContours(retrieval = ContourRetrieval.External).size) // outer only
```

```scala mdoc
Managed.use(ring())(_.findContours(retrieval = ContourRetrieval.List).size) // outer + hole
```

| Mode | Returns | Nesting |
|---|---|---|
| `External` | the outermost outline of each region only (**default**) | ignored |
| `List` | every contour, including holes, as a flat list | flattened |
| `CComp` | every contour, organised two-level (outer boundaries + their holes) | two-level |
| `Tree` | every contour, in a full parent/child nesting tree | full tree |

:::note Hierarchy is not exposed (yet)
`CComp` and `Tree` compute a parent/child hierarchy, but scalacv does not currently surface it — handing back
OpenCV's raw `Nx1 CV_32SC4` index Mat would be exactly the untyped, unmanaged shape this library exists to
remove. A typed nesting API can be added later. Until then, choose between `External` and `List` unless you
only need the count.
:::

## Approximation: how the outline is compressed

The second knob is `approximation`, a `ContourApproximation`. The default, `Simple`, collapses straight runs
to their endpoints, so an axis-aligned rectangle comes back as its 4 corners. `None` keeps every pixel on the
boundary:

```scala mdoc
Managed.use(shapes())(_.findContours(approximation = ContourApproximation.Simple).head.points.size)
```

```scala mdoc
Managed.use(shapes())(_.findContours(approximation = ContourApproximation.None).head.points.size)
```

| Mode | Effect | Reach for it when |
|---|---|---|
| `Simple` | collapses straight runs to their endpoints (**default**) | corner-counting, polygon work, drawing |
| `None` | keeps every pixel on the boundary | you need the full pixel chain (arc-length precision, curvature) |
| `Tc89L1` | Teh–Chin chain approximation (L1) | you want a smoother poly than `Simple` gives |
| `Tc89Kcos` | Teh–Chin chain approximation (k-cosine) | as above, different corner metric |

`Simple` is what you want for corner-counting and polygon work; `None` when you need the full pixel chain.

## Filtering: keeping the shapes you want

Because the result is plain `Seq[Contour]`, the whole Scala collections API is available. The near-universal
first move is to throw away noise by area — tiny contours are almost always specks:

```scala mdoc
Managed.use(shapes())(_.findContours().map(_.area.round).sorted)
```

```scala mdoc
Managed.use(shapes())(_.findContours().count(_.area > 1000))
```

From there it composes like any other data: `filter` by `area` or `perimeter`, `sortBy(_.boundingRect.x)` to
order left-to-right, `maxBy(_.area)` to grab the largest object, `map(_.centroid)` to collect centres. None of
it touches native memory.

## Drawing contours back

`drawContours` is the renderer for what `findContours` returns — see [Drawing](/drawing) for the full set of
draw ops. Passed a stroke it outlines each contour; passed `Thickness.Filled` it fills them, which is the
usual way to rebuild a **mask** from a set of contours. Here we trace the shapes, then paint the outermost
ones solid into a fresh canvas and count the foreground pixels:

```scala mdoc
val filledPixels: Int =
  val cs = Managed.use(shapes())(_.findContours())
  Managed.use(Mat(120, 220, CvType.CV_8UC1, org.opencv.core.Scalar(0))) { mask =>
    mask.drawContours(cs, Scalar.White, Thickness.Filled)
    org.opencv.core.Core.countNonZero(mask)
  }
```

`drawContours` mutates the receiver and returns `Unit`; the mid-level version above takes a raw `Mat`. On an
`Image`, the same call is a transform that consumes and returns the image (`Thickness.Default` for outlines,
`Thickness.Filled` for a mask).

## The high-level view

Everything above is the mid-level `Mat` API. On an `Image`, `contours` is a **query** — it borrows the image
and returns plain data, leaving it alive for the next step (see [The Image API](/image-api)):

```scala mdoc:silent
val im = Image.wrap(Managed(shapes()))
```

```scala mdoc
im.contours(retrieval = ContourRetrieval.External).map(_.boundingRect)
```

```scala mdoc:invisible
im.close()
```

Because a query leaves the image alive, close it yourself when the surrounding work is done (or wrap the whole
thing in `Image.reading`, which closes for you). The full read → threshold → trace → annotate chain reads as
one line:

```scala mdoc:compile-only
Image.reading("parts.png") { img =>
  val cs = img.gray.threshold(127).contours()
  cs.filter(_.area > 500).map(_.boundingRect) // keep the big ones
}
```

:::warning `contours` borrows, transforms consume
`img.contours(...)` is a **query**: it leaves `img` alive, so you can keep chaining or must `close()` it
yourself. `img.gray`, `img.threshold(...)` and every `draw*` on an `Image` are **transforms**: they consume
the receiver and hand back a new image. Reusing a consumed `Image` throws — take a `.copy` first if you need
to branch. This distinction is the whole of [the Image API](/image-api).
:::

## Next

- [The Image API](/image-api) — queries, transforms, and lifetimes
- [Image processing](/image-processing) — threshold, Canny, and the mid-level ops that produce a binary image
- [Drawing](/drawing) — `drawContours`, `Thickness`, and the rest of the annotation surface
- [Geometry](/geometry) — `Rect`, `Point`, and the value types the metrics return
