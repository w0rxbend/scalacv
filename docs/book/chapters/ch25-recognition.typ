#import "../lib/book.typ": *

#chapter("Face Recognition", subtitle: [From "there is a face" to "this is the same face".])

The detector in Chapter 24 answers a question about geometry. It hands back a `Face` --- a box, five
landmarks, a score --- and every one of those numbers is a statement about pixels. Nothing in it
knows who the person is, and nothing in it can be made to know: YuNet was trained to find faces in
general, and it finds yours exactly as readily as it finds a stranger's.

The obvious way to close that gap is to train a classifier with one output per person. It is also
the wrong shape for almost every system anyone actually builds. A classifier has to be retrained
when somebody joins, retrained when somebody leaves, and it needs enough labelled images of each
person to learn a decision boundary --- which is precisely what you do not have on the day a new
employee arrives with one photograph from their pass. Worse, a fixed set of outputs cannot say
"none of these"; softmax always sums to one, so the stranger who walks past the camera is
assigned, with confidence, to whichever of your staff he most resembles.

Recognition is done the other way round. A network is trained once, on faces it will never see
again, with an objective that has nothing to do with your particular people: map a face image to a
fixed-length vector such that two pictures of one person land close together and pictures of
different people land far apart. The identities in the training set are scaffolding; what is kept is
the mapping. Feed it a face it has never encountered and it still places that face somewhere
sensible, because it learned a geometry rather than a list.

That turns identification into a lookup. Store one vector per enrolled person, embed the face in
front of you, take the nearest stored vector, and accept it only if it is near enough. Adding a
person is appending a row. Removing one is deleting a row. And "none of these" falls out of the
threshold rather than having to be modelled --- which, as this chapter will keep insisting, is the
case people forget to write and the case that decides whether the system is usable at all.

#sect("The embedding, and what it is made of")

scalacv wraps OpenCV's SFace recogniser, `org.opencv.objdetect.FaceRecognizerSF`, behind
`FaceRecognizer`. Like the detector of Chapter 24 it lives in `scalacv-vision`, so everything in this
chapter needs that module on the classpath as well as core. It produces a `FaceEmbedding`:

```scala
final case class FaceEmbedding(values: Vector[Float])
```

That is the whole type. A hundred and twenty-eight `Float`s in an immutable `Vector`, with a
`require` that rejects an empty one. There is no native handle in it, no `Mat`, nothing to release.
This matters more than it looks: an embedding outlives every native object that produced it, so you
can free the recogniser, free the frame, shut the camera down, and the vector still compares. It
serialises like any other `Vector[Float]`, and it goes into a database column without ceremony.

The three-step model is worth holding in your head, because each step lives in a different part of
the library and the middle one is the only one that needs a neural network:

```text
detect a face   ->   embed it into a 128-vector   ->   compare or look it up
 YuNet, Face          SFace, FaceEmbedding           cosineSimilarity, Gallery
```

#sect("Two models, and neither ships with the library")

Recognition needs both networks. The YuNet detector from Chapter 24 finds the face and, critically,
supplies the five landmarks SFace uses to align it. SFace itself is a separate ONNX file,
`face_recognition_sface_2021dec.onnx`, about 37 MB, from the OpenCV Zoo. Neither model is committed
to this repository: `THIRD-PARTY.md` records YuNet as downloaded rather than vendored precisely
because redistributing it would oblige scalacv to reproduce its notice, and no model file ships in
the published jars at all.

`FaceRecognizer.modelSpec` is the registry entry for SFace: a `ModelSpec` like any other, carrying a
file name, a list of URLs, and a pinned SHA-256 that `Models.fetch` checks before OpenCV is ever
shown the bytes. It is leaner than `FaceDetect.modelSpec`, which has two mirrors and a `sizeBytes` of
`232589` against SFace's one Zoo URL and no size. `sizeBytes` is optional and only ever improves the
failure message: a mirror that answers a model request with an HTML error page reports
#emph[expected 232589 bytes, got 131] for YuNet, and a bare SHA-256 mismatch for SFace. Both
downloads are equally safe; one of them explains itself better.

