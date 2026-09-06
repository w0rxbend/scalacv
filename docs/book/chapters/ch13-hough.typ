#import "../lib/book.typ": *

#chapter("Finding Lines and Circles", subtitle: [Recovering straight structure from an edge map that is full of holes.])

A photograph of a page on a desk looks like a straightforward problem right up to the moment you
write code for it. What you want is the four edges of the sheet, or the baselines of the text, so
that you can work out how far the page is rotated. What you have, after `canny`, is a `CV_8UC1`
bitmap in which some pixels are set and the rest are not. Nothing in that bitmap says which set
pixels belong together. The edge of the page and the rim of the shadow under a coffee cup are the
same white, and both are only pixels.

Fitting a straight line to a set of points is a solved problem --- least squares, four lines of
Scala. The difficulty is that fitting presupposes the grouping, and the grouping is the actual
question. It gets worse: real edges arrive in pieces. A page edge crosses a shadow and drops out for
thirty pixels; a ruled line is interrupted by every descender crossing it; an anti-aliased boundary
flickers on and off as the gradient wanders either side of the hysteresis threshold. Any method that
needs a contiguous run of edge pixels reports the pieces rather than the line, and then you are
writing the code that stitches pieces back together --- the same problem again, with more state.

The Hough transform turns the question round. Instead of asking a candidate line which pixels lie on
it, it asks each edge pixel which lines could pass through it, and lets that pixel vote for all of
them. One pixel is consistent with an infinite family of lines, so one pixel tells you nothing. But
a line that is really in the image collects a vote from every pixel along it --- and it makes no
difference whether those pixels are contiguous, because votes are added, not linked. Thirty missing
pixels cost thirty votes out of eight hundred. Where the votes pile up, there is a line.

That is the whole idea, and the rest of this chapter is its cost. The vote count is the parameter
you tune, and a vote count is neither a distance nor a fraction: a line spanning a 4000-pixel
photograph collects ten times the votes of the same line in a 400-pixel thumbnail, so a threshold
that works on one is silently wrong on the other. And every vote comes from the edge map underneath,
so a Hough call is never tuned on its own --- you are tuning `canny` and the transform together.

#sect("Voting instead of fitting")

Picture a second image, one you never display. Its axes are not $x$ and $y$ but the two numbers that
name a line, and each cell is a counter. For every set pixel in the edge map, the transform walks the
family of lines through that pixel and adds one to each corresponding counter. A cell holding 640
then means: 640 edge pixels are consistent with this particular line. That second image is the
accumulator, and the transform's output is its peaks.

The obvious way to name a line, $y = m x + c$, is unusable here: a vertical line has infinite slope,
so half of the accumulator would be unreachable and the sampling would be wildly uneven. The
parameterisation everyone uses instead --- introduced for exactly this purpose by Duda and Hart in
1972 --- drops a perpendicular from the origin to the line and names the line by that perpendicular:
`rho`, its length in pixels, and `theta`, its angle in radians. Every line in the plane gets one
finite, bounded pair, verticals included.

The single most common misreading of a Hough result follows directly. `theta` is the angle of the
*normal*, not of the line. A horizontal line has a vertical normal, so it comes back at
`theta = Pi/2`; a vertical line comes back at `theta = 0`. `PolarLine`'s own scaladoc says so, and
it is worth reading the result out in degrees the first few times.

The accumulator's cell size is the other pair of knobs, and both are parameters of every call:
`rho`, the distance resolution in pixels (default `1.0`), and `theta`, the angle resolution in
radians (default `math.Pi / 180`, one degree). Finer cells separate nearly coincident lines, at the
price of splitting one real line's votes across neighbouring cells, which lowers its peak and can
push it under your threshold. That trade is why a Hough call finding nothing sometimes wants a
*coarser* accumulator rather than a lower threshold.

#sect("What the transforms will accept")

