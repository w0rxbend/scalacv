package scalacv

import org.opencv.core.{Mat, MatOfPoint2f, MatOfPoint3f, Point as CvPoint, Point3}
import org.opencv.dnn.Net

/** One named landmark of a [[Pose]] — a point in image pixels and the model's confidence in it. */
final case class Keypoint(name: String, point: Point, score: Float)

/** A keypoint naming and connectivity scheme — the "which landmark is which, and which bones connect them"
  * that a pose model implies but does not carry.
  *
  * @param names
  *   the keypoint names, in the model's output order.
  * @param edges
  *   index pairs that form the skeleton's bones (for drawing).
  */
final case class PoseTopology(names: Seq[String], edges: Seq[(Int, Int)]):
  require(
    edges.forall((a, b) => a >= 0 && a < names.size && b >= 0 && b < names.size),
    "every edge must reference valid keypoint indices"
  )

  /** The number of keypoints this topology expects. */
  def size: Int = names.size

object PoseTopology:

  /** The 17-keypoint COCO body layout used by MoveNet and OpenPose(COCO). */
  val CocoBody17: PoseTopology = PoseTopology(
    names = Seq(
      "nose",
      "left_eye",
      "right_eye",
      "left_ear",
      "right_ear",
      "left_shoulder",
      "right_shoulder",
      "left_elbow",
      "right_elbow",
      "left_wrist",
      "right_wrist",
      "left_hip",
      "right_hip",
      "left_knee",
      "right_knee",
      "left_ankle",
      "right_ankle"
    ),
    edges = Seq(
      (0, 1),
      (0, 2),
      (1, 3),
      (2, 4), // head
      (5, 6), // shoulders
      (5, 7),
      (7, 9), // left arm
      (6, 8),
      (8, 10), // right arm
      (5, 11),
      (6, 12),
      (11, 12), // torso
      (11, 13),
      (13, 15), // left leg
      (12, 14),
      (14, 16) // right leg
    )
  )

  /** The 21-landmark hand layout used by MediaPipe Hands: wrist, then thumb→pinky, four points each. */
  val Hand21: PoseTopology =
    val fingers = Seq("thumb", "index", "middle", "ring", "pinky")
    val joints = Seq("cmc", "mcp", "ip", "tip") // thumb naming; the others read mcp/pip/dip/tip but the
    // count is what matters for the skeleton, so a uniform four-per-finger naming keeps it simple.
    val names = "wrist" +: fingers.flatMap(f => joints.map(j => s"${f}_$j"))
    val edges = fingers.zipWithIndex.flatMap: (_, fi) =>
      val base = 1 + fi * 4
      Seq((0, base)) ++ (0 until 3).map(k => (base + k, base + k + 1))
    PoseTopology(names, edges)

/** A detected pose — an ordered set of [[Keypoint]]s for a known [[PoseTopology]], as plain immutable data
  * that stays valid after the frame and the network output are freed.
  */
final case class Pose(keypoints: Seq[Keypoint], topology: PoseTopology):

  /** The keypoint with this name, if the model reported it. */
  def apply(name: String): Option[Keypoint] = keypoints.find(_.name == name)

  /** Keypoints at or above `minScore`. */
  def confident(minScore: Float = 0.3f): Seq[Keypoint] = keypoints.filter(_.score >= minScore)

  /** Mean confidence across all keypoints — a quick "is there a pose here at all" score. */
  def meanScore: Float = if keypoints.isEmpty then 0f else keypoints.map(_.score).sum / keypoints.size

  /** The skeleton's bones as point pairs, keeping only those whose **both** endpoints clear `minScore`. Ready
    * to draw.
    */
  def bones(minScore: Float = 0.3f): Seq[(Point, Point)] =
    topology.edges.collect:
      case (a, b) if keypoints(a).score >= minScore && keypoints(b).score >= minScore =>
        (keypoints(a).point, keypoints(b).point)

/** How a pose network encodes its keypoints in the tensor `forward` returns — see [[PoseEstimator]]. */
enum KeypointLayout:

  /** Direct regression, `[1, 1, K, 3]` rows of `(y, x, score)` normalised to `[0, 1]` — MoveNet's format. */
  case Regression

  /** One heatmap per keypoint, `[1, K, H, W]`; each keypoint is the arg-max of its plane — OpenPose's format.
    */
  case Heatmap

/** Human-pose (skeleton) estimation over a keypoint network run through [[Dnn]].
  *
  * MediaPipe's models ship as TFLite; OpenCV's inference path — and therefore scalacv's — is ONNX, so this is
  * built the way [[FaceDetect]] and [[Dnn]] are: **you bring the model** (`Dnn.fromOnnx`), and scalacv
  * provides the typed result and the decode. The two common output layouts are both handled
  * ([[PoseEstimator.decode]]), so a MoveNet or an OpenPose export drops in by naming its [[KeypointLayout]]
  * and [[PoseTopology]].
  *
  * {{{
  * // With a caller-loaded Net (see Dnn):
  * val pose = Dnn.blobFromImage(image.mat, size = Some(Size(192, 192)), swapRB = true).use { blob =>
  *   Dnn.forward(net, blob).use { out =>
  *     PoseEstimator.decode(out.mat, image.size, KeypointLayout.Regression)
  *   }
  * }
  * }}}
  *
  * For hand and head pose see [[PoseTopology.Hand21]] and [[HeadPose]].
  */
