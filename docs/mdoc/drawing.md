# Drawing & annotation

Annotating an image — a box round a detection, a label, a contour rendered back into a mask — is the
one place scalacv deliberately breaks its own rules. If you have ever wanted to draw a green rectangle
around a face, burn a timestamp into a recorded frame, or turn a `Seq[Contour]` back into a picture,
this is the page. Every snippet here is compiled by mdoc against the real library, so it cannot drift
out of date.

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
OpenCv.load()
```

## The shortest possible example

On an [`Image`](/image-api) drawing reads as a pipeline: each `draw*` call consumes the image, paints
into its Mat in place, and returns a fresh `Image` to chain from. End on a terminal (`bytes` or
`write`) that encodes and releases:

```scala mdoc:silent
val firstBytes: Either[CvError, Array[Byte]] =
  Image.blank(width = 200, height = 120)
    .drawRect(Rect(20, 20, 70, 60), Scalar.Green)
    .drawText("hi", Point(16, 110), Scalar.White)
    .bytes(".png")
```

That is the whole idea. The rest of this page explains *why* drawing mutates (and why that is safe),
the full set of primitives, and how to reach the low-level knobs the `Image` API hides.

## The mutation contract

Everywhere else in scalacv an operation leaves its input alone and hands back a fresh, caller-owned
result. **Drawing is the exception: every `draw*` and `fill*` op mutates the image it is given and
returns `Unit`.**

That is not an oversight, it is OpenCV. Its drawing functions have no out-of-place form — they rasterise
straight into the pixels you pass them. Pretending otherwise would mean cloning a whole frame for every
annotation, which is exactly what a per-frame overlay cannot afford. So scalacv makes the mutation
impossible to miss instead of hiding it: the names all say `draw`/`fill`, and the return type is `Unit`,
so no call site can mistake one for a pure transform.

There are two ways to reach these ops, and they sit at different levels:

- **Mid-level** — extension methods on a raw `org.opencv.core.Mat`. You own the `Mat`, you draw on it,
  you release it. This page uses them for the primitives.
- **High-level** — [`Image`](/image-api) draw methods. These wrap the same ops as *transforms*: they
  consume the `Image`, mutate its Mat in place (no copy), and hand back a new `Image` to chain from. See
  [the last section](#the-high-level-image-transforms).

:::note Two levels, one behaviour
The mid-level Mat op returns `Unit` and mutates in place. The high-level `Image` op returns a new
`Image` and *also* mutates in place — it just moves the Mat into the returned `Image` (no pixel copy)
so the pipeline can chain. Neither one copies the frame. The difference is who tracks ownership: you,
or the `Image`.
:::

Because the mid-level ops write into a Mat you allocated, the shape is always the same: **make a Mat,
draw on it, encode or inspect it, release it.** `Managed.use` does the release for you:

```scala mdoc:silent
val lineBytes: Either[CvError, Array[Byte]] =
  Managed.use(Mat.zeros(120, 200, CvType.CV_8UC3)) { canvas =>
    canvas.drawLine(Point(10, 10), Point(190, 110), Scalar.Red, Thickness.Stroke(2))
    Images.encode(canvas, ".png")
  }
```

An empty Mat (one with no allocated data) has nothing to draw into, so every op checks and throws
`IllegalArgumentException` rather than aborting in native code with an opaque message:

```scala mdoc:crash
Mat().drawLine(Point(0, 0), Point(10, 10), Scalar.White)
```

## The primitive catalog

Every drawing op at a glance — which level exposes it, and whether it can be filled:

| Op | On `Mat` | On `Image` | Thickness accepted |
| --- | :---: | :---: | --- |
| `drawLine` | ✅ | — | `Thickness.Stroke` only |
| `drawArrow` | ✅ | — | `Thickness.Stroke` only |
| `drawRect` | ✅ | ✅ | `Thickness` (`Filled` ok) |
| `drawCircle` | ✅ | ✅ | `Thickness` (`Filled` ok) |
| `drawText` | ✅ | ✅ | `Thickness.Stroke` only |
| `drawPolyline` | ✅ | — | `Thickness.Stroke` only |
| `fillPolygon` | ✅ | — | always filled |
| `drawContours` | ✅ | ✅ | `Thickness` (`Filled` ok) |
| `drawSegments` | ✅ | — | `Thickness.Stroke` only |
| `drawRects` | — | ✅ | `Thickness` (`Filled` ok) |

The rule behind the last column: only **closed shapes** can be filled. Lines, arrows, text and segments
accept `Thickness.Stroke` only, so the fill sentinel is not even expressible — the mistake stops
compiling instead of aborting native code (see [Style](#style-thickness-line-type-font-colour)).

## The primitives

### Line and arrow

```scala mdoc:silent
val arrowBytes =
  Managed.use(Mat.zeros(120, 200, CvType.CV_8UC3)) { canvas =>
    canvas.drawLine(Point(10, 10), Point(120, 90), Scalar.White)
    // An arrowhead at `to`; tipLength is a fraction of the whole line, so it stays in proportion.
    canvas.drawArrow(Point(10, 100), Point(180, 40), Scalar.Green, tipLength = 0.15)
    Images.encode(canvas, ".png")
  }
