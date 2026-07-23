package scalacv

import java.nio.file.{Files, Path}

import org.opencv.core.Mat

/** Detects faces with YuNet — the modern detector.
  *
  * Where [[FaceDetectHaar]] runs a 2001 Haar cascade, YuNet is a small CNN (232 kB): more accurate, faster,
  * and it returns five facial landmarks and a confidence per face rather than a bare rectangle. The model is
  * downloaded and checksum-verified at runtime ([[FaceDetect.downloadModel]]) rather than vendored — a
  * licensing decision, not a size one (ROADMAP §3.5, `THIRD-PARTY.md`). `FaceDetectorYN` is one of the 185
  * types with no public `release()`, so it is taken with [[Managed.use]] and freed through the `delete(long)`
  * bridge.
  */
object FaceDetectYN:

  /** Fetches the model into `cacheDir` (a no-op once cached), builds a detector sized for `image`, and
    * detects every face in it. Any stage that fails — the download, the checksum, the model load — short
    * circuits to a `Left`, so a machine with no network degrades to a clear message rather than a crash.
    *
    * `image` must be 8-bit 3-channel BGR, which every [[Fixtures]] scene already is.
    */
  def run(image: Mat, cacheDir: Path): Either[CvError, Seq[Face]] =
    for
      model <- FaceDetect.downloadModel(cacheDir)
      detector <- FaceDetect.create(model.toString, Size(image.cols, image.rows))
    yield detector.use(d => FaceDetect.detect(d, image))

@main def yunet(): Unit =
  OpenCv.load()
  // A blank synthetic scene carries no face; like the Haar example, the point here is the wiring —
  // download, verify, build, detect, release — not a hit count. FaceDetectTest asserts real detections
  // against a drawn face.
  val cacheDir = Files.createTempDirectory("scalacv-yunet-example")
  Fixtures.shapes().use { m =>
    FaceDetectYN.run(m, cacheDir) match
      case Right(faces) =>
        println(s"found ${faces.size} face(s)")
        faces.zipWithIndex.foreach: (f, i) =>
          println(f"  #$i  box=${f.box}  score=${f.score}%.2f  eyes=${f.rightEye} ${f.leftEye}")
      case Left(err) => println(s"YuNet unavailable: ${err.getMessage}")
  }
