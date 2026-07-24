# Camera calibration

Every metric thing scalacv does with geometry — a marker's [distance in centimetres](/marker-ar),
[where the camera is](/navigation#absolute-localization), the [depth of a stereo pair](/navigation#stereo-depth-obstacles) —
rests on knowing the camera's optics: its focal length, its optical centre, and how its lens bends
straight lines. Those numbers are the [`Intrinsics`](/api/core/scalacv/Intrinsics.html). Until you
measure them you are stuck with `Intrinsics.approx`, a field-of-view *guess* — good enough to watch an
overlay track, not good enough to measure with. **Calibration replaces the guess with measurement.**

```scala mdoc:invisible
import scalacv.*
import org.opencv.core.{CvType, Mat}
import org.opencv.core as cv
OpenCv.load()
```

## The target

Calibration works by showing the camera something whose geometry you already know and seeing how the
lens deforms it. The classic target is a chessboard, described by its **inner-corner** grid — the
crossings *between* squares, not the squares themselves. A board of 10×7 squares has a 9×6 inner grid:

```scala mdoc:silent
val board = ChessboardPattern(columns = 9, rows = 6, squareSize = 0.025) // 25 mm squares
```

`squareSize` is the real edge length of one square. It sets the unit the recovered geometry is
expressed in — pass metres and a marker's `distance` comes back in metres — but it does **not** affect
the intrinsics, which are always in pixels.

## Finding the board

[`findCorners`](/api/core/scalacv/Calibration$.html) locates and sub-pixel-refines the whole inner
grid in one image, returning the corners in pixel coordinates (or `None` if the full board is not
visible — the detector is all-or-nothing):

```scala mdoc:invisible
// A synthetic flat board with a white quiet zone, so the doc runs with no fixture files.
def chessboard(): Image =
  val square = 40; val margin = 40
  val w = (board.columns + 1) * square + 2 * margin
  val h = (board.rows + 1) * square + 2 * margin
  val m = Mat(h, w, CvType.CV_8UC3, cv.Scalar(255, 255, 255))
  for r <- 0 until board.rows + 1; c <- 0 until board.columns + 1 if (r + c) % 2 == 0 do
    val roi = m.submat(margin + r * square, margin + (r + 1) * square, margin + c * square, margin + (c + 1) * square)
    roi.setTo(cv.Scalar(0, 0, 0)); roi.release()
  Image.wrap(Managed(m))
```

```scala mdoc
val img = chessboard()
try Calibration.findCorners(img, board).map(_.size)
finally img.close()
```

All 54 corners (9 × 6), found. This is also how you build an interactive capture tool: show the live
frame, call `findCorners`, and only keep frames where it returns `Some`.

## Recovering the camera

Hand several views of the board — from a *range of angles*, which is what makes the problem
well-posed — to [`fromChessboard`](/api/core/scalacv/Calibration$.html). It finds the board in each,
drops the frames where it is not fully visible, and runs OpenCV's calibration to recover the camera:

```scala mdoc:compile-only
val frames: Seq[Image] = (1 to 15).flatMap(i => Image.read(s"calib/$i.jpg").toOption)

Calibration.fromChessboard(frames, board, minViews = 8) match
  case Right(calib) =>
    val i = calib.intrinsics
    println(f"fx=${i.fx}%.1f fy=${i.fy}%.1f  centre=(${i.cx}%.1f, ${i.cy}%.1f)")
    println(f"RMS reprojection error: ${calib.reprojectionError}%.3f px")
  case Left(err) =>
    System.err.println(err.getMessage) // too few views showed the board, or the solver failed
```

The headline quality number is [`reprojectionError`](/api/core/scalacv/Calibration.html): the
root-mean-square distance, over every corner of every view, between where a corner actually sat and
where the recovered model predicts it. **Under ~1 px is a good calibration**; several pixels means
blurry frames, a poor board, or too few angles. It comes back with the result rather than being
buried, precisely so you can judge the fit.

When too few views show the whole board the result is a
[`CvError.CalibrationFailed`](/api/core/scalacv/CvError$$CalibrationFailed.html) — a value, not an
exception, because how many boards a capture happened to catch is data, not a bug.

## Straightening the lens

With a calibration in hand, [`undistort`](/api/core/scalacv/Image.html) maps the barrel or pincushion
bend of a wide lens back out, so straight edges in the world come back straight:

```scala mdoc:compile-only
val calibration: Calibration = ???
Image.reading("wide-angle.jpg")(_.undistort(calibration).write("straightened.jpg"))
```

## Feeding the geometry stack

The recovered `intrinsics` are the *same* [`Intrinsics`](/api/core/scalacv/Intrinsics.html) the pose
tools already take — so a calibration drops straight in where an `Intrinsics.approx` guess used to be,
turning "indicative" into metric:

```scala mdoc:compile-only
val calib: Calibration = ???
val marker: ArucoMarker = ???

// Marker pose, now to scale (see: Marker AR)
Ar.estimatePose(marker, markerLength = 0.05, calib.intrinsics)
```

That is the whole point of measuring the camera: [`Ar`](/marker-ar),
[`Localizer`, `VisualOdometry`](/navigation) and [`HeadPose`](/pose-estimation) all become metric the
moment you hand them a real calibration instead of a field-of-view estimate.

The full runnable version — which projects a board through a *known* camera and recovers it back to
within a fraction of a percent — is
[`CalibrationDemo`](https://github.com/w0rxbend/scalacv/blob/master/examples/src/scalacv/CalibrationDemo.scala).
