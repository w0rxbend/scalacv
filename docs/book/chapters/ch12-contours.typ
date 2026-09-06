#import "../lib/book.typ": *

#chapter("Contours and Shape Analysis", subtitle: [From a binary mask to measured objects you can count, sort and reject.])

A threshold answers one question, once per pixel: is this pixel foreground? That is a useful answer
and it is never the answer anyone asked for. The requirement that reached you was phrased about
things --- how many parts are on the tray, is any of them short of tolerance, which one is lying
skew, is the label centred on the box. Between the per-pixel answer and the per-object question sits
a step that most of classical vision turns on, and it has a name: tracing the boundary of every
connected region and keeping the boundary as data.

That boundary is a contour. Walk the edge of one white blob, one pixel at a time, all the way round
until you are back where you started, and write down where you went. What you get is an ordered,
closed ring of coordinates, and it changes what you are allowed to ask. A blob of pixels has no
area; a closed polygon does. A blob has no centre, no corner count, no orientation and no best-fit
box; a polygon has all four, and each of them is arithmetic rather than image processing. Once the
mask becomes a `Seq[Contour]`, the rest of the pipeline is Scala collections work.

The step is also where OpenCV's Java bindings are at their most treacherous. `Imgproc.findContours`
does not return anything; it fills a `java.util.List[MatOfPoint]` you pass in, plus a hierarchy
`Mat`. Every element of that list is a live native handle the caller is expected to free
individually, and nothing about the signature says so. The values behave impeccably --- you read
their points, you measure them, you print them --- while the process's resident set climbs by a
frame's worth of boundary chains every iteration of the loop. The library's own source calls it the
single most reliable leak in the OpenCV Java API, and that is the right description: it is the one
where doing the obvious thing leaks every time.

So `findContours` in this library copies the points across the native boundary, frees every
`MatOfPoint` and the hierarchy `Mat` before it returns, and hands back ordinary immutable Scala
data. The consequence is that this chapter's API is small: one call at each of the two levels, two
enums, and one case class with eight members. Everything else is arithmetic on plain values, and
none of it can crash your process.

The running example is a top-down camera over machined parts on a dark conveyor, lit so the metal
reads bright against the belt. By the end of the chapter it produces a `Seq[Part]` --- one entry per
object, with a position, a size and a shape verdict --- and an annotated frame to look at when the
numbers disagree with your eyes.

#sect("Getting a binary image to trace")

`findContours` accepts a single-channel 8-bit image (`CV_8UC1`, or `CV_32SC1`) and nothing else.
Non-zero is foreground, zero is background. Hand it a three-channel frame and OpenCV raises, which
this library reports as a `CvError.NativeCall` naming the operation `findContours` rather than as a
bare `CvException`; hand it a `Mat` with no data at all and you get an `IllegalArgumentException`
before any native call happens, because an empty input is a bug in your code, not a data-dependent
failure.

Two routes normally get you there, and they answer different questions. Thresholding gives you the
outline of a filled region: one contour per solid blob, with a meaningful area and centroid. Canny
gives you the outline of edges, so a thick stroke yields the outside of the stroke and the inside of
it --- two contours around what your eye calls one line. For counting and measuring solid objects,
threshold; for tracing thin structure, Canny.

#example([The mask the rest of the chapter measures. `copy` keeps the source frame alive for annotation later.])[
```scala
import scalacv.*

val mask: Image =
  frame.copy                                 // branch: `frame` stays usable
       .gray                                 // CV_8UC1, which findContours requires
       .threshold(200)                       // bright metal on a dark belt
       .morphology(MorphOp.Open, radius = 2) // erode then dilate: specks vanish
```
]

The opening is not decoration. A single stray bright pixel is a legitimate connected region, and
`findContours` will faithfully hand you a contour for it. `MorphOp.Open` erodes and then dilates, so
anything thinner than the structuring element disappears and everything else comes back to roughly
its original size --- two pixels of radius removes sensor speckle and JPEG ringing without touching
a part. Deleting noise here, in one pass over the mask, is cheaper than filtering a thousand
one-point contours afterwards.

#sect("Finding the contours")

At the high level, `contours` is a method on `Image`, and it is a query rather than a transform: it
borrows the image, returns plain data, and leaves the image alive for the next call. That is the
opposite of `gray`, `threshold` and every `draw*`, which consume the receiver.

At the mid level, `findContours` is an extension method on a raw `Mat` with the same two parameters
and the same defaults. `Image.contours` delegates straight to it.

