# Visual navigation & SLAM

SLAM, localization, navigation and obstacle detection are built from the same visual pieces, and OpenCV — so
scalacv — provides the **front end** of that stack: detecting and tracking what the camera sees, and turning
motion and stereo into geometry. The **back end** that makes it full *SLAM* — a persistent map, loop closure,
global bundle adjustment — is a different kind of software (g2o, GTSAM, ORB-SLAM) and is deliberately out of
scope. Knowing where that line falls is half the battle:

| Task | scalacv provides (OpenCV front end) | Needs a back end beyond OpenCV |
|---|---|---|
| **Tracking** | [`OpticalFlow`](#optical-flow) — follow points frame to frame | — |
| **Visual odometry** | [`VisualOdometry`](#visual-odometry) per pair; [`Odometry`](#the-odometry-pipeline) — the running loop | scale, drift correction |
| **Localization** | [`Localizer`](#localization-against-a-map) — absolute pose via `solvePnP`; [`Features`](#features--matching) to match a map | a map to localize against |
| **Obstacle detection** | [`StereoDepth`](#stereo-depth--obstacles) + `Obstacles` | — |
| **Navigation** | [`Navigator`](#reactive-navigation) — reactive obstacle-avoidance steering | a map, a goal, a planner |
| **Mapping** | [`LoopDetector`](#mapping-loop-closure--occupancy) — revisit detection; [`OccupancyGrid`](#mapping-loop-closure--occupancy) | — |
| **Full SLAM** | all of the above as the front end | pose-graph optimisation, bundle adjustment |

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

`Localizer` gives the camera's **absolute** pose from correspondences between a map's known 3D points and their
matches in this frame, via `solvePnP` (the same routine [head pose](/pose-estimation) uses, at map scale).
Unlike odometry it does not drift — it is what a map is *for*. Here the correspondences are synthetic, from a
camera two units to the side of the world origin:

```scala mdoc
{
  val world = Seq((-1.0, -1.0, 6.0), (1.0, -1.0, 6.5), (-1.0, 1.0, 7.0), (1.0, 1.0, 5.5), (0.0, 0.0, 8.0), (0.6, -0.4, 7.2))
  def seen(p: (Double, Double, Double)): Point =
    val (x, y, z) = p
    Point(600 * (x - 2.0) / z + 320, 600 * y / z + 240)
  Localizer.locate(world, world.map(seen), focal = 600, principalPoint = Point(320, 240))
    .map(pose => f"camera at world (${pose.position(0)}%.1f, ${pose.position(1)}%.1f, ${pose.position(2)}%.1f)")
    .getOrElse("could not localize")
}
```

In practice the 3D↔2D pairs come from matching this frame's [`Features`](#features--matching) to the map; the
recovered pose then anchors the drifting odometry.

## Reactive navigation

The shortest path from "where are the obstacles" to "what do I do" is `Navigator`: read a disparity map, split
the view into thirds, and pick a `Steering` toward the clearest — obstacle avoidance with no map at all:

```scala mdoc
{
  // Something near, filling the right two-thirds of the view ahead.
  val disparity = Image.blank(300, 150, Scalar.Black, channels = 1).drawRect(Rect(120, 0, 180, 150), Scalar(220), Thickness.Filled)
  val guidance = Navigator.steer(disparity)
  disparity.close()
  s"${guidance.steering}, clearance ahead ${(guidance.clearanceAhead * 100).round}%"
}
```

A planner — a map, a goal, a path — layers on top; the reflex keeps you off the walls while it thinks.

## The odometry pipeline

`Odometry` wires the primitives into the running loop: feed it frames and it tracks features and estimates
each step's motion for you, keeping the previous frame internally. It is `AutoCloseable`, and monocular (each
step's translation is up to scale). Drive it straight off a [`Camera`](/video):

```scala mdoc:compile-only
val odometry = Odometry.monocular(focal = 500, principalPoint = Point(320, 240))
try Camera.usingFile("drive.mp4")(_.foreach()(frame => odometry.update(frame).foreach(step => println(step.inliers))))
finally odometry.close()
```

## Mapping: loop closure & occupancy

Two pieces move the front end toward an actual map.

**Loop closure.** `LoopDetector` keeps each keyframe's [`Features`](#features--matching) and flags when a new
frame revisits an old place — the cue that lets a back end cancel accumulated drift:

```scala mdoc
{
  def place(seed: Int): Image =
    val r = new scala.util.Random(seed)
    Image.blank(220, 180, Scalar(30, 30, 30)).drawRects(
      Seq.fill(7)(Rect(10 + r.nextInt(170), 10 + r.nextInt(130), 18 + r.nextInt(16), 18 + r.nextInt(16))),
      Scalar.White, Thickness.Filled)
  val loops = LoopDetector(minMatches = 25, recentExclusion = 2)
  try
    (1 to 5).foreach(s => { val p = place(s); try loops.process(p) finally p.close() })
    val revisit = place(1) // revisit the very first place
    val closure = try loops.detect(revisit).map(l => s"loop to keyframe ${l.keyframe}, ${l.matches} matches") finally revisit.close()
    closure.getOrElse("no loop")
  finally loops.close()
}
```

**Occupancy grid.** `OccupancyGrid` accumulates free/occupied evidence into a top-down log-odds map. A range
reading marks the ray to an obstacle as free and its endpoint as occupied; `toImage` renders the map:

```scala mdoc
{
  val grid = OccupancyGrid(cols = 60, rows = 60, resolution = 0.1)
  grid.observe(0.0, 0.0, 2.0, 0.0) // sensor at origin, obstacle 2m ahead
  s"ahead occupied ${grid.isOccupied(2.0, 0.0)}, 1m out free ${!grid.isOccupied(1.0, 0.0)}"
}
```

## Where OpenCV ends

The front end now reaches quite far: it tracks, estimates motion, localizes against a map, detects revisited
places, and builds an occupancy grid. What remains is the **global optimisation** — taking the keyframes, the
loop closures, and the odometry constraints and solving for the trajectory and map that best fit them all
(pose-graph optimisation, bundle adjustment). That is a nonlinear-least-squares back end (g2o, GTSAM,
Ceres), not computer vision, and belongs to those libraries. scalacv gives you every per-frame piece that
feeds them — clean, typed, and resource-safe.