#example("Both models, fetched and checksum-verified, into one directory.")[
```scala
import java.nio.file.Path
import scalacv.*

OpenCv.load()
val models = Path.of("models")

val yunet: Either[CvError, Path] = FaceDetect.downloadModel(models)
val sface: Either[CvError, Path] = Models.fetch(FaceRecognizer.modelSpec, models)
```
]

`FaceRecognizer.load` takes the path and returns an `Either`, on the same terms as every other model
loader in the book. A path with no file at it comes back as `CvError.LoadFailed` carrying the
message #emph[no such file --- supply the SFace ONNX model path]; a file that is not an SFace
network fails inside OpenCV's ONNX importer and is caught into a `Left` as well. Neither is an
exception, because neither is a bug --- mistyping a path is a thing users do.

#memory[
  A `FaceRecognizer` owns a native `FaceRecognizerSF`, and behind that ONNX handle sit the model's
  weights in full --- the ~37 MB the file carries, parsed into native memory, against a Scala object
  the heap measures in tens of bytes. `FaceRecognizerSF` is one of the 185 generated binding types
  with no public `release()`, so the class holds a `Managed[FaceRecognizerSF]` built with
  `Releasable.nativeHandle` --- the same bridge `FaceDetect` uses for `FaceDetectorYN`, and for the
  same reason: it reads the native address and disarms the binding's unconditional `finalize()`
  before freeing the pointer, without which a released handle is a live double-free.
  `FaceRecognizer` is `AutoCloseable` and its `close()` calls `handle.release()`. Close it ---
  `Using.resource`, or explicitly in a `finally`. Leaking one in a loop that rebuilds the recogniser
  per request is the fastest way to reproduce Chapter 1's measurement: flat heap, rising
  resident set, an OOM kill with no Java stack trace.
]

#sect("From a Face to a vector")

`embed` is the entire inference surface:

```scala
def embed(image: Image, face: Face): FaceEmbedding
```

Two arguments, and the relationship between them is the thing to get right. `image` must be the BGR
frame the `Face` was detected in, because a `Face` is nothing but coordinates into that exact image.
`embed` borrows both --- it consumes neither, so you can detect once and embed every face in the
frame inside a single `Image.reading` block with no `.copy` anywhere.

Internally it rebuilds the 1×15 detection row SFace's `alignCrop` expects (box, five landmark pairs,
score --- YuNet's own output format, reconstructed from the decoded `Face`), calls `alignCrop` to
produce a canonical crop, then `feature` to produce the vector, and copies the numbers out of
OpenCV's result Mat. All of it happens inside a `Managed.scope`, so the three intermediate native
objects are released whether the call returns or throws.

#example("Detect and embed inside one borrow of the frame.")[
```scala
Image.reading("ada.jpg") { img =>
  img.faces(detector).headOption.map(face => rec.embed(img, face))
}
```
]

#subsect("Why you pass the frame and not a crop")

The first thing a reader reaches for is to cut the face out and hand the recogniser a tidy thumbnail.
`Face.clippedBox` even exists to make that crop safe. It is the wrong move here, and it fails
quietly.

#example("Wrong: the landmarks index the frame, not the thumbnail.")[
```scala
import scala.util.Using

Image.reading("group.jpg") { frame =>
  val face = frame.faces(detector).head
  Using.resource(frame.copy.crop(face.clippedBox(frame).get)): thumb =>
    rec.embed(thumb, face) // the five points now land somewhere else entirely
}
```
]

The lifetimes in that listing are all correct --- `copy` gives an owned `Image`, `crop` consumes it
and hands back another, and `Using.resource` closes the survivor --- which is part of why the bug is
hard to see. The only thing wrong with it is the argument to `embed`.

A face 600 pixels into a group photograph has landmarks with x-coordinates near 600. Crop the box
out and the same eye is now 20 pixels from the left edge of a 90-pixel thumbnail, but the `Face` you
passed still says 600. If you are lucky, `alignCrop` rejects the geometry and you get a
`CvError.NativeCall`. If you are not, it aligns on whatever happens to be at those coordinates and
returns a perfectly well-formed 128-vector describing nothing --- which then goes into your gallery,
scores badly against the person it belongs to, and occasionally scores well against someone else.

The right version has nothing removed from it but the crop:

#example("Right: the frame the detector saw, and the face as detected.")[
```scala
Image.reading("group.jpg") { frame =>
  val face = frame.faces(detector).head
  rec.embed(frame, face)
}
```
]

