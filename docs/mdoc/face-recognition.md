# Face recognition

[Detection](/object-detection) finds *where* the faces are. Recognition answers *whose* face it is. scalacv
wraps OpenCV's SFace ([`FaceRecognizerSF`](/api/core/scalacv/FaceRecognizer.html)) to turn an aligned face
into a 128-dimensional **embedding**, and gives you a [`Gallery`](/api/core/scalacv/Gallery.html) to enrol
known people and identify new faces against them.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

## The model is yours to supply

Like the [YuNet detector](/object-detection#faces), the SFace model is a file you provide — download
`face_recognition_sface_2021dec.onnx` (~37 MB) from the [OpenCV Zoo](https://github.com/opencv/opencv_zoo)
and hand its path to `FaceRecognizer.load`. It returns an `Either`, so a missing or invalid file is a value,
not an exception:

```scala mdoc:compile-only
import scala.util.Using

for recognizer <- FaceRecognizer.load("sface.onnx") yield
  Using.resource(recognizer): rec =>
    // ... use rec.embed(...)
    ()
```

## From a detected face to an embedding

Recognition builds on detection: `embed` takes a [`Face`](/api/core/scalacv/Face.html) (from
[`Image.faces`](/object-detection#faces)) and the frame it came from, aligns and crops it using the five
landmarks, and returns the embedding.

```scala mdoc:compile-only
FaceRecognizer.load("sface.onnx").foreach { recognizer =>
  val detector = FaceDetect.create("yunet.onnx", Size(320, 320)).toOption.get
  Image.reading("photo.jpg") { img =>
    val face = img.faces(detector.get).head
    val embedding: FaceEmbedding = recognizer.embed(img, face)
    embedding.values.size // 128
  }
  detector.release()
  recognizer.close()
}
```

## Comparing embeddings

Two faces are compared by the angle between their embeddings. `cosineSimilarity` is SFace's own metric
(higher is more alike; ~0.36 and up is typically the same person); `l2Distance` is the Euclidean alternative
(lower is more alike). Because an embedding is plain immutable data, it outlives every native object and
stores cheaply.

```scala mdoc
{
  // Stand-in vectors so the page runs without a model — real ones come from `embed`.
  val a = FaceEmbedding(Vector(1.0f, 0.2f, 0.1f))
  val b = FaceEmbedding(Vector(0.95f, 0.25f, 0.15f)) // a lookalike
  val c = FaceEmbedding(Vector(-1.0f, -0.3f, 0.4f))  // someone else
  f"same-ish: ${a.cosineSimilarity(b)}%.3f, different: ${a.cosineSimilarity(c)}%.3f"
}
```

## Enrolling and identifying: the Gallery

A `Gallery` is an immutable "who is this?" lookup. Enrol named embeddings (a name may be enrolled several
times, for different poses), then `identify` a fresh face — the best match above the threshold wins, or
`None` for a stranger. The default threshold is SFace's recommended `0.363`.

```scala mdoc
{
  val gallery = Gallery.empty
    .enroll("ada", FaceEmbedding(Vector(1.0f, 0.2f, 0.1f)))
    .enroll("grace", FaceEmbedding(Vector(0.1f, 1.0f, 0.2f)))

  val probe = FaceEmbedding(Vector(0.95f, 0.25f, 0.15f)) // looks like ada
  gallery.identify(probe) match
    case Some(FaceMatch(name, s)) => f"recognised $name ($s%.3f)"
    case None                     => "stranger"
}
```

Enrolment is a value operation — `enroll` returns a new gallery — so a gallery is safe to share, snapshot, or
persist (serialise the `(name, embedding.values)` pairs).

## Putting it together

The end-to-end shape is: detect faces once to enrol your known people, then for each incoming frame detect,
embed, and `identify`:

```scala mdoc:compile-only
FaceRecognizer.load("sface.onnx").foreach { rec =>
  val detector = FaceDetect.create("yunet.onnx", Size(320, 320)).toOption.get

  // Enrol from labelled reference photos.
  var gallery = Gallery.empty
  for (name, path) <- Seq("ada" -> "ada.jpg", "grace" -> "grace.jpg") do
    Image.reading(path) { ref =>
      ref.faces(detector.get).headOption.foreach(f => gallery = gallery.enroll(name, rec.embed(ref, f)))
    }

  // Identify everyone in a new frame.
  Image.reading("group.jpg") { frame =>
    for face <- frame.copy.faces(detector.get) do
      println(gallery.identify(rec.embed(frame.copy, face)))
  }

  detector.release()
  rec.close()
}
```
