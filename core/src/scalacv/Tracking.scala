package scalacv

import scala.collection.mutable

import org.opencv.core.{Core, CvType, Mat, Rect as CvRect}
import org.opencv.video.{KalmanFilter, Tracker as CvTracker}

/** Which single-object tracking algorithm to run. All three ship in this OpenCV build.
  *
  *   - [[Csrt]] — the accuracy pick: discriminative correlation filter with channel/spatial reliability.
  *     Slower, but it handles scale change and partial occlusion well.
  *   - [[Kcf]] — the speed pick: kernelised correlation filter. Fast and steady, but it does not adapt its
  *     box to scale.
  *   - [[Mil]] — multiple-instance learning. Robust to small appearance changes; no failure detection.
  */
enum TrackerKind:
  case Csrt, Kcf, Mil

/** A single-object tracker: told where an object is in one frame ([[init]]), it finds it in the next
  * ([[update]]) without re-detecting. This is *model-free* tracking — it learns the object's appearance from
  * the box you give it, so it works on anything, not just a class a detector knows.
  *
  * Owns a native tracker, so it is **caller-owned** — [[close]] it (or use `Using`). A tracker is stateful
  * and single-object; for many objects that come and go, use [[ObjectTracker]] instead.
  *
  * {{{
  * Using.resource(Tracker.create(TrackerKind.Csrt)): tracker =>
  *   tracker.init(firstFrame, box)
  *   for frame <- frames do tracker.update(frame).foreach(b => frame.drawRect(b).write(...))
  * }}}
  */
final class Tracker private (private val handle: Managed[CvTracker]) extends AutoCloseable:

  private var started = false

  /** Starts tracking the object inside `box` in `image`. May be called again to re-seed on a fresh box. */
  def init(image: Image, box: Rect): Unit =
    Cv.orThrow("Tracker.init")(handle.get.init(image.mat, box.toCv))
    started = true

  /** Locates the object in `image`. `None` when the tracker has lost it (CSRT and KCF report this; MIL always
    * returns a box). Must be preceded by [[init]].
    */
  def update(image: Image): Option[Rect] =
    require(started, "call Tracker.init before update")
    val out = CvRect()
    if Cv.orThrow("Tracker.update")(handle.get.update(image.mat, out)) then Some(Rect.from(out)) else None

  def close(): Unit = handle.release()

object Tracker:

  private given Releasable[CvTracker] = Releasable.handle(_.getNativeObjAddr)

  /** Builds a tracker of the given kind. Free it when done. */
  def create(kind: TrackerKind): Tracker =
    val native: CvTracker = kind match
      case TrackerKind.Csrt => org.opencv.tracking.TrackerCSRT.create()
      case TrackerKind.Kcf => org.opencv.tracking.TrackerKCF.create()
      case TrackerKind.Mil => org.opencv.video.TrackerMIL.create()
    new Tracker(Managed(native))

/** A constant-velocity Kalman filter over a 2D point — the smoother behind [[ObjectTracker]], useful on its
  * own to steady a jittery detection or to coast through a frame where the measurement dropped out.
  *
  * The state is position and velocity `(x, y, vx, vy)`; you [[predict]] the next position, then [[correct]]
  * it with a fresh measurement (or skip the correction if you have none this frame and trust the model). Owns
  * a native filter — **caller-owned**, [[close]] it.
  */
final class Kalman private (private val handle: Managed[KalmanFilter]) extends AutoCloseable:

  /** Advances the model one step and returns the predicted position. */
  def predict(): Point =
    Managed.use(handle.get.predict())(state => Point(state.get(0, 0)(0), state.get(1, 0)(0)))

  /** Folds `measurement` into the model and returns the corrected (smoothed) position. */
  def correct(measurement: Point): Point =
    Managed.use(Mat(2, 1, CvType.CV_32F)): z =>
      z.put(0, 0, measurement.x, measurement.y)
      Managed.use(handle.get.correct(z))(state => Point(state.get(0, 0)(0), state.get(1, 0)(0)))

  def close(): Unit = handle.release()

object Kalman:

  private given Releasable[KalmanFilter] = Releasable.handle(_.getNativeObjAddr)

  /** A filter tracking `initial`, ready to [[Kalman.predict]]. `processNoise` is how much the model is
    * allowed to drift (larger ⇒ more responsive, more jitter); `measurementNoise` is how much the
    * measurements are trusted (larger ⇒ smoother, laggier).
    */
  def point(initial: Point, processNoise: Double = 1e-2, measurementNoise: Double = 1e-1): Kalman =
    val kf = KalmanFilter(4, 2, 0)
    // Constant-velocity transition: x += vx, y += vy each step (dt = 1).
    Managed.use(kf.get_transitionMatrix()): t =>
      t.put(0, 0, 1.0, 0, 1.0, 0, 0, 1.0, 0, 1.0, 0, 0, 1.0, 0, 0, 0, 0, 1.0)
    // Measurement observes position only: the 2×4 identity picks x and y out of the state.
    Managed.use(kf.get_measurementMatrix())(Core.setIdentity(_))
    Managed.use(kf.get_processNoiseCov())(Core.setIdentity(_, Scalar(processNoise).toCv))
    Managed.use(kf.get_measurementNoiseCov())(Core.setIdentity(_, Scalar(measurementNoise).toCv))
    Managed.use(kf.get_errorCovPost())(Core.setIdentity(_, Scalar(1.0).toCv))
    Managed.use(kf.get_statePost())(_.put(0, 0, initial.x, initial.y, 0.0, 0.0))
    new Kalman(Managed(kf))

