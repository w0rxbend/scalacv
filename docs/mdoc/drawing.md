# Drawing & annotation

Annotating an image — a box round a detection, a label, a contour rendered back into a mask — is the
one place scalacv deliberately breaks its own rules. Every snippet here is compiled by mdoc against the
real library, so it cannot drift out of date.

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
OpenCv.load()
```

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
`IllegalArgumentException` rather than aborting in native code with an opaque message.

## The primitives

### Line

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
  `Thickness.Stroke` only — the mistake stops compiling rather than crashing.
- **`LineType`** — `Connected8` (the default), `Connected4`, or `AntiAliased` for smooth edges.
- **`Font`** — the six Hershey fonts (`Simplex`, `Plain`, `Duplex`, `Complex`, `Triplex`, `Script`).
- **`Scalar`** is a pixel value in the Mat's channel order, and **OpenCV's default order is BGR, not
  RGB**. The named constants respect that: `Scalar.Red` is `Scalar(0, 0, 255)`. `Scalar.White`,
  `Scalar.Black`, `Scalar.Red`, `Scalar.Green` and `Scalar.Blue` cover the common cases; see
  [Geometry](/geometry) for the full value type.

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

`Image` exposes the everyday subset — `drawRect`, `drawCircle`, `drawText`, `drawContours` — and drops
the rarely-chained knobs (`lineType`, `font`, arrows, raw polylines). For those, borrow the Mat with
`.mat` and use the mid-level ops shown above; the image stays yours.

`markFaces` is the one-call "show me what YuNet found": a box per face and a dot per landmark. It pairs
directly with [face detection](/object-detection).

```scala mdoc:compile-only
val detector: org.opencv.objdetect.FaceDetectorYN = ??? // from FaceDetect.create(model, size)

Image.reading("crowd.jpg") { img =>
  val found = img.faces(detector)      // a query: borrows, image stays alive
  img.markFaces(found).write("marked.jpg")
}
```

See the [Cookbook](/cookbook) for end-to-end recipes that read, detect and annotate in one pass.