object PoseEstimator:

  /** Decodes a network's output tensor into a [[Pose]] in image pixels.
    *
    * @param output
    *   the Mat from `Dnn.forward`.
    * @param imageSize
    *   the size of the image the keypoints should be scaled to.
    * @param layout
    *   how the tensor encodes keypoints — see [[KeypointLayout]].
    * @param topology
    *   the naming/connectivity; its `size` must match the model's keypoint count.
    */
  def decode(
      output: Mat,
      imageSize: Size,
      layout: KeypointLayout,
      topology: PoseTopology = PoseTopology.CocoBody17
  ): Pose =
    layout match
      case KeypointLayout.Regression => decodeRegression(output, imageSize, topology)
      case KeypointLayout.Heatmap => decodeHeatmap(output, imageSize, topology)

  /** `[1, 1, K, 3]` rows of `(y, x, score)`, normalised — MoveNet. */
  private def decodeRegression(output: Mat, imageSize: Size, topology: PoseTopology): Pose =
    val k = topology.size
    // Validate before reshape: a model whose output is not K×(y,x,score) makes `reshape` throw a raw
    // CvException about total-size mismatch. Name it instead, the way FaceDetect names a wrong column count.
    val expected = k * 3
    if output.total() != expected then
      throw CvError.NativeCall(
        "decoding the pose regression output",
        IllegalStateException(
          s"expected $expected values (K=$k keypoints × (y, x, score)) but the model produced ${output.total()}. " +
            "This is not the regression pose model this topology decodes."
        )
      )
    Managed.use(output.reshape(1, k)): flat => // k rows x 3 cols (y, x, score)
      val row = Array.ofDim[Float](3)
      val kps = (0 until k).map: i =>
        flat.get(i, 0, row)
        Keypoint(topology.names(i), Point(row(1) * imageSize.width, row(0) * imageSize.height), row(2))
      Pose(kps.toSeq, topology)

  /** `[1, K, H, W]` — one heatmap per keypoint; each keypoint is the arg-max of its plane. OpenPose. */
  private def decodeHeatmap(output: Mat, imageSize: Size, topology: PoseTopology): Pose =
    // `size(1..3)` reads dimensions that only exist on a 4-D [1, K, H, W] tensor; on anything else it throws a
    // raw CvException. Name the shape mismatch up front, mirroring FaceDetect's wrong-column-count error.
    if output.dims() != 4 then
      throw CvError.NativeCall(
        "decoding the pose heatmap output",
        IllegalStateException(
          s"expected a 4-D [1, K, H, W] heatmap tensor but the model produced a ${output.dims()}-D output. " +
            "This is not the heatmap pose model this topology decodes."
        )
      )
    val k = output.size(1)
    val h = output.size(2)
    val w = output.size(3)
    require(k == topology.size, s"the model has $k keypoints but ${topology.names.size} were named")
    Managed.use(output.reshape(1, k)): flat => // k rows x (h*w) cols
      val plane = Array.ofDim[Float](h * w)
      val kps = (0 until k).map: c =>
        flat.get(c, 0, plane)
        var best = 0
        var bestVal = plane(0)
        var idx = 1
        while idx < plane.length do
          if plane(idx) > bestVal then
            bestVal = plane(idx)
            best = idx
          idx += 1
        val py = best / w
        val px = best % w
        Keypoint(
          topology.names(c),
          Point(px.toDouble / w * imageSize.width, py.toDouble / h * imageSize.height),
          bestVal
        )
      Pose(kps.toSeq, topology)

/** Head orientation in degrees — the classic yaw / pitch / roll. */
final case class HeadPose(yaw: Double, pitch: Double, roll: Double)

/** Head-pose estimation from a detected [[Face]]'s five landmarks, via `solvePnP` against a canonical 3D face
  * model. No extra model file — it reuses what [[FaceDetect]] already gives you.
  *
  * The 3D reference is an approximate generic head, so the angles are indicative rather than metric: good for
  * "looking left / up / tilted", not for a calibrated measurement. For that, a dedicated head-pose network
  * (run through [[Dnn]]) and a calibrated camera matrix are the way.
  */