```

Coordinates that run off the edge of the image are clipped, not rejected — which is what makes drawing a
detection near a frame boundary safe. Lines and arrows accept `Thickness.Stroke` only: there is no such
thing as a filled line, and OpenCV aborts if you pass it the fill sentinel, so it does not compile.

`drawArrow`'s `tipLength` is a *fraction* of the line's length (OpenCV's own default is `0.1`), so the
head stays in proportion no matter how long the line — handy for motion/flow overlays where arrows vary
wildly in length.

### Rectangle

```scala mdoc:silent
val rectBytes =
  Managed.use(Mat.zeros(120, 200, CvType.CV_8UC3)) { canvas =>
    canvas.drawRect(Rect(20, 20, 80, 60), Scalar.Blue, Thickness.Stroke(2))
    // Thickness.Filled draws a solid block — a label background, or a piece of a mask.
    canvas.drawRect(Rect(120, 40, 50, 50), Scalar.Red, Thickness.Filled)
    Images.encode(canvas, ".png")
  }
```

### Circle

```scala mdoc:silent
val circleBytes =
  Managed.use(Mat.zeros(120, 200, CvType.CV_8UC3)) { canvas =>
    canvas.drawCircle(Point(60, 60), 40, Scalar.Green, Thickness.Stroke(2))
    canvas.drawCircle(Point(150, 60), 25, Scalar.White, Thickness.Filled)
    Images.encode(canvas, ".png")
  }
```

A negative radius is a programmer error, so it is rejected up front rather than passed to native code:

```scala mdoc:crash
Mat.zeros(50, 50, CvType.CV_8UC3).drawCircle(Point(25, 25), -5, Scalar.White)
```

### Text (and the baseline caveat)

`at` is **not** the top-left corner. OpenCV anchors text on the *baseline's left end*, so a `y` of `0`
puts almost the whole string above the image and draws nothing visible. Only the Hershey vector fonts
exist — there is no system-font rendering — and any non-ASCII character is drawn as `?`.

`Draw.textSize` measures a string with the same arguments `drawText` takes, so you can place or box it
before drawing. The metrics carry a separate `baseline`: a background box has to be `size.height +
baseline` tall to enclose the descenders of a `g` or `y`, and forgetting it clips them.

```scala mdoc:silent
val labelBytes =
  Managed.use(Mat.zeros(80, 260, CvType.CV_8UC3)) { canvas =>
    val text   = "faces: 3"
    val origin = Point(12, 48)
    val m      = Draw.textSize(text, scale = 0.8)

    // A filled backing box, tall enough to clear the descenders (height + baseline):
    val box = Rect(
      origin.x.toInt,
      (origin.y - m.size.height).toInt,
      m.size.width.toInt,
      (m.size.height + m.baseline).toInt
    )
    canvas.drawRect(box, Scalar.Black, Thickness.Filled)
    canvas.drawText(text, origin, Scalar.White, scale = 0.8)
    Images.encode(canvas, ".png")
  }
```

`Draw.textSize` is a plain query — it changes nothing, it just answers "how big is this string?" The
`baseline` comes back separately for the reason above:

```scala mdoc
Draw.textSize("gravity", scale = 1.0).baseline
```

:::tip A readable label needs a backing box
Text drawn straight onto a busy photo is unreadable where the background is the same colour. The
`textSize` → filled `Rect` → `drawText` sequence above is the standard "label with a plate behind it"
recipe. Add a pixel or two of padding to the box for breathing room.
:::

### Polyline and filled polygon

`drawPolyline` connects a run of points; `closed` (default `true`) controls whether the last point joins
back to the first. `fillPolygon` fills the outline solid — self-intersections resolve by the even-odd
rule. Both accept an empty point list as a no-op, because a polyline is often the output of a filter and
filtering everything away is a legitimate result, not an error.

```scala mdoc:silent
val polygonBytes =
  Managed.use(Mat.zeros(120, 200, CvType.CV_8UC3)) { canvas =>
    val triangle = Seq(Point(20, 100), Point(60, 20), Point(100, 100))
    canvas.drawPolyline(triangle, closed = true, color = Scalar.Green, thickness = Thickness.Stroke(2))
    canvas.fillPolygon(Seq(Point(120, 100), Point(160, 30), Point(190, 100)), Scalar.Blue)
    Images.encode(canvas, ".png")
  }