#example([Two levels, one behaviour. The defaults are `External` and `Simple` in both.])[
```scala
// high level: a query on an Image, which stays alive afterwards
val cs: Seq[Contour] = mask.contours()

// mid level: an extension on the borrowed Mat, for when you are already there
val cs2: Seq[Contour] = mask.mat.findContours(
  retrieval = ContourRetrieval.External,
  approximation = ContourApproximation.Simple
)
```
]

Both return `Seq[Contour]` in OpenCV's own order, which is a scan-order artefact rather than a
promise --- sort by something you care about before you rely on the sequence. An image with no
foreground at all yields an empty `Seq`, not an error.

#memory[
The `Seq[Contour]` outlives the `Mat` it came from. `findContours` releases every `MatOfPoint` it
allocated in a `finally` block --- so a failure partway through frees what was already allocated,
and there is no handle left in the result to forget. Close the mask, close the image, return the
contours, cache them, hand them to another thread, measure them an hour later.
]

#sect("Retrieval: which outlines you get")

Picture a bright square with a square hole punched out of its middle, and inside that hole a smaller
bright square sitting free. Three boundaries exist in the mask: the outside of the big square, the
edge of the hole, and the outside of the small square. `retrieval` decides which of them come back
to you and how they are related.

#figure-table([The four retrieval modes. Nesting is computed for `CComp` and `Tree`, but not exposed.])[
#tbl(
  columns: (auto, 1fr, auto),
  [*Mode*], [*Returns*], [*Nesting*],
  [`External`], [the outermost outline of each region only --- the big square, and nothing inside it (*default*)], [ignored],
  [`List`], [every boundary, flat: outer, hole, and inner square, in one undifferentiated `Seq`], [flattened],
  [`CComp`], [every boundary, organised two-level: outer boundaries and their holes], [two-level],
  [`Tree`], [every boundary, in a full parent/child nesting tree], [full tree],
)
]

`External` is the default because it is what a caller who never mentions the hierarchy almost always
means: on a conveyor, one part is one contour. A hole in a washer is not a second part, and a
reflection inside the part is not a third --- switching to `List` on that tray silently doubles the
count.

#note[
The hierarchy arrives from OpenCV as an `Nx1 CV_32SC4` `Mat` of indices --- an untyped, unmanaged
structure of exactly the shape this library exists to remove --- so handing it back would undo the
point of copying the contours out. Until a typed nesting API exists, choose between `External` and
`List`, and use `Tree` only when the count itself is what you want.
]

#warning[
`area` measures the outline it is given, and an outline knows nothing about what is inside it. The
outer contour of a washer reports the area of a solid disc; the hole is not subtracted. If your
parts have holes and the area matters, retrieve with `List` and subtract the inner areas yourself,
or measure something the hole does not affect, such as the bounding box. `List` gives you outer
boundaries and holes in one flat `Seq` with no marker saying which is which, so pairing them up means
testing containment yourself --- workable for one hole per part, guesswork for a lace pattern.
]

#sect("Approximation: how the outline is stored")

The second parameter decides how much of the traced boundary is kept. A 40 × 30 filled rectangle has
$2 times (40 + 30) - 4 = 136$ pixels on its boundary, counting each corner once. `Simple`, the
default, collapses straight runs to their endpoints and stores that rectangle as four points.
`None` keeps all 136.

#figure-table("The four approximation modes.")[
#tbl(
  columns: (auto, 1fr, 1fr),
  [*Mode*], [*Effect*], [*Reach for it when*],
  [`Simple`], [collapses straight runs to their endpoints (*default*)], [corner counting, polygon work, drawing],
  [`None`], [keeps every pixel on the boundary], [curvature, arc-length precision, sub-shape analysis],
  [`Tc89L1`], [Teh--Chin chain approximation, L1 metric], [you want a smoother polygon than `Simple` gives],
  [`Tc89Kcos`], [Teh--Chin chain approximation, k-cosine metric], [as above, with a different corner metric],
)
]

The compression is not only about memory, though on a frame full of blobs the difference between
four points and several hundred per object is real. It changes what a vertex count means: `Simple`
has already done most of a shape classifier's work on axis-aligned figures --- which is exactly why
`approx` exists for the ones it has not.

#sect("What a Contour carries")

