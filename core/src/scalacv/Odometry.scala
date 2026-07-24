package scalacv

/** A running visual-odometry pipeline: feed frames in order, get the camera's motion each step.
  *
  * This is the front-end **loop** of visual SLAM, composing the per-frame primitives: it keeps the previous
  * frame and its tracked points, follows them into each new frame with [[OpticalFlow]], and estimates the
  * step's motion with [[VisualOdometry]]. Chaining the steps is dead-reckoning — it drifts, and correcting
  * that drift (loop closure, global optimisation) is the SLAM back end, beyond OpenCV.
  *
  * Monocular, so each step's translation is a **unit direction** (scale is unobservable from one camera). The
  * pipeline retains a frame's worth of native memory between calls, so it is `AutoCloseable` — [[close]] it.
  *
  * {{{
  * val odometry = Odometry.monocular(focal = 500, principalPoint = Point(320, 240))
  * try camera.foreach(frame => odometry.update(frame).foreach(step => track(step)))
  * finally odometry.close()
  * }}}
  *
  * Not thread-safe: feed one frame at a time.
  */
final class Odometry private (focal: Double, principalPoint: Point) extends AutoCloseable:

  private var previous: Image | Null = null
  private var previousPoints: Seq[Point] = Seq.empty
  private var frames = 0

  /** Feeds the next frame and returns the camera's motion since the previous one. `None` on the very first
    * frame (it becomes the reference) and whenever too few points survive to estimate a motion.
    */
  def update(frame: Image): Option[CameraMotion] =
    frames += 1
    previous match
      case null =>
        previous = frame.copy
        previousPoints = OpticalFlow.goodFeatures(frame)
        None
      case prev =>
        val tracked = OpticalFlow.track(prev, frame, previousPoints).filter(_.found)
        val motion =
          if tracked.size >= 8 then
            VisualOdometry.estimate(tracked.map(_.from), tracked.map(_.to), focal, principalPoint)
          else None
        prev.close()
        previous = frame.copy
        previousPoints = OpticalFlow.goodFeatures(frame)
        motion

  /** How many frames have been fed so far. */
  def framesProcessed: Int = frames

  /** Releases the retained frame. Idempotent. */
  def close(): Unit =
    val p = previous
    previous = null
    previousPoints = Seq.empty
    if p != null then p.close()

object Odometry:

  /** A monocular odometry pipeline for a camera with the given pinhole intrinsics. */
  def monocular(focal: Double, principalPoint: Point): Odometry =
    require(focal > 0, s"focal length must be positive, got $focal")
    new Odometry(focal, principalPoint)
