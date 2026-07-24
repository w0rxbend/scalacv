# Visual navigation & SLAM

SLAM, localization, navigation and obstacle detection are built from the same visual pieces, and OpenCV — so
scalacv — provides the **front end** of that stack: detecting and tracking what the camera sees, and turning
motion and stereo into geometry. The **back end** that makes it full *SLAM* — a persistent map, loop closure,
global bundle adjustment — is a different kind of software (g2o, GTSAM, ORB-SLAM) and is deliberately out of
scope. Knowing where that line falls is half the battle:

| Task | scalacv provides (OpenCV front end) | Needs a back end beyond OpenCV |
|---|---|---|
| **Tracking / navigation** | [`OpticalFlow`](#optical-flow) — follow points frame to frame | — |
| **Visual odometry** | [`VisualOdometry`](#visual-odometry) — relative motion per frame pair | scale, drift correction |
| **Localization / relocalization** | [`Features`](#features--matching) — ORB detect + match; `solvePnP` | a map to localize against |
| **Obstacle detection** | [`StereoDepth`](#stereo-depth--obstacles) + `Obstacles` | — |
| **Full SLAM** | all of the above as the front end | keyframe graph, loop closure, bundle adjustment |

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
def scene(ox: Int, oy: Int): Image =
  Image.blank(220, 180, Scalar(30, 30, 30)).drawRects(
    Seq(Rect(30 + ox, 30 + oy, 26, 26), Rect(130 + ox, 40 + oy, 30, 22), Rect(70 + ox, 110 + oy, 22, 34), Rect(150 + ox, 120 + oy, 26, 26)),
    Scalar.White, Thickness.Filled)
```

## Optical flow

Seed good corners, then follow them into the next frame with pyramidal Lucas–Kanade. Each surviving
[`Track`](/navigation) carries its own displacement — the raw material of egomotion and of "is anything
moving, and which way":

```scala mdoc
{
  val a = scene(0, 0)
  val b = scene(6, 4) // the same scene shifted right-and-down
  val tracks = OpticalFlow.track(a, b).filter(_.found)
  val meanShift = (tracks.map(_.displacement.x).sum / tracks.size, tracks.map(_.displacement.y).sum / tracks.size)
  a.close(); b.close()
  s"${tracks.size} points, mean shift $meanShift"
}
```

## Features & matching

ORB finds repeatable keypoints and binary descriptors; a cross-checked Hamming matcher pairs them across
images. This is how a system recognises a place it has seen before — relocalization and loop-closure
detection:

```scala mdoc
{
  val one = Features.detect(scene(0, 0))
  val two = Features.detect(scene(8, 0))
  val matched = Features.matches(one, two).size
  one.close(); two.close()
  s"${one.size} vs ${two.size} features, $matched matches"
}
```

`Descriptors` owns native memory — close it (or take it into a `Using` block).

## Visual odometry

Feed matched correspondences to the essential-matrix estimator and `recoverPose` to get the camera's motion
between two frames — a 3×3 rotation and a **unit** translation direction (a single camera cannot see absolute
scale). Here the correspondences come from projecting known 3D points before and after a sideways move:

```scala mdoc
{
  val world = Seq((-1.0, -1.0, 5.0), (1.0, -1.0, 6.0), (-1.0, 1.0, 7.0), (1.0, 1.0, 5.5), (0.0, 0.0, 6.0), (0.5, -0.7, 5.2), (-0.6, 0.4, 6.5), (0.2, 0.8, 5.8))
  def project(p: (Double, Double, Double), camX: Double): Point =
    val (x, y, z) = p
    Point(500 * (x - camX) / z + 320, 500 * y / z + 240)
  val motion = VisualOdometry.estimate(world.map(project(_, 0.0)), world.map(project(_, 0.4)), focal = 500, principalPoint = Point(320, 240))
  motion.map(m => s"${m.inliers} inliers, unit translation, rotation ~identity").getOrElse("degenerate")
}
```

Chaining these per-frame motions is dead-reckoning odometry; it **drifts**, and correcting that drift is the
back end's job.

## Stereo depth & obstacles

From a rectified stereo pair, `StereoDepth.disparity` produces a map where **brighter is nearer**, and
`Obstacles.fromDisparity` reads the near-field blobs off it — the obstacle detector for a robot or drone:

```scala mdoc
{
  // A disparity map with one near (bright) block, as StereoDepth would output.
  val disparity = Image.blank(200, 150, Scalar.Black, channels = 1).drawRect(Rect(60, 50, 44, 40), Scalar(210), Thickness.Filled)
  val obstacles = Obstacles.fromDisparity(disparity, minNearness = 0.5)
  disparity.close()
  obstacles.map(o => s"obstacle ${o.region} nearness ${(o.nearness * 100).round}%").mkString("; ")
}
```

Rectifying the pair first (`stereoRectify`, from a one-time stereo calibration) is assumed — it is off the hot
path and not wrapped here.

## Localization against a map

Once you have a map of known 3D points and can match this frame's features to them, `solvePnP` gives the
camera's absolute pose — the same routine [head pose](/pose-estimation) uses, at map scale. The 3D↔2D
correspondences come from [`Features`](#features--matching):

```scala mdoc:compile-only
// worldPoints: the map's 3D points; imagePoints: their matched 2D projections in this frame.
// org.opencv.calib3d.Calib3d.solvePnP(worldPoints, imagePoints, cameraMatrix, distCoeffs, rvec, tvec)
Features.detect(scene(0, 0)) // → match against the map, then solvePnP for the pose
```

## Where OpenCV ends

Everything above is per-frame or per-pair — no memory of the world. A real SLAM system adds a **back end**:
keyframes and a pose graph, loop-closure detection to recognise revisited places, and bundle adjustment to
optimise the whole trajectory and map jointly. Those belong to dedicated libraries. scalacv gives you the
visual front end that feeds them — clean, typed, and resource-safe — which is exactly the part that has to run
on every frame.
