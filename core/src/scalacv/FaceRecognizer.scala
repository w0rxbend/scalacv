package scalacv

import java.nio.file.{Files, Paths}

import org.opencv.core.{CvType, Mat}
import org.opencv.objdetect.FaceRecognizerSF

/** A face's identity as a fixed-length embedding — a 128-dimensional vector produced by [[FaceRecognizer]].
  *
  * Two faces are compared by the *angle* between their embeddings ([[cosineSimilarity]], higher is more
  * alike) or their [[l2Distance]] (lower is more alike). The vector is plain immutable data, so it outlives
  * every native object and is cheap to store in a [[Gallery]] or a database.
  */
final case class FaceEmbedding(values: Vector[Float]):
  require(values.nonEmpty, "a face embedding cannot be empty")

  /** Cosine similarity in `[-1, 1]` — SFace's own metric. ~0.36 and above is typically the same person. */
  def cosineSimilarity(other: FaceEmbedding): Double =
    require(values.size == other.values.size, "embeddings must have the same length to compare")
    var dot, na, nb = 0.0
    var i = 0
    while i < values.size do
      dot += values(i) * other.values(i)
      na += values(i) * values(i).toDouble
      nb += other.values(i) * other.values(i).toDouble
      i += 1
    if na == 0 || nb == 0 then 0.0 else dot / (math.sqrt(na) * math.sqrt(nb))

  /** Euclidean (L2) distance between the embeddings — SFace's alternative metric, ~1.13 and below is the same
    * person.
    */
  def l2Distance(other: FaceEmbedding): Double =
    require(values.size == other.values.size, "embeddings must have the same length to compare")
    math.sqrt(values.lazyZip(other.values).map((a, b) => (a - b).toDouble * (a - b)).sum)

/** A named best match from a [[Gallery]]: who it is and how strong the cosine similarity was. */
final case class FaceMatch(name: String, similarity: Double)

/** An immutable set of enrolled faces — a "who is this?" lookup. Enroll named embeddings, then [[identify]] a
  * fresh one against them; the highest-scoring entry above the threshold wins, or `None` for a stranger.
  *
  * Immutable and value-like: [[enroll]] returns a new gallery, so a gallery is safe to share and snapshot.
  */
final class Gallery private (private val entries: Vector[(String, FaceEmbedding)]):

  /** This gallery plus one more enrolled face. The same name may be enrolled several times (different poses);
    * [[identify]] takes the best-scoring of them.
    */
  def enroll(name: String, embedding: FaceEmbedding): Gallery = new Gallery(entries :+ (name -> embedding))

  /** The best match for `embedding` at or above `threshold` cosine similarity, or `None` if no one is close
    * enough. The default threshold is SFace's recommended 0.363.
    */
  def identify(embedding: FaceEmbedding, threshold: Double = Gallery.CosineThreshold): Option[FaceMatch] =
    entries.iterator
      .map((name, e) => FaceMatch(name, e.cosineSimilarity(embedding)))
      .filter(_.similarity >= threshold)
      .maxByOption(_.similarity)

  /** Every enrolled name (with duplicates if a name was enrolled more than once). */
  def names: Seq[String] = entries.map(_._1)
  def size: Int = entries.size
  def isEmpty: Boolean = entries.isEmpty

object Gallery:

  /** SFace's recommended same-person cutoff for cosine similarity. */
  val CosineThreshold: Double = 0.363

  /** An empty gallery to enroll into. */
  val empty: Gallery = new Gallery(Vector.empty)

/** Face recognition via `org.opencv.objdetect.FaceRecognizerSF` (SFace) — turns an aligned face into an
  * embedding you can compare or look up in a [[Gallery]].
  *
  * The model is **yours to supply**, exactly as with the [[FaceDetect YuNet detector]]: download the SFace
  * ONNX (`face_recognition_sface_2021dec.onnx`, ~37 MB, from the OpenCV Zoo) and hand [[load]] its path.
  * Recognition builds on detection: [[embed]] takes a [[Face]] (from [[Image.faces]]) and the image it came
  * from, aligns and crops the face using its five landmarks, then extracts the embedding.
  *
  * {{{
  * for recognizer <- FaceRecognizer.load("sface.onnx") yield
  *   Using.resource(recognizer): rec =>
  *     val enrolled = Gallery.empty.enroll("ada", rec.embed(refImage, refFace))
  *     enrolled.identify(rec.embed(frame, face)) match
  *       case Some(FaceMatch(name, s)) => println(f"$name ($s%.2f)")
  *       case None                     => println("stranger")
  * }}}
  *
  * Owns a native recognizer — **caller-owned**, [[close]] it (or use `Using`).
  */
final class FaceRecognizer private (private val handle: Managed[FaceRecognizerSF]) extends AutoCloseable:

  /** Aligns and crops `face` out of `image` (using its landmarks) and returns its [[FaceEmbedding]]. The
    * image must be the BGR frame the face was detected in.
    */
  def embed(image: Image, face: Face): FaceEmbedding =
    val row = FaceRecognizer.faceRow(face)
    val aligned = Mat()
    val feature = Mat()
    try
      Cv.orThrow("FaceRecognizerSF.alignCrop")(handle.get.alignCrop(image.mat, row, aligned))
      Cv.orThrow("FaceRecognizerSF.feature")(handle.get.feature(aligned, feature))
      // feature() reuses an internal buffer across calls, so copy the row out before it is overwritten.
      val out = Array.ofDim[Float](feature.cols)
      feature.get(0, 0, out)
      FaceEmbedding(out.toVector)
    finally
      row.release()
      aligned.release()
      feature.release()

  def close(): Unit = handle.release()

object FaceRecognizer:

  private given Releasable[FaceRecognizerSF] = Releasable.handle(_.getNativeObjAddr)

  /** Loads an SFace recognizer from an ONNX model file. `Left` if the path has no file or the model cannot be
    * read as an SFace network.
    */
  def load(modelPath: String): Either[CvError, FaceRecognizer] =
    if !Files.isRegularFile(Paths.get(modelPath)) then
      Left(CvError.LoadFailed(modelPath, "no such file — supply the SFace ONNX model path"))
    else
      Cv.attempt(s"FaceRecognizerSF.create('$modelPath')")(FaceRecognizerSF.create(modelPath, ""))
        .map(sf => new FaceRecognizer(Managed(sf)))

  /** The 1×15 detection row SFace's `alignCrop` expects: box, five landmarks, score — the YuNet output format
    * reconstructed from a decoded [[Face]].
    */
  private def faceRow(face: Face): Mat =
    val lm = face.landmarks
    val row = Array[Float](
      face.box.x.toFloat,
      face.box.y.toFloat,
      face.box.width.toFloat,
      face.box.height.toFloat,
      lm(0).x.toFloat,
      lm(0).y.toFloat,
      lm(1).x.toFloat,
      lm(1).y.toFloat,
      lm(2).x.toFloat,
      lm(2).y.toFloat,
      lm(3).x.toFloat,
      lm(3).y.toFloat,
      lm(4).x.toFloat,
      lm(4).y.toFloat,
      face.score
    )
    val m = Mat(1, 15, CvType.CV_32F)
    m.put(0, 0, row)
    m