All three transforms are extension methods on `org.opencv.core.Mat`, brought in by
`import scalacv.*`, and all three demand a non-empty, 8-bit, single-channel image. Handing one a
colour photograph is the first mistake everybody makes, because at the call site it reads perfectly
well.

#example("The version that does not run.")[
```scala
Image.reading("page.jpg") { img =>
  img.mat.houghLinesP(threshold = 80) // IllegalArgumentException
}
```
]

The check happens in Scala, before anything crosses JNI, and names the offending Mat:
`houghLinesP needs a non-empty 8-bit single-channel image (typically the output of Canny), but got
3024x4032 of type CV_8UC3`. Without that check the same call fails on the far side of JNI, with a
message that names neither the call nor the reason.

The precondition catches the type, and only the type. The second mistake is quieter:

#warning[
`img.gray.mat.houghLinesP(threshold = 80)` compiles, passes the precondition, and returns rubbish. A
greyscale photograph is `CV_8UC1`, but the transform treats every non-zero pixel as an edge pixel
entitled to vote --- and in a photograph that is nearly every pixel. You get a long list of
plausible-looking segments that correspond to nothing. Run `canny` first, and when a result makes no
sense, write the edge map out and look at it before touching any Hough parameter.
]

#example("Edges first, then the transform.")[
```scala
import scalacv.*

OpenCv.load()

val segments = Image.reading("page.jpg") { img =>
  val edges = img.gray.canny(threshold1 = 80, threshold2 = 160) // Image, CV_8UC1
  val found = edges.mat.houghLinesP(threshold = 80, minLineLength = 120, maxLineGap = 10)
  edges.close()
  found
}
```
]

`Image.reading` closes `img` for you, on success, on failure and on exception. It does not close
`edges`, because `edges` is a different `Image` that `img.gray` produced by consuming `img` --- so
that one `close()` is yours, and the block returns `Either[CvError, Seq[Segment]]`.

#memory[
`edges.mat` *borrows* the Mat; the `Image` still owns it and will release it. Do not call
`release()` on what `mat` hands you. In the other direction, what comes back from a transform is
ordinary immutable Scala data --- `Segment` is four `Int`s and `PolarLine` two `Float`s, with no
pointer behind either --- so `segments` stays valid long after every Mat in this block is gone. The
intermediate `Nx1` Mat that OpenCV writes its answer into never escapes at all: the private
`Hough.decoding` helper allocates it, runs the transform, decodes it, and releases it in a
`finally`. There is no handle here for you to leak.
]

#sect("Segments, lines, and votes")

#figure-table("The three entry points, all extension methods on `Mat`.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Method], [Returns], [Reach for it when],
  [`houghLines`], [`Seq[PolarLine]`], [you want an orientation or a position and endpoints are irrelevant --- a dominant angle, a vanishing point, the tilt of a page],
  [`houghLinesP`], [`Seq[Segment]`], [you want to draw or measure the actual runs of edge: table sides, bar-code bars, the border of a form],
  [`houghLinesWithAccumulator`], [`Seq[PolarLineWithVotes]`], [you want to rank or threshold the polar lines yourself, because you need the vote counts],
)
]

`houghLinesP` --- the probabilistic transform --- is the one most code wants, and it takes two
parameters the standard transform has no use for. `minLineLength` discards segments shorter than that
many pixels (`0.0`, the default, keeps everything), and `maxLineGap` is the largest break, in pixels,
still bridged into a single segment (`0.0` bridges nothing). Together they answer fragmentation:
`maxLineGap` re-joins the run a shadow broke, `minLineLength` drops the specks that survived.

`Segment` carries `x1`, `y1`, `x2`, `y2` as `Int`, and this is not a rounding decision --- the Mat
underneath really is int32. It also gives you `start` and `end` as `Point`s and a `length`, which
makes the near-universal filter a one-liner:

```scala
segments.filter(_.length > 200)
```