object HeadPose:

  // Canonical 3D landmark positions (arbitrary units), in the same order as Face's landmarks:
  // right eye, left eye, nose tip, right mouth corner, left mouth corner. X→image-right, Y→image-down,
  // Z→away from the camera; the nose tip is the origin and protrudes toward the viewer.
  private val model = Array(
    Point3(-45, -34, 27), // right eye  (subject's right -> image left)
    Point3(45, -34, 27), // left eye
    Point3(0, 0, 0), // nose tip
    Point3(-30, 35, 22), // right mouth corner
    Point3(30, 35, 22) // left mouth corner
  )

  /** Estimates the head orientation for `face` under the given camera [[Intrinsics]], or `None` if `solvePnP`
    * fails to converge (degenerate landmarks). Pass a calibrated model for a sharper result; for a quick
    * uncalibrated guess use the [[Size]] overload.
    */
  def estimate(face: Face, intrinsics: Intrinsics): Option[HeadPose] =
    // Every native Mat is acquired through Managed.use, so a throw from any constructor frees the ones
    // already allocated — the plain val-before-try form leaked them. The whole solvePnP block runs in
    // Cv.attempt: degenerate landmarks can make OpenCV *throw* rather than return `ok = false`, and the
    // documented contract here is `None` on failure, not a raw CvException.
    Managed.use(MatOfPoint3f(model*)): objectPoints =>
      Managed.use(MatOfPoint2f(face.landmarks.map(p => CvPoint(p.x, p.y))*)): imagePoints =>
        Managed.use(intrinsics.cameraMatrix): camera =>
          Managed.use(intrinsics.distCoeffs): distortion =>
            Managed.use(Mat()): rvec =>
              Managed.use(Mat()): tvec =>
                Cv.attempt("solvePnP") {
                  val ok = org.opencv.calib3d.Calib3d.solvePnP(
                    objectPoints,
                    imagePoints,
                    camera,
                    distortion,
                    rvec,
                    tvec,
                    false,
                    org.opencv.calib3d.Calib3d.SOLVEPNP_EPNP
                  )
                  if !ok then None
                  else
                    Managed.use(Mat()): rotation =>
                      org.opencv.calib3d.Calib3d.Rodrigues(rvec, rotation)
                      Managed.use(Mat()): mtxR =>
                        Managed.use(Mat()): mtxQ =>
                          // RQDecomp3x3 returns the Euler angles (degrees) about x, y, z.
                          val euler = org.opencv.calib3d.Calib3d.RQDecomp3x3(rotation, mtxR, mtxQ)
                          Some(HeadPose(yaw = euler(1), pitch = euler(0), roll = euler(2)))
                }.getOrElse(None)

  /** Estimates the head orientation for `face` in an image of `imageSize`, using an uncalibrated pinhole
    * guess — focal length ≈ image width, principal point at the centre, no lens distortion. Enough for
    * "looking left / up / tilted"; pass real [[Intrinsics]] when you have them.
    */
  def estimate(face: Face, imageSize: Size): Option[HeadPose] =
    estimate(face, Intrinsics(imageSize.width, imageSize.width, imageSize.width / 2, imageSize.height / 2))

/** The high-level pose overlay on [[Image]] — an extension method so it lives beside the pose types rather
  * than in the image class. `import scalacv.*` gives `image.drawSkeleton(pose)`.
  */
extension (img: Image)

  /** Runs a keypoint `Net` over this image and decodes a [[Pose]] — the one-call form of the blob → forward →
    * decode dance, the pose counterpart to `image.faces(detector)`. The image is only read from (it stays
    * alive), and the network is borrowed, not released.
    *
    * The blob knobs mirror [[Dnn.blobFromImage]] and are model-specific — the defaults suit a MoveNet-style
    * export (RGB input, `[0, 1]` range); pass the values your model documents. For a network whose output
    * layout or keypoint scheme differs, name the [[KeypointLayout]] and [[PoseTopology]]. When you need the
    * intermediate blob or output tensor, drop to [[Dnn]] and [[PoseEstimator.decode]] directly.
    *
    * @param net
    *   a caller-owned pose network (see [[Dnn.fromOnnx]]). Stateful — do not share one across threads.
    * @param inputSize
    *   the spatial size the network expects, e.g. `Size(192, 192)` for MoveNet Lightning.
    * @param layout
    *   how the output tensor encodes keypoints — see [[KeypointLayout]].
    */
  def estimatePose(
      net: Net,
      inputSize: Size,
      layout: KeypointLayout,
      topology: PoseTopology = PoseTopology.CocoBody17,
      scaleFactor: Double = 1.0 / 255,
      mean: Scalar = Scalar(0, 0, 0),
      swapRB: Boolean = true
  ): Pose =
    Dnn
      .blobFromImage(img.mat, scaleFactor, Some(inputSize), mean, swapRB)
      .use: blob =>
        Dnn
          .forward(net, blob)
          .use: out =>
            PoseEstimator.decode(out, img.size, layout, topology)

  /** Draws a [[Pose]] skeleton: a line per bone and a dot per confident keypoint. */
  def drawSkeleton(
      pose: Pose,
      minScore: Float = 0.3f,
      color: Scalar = Scalar.Green,
      jointColor: Scalar = Scalar.Red
  ): Image =
    img.paint: m =>
      pose.bones(minScore).foreach((a, b) => m.drawLine(a, b, color, Thickness.Stroke(2)))
      pose.confident(minScore).foreach(kp => m.drawCircle(kp.point, 3, jointColor, Thickness.Filled))