`Contour` is a `final case class` with a single field --- `points: Seq[Point]` --- and seven derived
members. The five measurements are `lazy val`s, computed on first read and then cached, so
`filter(_.area > 400).sortBy(_.area)` measures each contour once rather than twice, and a contour
you discard on a cheaper test never pays for a native call at all. Only `isEmpty`, which reads the
points and nothing else, and `approx`, which takes an argument, are plain `def`s.

#figure-table([The whole of `Contour`. Nothing here holds a native handle.])[
#tbl(
  columns: (auto, auto, 1fr),
  [*Member*], [*Type*], [*What it gives you*],
  [`points`], [`Seq[Point]`], [the outline vertices, in order; whole numbers widened to `Double`],
  [`isEmpty`], [`Boolean`], [true only for a hand-built empty contour; `findContours` never emits one],
  [`boundingRect`], [`Rect`], [the upright bounding box, from `Imgproc.boundingRect`],
  [`area`], [`Double`], [enclosed area by the shoelace formula; always non-negative],
  [`perimeter`], [`Double`], [closed arc length, from `Imgproc.arcLength` with `closed = true`],
  [`centroid`], [`Option[Point]`], [centre of mass from image moments; `None` where undefined],
  [`convexHull`], [`Contour`], [the tightest convex outline enclosing these points],
  [`approx(epsilon, closed)`], [`Contour`], [Ramer--Douglas--Peucker simplification],
)
]

`points` comes back as whole numbers even though `Point` carries `Double` fields: `findContours`
produces `CV_32SC2`, so every coordinate is an integer that happens to have been widened. The
`Double` is there because the same `Point` type serves feature and pose work, where sub-pixel
positions matter.

`centroid` is an `Option` for a reason worth stating, because it is the member people most often
`get` without thinking. It divides the first-order moments by `m00`, the zeroth moment, which is the
enclosed area. A contour of three collinear points, or of a single pixel, encloses nothing; `m00` is
zero and the centroid is genuinely undefined rather than merely awkward. The `Option` is that fact,
not defensive padding.

`convexHull` returns a new `Contour` whose vertices are *exactly* points of the original ---
`Imgproc.convexHull` computes indices, which the library maps back through `points` rather than
re-deriving coordinates. For an L-shaped outline the hull drops the one reflex vertex that pokes
inward and fills the notch, so the hull's area is always at least the contour's own; the ratio of
the two is a usable measure of how dented a shape is.

#sidebar("Why a 40 × 30 rectangle has an area of 1131")[
Fill a 40 × 30 block of pixels, trace it, and `area` reports 1131, not 1200. This looks like an
off-by-one and is not. `Imgproc.contourArea` applies the shoelace formula to the polygon whose
vertices are the *centres* of the boundary pixels, and the centres of a 40-wide block span 39
pixels, not 40. So the polygon is 39 × 29 = 1131, and the perimeter is $2 times (39 + 29) = 136$ ---
the same 136 the `None` chain reports as a point count, and that is an identity rather than a
coincidence: the chain holds $2 times (w + h) - 4$ pixels, the centre polygon measures
$2 times ((w - 1) + (h - 1))$, and those are the same expression rearranged. Area is the measurement
that shrinks under the centres convention; perimeter comes out unchanged.

`boundingRect` uses the other convention: OpenCV's box is inclusive of the extreme pixels, so the
same shape reports `width == 40`. That is why the library delegates to `Imgproc.boundingRect` rather
than taking a minimum and maximum over the points in Scala --- re-deriving the convention by hand is
how two definitions drift apart over a release or two.

The practical rule: `area` is a measurement, `boundingRect.area` is an envelope, and they will never
be equal even for a perfect rectangle. Compare like with like, and never use one as a sanity check on
the other.
]

#sect("Measuring: four numbers and four ratios")

The four direct measurements --- area, perimeter, bounding box and centroid --- are the raw
material, and they are where `Contour` stops. What survives contact with a real production line is
usually a ratio of two of them, because a ratio is dimensionless: it does not change when the camera
moves 50 mm closer to the belt, and it does not need recalibrating when you swap a lens. The library
wraps no such ratio, and it does not need to --- each is two or three lines over members it already
has, and an `extension` block puts them on `Contour` as though they had shipped with it.