```

An empty list draws nothing rather than throwing — the "filtered everything out" case is legitimate:

```scala mdoc:silent
Managed.use(Mat.zeros(50, 50, CvType.CV_8UC3)) { canvas =>
  canvas.drawPolyline(Seq.empty, color = Scalar.White) // no-op, no error
}
```

## Rendering typed results

Two draw ops exist to make otherwise-invisible detector output visible: they turn the typed results of
`findContours` and the Hough transforms back into pixels.

### Contours

`drawContours` renders what [`findContours`](/contours) returns. `Thickness.Filled` fills them, which is
the usual way to turn a set of contours back into a mask.

```scala mdoc:silent
val contourBytes =
  Managed.use(Mat.zeros(100, 100, CvType.CV_8UC1)) { mask =>
    mask.drawRect(Rect(20, 20, 50, 40), Scalar.White, Thickness.Filled)
    val found = mask.findContours()            // Seq[Contour]

    Managed.use(Mat.zeros(100, 100, CvType.CV_8UC3)) { overlay =>
      overlay.drawContours(found, Scalar.Green, Thickness.Stroke(2))
      Images.encode(overlay, ".png")
    }
  }
```

### Segments

`drawSegments` renders what [`houghLinesP`](/hough) returns — a `Seq[Segment]` that is otherwise just
numbers.

```scala mdoc:silent
val segmentBytes =
  Managed.use(Mat.zeros(100, 100, CvType.CV_8UC1)) { edges =>
    edges.drawLine(Point(10, 50), Point(90, 50), Scalar.White)
    val segments = edges.houghLinesP(threshold = 20)   // Seq[Segment]

    Managed.use(Mat.zeros(100, 100, CvType.CV_8UC3)) { overlay =>
      overlay.drawSegments(segments, Scalar.Red, Thickness.Stroke(2))
      Images.encode(overlay, ".png")
    }
  }
```

### Many boxes in one call

Detector output is usually a *list* of boxes. On an `Image`, `drawRects` paints a whole `Seq[Rect]` in
one pass — one green box per detection, motion region, or ROI:

```scala mdoc:silent
val boxesBytes: Either[CvError, Array[Byte]] =
  Image.blank(200, 150)
    .drawRects(Seq(Rect(10, 10, 40, 40), Rect(80, 30, 50, 60)), Scalar.Green, Thickness.Stroke(2))
    .bytes(".png")