Segments are invisible until you draw them. `drawSegments` is their renderer, it lives on `Mat`, and
like every `draw*` operation it mutates its receiver:

#example("Annotating the borrowed Mat of an image you still own.")[
```scala
Image.reading("page.jpg") { img =>
  val edges = img.copy.gray.canny(threshold1 = 80, threshold2 = 160)
  val found = edges.mat.houghLinesP(threshold = 80, minLineLength = 120, maxLineGap = 10)
  edges.close()
  img.mat.drawSegments(found, Scalar.Red, Thickness.Stroke(2))
  img.write("lines.png")
}
```
]

Note the `copy`. Every `Image` transform consumes its receiver, so without it `img.gray` spends
`img` and the later `img.mat` throws `IllegalStateException` at the reuse rather than at the move.
Run with `-Dscalacv.trackOwnership=true` and that exception carries the consuming call as its cause.

#subsect("Polar form, and how to draw it")

A `PolarLine` is infinite; it has no endpoints to draw. To render one you pick two points far enough
apart to leave the frame. Drop the perpendicular from the origin --- that foot is at
`(rho * cos(theta), rho * sin(theta))` --- then walk both ways along the line's own direction, which is
the normal turned through a right angle: `(-sin(theta), cos(theta))`.

#example("Turning an infinite line into something drawable.")[
```scala
def toSegment(line: PolarLine, reach: Double): Segment =
  val a = math.cos(line.theta.toDouble)
  val b = math.sin(line.theta.toDouble)
  val x0 = a * line.rho
  val y0 = b * line.rho
  Segment(
    math.round(x0 - reach * b).toInt,
    math.round(y0 + reach * a).toInt,
    math.round(x0 + reach * b).toInt,
    math.round(y0 - reach * a).toInt
  )

// `reach` only has to exceed the image diagonal for the segment to cross the whole frame.
val reach = math.hypot(edges.width.toDouble, edges.height.toDouble)
edges.mat.houghLines(threshold = 150).map(toSegment(_, reach))
```
]

The rounding here is yours, not OpenCV's: these `Segment`s are drawing coordinates you invented,
not the int32 endpoints `houghLinesP` measured. `drawSegments` cannot tell the two apart --- so do
not go on to treat one as a measurement.

#figure-table("Reading a `PolarLine` back, for a 400x300 image.")[
#tbl(
  columns: (auto, auto, 1fr),
  [`theta`], [`rho`], [The line],
  [`0`], [`130`], [vertical, at `x = 130`],
  [`Pi/2` (90°)], [`50`], [horizontal, at `y = 50`],
  [`3Pi/4` (135°)], [`0`], [the main diagonal: through the top-left corner, descending to the right at 45°],
  [a hair under `Pi` (179°)], [about `-127`], [near-vertical around `x = 130`, tilted one degree the other way from `theta = 0`],
)
]

#subsect("Ranking by votes")

`houghLines` returns its lines sorted strongest-first but throws the magnitudes away, so there is no
way to ask how much stronger the winner was. `houghLinesWithAccumulator` keeps them, in a
`PolarLineWithVotes(rho, theta, votes)` whose first two fields are the `PolarLine`'s and whose third
is an `Int` --- call `.line` on one to drop the votes and get the plain `PolarLine` back. The votes
are the raw accumulator counts, so `lines.map(_.votes)` says at a glance whether you have
one dominant structure or six equal candidates.

#sidebar("Three results, three element types")[
OpenCV returns all three answers as an anonymous `Nx1` multi-channel Mat whose channels have no names
and whose element type differs per transform: `HoughLines` gives `CV_32FC2` holding `(rho, theta)`,
`HoughLinesWithAccumulator` gives `CV_32FC3` holding `(rho, theta, votes)`, and `HoughLinesP` gives
`CV_32SC4` holding `(x1, y1, x2, y2)` --- int32, not float. Nothing in the Java signature tells you
any of this. Read the probabilistic result with a float accessor and it throws; read a float result
with the wrong channel count and you silently reinterpret bit patterns into coordinates that look
almost plausible.