#example([The four ratios. None of these is library API; this block is the whole of their definition.])[
```scala
extension (c: Contour)

  /** Bounding-box width over height. 1.0 for a square or a circle,
    * large for a bar lying along x, small for the same bar upright. */
  def aspectRatio: Double =
    val box = c.boundingRect
    if box.height == 0 then 0.0 else box.width.toDouble / box.height

  /** How much of its own bounding box the shape fills. A rectangle
    * approaches 1.0, a circle about 0.785, a diagonal bar under 0.5. */
  def extent: Double =
    val box = c.boundingRect.area
    if box == 0L then 0.0 else c.area / box.toDouble

  /** Area over convex-hull area. 1.0 for a convex shape; low means
    * deep concavities — a gear's teeth, a hand's fingers, two parts
    * that have been welded into one blob by the threshold. */
  def solidity: Double =
    val hull = c.convexHull.area
    if hull == 0.0 then 0.0 else c.area / hull

  /** 4·pi·area / perimeter². 1.0 for a perfect circle, about 0.785
    * for a square, and falling fast as the outline gets ragged. */
  def circularity: Double =
    if c.perimeter == 0.0 then 0.0 else 4 * math.Pi * c.area / (c.perimeter * c.perimeter)
```
]

Each earns its keep somewhere different. `aspectRatio` separates a bar from a disc with no shape
fitting at all. `extent` catches shapes that lie diagonally, which the upright box cannot express ---
the cheapest possible "is this thing skew?" test. `solidity` is the standard detector for two
objects touching: two discs that overlap in the mask make one contour whose hull spans both, and the
ratio drops through the floor while area and bounding box both still look plausible. `circularity`
answers "round or not" without counting vertices, and degrades gracefully as an outline gets ragged
instead of failing outright.

#tip[
`centroid` and `boundingRect` disagree for any shape that is not symmetric, and the disagreement is
information. The centre of the bounding box is where the object *fits*; the centroid is where its
mass *is*. For a pick-and-place target you want the centroid. For a crop rectangle you want the box.
]

#sect("Classifying by vertex count")

Ask how many sides a shape has and the naive move is to count `points`. On a `Simple` contour of a
clean axis-aligned rectangle that even works, which is unfortunate, because it means the technique
survives the first test and fails on the first rotated part. A rotated rectangle's edges are
staircases of pixels, and `Simple` cannot collapse a staircase: it reports dozens of vertices for a
four-sided shape.

`approx` is the fix. It runs Ramer--Douglas--Peucker simplification: drop as many vertices as
possible, subject to no point of the original outline lying more than `epsilon` pixels away from the
simplified one. Set `epsilon` well and a staircase collapses back to the four corners it was always
meant to be.

The mistake to avoid is a fixed `epsilon`:

```scala
c.approx(3.0).points.size   // wrong: 3 px is loose on a 40 px part, tight on a 400 px one
```

Three pixels of tolerance erases real corners on a small part and leaves every staircase step on a
large one. Since `epsilon` is a distance that must scale with the shape, take it as a fraction of
the perimeter:

#example("Vertex counting that works at any scale. 0.02 is the usual starting point.")[
```scala
def sides(c: Contour): Int = c.approx(0.02 * c.perimeter).points.size

def classify(c: Contour): String = sides(c) match
  case 3 => "triangle"
  case 4 => if c.aspectRatio > 0.95 && c.aspectRatio < 1.05 then "square" else "rectangle"
  case 5 => "pentagon"
  case 6 => "hexagon"
  case n if n > 6 && c.circularity > 0.85 => "circle"
  case _ => "unknown"
```
]

Two per cent of the perimeter is the rule of thumb, and it is only that. Raise it towards 0.04 when
the mask is ragged and you keep getting spurious corners; drop it towards 0.01 when genuine shallow
corners are being smoothed away. `approx` rejects a negative `epsilon` with an
`IllegalArgumentException` rather than passing it to OpenCV, and `closed` defaults to `true`, which
is right for anything `findContours` produced --- those are always closed curves, and asking for the
open length of one silently drops the final edge.

The circle case is worth its own note. A circle has no vertices to count; what it has is a vertex
count that stays high as you shrink `epsilon`, because there is no straight run anywhere to
collapse. Pairing that with high `circularity` is more robust than either test alone.

#sect("Filtering: the highest-value line in the pipeline")

Here is the whole detection, written the way it is written the first time:

```scala
val parts = mask.contours()   // and every dust mote on the belt is now a "part"
```

A three-pixel speck is a connected region. So is the corner of a reflection, so is a scratch on the
belt, so is one hot pixel. The count is wrong, the statistics are wrong, and the failure is silent
because a `Seq` of the wrong length looks exactly like a `Seq` of the right one.

