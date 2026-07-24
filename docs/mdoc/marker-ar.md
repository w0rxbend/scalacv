# Marker AR

Give scalacv a printed [ArUco](/object-detection#aruco-markers) tag and a camera model and it will tell
you where that tag sits in space — its 3D pose — and let you draw on top of it as if the drawing were
glued to the tag. That is the whole of marker-based augmented reality: **detect → pose → project**,
and each step is one call.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

## The camera model

Pose recovery needs to know the camera's optics: focal length and principal point, the
[`Intrinsics`](/api/core/scalacv/Intrinsics.html). A real camera's numbers come from a chessboard
calibration, but when you just want an overlay to track, `Intrinsics.approx` guesses a serviceable
model from the image size and an estimated field of view:

```scala mdoc:silent
val intrinsics = Intrinsics.approx(Size(1280, 720), horizontalFovDegrees = 60)
```

A narrower field of view is a longer lens, so `fx`/`fy` grow as the angle shrinks; the principal point
sits at the image centre. If you have calibrated, construct it directly with your own
`fx, fy, cx, cy` and distortion coefficients.

## Detect and pose in one step

`arMarkers` finds every tag from a dictionary and solves each one's pose, returning a
[`MarkerPose`](/api/core/scalacv/MarkerPose.html) — the marker plus its [`Pose3D`](/api/core/scalacv/Pose3D.html).
You give it the tag's real side length (metres, conventionally); the pose comes back in that unit, so
`distance` is a real camera-to-tag distance.

```scala mdoc:invisible
// A synthetic fronto-parallel view: tag id 7 on a white quiet zone so the detector finds it.
def markerScene(): Image =
  val bordered =
    Aruco
      .generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 240)
      .use(_.border(80, 80, 80, 80, color = Scalar.White))
  Image.wrap(bordered).convert(ColorConversion.GrayToBgr)
```

```scala mdoc:silent
val markers = markerScene().arMarkers(intrinsics = Intrinsics.approx(Size(400, 400)), markerLength = 0.05)
```

```scala mdoc
{
  markers.map(m => s"marker ${m.id}: ${f"${m.distance}%.3f"} m away").mkString("\n")
}
```

Under the hood this is `solvePnP` with the square-planar `IPPE_SQUARE` solver — faster and steadier for
a flat tag than the general iterative method. The pose's `rvec`/`tvec` are OpenCV's axis-angle rotation
and translation; you rarely read them directly (a head-on tag comes back as a ~180° flip, because the
marker frame is y-up and the image frame y-down), but `distance` is often all you want.

## Draw on the tag

`drawMarkerAxes` overlays a 3D coordinate frame at every tag — X red, Y green, Z pointing out of the
plane toward the camera — the classic "is my pose right?" check:

```scala mdoc:silent
markerScene()
  .drawMarkerAxes(Intrinsics.approx(Size(400, 400)), markerLength = 0.05)
  .write("axes.png")
```

`drawMarkerCube` stands a wireframe cube on each tag — the hello-world of AR:

```scala mdoc:silent
markerScene()
  .drawMarkerCube(Intrinsics.approx(Size(400, 400)), markerLength = 0.05, color = Scalar.Green)
  .write("cube.png")
```

## Projecting your own geometry

Both overlays are thin wrappers over [`Ar.project`](/api/core/scalacv/Ar$.html), which maps any set of
3D model points — in the tag's own frame — through a pose and the camera to pixel coordinates you draw
with the ordinary [drawing verbs](/drawing). To hang your own model off a tag, project its points and
draw the edges:

```scala mdoc:silent
val scene = markerScene()
val intr = Intrinsics.approx(scene.size)
scene.arMarkers(intr, markerLength = 0.05).headOption.foreach { mp =>
  // A vertical mast rising 10 cm out of the tag centre.
  val Seq(base, tip) = Ar.project(Seq(Point3(0, 0, 0), Point3(0, 0, 0.1)), mp.pose, intr)
  scene.mat.drawLine(base, tip, Scalar.Red, Thickness.Stroke(3))
}
scene.close()
```

Because `project` takes plain [`Point3`](/api/core/scalacv/Point3.html) data, anything you can describe
as 3D points — a bounding box, a label anchor, a mesh — rides on the tag with no extra machinery.
