package scalacv

/** Face recognition, headless: the [[Gallery]] enrol/identify flow shown with stand-in embeddings (so it runs
  * with no model). With a real SFace model you would build these embeddings from detected faces via
  * `FaceRecognizer.embed`; the comparison and lookup are exactly the same.
  */
@main def faceRecognitionDemo(): Unit =
  OpenCv.load()

  // In a real pipeline: recognizer.embed(image, face). Here, illustrative fixed vectors.
  val ada = FaceEmbedding(Vector(1.0f, 0.2f, 0.1f))
  val grace = FaceEmbedding(Vector(0.1f, 1.0f, 0.2f))
  val gallery = Gallery.empty.enroll("ada", ada).enroll("grace", grace)

  // A fresh face that looks a lot like ada.
  val probe = FaceEmbedding(Vector(0.95f, 0.25f, 0.15f))
  gallery.identify(probe) match
    case Some(FaceMatch(name, similarity)) => println(f"recognised $name (cosine $similarity%.3f)")
    case None => println("stranger — no match above the threshold")

  // A genuine stranger is rejected.
  val stranger = FaceEmbedding(Vector(-1.0f, -0.3f, 0.4f))
  println(s"stranger identified as: ${gallery.identify(stranger)}")

  println(s"enrolled: ${gallery.names.mkString(", ")}")
  println("OK")