/** One tracked object as reported by [[ObjectTracker.update]]: a stable [[id]] that persists across frames,
  * the current [[box]], and how long the track has lived.
  */
final case class ObjectTrack(id: Int, box: Rect, hits: Int, age: Int)

/** Tracking-by-detection: turns a per-frame stream of *detections* (from any detector — faces, motion boxes,
  * a DNN) into *tracks* with stable identities. This is the "SORT-lite" pattern — the piece that lets you say
  * "person #3" frame after frame, or count how many distinct objects have passed.
  *
  * Each frame it [[ObjectTracker.update]]s: every live track is advanced by its own [[Kalman]] filter,
  * detections are matched to tracks by bounding-box overlap (IoU, greedily best-first), matched tracks are
  * corrected toward their detection, unmatched detections spawn new tracks, and tracks unseen for `maxAge`
  * frames are retired. Stateful and caller-owned — [[close]] it to free the per-track filters.
  *
  * It is detector-agnostic by design: it never looks at the image, only at the boxes, so it composes with
  * whatever produced them.
  */
final class ObjectTracker(
    iouThreshold: Double = 0.3,
    maxAge: Int = 5,
    minHits: Int = 1
) extends AutoCloseable:
  require(iouThreshold >= 0 && iouThreshold <= 1, s"iouThreshold must be in [0, 1], got $iouThreshold")
  require(maxAge >= 0, s"maxAge cannot be negative, got $maxAge")

  private final class Trk(
      val id: Int,
      val kalman: Kalman,
      var size: (Int, Int),
      var box: Rect,
      var hits: Int,
      var age: Int,
      var timeSinceUpdate: Int
  )

  private val tracks = mutable.ArrayBuffer.empty[Trk]
  private var nextId = 0
  private var total = 0

  /** How many distinct objects have ever been tracked — a running unique count. */
  def count: Int = total

  /** Advances every track, associates `detections` to them, and returns the tracks confirmed this frame (seen
    * at least `minHits` times and matched to a detection this frame), each with its stable id.
    */
  def update(detections: Seq[Rect]): Seq[ObjectTrack] =
    // 1. Predict every existing track forward one step.
    tracks.foreach: t =>
      val c = t.kalman.predict()
      t.box = ObjectTracker.centeredRect(c, t.size)
      t.age += 1
      t.timeSinceUpdate += 1

    // 2. Greedily associate detections to tracks by IoU, best overlap first.
    val matched = greedyMatch(detections)
    val matchedDets = matched.values.toSet

    // 3. Correct matched tracks toward their detection.
    matched.foreach: (ti, di) =>
      val det = detections(di)
      tracks(ti).kalman.correct(ObjectTracker.center(det))
      tracks(ti).size = (det.width, det.height)
      tracks(ti).box = det
      tracks(ti).hits += 1
      tracks(ti).timeSinceUpdate = 0

    // 4. Spawn a track for every detection that matched nothing.
    detections.indices
      .filterNot(matchedDets)
      .foreach: di =>
        val det = detections(di)
        tracks += Trk(nextId, Kalman.point(ObjectTracker.center(det)), (det.width, det.height), det, 1, 0, 0)
        nextId += 1
        total += 1

    // 5. Retire tracks unseen for too long, freeing their filters.
    val dead = tracks.filter(_.timeSinceUpdate > maxAge)
    dead.foreach(_.kalman.close())
    tracks --= dead

    // 6. Report the confirmed, freshly-seen tracks.
    tracks.iterator
      .filter(t => t.hits >= minHits && t.timeSinceUpdate == 0)
      .map(t => ObjectTrack(t.id, t.box, t.hits, t.age))
      .toSeq

  /** Greedy IoU association: track index → detection index, each used at most once. */
  private def greedyMatch(detections: Seq[Rect]): Map[Int, Int] =
    val candidates =
      for
        ti <- tracks.indices
        di <- detections.indices
        iou = ObjectTracker.iou(tracks(ti).box, detections(di))
        if iou >= iouThreshold
      yield (iou, ti, di)
    val usedTracks = mutable.Set.empty[Int]
    val usedDets = mutable.Set.empty[Int]
    val result = mutable.Map.empty[Int, Int]
    candidates
      .sortBy(-_._1)
      .foreach: (_, ti, di) =>
        if !usedTracks(ti) && !usedDets(di) then
          result(ti) = di
          usedTracks += ti
          usedDets += di
    result.toMap

  def close(): Unit =
    tracks.foreach(_.kalman.close())
    tracks.clear()

object ObjectTracker:

  private def center(r: Rect): Point = Point(r.x + r.width / 2.0, r.y + r.height / 2.0)

  private def centeredRect(c: Point, size: (Int, Int)): Rect =
    Rect(math.round(c.x - size._1 / 2.0).toInt, math.round(c.y - size._2 / 2.0).toInt, size._1, size._2)

  /** Intersection-over-union of two boxes: 0 when disjoint, 1 when identical. */
  private[scalacv] def iou(a: Rect, b: Rect): Double =
    val x1 = math.max(a.x, b.x)
    val y1 = math.max(a.y, b.y)
    val x2 = math.min(a.x + a.width, b.x + b.width)
    val y2 = math.min(a.y + a.height, b.y + b.height)
    val inter = math.max(0, x2 - x1).toDouble * math.max(0, y2 - y1)
    val union = a.area.toDouble + b.area - inter
    if union <= 0 then 0.0 else inter / union