`Hough` decodes all three through `Mat.get(row, 0): Array[Double]`, the one accessor that is
depth-generic on the Java side and so correct for all three shapes. The library's tests assert on the
recovered geometry rather than on "some lines came back", because a wrong decoder passes the weaker
test.

Parameter order is the other deliberate departure. OpenCV's C++ signature puts `rho` and `theta`
before `threshold`; scalacv puts `threshold` first, because it is the only argument without a
sensible default, which makes `edges.houghLinesP(50)` the short form for an edge `Mat` named `edges`. The cost is that positional
calls do not transfer between the two APIs. Name your arguments and it stops mattering.
]

#sect("Tuning, as a procedure")

Hough tuning has a reputation for being mystical, which it earns only when people change parameters
in an arbitrary order. There is a sequence, and it works because each step has a symptom you can see.

+ *Look at the edge map.* Write it to a file. If the structure is not visible to you as a human, no
  threshold will find it, and you are tuning `canny`, not Hough. `canny(80, 160)` is a reasonable
  start for a well-lit photograph; halve both for a flat one.

+ *Start deliberately permissive.* `edges.mat.houghLinesP(threshold = 50, minLineLength =
  edges.width / 4.0, maxLineGap = 5)`. You should get too much --- a useful state, where
  nothing at all tells you only that something, somewhere, is wrong.

+ *Raise `threshold` until the noise stops.* Move in steps of about 20 and watch the count, not the
  pictures. Noise segments die off in a rush; the structure you want holds. If everything dies at
  once, the real lines were never much stronger than the noise, and the answer is a better edge map.

+ *Now relax `maxLineGap`.* With the noise gone, one real edge reported as four pieces is a
  fragmentation problem, and `maxLineGap` is its only cure: 5 to 10 for a clean scan, 15 to 25 for a
  photograph with shadows. Raise it too far and two collinear edges either side of a real gap --- the
  dashes of a lane marking, the perforations on a sheet --- merge into one segment.

+ *Trim with `minLineLength` last,* once you know how long the real segments come out.

#tip[
For `houghLines`, `minTheta` and `maxTheta` bound the reported angle in radians (defaults `0.0` and
`math.Pi`). If you only care about near-horizontal lines, bracket `math.Pi / 2` instead of filtering
afterwards: the accumulator never scores the other orientations, which is both faster and much less
noisy than discarding them later. `srn` and `stn` divide `rho` and `theta` for a coarse-to-fine
search and default to `0.0`, which selects the classic transform --- leave them there unless you
have measured a reason not to.
]

#sect("Circles, and the escape hatch")

The same voting idea extends to circles: an edge pixel votes for every circle that could pass through
it, and the accumulator gains a third dimension for the radius. OpenCV implements that as
`Imgproc.HoughCircles`, and scalacv does *not* wrap it --- the line transforms are the ones the
library commits to. The circle result has a different shape again (one row of `N` three-channel
entries, read by column rather than by row), and its parameters are markedly more brittle than the
line transforms'. What the library gives you instead is a clean way down to the raw call.

#example("HoughCircles through the low-level API.")[
```scala
import scalacv.*
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

final case class Circle(x: Float, y: Float, radius: Float)

// `gray` is borrowed: single-channel, 8-bit, and blurred — not an edge map.
def circles(gray: Mat, minDist: Double): Seq[Circle] =
  Managed.use(Mat()) { out =>
    Imgproc.HoughCircles(
      gray, out, Imgproc.HOUGH_GRADIENT,
      1.0,      // dp
      minDist,
      100.0,    // param1
      30.0,     // param2
      0, 0      // minRadius, maxRadius: 0 means "unbounded"
    )
    Vector.tabulate(out.cols()) { i =>
      val v = out.get(0, i)
      Circle(v(0).toFloat, v(1).toFloat, v(2).toFloat)
    }
  }
```
]