Alignment is not a formality that a bigger network would let you skip. SFace was trained on faces
warped to a canonical position using exactly these five points --- subject's right eye, left eye,
nose tip, right mouth corner, left mouth corner, in that order, as Chapter 24 sets out. A head
tilted fifteen degrees is, to a raw crop, a different picture; after `alignCrop` it is the same
picture. That warp is where most of the pose robustness in the system comes from, and it is free,
because the detector already produced the landmarks it needs.

#warning[
  A `FaceRecognizer` is one per thread, exactly like the detector. `alignCrop` and `feature` both run
  against a single native object, and `FaceRecognizerSF.feature()` writes into an internal buffer that
  `embed` copies its numbers out of. If a second thread calls `embed` between that write and that
  copy, the first thread copies out the other face's vector --- no exception, no warning, an
  embedding belonging to somebody else. Build one recogniser per worker, or guard a shared one with a
  lock. `Gallery` and `FaceEmbedding` are immutable and genuinely safe to share.
]

#sect("Comparing two embeddings")

`FaceEmbedding` carries both of SFace's metrics. They point in opposite directions, which is the one
thing to keep straight.

#figure-table("SFace's two metrics, their direction, and their published same-person cutoffs.")[
#tbl(
  columns: (1.1fr, 1.1fr, 0.9fr, 1.2fr),
  [Metric], [Direction], [Same person], [Call],
  [Cosine similarity], [higher is more alike, in `[-1, 1]`], [0.363 or above], [`a.cosineSimilarity(b)`],
  [L2 distance], [lower is more alike, from 0], [about 1.13 or below], [`a.l2Distance(b)`],
)
]

Both return a `Double`, and both `require` that the two embeddings have the same length --- a check
that only bites when you mix a real 128-dimensional vector with a hand-written stand-in, which is
worth doing in tests and worth never doing anywhere else.

Pick one metric and stay with it. They are not interchangeable at the call site: a 0.4 that means
"same person" under cosine means "obviously not" under L2, and a comparison written against the
wrong direction inverts your whole system without ever throwing. `Gallery` uses cosine similarity,
and `Gallery.CosineThreshold` is the only cutoff the library exposes as a constant; the 1.13 figure
lives in `l2Distance`'s scaladoc and nowhere else, so a lookup keyed on L2 distance is one you write
yourself. That asymmetry is a reason to stay on cosine unless you have a specific need not to.

#sidebar("Why the metrics do their arithmetic in Double")[
  Both loops widen each `Float` to `Double` before multiplying or subtracting, rather than
  accumulating in `Float` and widening the sum. It is a deliberate correction, not idiom. `a * b` on
  two `Float`s is `Float` multiplication, so every term of the dot product was being rounded to 24
  bits of mantissa while the two norms, which did carry a `.toDouble`, were not --- three accumulators
  in one expression disagreeing about their own arithmetic. The comparison downstream is against a
  fixed cutoff, so a systematic rounding bias is precisely the thing that can nudge a borderline face
  across the line. Neither loop allocates an intermediate collection either: `identify` runs one of
  them once per enrolled face, and a 128-element `Vector` per comparison is a per-lookup cost with
  nothing to show for it.
]

#sect("Thresholds, and the trade you are actually making")

`Gallery.CosineThreshold` is `0.363`. Its scaladoc says what it is and no more: SFace's recommended
same-person cutoff for cosine similarity. It is not a number this library tuned, it is not a number
measured on your data, and it knows nothing about your camera, your lighting, or your population.

Treat it as the place to start, not the place to stop. The threshold is a dial with a cost at each
end and no setting that has neither:

- Lower it and you get #emph[false accepts]: strangers matched to enrolled people. In an access
  control system that is the failure that matters, and it is silent --- the door opens, nobody
  files a ticket.
- Raise it and you get #emph[false rejects]: enrolled people reported as unknown. This failure is
  loud, annoying, and self-reporting, which is why it is the safer direction to err in for anything
  that gates access.

Which end costs more is a property of your application, not of the model, and it is the one design
decision here that a library cannot make for you. `identify` therefore takes the threshold as an
argument with the recommended value as its default:

```scala
def identify(embedding: FaceEmbedding, threshold: Double = Gallery.CosineThreshold): Option[FaceMatch]
```

Do not tune it by intuition on the three photographs you happen to have. Collect pairs you know are
the same person and pairs you know are not, score them all, and look at where the two distributions
overlap. If they barely overlap, the exact cutoff hardly matters. If they overlap heavily, no cutoff
will save you and the problem is upstream --- bad crops, missed detections, or too few enrolments.

#sect("The Gallery")

`Gallery` is the lookup, and it is a value.

#example("The gallery's whole surface.")[
```scala
val gallery = Gallery.empty
  .enroll("ada", adaFrontal)
  .enroll("ada", adaProfile) // the same name, a second pose
  .enroll("grace", graceFrontal)

gallery.size      // 3
gallery.names     // Seq("ada", "ada", "grace") — duplicates included
gallery.isEmpty   // false

gallery.identify(probe) match
  case Some(FaceMatch(name, similarity)) => f"$name ($similarity%.3f)"
  case None                              => "unknown"
```
]

`enroll` returns a new gallery rather than mutating the one you had, so a gallery is safe to share
across threads, to snapshot, and to hold as a `val` while a background job builds the next version.
`identify` scores the probe against every entry, discards everything below the threshold, and returns
the highest of what is left as a `FaceMatch(name, similarity)` --- or `None`.

That `None` is the case to write first. A recogniser deployed anywhere real sees strangers far more
often than it sees enrolled people, and a pipeline whose only branch is `Some` will either crash on
the first passer-by or, worse, be written with a `.getOrElse("unknown")` that quietly discards the
score you needed for the audit log. Keep the similarity: it is the only evidence you have about how
close the call was.

#subsect("A gallery is an index, not your store of record")

`Gallery` keeps its entries in a `private val`, and its public surface is five members: `enroll`,
`identify`, `names`, `size` and `isEmpty`. `names` gives you the names --- with duplicates, in
enrolment order --- and nothing gives you the embeddings back. That is deliberate, and it decides
where persistence has to live: you cannot save a `Gallery`, because you cannot read one.

So keep the enrolments yourself and treat the gallery as something you build from them. A
`FaceEmbedding` is a `Vector[Float]` in a wrapper, so the pair is already the storable form:

#example("Enrolments are the durable thing; the gallery is derived from them.")[
```scala
final case class Enrolment(name: String, embedding: FaceEmbedding)

def galleryOf(enrolments: Seq[Enrolment]): Gallery =
  enrolments.foldLeft(Gallery.empty)((g, e) => g.enroll(e.name, e.embedding))
```
]

Rebuilding is cheap --- `enroll` appends to a `Vector` --- so a service that reloads its enrolments
on a schedule can build a fresh gallery and swap the reference, with readers on the old one
unaffected because neither value ever changes.

#sect("Enrolment: several shots, and what to do with them")

One reference photograph per person works in a demo and disappoints in a corridor. The variation that
breaks recognition is not identity, it is pose, illumination and expression --- a face lit from the
side, turned twenty degrees, or mid-sentence, embeds noticeably further from its own frontal portrait
than you would like.

The fix is more enrolments per person, deliberately varied: frontal and turned each way, with and
without glasses, under whatever lighting the deployment actually has. `Gallery` supports this
directly --- the same name may be enrolled any number of times, and `identify` takes the best-scoring
of a person's entries. Five references cost five dot products per lookup, which for a gallery of any
plausible size is nothing.

The alternative is to average a person's embeddings into one vector and enrol that. It halves the
lookup work and smooths out a single unlucky shot, and it costs you the ability to match a pose that
sits far from the mean --- an average of a frontal and two profiles resembles none of them
especially well. Because an embedding is plain data, you can build the mean yourself:

#example("Averaging several embeddings into one enrolment.")[
```scala
def mean(embeddings: Seq[FaceEmbedding]): FaceEmbedding =
  require(embeddings.nonEmpty, "no embeddings to average")
  val n = embeddings.head.values.size
  val sums = Array.ofDim[Double](n)
  for e <- embeddings; i <- 0 until n do sums(i) += e.values(i).toDouble
  FaceEmbedding(sums.map(s => (s / embeddings.size).toFloat).toVector)
```
]