One clause fixes it:

#example("Reject by area first. Everything downstream gets cheaper and more honest.")[
```scala
val parts = mask.contours().filter(_.area >= 400)
```
]

Pick the threshold from the smallest object you must not miss, then halve it. A part 30 × 30 pixels
across at the working distance encloses roughly 900 px², so 400 leaves margin for a partly occluded
one while still deleting anything the optics could not have resolved. Area is the right axis for the
test because it falls as the square of the linear size: a speck one tenth the diameter of a part is
one hundredth of its area, so the two populations sit either side of a wide, uncrowded gap.

Because the result is a plain `Seq`, the rest is the standard library: `sortBy(_.boundingRect.x)`
orders objects left to right along the belt, `maxBy(_.area)` grabs the largest,
`flatMap(_.centroid)` collects the positions that exist, `partition` splits pass from fail. None of
it touches native memory, so none of it can be got wrong in a way that costs you a process.

#sect("Fitting a shape that is not upright")

`boundingRect` is axis-aligned, which is a poor description of a part lying at 30°: the box is far
larger than the object and its width and height say more about the angle than about the part. When
orientation matters, fit a shape instead of enclosing one. OpenCV has three fits for this, and
scalacv does not wrap them --- so this is a good place to use the escape hatch the library keeps
open on purpose.

#figure-table([The three fits, straight from `Imgproc`. All three take a `MatOfPoint2f`.])[
#tbl(
  columns: (auto, auto, 1fr),
  [*Call*], [*Returns*], [*Use it when*],
  [`minAreaRect`], [`RotatedRect`], [the object is rectangular and rotated: gives centre, size and angle],
  [`minEnclosingCircle`], [centre and radius, via out-parameters], [you need a bounding radius, or a rotation-invariant size],
  [`fitEllipse`], [`RotatedRect`], [the object is round-ish and you want its axes and tilt (needs at least 5 points)],
)
]

`RotatedRect` is a plain Java object with `center`, `size` and `angle` fields and no native handle,
so once you have one you can keep it. Getting there means building a `MatOfPoint2f`, and that
*is* native memory.

#example([Fitting a rotated box. `Managed` owns the point buffer; `Cv.orThrow` names the call if OpenCV raises.])[
```scala
import org.opencv.core.MatOfPoint2f
import org.opencv.imgproc.Imgproc

def minAreaBox(c: Contour): org.opencv.core.RotatedRect =
  val pts = c.points.map(p => org.opencv.core.Point(p.x, p.y))
  Managed(MatOfPoint2f(pts*)).use: m =>
    Cv.orThrow("minAreaRect")(Imgproc.minAreaRect(m))

// the tilt of a part, in degrees, from the fitted box
def tilt(c: Contour): Double = minAreaBox(c).angle
```
]

#memory[
`MatOfPoint2f` is a `Mat`. Constructing one allocates off-heap, and OpenCV's fitting functions take
ownership of nothing --- the buffer is still yours when the call returns. `Managed(...).use` frees it
on the way out, including when the native call throws, which is exactly the path a hand-written
`release()` at the end of the method misses. It is the pattern `Contour`'s own members use
internally: each materialises the points as a native Mat --- a `MatOfPoint` for `boundingRect`, `area`
and `centroid`, a `MatOfPoint2f` for `perimeter` and `approx`, because `arcLength` and `approxPolyDP`
accept nothing else --- measures it, and frees it before returning plain Scala data.
]

The library reaches for `minAreaRect` itself in `deskew`, with one difference worth copying. It does
not fit the box to a contour: it binarises the page with an inverted Otsu threshold, collects every
ink pixel with `Core.findNonZero`, and fits the rectangle to that cloud, because a page of text is
hundreds of separate blobs with no single outline to trace. The fitted `angle` is the tilt, and
OpenCV's convention for it is not intuitive --- the angle lives in a quarter-turn range, so a
rectangle at 5° and the same rectangle at 95° can report the same number. `deskew` folds it into a
tilt of at most 45° either way before rotating, and treats anything past its `maxAngle` (45° by
default) as a misread. Orientation logic built on `minAreaRect` needs both.

#sect("Drawing the result back")

Numbers agree with each other far more readily than they agree with reality. The fastest way to find
out whether your contours are the ones you think they are is to paint them onto the original frame
and look.