#memory[
This is the one place in the chapter where a Mat is yours. `Imgproc.HoughCircles` writes into the
output Mat you hand it and returns `void`; nothing in the binding frees that Mat, and nothing in the
signature says you are now the one who has to. `Managed.use` releases it when the block returns, exception or not, and the `Vector` it returns is
plain data. Once per frame in a video loop without that release, this is the textbook leak the
library exists to prevent --- forty bytes on the heap, megabytes off it, and no collection triggered
by either.
]

#figure-table("What each `HoughCircles` parameter actually controls.")[
#tbl(
  columns: (auto, 1fr),
  [Parameter], [Meaning],
  [`dp`], [inverse ratio of accumulator resolution to image resolution. `1.0` is the same grid; `2.0` is half the resolution and roughly a quarter of the memory and time. Start at `1.0`, and raise it before you lower `param2`.],
  [`minDist`], [minimum distance between the centres of two reported circles. Too small and one circle is reported several times over; too large and genuinely adjacent circles are suppressed. A sensible start is the smallest radius you expect to see.],
  [`param1`], [the *upper* Canny threshold used internally --- the lower one is half of it. This is where the edge map comes from, which is why the input is a greyscale image, not an edge image.],
  [`param2`], [the accumulator threshold for centres. This is the noise knob: lower it and you get false circles everywhere, raise it and real ones vanish. Tune it last, and by halving or doubling.],
  [`minRadius`, `maxRadius`], [the radius range in pixels; `0` for either leaves it unbounded. Bounding both is the cheapest accuracy improvement available, because it shrinks the search directly.],
)
]

The standing advice to blur first is not superstition. `HoughCircles` estimates a gradient direction
at every edge pixel and votes along it, so pixel-level noise does not merely add spurious edges --- it
*misaims* the votes the real edges cast. A median blur of radius 2 (a 5×5 neighbourhood) before the
call is the usual fix:

```scala
val detected: Either[CvError, Seq[Circle]] =
  Image.reading("coins.jpg") { img =>
    val prepared = img.gray.medianBlur(radius = 2) // Image, CV_8UC1
    val found = circles(prepared.mat, minDist = 20)
    prepared.close()
    found
  }
```

If your round things are solid and well separated, contours are usually the better tool anyway: take
`contours()` and score each one's circularity as `4 * math.Pi * c.area / (c.perimeter * c.perimeter)`,
which is `1.0` for a perfect circle and falls away as the outline gets less round. That gives you area,
centroid and bounding box for free, and never invents a circle where there is only an arc.

#sect("Worked example: the skew of a page")

Back to the page on the desk. The measurement is a single number --- how many degrees the page is
rotated --- and the segments already contain it. The long, near-horizontal ones are the text baselines
and the top and bottom edges of the sheet; each carries the same tilt, plus noise.

#example("Skew, in degrees, from the segments of a document photograph.")[
```scala
/** The dominant tilt of `edges` in degrees, positive when the structure runs downhill to
  * the right --- a clockwise tilt on screen. None when nothing long and near-horizontal
  * was found.
  */
def skewDegrees(edges: Mat, maxTilt: Double = 20.0): Option[Double] =
  val minLength = edges.cols / 4.0
  val tilts = edges
    .houghLinesP(threshold = 80, minLineLength = minLength, maxLineGap = 20)
    .map(s => math.toDegrees(math.atan2((s.y2 - s.y1).toDouble, (s.x2 - s.x1).toDouble)))
    .map(a => if a > 90 then a - 180 else if a <= -90 then a + 180 else a) // fold to (-90, 90]
    .filter(a => math.abs(a) <= maxTilt)
    .sorted
  if tilts.isEmpty then None else Some(tilts(tilts.size / 2))
```
]