The division by `embeddings.size` changes no cosine score whatsoever --- `cosineSimilarity` divides
through by both norms, so it is blind to scale --- but keep it anyway, or `l2Distance` against that
entry becomes meaningless.

The default advice is to keep them all. Store the individual vectors even if you enrol an average as
well, because you can always recompute the average from the vectors and you can never recover the
vectors from the average, and the day you want to re-tune your threshold you will want the raw
distribution.

#sect("The worked example")

Everything above, assembled: fetch both models, enrol a handful of named people from labelled
photographs, then identify every face in a new frame, reporting a score or an honest `unknown`.

#example("Enrol a gallery from labelled photographs.")[
```scala
import org.opencv.objdetect.FaceDetectorYN

def enrol(
    detector: Managed[FaceDetectorYN],
    rec: FaceRecognizer,
    people: Seq[(String, Seq[String])]
): Gallery =
  people.foldLeft(Gallery.empty) { case (g0, (name, paths)) =>
    paths.foldLeft(g0) { (g, path) =>
      Image
        .reading(path) { img =>
          img.faces(detector).headOption match
            case Some(face) => g.enroll(name, rec.embed(img, face))
            case None       => g // no face in the reference shot; nothing to enrol
        }
        .getOrElse(g) // an unreadable file leaves the gallery as it was
    }
  }
```
]

Two failures are handled there and neither is an exception. A reference photograph with no detectable
face contributes nothing, and a file that will not read leaves the gallery unchanged --- `reading`
hands back a `Left` and `getOrElse` absorbs it. In a real enrolment tool you would report both rather
than swallow them; the shape is what matters here.

#example("Identify every face in a frame, unknowns included.")[
```scala
final case class Sighting(box: Rect, name: Option[String], similarity: Double)

def identifyAll(
    gallery: Gallery,
    detector: Managed[FaceDetectorYN],
    rec: FaceRecognizer,
    path: String
): Either[CvError, Seq[Sighting]] =
  Image.reading(path) { frame =>
    frame.faces(detector).map { face =>
      val embedding = rec.embed(frame, face)
      // -1.0 is the least a cosine similarity can be and the filter is `>=`, so this always
      // returns the nearest enrolment; the accept/reject decision is made here, with the score.
      gallery.identify(embedding, threshold = -1.0) match
        case Some(FaceMatch(n, s)) if s >= Gallery.CosineThreshold => Sighting(face.box, Some(n), s)
        case Some(FaceMatch(_, s))                                 => Sighting(face.box, None, s)
        case None                                                  => Sighting(face.box, None, 0.0)
    }
  }
```
]

`Sighting` keeps the box, so the result can be drawn with the tools from Chapter 14, and keeps the
name as an `Option` rather than the string `"unknown"` --- an unknown face is a different kind of
answer from a named one, and the type should say so before a downstream `match` has to guess.

The threshold of `-1.0` is the trick worth stealing. `identify` at its default returns `None` for
anything below the cutoff, and `None` carries no number, so the score you most want in the log ---
the one for the face that #emph[nearly] matched --- is the one the default throws away. Cosine
similarity is bounded below by `-1` and `identify` filters on `>=`, so a threshold of `-1.0` admits
every entry and hands back the best one whatever it scored. Compare against
`Gallery.CosineThreshold` yourself and you get the same decision plus the evidence for it. The last
case, `None`, is then reachable only for an empty gallery, which is exactly the condition it should
be reporting.

#example("The whole pipeline, with both native owners released exactly once.")[
```scala
val people = Seq(
  "ada" -> Seq("ada-frontal.jpg", "ada-left.jpg", "ada-lamp.jpg"),
  "grace" -> Seq("grace-frontal.jpg", "grace-smiling.jpg")
)

val result =
  for
    yunetPath <- FaceDetect.downloadModel(models)
    sfacePath <- Models.fetch(FaceRecognizer.modelSpec, models)
    detector <- FaceDetect.create(yunetPath.toString, Size(320, 320))
    rec <- FaceRecognizer.load(sfacePath.toString)
  yield
    try identifyAll(enrol(detector, rec, people), detector, rec, "group.jpg")
    finally
      detector.release()
      rec.close()

result match
  case Left(err)               => println(s"setup failed: $err")
  case Right(Left(err))        => println(s"the frame failed: $err")
  case Right(Right(sightings)) =>
    sightings.foreach {
      case Sighting(box, Some(name), s) => println(f"$name at $box (cosine $s%.3f)")
      case Sighting(box, None, s)       => println(f"unknown face at $box (best $s%.3f)")
    }
```
]