```

## Style: thickness, line type, font, colour

Four knobs are shared by the ops above, all with sensible defaults:

```scala mdoc:silent
val outline = Thickness.Stroke(2)   // an outline N pixels wide (N >= 1)
val solid   = Thickness.Filled      // a solid shape — closed shapes only
val edge    = LineType.AntiAliased  // smooth diagonals; also Connected4 / Connected8 (default)
val face    = Font.Duplex           // a Hershey vector font; Simplex is the default
val amber   = Scalar(0, 191, 255)   // channels are B, G, R — not R, G, B
```

- **`Thickness`** splits "how wide" from "filled" at the type level. `Stroke(n)` is an outline; `Filled`
  is a solid shape. OpenCV encodes filled as a thickness of `-1`, a sentinel ordinary arithmetic can
  produce by accident and one that aborts native code if handed to a line or to text. Splitting the two
  cases means the ops that *can* be filled accept `Thickness` while lines and text accept
  `Thickness.Stroke` only — the mistake stops compiling rather than crashing. `Thickness.Stroke(0)`
  throws (a stroke must be at least one pixel), so `Filled` is the only way to say "solid".
- **`LineType`** — `Connected8` (the default), `Connected4`, or `AntiAliased` for smooth edges.
- **`Font`** — the six Hershey fonts.
- **`Scalar`** is a pixel value in the Mat's channel order, and **OpenCV's default order is BGR, not
  RGB**. The named constants respect that: `Scalar.Red` is `Scalar(0, 0, 255)`. See
  [Geometry](/geometry) for the full value type.

### Line types

| `LineType` | When |
| --- | --- |
| `Connected8` | the default; 8-connected raster, good enough for most overlays |
| `Connected4` | 4-connected; slightly thinner diagonals |
| `AntiAliased` | smooth edges — use for text and any diagonal a human will look at closely |

### Fonts

All six Hershey vector fonts, the only fonts OpenCV can render:

| `Font` | Style |
| --- | --- |
| `Simplex` | plain sans (the default) |
| `Plain` | small, thin sans |
| `Duplex` | double-stroke sans, heavier |
| `Complex` | serif |
| `Triplex` | triple-stroke serif, heaviest |
| `Script` | handwriting-style |

### Named colours

| Constant | BGR value |
| --- | --- |
| `Scalar.White` | `(255, 255, 255)` |
| `Scalar.Black` | `(0, 0, 0)` |
| `Scalar.Red` | `(0, 0, 255)` |
| `Scalar.Green` | `(0, 255, 0)` |
| `Scalar.Blue` | `(255, 0, 0)` |

:::warning BGR, not RGB
`Scalar(255, 0, 0)` is **blue**, not red. OpenCV stores pixels in blue-green-red order, and `Scalar`
is a raw pixel value, so it inherits that order. When a colour comes out wrong, this is almost always
why. The named constants (`Scalar.Red` etc.) are the safe way to avoid thinking about it.
:::

## The high-level Image transforms

On an [`Image`](/image-api) the same drawing is exposed as chainable transforms: each one mutates the
image's Mat in place (no copy — the `Image` owns it) and hands back a new `Image`, so annotation reads
as one pipeline and ends in a terminal like `bytes` or `write`.

```scala mdoc:silent
val annotatedBytes: Either[CvError, Array[Byte]] =
  Image.blank(width = 200, height = 120)
    .drawRect(Rect(20, 20, 70, 60), Scalar.Green)
    .drawCircle(Point(150, 60), 30, Scalar.Red, Thickness.Filled)
    .drawText("scene", Point(16, 100), Scalar.White)
    .bytes(".png")
```

`Image` exposes the everyday subset — `drawRect`, `drawRects`, `drawCircle`, `drawText`, `drawContours` —
and drops the rarely-chained knobs (`lineType`, `font`, arrows, raw polylines). For those, borrow the
Mat with `.mat` and use the mid-level ops shown above; the image stays yours.

```scala mdoc:silent
val mixedBytes: Either[CvError, Array[Byte]] =
  val img = Image.blank(200, 120)
  img.mat.drawArrow(Point(10, 60), Point(120, 60), Scalar.Green) // mid-level knob on the borrowed Mat
  img.drawText("go", Point(130, 66), Scalar.White).bytes(".png") // back to the high-level pipeline
```

:::warning `.mat` borrows — don't close it
`img.mat` hands you the underlying `Mat` without transferring ownership. Draw on it, but let the
`Image` release it (via a terminal or `close()`); don't call `.release()` on the borrowed Mat yourself
or the `Image` is left pointing at freed memory. See [Mat lifecycle](/mat-lifecycle).
:::

### Domain overlays

The verbs above are the general-purpose ones. Detector modules add their own one-call overlays as
extension methods, each turning a typed result straight into pixels:

| Overlay | Renders | Lives in |
| --- | --- | --- |
| `markFaces(faces)` | a box per face + a dot per landmark | [object detection](/object-detection) |
| `drawSkeleton(pose)` | a stick figure from keypoints | [pose estimation](/pose-estimation) |
| `drawTracks(tracks)` | boxes with track IDs | [tracking](/tracking) |
| `drawMarkerAxes(...)` | 3-D axes on an AR marker | [marker AR](/marker-ar) |

`markFaces` is the one-call "show me what YuNet found": a box per face and a dot per landmark. It pairs
directly with [face detection](/object-detection).

```scala mdoc:compile-only
val detector: org.opencv.objdetect.FaceDetectorYN = ??? // from FaceDetect.create(model, size)

Image.reading("crowd.jpg") { img =>
  val found = img.faces(detector)      // a query: borrows, image stays alive
  img.markFaces(found).write("marked.jpg")
}
```

## Next

- [Contours](/contours) and [Hough transforms](/hough) — the typed detector output `drawContours` and `drawSegments` render.
- [Geometry](/geometry) — `Point`, `Rect`, `Size`, `Scalar` and the BGR value model.
- [Cookbook](/cookbook) — end-to-end recipes that read, detect and annotate in one pass.