Three decisions there are worth naming. The fold to `(-90, 90]` exists because a segment's endpoints
come back in no particular order, so one physical line yields either 3° or −177° depending on which
end OpenCV listed first --- average those and you get nonsense. The `maxTilt` filter discards the
vertical structure, the page's own left and right edges, which are no less real and would otherwise
dominate. And the estimate is the *median*, not the mean: one surviving diagonal --- a pen lying
across the page --- moves a mean by degrees and a median not at all.

#example("The measurement, end to end.")[
```scala
val skew: Either[CvError, Option[Double]] =
  Image.reading("page.jpg") { img =>
    val edges = img.gray.canny(threshold1 = 60, threshold2 = 180)
    val angle = skewDegrees(edges.mat)
    edges.close()
    angle
  }
```
]

Straightening from there is `image.rotate(skew)` on a freshly read `Image`, once the number is out
of the `Option`. The absent minus sign is the point: `rotate` turns counter-clockwise about the
centre, `skewDegrees` measures a clockwise tilt as positive, and the two conventions cancel. The
rotation expands the canvas as it goes, so no corner is clipped --- which is where the fill matters.
`Image.rotate` leaves the exposed corners in `Scalar.Black`, and black corners on a scan look exactly
like ink to whatever reads the page next.

The library's own `deskew()` fills those corners white, and it does not use Hough at all: it
binarises with an inverted Otsu threshold so the text becomes the foreground, collects the ink pixels
with `findNonZero`, fits a `minAreaRect` to the lot, and rotates by that rectangle's angle folded
into `(-45, 45]`. It keeps the original frame size rather than expanding it, which is what you want
for a page that was already the right shape. Two angles are refused rather than applied: anything
beyond `maxAngle` (default `45.0`), read as a misdetection, and anything under a tenth of a degree,
not worth an interpolation pass. Both leave you a plain copy.

The two estimators fail differently, which is the useful thing to know about them. `minAreaRect` over
the ink measures the text block, so it wants a page that is mostly text and is confused by a large
figure. The Hough estimate measures long straight runs, so it wants ruled lines, table borders or a
clean page edge, and finds nothing to work with on a sparse page of prose. Where a document has both,
comparing the two angles is a confidence check neither gives you alone.

#sect("When Hough is the wrong tool")

Hough finds straight runs of edge, and only that. Three families of problem look like they are asking
for lines and are not.

*Closed shapes.* If what you want is a region --- its area, its centroid, whether it is a rectangle
--- then `contours()` gives you the outline as connected data, and `Contour.approx` collapses a noisy
quadrilateral to four corners in one call. Hough hands you four unrelated segments plus the job of
pairing them into corners, which is harder than it sounds when one side is missing.

*Structure across two images.* Matching a scene between frames, or finding a known object in a
photograph, is a correspondence problem, not a line-detection one. `Features.detect` and
`Features.matches` do that with ORB keypoints, which survive rotation and scale changes that move
every Hough line in the frame.

*Anything semantic.* Hough cannot tell a lane marking from a crack in the tarmac, or a shelf edge
from its own shadow: both are equally straight and vote identically. Where the distinction lives in
what the thing *is* rather than in its geometry, that is a learned detector's job --- `Dnn.fromOnnx`
and an ONNX model --- and the honest use of Hough is as a cheap prefilter that proposes candidates
for something else to judge.

#sect("Where the angle goes next")

The skew number computed here is not the end of anything; it is an input. Chapter 33 takes the OCR
pipeline up in full --- `forOcr`, the grayscale, denoise, adaptive-threshold and deskew chain that
decides whether the text comes out clean, and the `OcrEngine` you plug in behind it. The nearer stop
is Chapter 14, which turns `drawSegments` and the rest of the annotation surface --- `Thickness`,
`LineType`, the BGR ordering of `Scalar` --- from the one call used here into the full instrument.