`drawContours` exists at both levels. On a `Mat` it mutates the receiver and returns `Unit`; on an
`Image` it is a transform, so it consumes the image and hands back a new one. `Thickness.Default` is
a one-pixel outline, `Thickness.Stroke(2)` is wider, and `Thickness.Filled` fills them --- which is
the usual way to turn a set of contours back into a mask after you have filtered it.

#example([Annotating the original frame. `drawRects` and `drawText` are transforms too, so this chains.])[
```scala
val kept = mask.contours().filter(_.area >= 400)

// each call consumes the image and returns the next one; `write` releases
frame.drawContours(kept, Scalar.Green, Thickness.Stroke(2))
     .drawRects(kept.map(_.boundingRect), Scalar.Red)
     .drawText(s"${kept.size} parts", Point(10, 30), Scalar.White, scale = 0.8)
     .write("tray-annotated.png")
```
]

Note the colours. OpenCV `Mat`s are BGR, so `Scalar.Red` is `Scalar(0, 0, 255)`. The library's
constants are ordered correctly, but the moment you write a literal by hand you are in BGR, and the
mistake looks like a rendering bug rather than a channel-order one.

#memory[
`drawContours` is not leak-free: the generated Java binding strands one `Mat` per polygon on the way
in, upstream of anything the library can reach. It is bounded per call and unbounded across a video
loop, so pass the whole `Seq` to one call rather than looping and calling it once per contour ---
fifty contours in one call, not fifty calls. Chapter 14, #emph[Drawing and Annotation], has the full
accounting.
]

#sect("The whole pass")

Everything above, as one function. It takes a frame, returns measured objects, and leaves the caller
holding nothing that needs freeing.

#example([Mask to measured parts. The `Seq[Part]` outlives every native object in the function.])[
```scala
import scalacv.*

final case class Part(
    id: Int,
    centre: Point,
    box: Rect,
    areaPx: Double,
    tiltDegrees: Double,
    shape: String
)

def inspect(frame: Image, minArea: Double = 400.0): Seq[Part] =
  val mask = frame.copy.gray.threshold(200).morphology(MorphOp.Open, radius = 2)
  try
    mask.contours()                       // External + Simple
        .filter(_.area >= minArea)        // drop specks before measuring anything
        .sortBy(_.boundingRect.x)         // left to right along the belt
        .zipWithIndex
        .flatMap { (c, i) =>
          c.centroid.map { centre =>     // no centroid, no part: it enclosed nothing
            Part(
              id = i,
              centre = centre,
              box = c.boundingRect,
              areaPx = c.area,
              tiltDegrees = minAreaBox(c).angle,
              shape = classify(c)
            )
          }
        }
  finally mask.close()                    // the query borrowed it; closing is yours
```
]

The end of that function is where the ownership rules land. `contours` borrows the mask and leaves
it alive --- unlike `gray` and `threshold` above it, which consumed their receivers and are already
gone --- so closing is the caller's job, and `close()` is idempotent, which makes the `finally` safe
even on paths that never reached the trace. `frame` is untouched, because `copy` branched at the
top so the caller can annotate the original afterwards.

The reverse of that reasoning is the failure mode. Drop the `copy` and `gray` consumes `frame`
itself; the annotation call then throws `IllegalStateException` from `Managed` --- in Scala, before
anything reaches JNI, with a message that tells you the image was spent by a transform and suggests
`.copy`. Run with `-Dscalacv.trackOwnership=true` and it will also point at the line that consumed
it.

Wrap the whole thing in `Image.reading` and the frame is closed for you on every path, including the
exceptional ones:

```scala
Image.reading("tray-0417.png")(frame => inspect(frame))
```

#sect("Where this goes next")

Contours find things that are solid. They are the right tool when your subject is a region --- a
part, a blob, a cell, a coloured patch --- and the wrong one when it is a line that was never filled
in: a lane marking, a wire, the edge of a page against a desk. A line has no interior to trace, and
thresholding one produces a ribbon whose contour describes the ribbon rather than the line. The
Hough transform is the classical answer to that, and it is what the next chapter takes up:
`houghLines` and its `PolarLine` results, `houghLinesP` returning drawable `Segment` values, and
`houghLinesWithAccumulator` for when the vote count itself is the evidence you need.

The drawing calls used here to check the work --- `drawContours`, `drawRects`, `drawText`, the
`Thickness` type and the BGR ordering that catches everyone once --- get their full treatment in
Chapter 14, along with text measurement and why the whole of it lives in `core` rather than in a GUI
module.