Four fallible steps compose in one `for`; the first `Left` short-circuits, and because the downloads
come first, a failure there leaves nothing native to leak. The `try`/`finally` is the load-bearing
part of the listing, and it is not there for OpenCV failures: `Image.reading` runs its whole block
inside `Cv.attempt`, so a `CvError` out of `embed` returns as the inner `Left` rather than unwinding.
It is there for what #emph[does] propagate --- a `require` in `FaceDetect.detect` rejecting a frame
that is not 8-bit BGR, a use-after-move, anything your own code throws --- because those are the
paths on which a detector and a recogniser would otherwise be abandoned holding a parsed model
each.

#figure-table("Recognition symptoms and where the cause almost always is.")[
#tbl(
  columns: (1.2fr, 1fr, 1.2fr),
  [Symptom], [Likely cause], [Fix],
  [Everyone matches everyone], [threshold too low], [keep `Gallery.CosineThreshold`, or raise it],
  [Known people read as unknown], [too few enrolments, or poor references], [enrol several poses per person],
  [Confident wrong matches], [a crop was embedded instead of the frame], [pass the frame the `Face` came from],
  [An embedding now and then belongs to another face], [one recogniser shared across threads],
  [one per worker, or a lock],
  [`load` returns a `Left`], [wrong path, or not an SFace ONNX], [check the `CvError.LoadFailed` message],
)
]

#sect("What a score is not")

Three things are worth saying once, plainly, before you ship any of this.

A similarity is not an identity claim. `FaceMatch("ada", 0.41)` says one vector was closer to another
vector than a threshold you chose. It does not say the person in the frame is Ada, and any system that
treats it as though it does --- an unlock, an accusation, an automated report --- has promoted a
measurement into a fact without anyone deciding to.

Accuracy is not uniform across people. Published evaluations of face recognition systems, including
NIST's, repeatedly find error rates that differ by demographic group, and a threshold chosen on one
population does not transfer to another. A single global cutoff therefore distributes its false
accepts and false rejects unevenly, and it will do so silently unless you measure per group. If you
cannot measure it, you cannot claim it is not happening.

Face embeddings are biometric data. Under GDPR Article 9 they are a special category with a narrow
set of lawful bases; Illinois' BIPA requires written consent before collection and imposes a private
right of action; several other jurisdictions have equivalents, and the list grows. The practical
consequences for the code in this chapter are small and concrete: treat the gallery as sensitive
personal data at rest, keep embeddings and names out of logs and out of crash dumps, and build the
deletion path --- a person's enrolments removed on request, everywhere they were copied --- before
you build the enrolment path, because it is far harder to add afterwards. `Gallery` being an
immutable value makes the storage question yours rather than the library's, which is the right place
for it, and does not answer it.

#sect("Where this leaves you")

You can now put a name on a face in a single frame, with a score you chose the meaning of and an
`unknown` that is a real answer rather than an oversight. What you cannot yet do is keep that name
attached to a person as they move. Run `identify` on every frame of a video and you pay for an SFace
forward pass per face per frame, and you still watch the label flicker off the moment someone turns
their head far enough that no enrolment clears the threshold.

Chapter 26 goes underneath both models in this pipeline before that problem is worth attacking.
YuNet and SFace are ONNX graphs run by OpenCV's `dnn` module, and `Dnn` is that machinery with
nothing wrapped around it: load a graph, build an input tensor from an image, run one forward pass,
decode the output tensor yourself. Three things this chapter treated as given generalise there --- a
fetch-and-verify download, a caller-owned native model, and the pre-processing numbers that decide
whether the output means anything --- and they apply to any network you can export to ONNX.

The continuity problem is Chapter 30: tracking, from the `Kalman` smoother through the single-object
`Tracker` and its `TrackerKind` choices to `ObjectTracker` and its stable ids. That is the machinery
that lets you recognise a face once, expensively, and keep calling it by name for as long as it
stays in the frame.
