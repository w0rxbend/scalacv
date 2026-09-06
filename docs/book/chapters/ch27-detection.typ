#import "../lib/book.typ": *

#chapter("Object Detection", subtitle: [Boxes with labels, and the arithmetic that puts them back where they belong.])

Everything in this book so far has answered a question about pixels with more pixels. A blur returns
an image. A threshold returns an image. Even contours, which finally hand back plain data, hand back
data of a length the picture itself decided. Detection is the first task where you ask a question
whose answer is a list of assertions about the world --- *there is a person here, at this
confidence; there is a van there* --- and where the list can be empty, or can have forty entries,
and where most of the forty are wrong.

It is worth being precise about which of the three neighbouring tasks you actually have.
Classification takes a whole image and returns one label: this photograph contains a cat. It has no
opinion about where. Segmentation takes a whole image and returns a label per pixel: this pixel is
cat, that one is not. It has an exact opinion about where, and no notion of how many cats. Detection sits between them and returns a variable-length list of axis-aligned rectangles,
each with a class and a score. It is the only one of the three whose output length is a prediction,
and that is the source of nearly all its difficulty: two of the rectangles may be the same cat, one
may be a cushion, and nothing in the output tells you which.

The second thing to be precise about is where the work lives. A detector's forward pass is one call.
Everything expensive to get right happens on either side of it --- resizing the frame into the
network's input space without lying about the aspect ratio, reading a raw output tensor of eight
thousand candidate rows into boxes, discarding the duplicates, discarding the seventy-eight classes
you do not care about, and mapping the survivors back into the coordinate system your frame is
actually in. That last step is where the bugs live, and they are quiet bugs: a detector with a
broken coordinate map still draws boxes, still draws them near the objects, and still looks roughly
right on the frame you happened to test with.

This chapter builds one pipeline for a fixed camera watching a loading bay, counting people and
vans. It is deliberately the boring case, because the boring case is the one where a coordinate map
that is quietly wrong goes unnoticed for a month.

#sect("The words on the model card")

You will not train the model. You will download an ONNX export from somewhere and read a page
describing it, and that page assumes a vocabulary. Six terms carry almost all of the weight.

#figure-table("The vocabulary a detector model card assumes you already have.")[
#tbl(
  columns: (0.8fr, 2.6fr),
  [Term], [What it means when you are the one calling the model],
  [Anchor],
  [One of the fixed positions, and sometimes fixed box shapes, at which the network is allowed to
   propose an object. A modern export proposes at every cell of several downsampled grids, so an
   anchor is essentially "a row of the output tensor". You never choose them; you only have to know
   there are thousands and that each one produces a candidate.],
  [Stride],
  [The downsampling factor of the grid an anchor sits on. A stride-32 grid over a 640-pixel input is
   20 × 20 cells, each cell responsible for a 32-pixel patch. The largest stride finds large
   objects; the smallest stride is the one that decides whether small objects can be found at all.],
  [Confidence],
  [The score attached to a candidate. Depending on the export it is a class probability, or a class
   probability multiplied by an objectness term. It is calibrated against the training set and not
   against your camera, so treat it as an ordering, not as a probability.],
  [IoU],
  [Intersection over union: the area two boxes share divided by the area they jointly cover. Zero
   for disjoint boxes, one for identical ones. It is how duplicates are recognised, and how a
   prediction is judged to have matched a hand-drawn label.],
  [NMS],
  [Non-maximum suppression. Sort the candidates by confidence, keep the best, delete every remaining
   box whose IoU with it exceeds a threshold, repeat. It is the step that turns eight thousand rows
   into four boxes.],
  [mAP],
  [Mean average precision: the leaderboard number. See the sidebar --- it is the term most likely to
   make you choose the wrong model.],
)
]

#sidebar("What mAP does and does not tell you")[
  `mAP@0.5:0.95` is an average of an average. For each class, precision is averaged over the whole
  recall range to give an average precision; that is computed at ten IoU match thresholds from 0.50
  to 0.95 and averaged again; then the per-class results are averaged over all eighty classes.

  Three consequences follow, and all three matter more than the number itself. It is dominated by
  classes you do not have --- toothbrushes and giraffes count exactly as much as people. It rewards
  tight box regression, because the strict IoU thresholds punish a box that is correct but loose,
  and a loose box is usually fine for counting. And it is measured on a public dataset photographed
  by people, not on a fixed camera at four in the morning under sodium lighting.

  A model two mAP points behind another can be the better model for your two classes and your
  camera. The only way to know is forty labelled frames of your own footage, which is also what the
  threshold-picking section below needs, so the work is not wasted.
]

#sect("What the library owns, and what it does not")

scalacv ships finished detectors for the closed-world cases, and it deliberately does not ship one
for the open-world case. Understanding why draws the line you will be working on either side of for
the rest of this chapter.

`Dnn`, `Qr` and `Aruco` are all in `scalacv-vision`, so that dependency has to be on the classpath
for anything in this chapter to compile.

`Detectors.scala` holds the fiducial and barcode detectors, and they follow one contract to the
letter: the detector is built, used and freed inside a single call, and the results cross the
boundary as ordinary immutable Scala data. `Qr.detectAndDecode(mat)` returns `Seq[QrCode]`, where a
`QrCode` is a `text: String` and a `corners: Seq[Point]`. It uses OpenCV's *multi* variant
unconditionally, because the single-code `detectAndDecode` returns only the first symbol it happens
to find and gives you no way to learn that there were others. A symbol that was located but could
not be decoded comes back with an empty `text` and usable `corners`, which is the right shape for
re-cropping and retrying rather than a failure.

`Aruco.detect(mat, dictionary = ArucoDictionary.Dict4x4_50)` returns `Seq[ArucoMarker]`, each an
`id: Int` and four `corners`, with `ArucoDictionary` naming every predefined dictionary OpenCV
ships. `Aruco.generateMarker(dictionary, id, sizePixels)` is the one method in the file that hands
back native memory --- a `Managed[Mat]`, because a rendered marker is an image you own. Both
detectors reject an empty `Mat` with an `IllegalArgumentException`, because an empty image is a
programmer error, while an image with nothing in it returns an empty `Seq`, because that is a
result. The high-level entry points are extension methods on `Image`: `img.qrCodes` and
`img.arucoMarkers(dictionary)`. Chapter 28, #emph[QR Codes, ArUco, and Augmented Reality], takes those
two apart properly; what matters here is the shape of the contract, because the rest of this chapter
is about what happens when a detector cannot honour it.

#memory[
  Neither `QRCodeDetector` nor `ArucoDetector` has a public `release()`; they are two of the 185
  generated types that expose only a private `delete(long)`. That is exactly why these signatures
  return `Seq` and not `Managed[…]`. If the library handed you a detector, you would be holding a
  native object the OpenCV Java API gives you no supported way to free. Building it, using it and
  freeing it inside one call means there is nothing left to own by the time the result reaches you,
  and the `Point` case classes in that result survive the detector's death because they were copied
  across the boundary rather than viewed through it.
]

For eighty-class object detection there is no equivalent, and no `Detector` type to reach for. The
reason is that the decode is not OpenCV's and not this library's --- it is the model's. YOLOv5,
YOLOv8, SSD and DETR exports disagree about the output tensor's rank, its axis order, whether there
is a separate objectness column, and whether box coordinates are centre-plus-size or
corner-to-corner in input pixels or in normalised units. A wrapper that guessed would be wrong
silently, which is the worst of the available failures.

So generic detection is delivered as `Dnn` plus a decoding layer you write: `Dnn.fromOnnx(path)` for
an `Either[CvError, Managed[Net]]`, `Dnn.blobFromImage(mat, scaleFactor, size, mean, swapRB, crop)`
for the NCHW input, `Dnn.forward(net, blob, outputName)` for one pass, and then your own code plus
`org.opencv.dnn.Dnn.NMSBoxes` for everything after. Chapter 5, #emph[Lifetimes: Managed, Releasable, and Scope], explains why every one of those returns a `Managed`, and Chapter 26, #emph[Deep Networks and ONNX], covers the load and the blob argument by argument; this chapter is about the code between the
second call and the last.

#memory[
  A loaded `Net` holds the model's entire weight set in native memory, and `Net` is another of the
  185 types with no public `release()`. Load it once, at start-up, outside every loop, and keep the
  `Managed[Net]` alive for the life of the pipeline --- reloading per frame would dwarf the
  inference cost and, without a `Managed`, would leak the weights each time. A `Net` is also
  stateful: `setInput` mutates it and `forward` reads that mutation back. `Dnn.forward` fuses the
  two so the window between them cannot be widened by accident, but it is not a lock. One `Net` per
  thread.
]

#sect("Choosing a model, and being honest about the cost")

Three numbers describe a detector's operating point: input resolution, parameter count, and the
latency the two of them produce on the machine you actually have. Only the first two are published.

Input resolution is not a speed knob. It is the knob that decides which objects can exist. A
letterbox scales by the *long* side, so a 1920 × 1080 frame going into a 640 input is scaled by
640/1920, a third --- not by 640/1080, which is the mistake that makes the next section's bug
attractive. A person 30 pixels tall in that frame is therefore 10 pixels tall in the network's
input, and 5 pixels tall at 320, where they are smaller than one cell of the stride-8 grid and the
network has no mechanism by which to propose them at all. Raising the input size is how you detect
small things; it is not a general accuracy dial, and on objects that already fill a third of the
frame it buys almost nothing.

The cost, on the other hand, tracks pixel count closely for a convolutional detector, and pixel
count is quadratic in the side.

#figure-table("What input side costs, relative to 320, and what it is for.")[
#tbl(
  columns: (0.7fr, 0.7fr, 1.5fr, 1.5fr),
  [Input side], [Pixels vs 320], [What it buys], [What it costs you],
  [320], [1.0×], [The cheapest useful band; fine for objects filling a tenth of the frame or more.],
  [Small and distant objects are absent, not merely missed.],
  [416], [1.7×], [The usual compromise for a fixed camera at a fixed distance.],
  [Little, if your objects are large; nothing at all if they were already detected at 320.],
  [640], [4.0×], [Small objects, crowded scenes, anything reaching the network at 40 pixels tall or
   less.],
  [Four times the convolution work and roughly four times the number of candidate rows to decode.],
  [960 and up], [9.0×], [Aerial and wide-area footage where objects are tens of pixels across.],
  [A band where CPU inference stops being a real-time option at all.],
)
]

The right-hand column is where the honesty has to be. This project's own benchmark page carries no
neural-network numbers at all, and says why: the heaviest thing most pipelines do is a forward pass,
and its cost belongs to the model and to the OpenCV DNN backend rather than to scalacv. Its
instruction is four words --- time your own model. No published figure substitutes for it, and none
is offered here.

What can be said without measuring is the shape of the bands. A nano-class detector at 320 or 416 is
the band where a real-time CPU loop is plausible on ordinary laptop hardware; a medium model at 640
is the band where it is not, and where the design question becomes which of three compromises you
want. You can drop frames, and accept that your effective frame rate is the detector's. You can
detect every Nth frame and carry the boxes between detections with a tracker, which is
Chapter 30's subject and by far the most common answer. Or you can move off the CPU, remembering
from Chapter 26 that a backend or target this build was not compiled with falls back to the
CPU silently --- no exception, no `Left`, no log line --- so a `DNN_TARGET_CUDA` that appears to do
nothing is almost never a bug in your code.

#tip[
  Time the pipeline in four pieces, not one: blob construction, forward, decode, and NMS. Warm the
  net with one throwaway `forward` before you start, because the first pass allocates the layer
  buffers and is not representative. Report the median rather than the mean --- a stop-the-world
  pause in the tail will otherwise dominate your average and send you optimising the wrong stage.
  Decoding eight thousand candidate rows in Scala is a larger share of the total than most people
  guess, and it is the one stage you control completely.
]

#sect("Letterbox, and the two coordinate systems that are not the same")

The network wants a square input of a fixed side. Your frame is 1920 × 1080. There are two ways to
reconcile that and they fail differently.

`Dnn.blobFromImage` with `crop = false` resizes both axes independently, which changes the aspect
ratio: a van becomes a squat van, and a model trained on undistorted crops has never seen one.
With `crop = true` it scales the short side to fit and centre-crops the rest away, which preserves
shapes and silently deletes everything near the left and right edges of a 16:9 frame --- including,
on a loading bay camera, the door.

The third option is a letterbox: scale by the smaller of the two ratios so the whole frame fits,
then pad the remainder with a constant colour. Nothing is distorted and nothing is discarded. The
price is that the network's coordinate space now contains padding that does not exist in your frame,
and every box that comes back is expressed in that space.

#example("Letterboxing a frame, and recording exactly what was done to it.")[
```scala
import scalacv.*

/** How a frame was fitted into the network's square input, so boxes can be mapped back. */
final case class Letterbox(scale: Double, padX: Int, padY: Int)

def letterbox(img: Image, side: Int, pad: Scalar = Scalar(114, 114, 114)): (Image, Letterbox) =
  // Read the size BEFORE resizeTo, which consumes the image (Chapter 4).
  val scale = math.min(side.toDouble / img.width, side.toDouble / img.height)
  val w = math.max(1, (img.width * scale).round.toInt)
  val h = math.max(1, (img.height * scale).round.toInt)
  val padX = (side - w) / 2
  val padY = (side - h) / 2
  val boxed = img
    .resizeTo(Size(w.toDouble, h.toDouble), Interpolation.Area)
    .border(padY, side - h - padY, padX, side - w - padX, BorderType.Constant, pad)
  (boxed, Letterbox(scale, padX, padY))
```
]

Two details in that listing are load-bearing. `Interpolation.Area` is the correct choice going down,
because it averages every source pixel that falls inside a destination pixel where `Linear` samples
a handful and aliases the rest away --- and a small object surviving the downscale is precisely what
detection depends on. And the far-side padding is computed by subtraction, `side - h - padY`, not as
a second `(side - h) / 2`: the halves lose a row whenever the difference is odd, and a 640-pixel
target holding a 361-pixel image would come out 639 rows tall and fail the network's shape check.

Now the mistake. Having letterboxed, the obvious way to map a box back is to scale each axis by the
ratio of the frame's side to the network's:

```scala
// WRONG after a letterbox: the padding is scaled along with the object.
def toFrame(box: Rect, frameW: Int, frameH: Int, side: Int): Rect =
  val sx = frameW.toDouble / side
  val sy = frameH.toDouble / side
  Rect((box.x * sx).toInt, (box.y * sy).toInt, (box.width * sx).toInt, (box.height * sy).toInt)
```

This is the correct map for a stretched resize and the wrong one for a letterbox, and the
interesting part is how it fails. It is exactly right whenever the frame is already square, because
then there is no padding to subtract and the two per-axis ratios coincide --- which is how it passes
the unit test somebody wrote against a 512 × 512 fixture. Everywhere else the error grows with the
aspect mismatch and with the distance from the centre of the frame. Feed it 1920 × 1080 letterboxed
into 640: `sx` is 3.0, which happens to be exactly the inverse of the scale factor, so widths and
horizontal positions come out perfect. `sy` is 1.6875 against the 3.0 it should be, so every box
keeps its width, loses 44 per cent of its height, and is dragged towards the middle row --- dead
right at the centre of the frame, out by 236 pixels at the top and bottom edges. On a 4:3 camera the
same bug is a gentle squash that looks like an ordinary regression error, survives review, survives
a demo, and fails the day somebody asks whether the person was inside the marked zone.

The right map undoes the two operations in reverse order: subtract the padding, then divide by the
single scale factor that was applied to both axes.

#example("The inverse of the letterbox, which is the only correct map back.")[
```scala
def toFrame(box: Rect, lb: Letterbox): Rect =
  Rect(
    ((box.x - lb.padX) / lb.scale).round.toInt,
    ((box.y - lb.padY) / lb.scale).round.toInt,
    (box.width / lb.scale).round.toInt,
    (box.height / lb.scale).round.toInt
  )
```
]

#warning[
  A box mapped back to the frame can still fall outside it. Detectors regress boxes from anchors, so
  an object at the edge legitimately produces a negative `x` or a width that runs past the right-hand
  column. That is fine for drawing --- OpenCV clips --- and fatal for cropping, which rejects a
  rectangle that is not wholly inside the image. Clip before you crop, never before you draw.
]

#sect("Decode once, and filter while you decode")

The output tensor's layout belongs to the export. The listing below decodes the common YOLOv8 shape:
three dimensions, `(1, 4 + classes, anchors)`, box coordinates as centre-x, centre-y, width and
height in *input-space pixels*, and one column per class with no separate objectness term. Read your
own export's shape from `output.dims()` and `output.size(i)` before you trust any of this; a YOLOv5
export is `(1, anchors, 5 + classes)`, transposed and with an extra column, and decoding one as the
other produces boxes rather than an error.

The detail worth copying is not the arithmetic but the `wanted` argument. A COCO model has eighty
classes and this pipeline cares about two. Scanning all eighty score columns for the best one, then
discarding seventy-eight of the results afterwards, does forty times the work in the innermost loop
--- and that loop runs once per anchor. At a 640 input the three grids have strides 8, 16 and 32, so
the anchor count is (80 × 80) + (40 × 40) + (20 × 20) = 8,400, and the tensor is 84 rows deep: four
box numbers plus eighty scores. The row itself still crosses the JNI boundary whole, in one `get`;
what `wanted` removes is the scan on top of it, 672,000 comparisons a frame against 16,800, to keep
at most a few dozen boxes.

#example("Decoding a YOLOv8-shaped output, reading only the classes you asked for.")[
```scala
import scalacv.*
import org.opencv.core.{Core, Mat}

/** One candidate, in the network's input space, before suppression. */
final case class Detection(box: Rect, classId: Int, score: Float)

def decode(output: Mat, classCount: Int, wanted: Set[Int], floor: Float): Seq[Detection] =
  val attributes = 4 + classCount
  val anchors = output.size(2)
  Managed.scope: own =>
    // reshape gives a header onto the same buffer: (1, attributes, anchors) -> attributes x anchors.
    val flat = own(output.reshape(1, attributes))
    val rows = own(Mat())
    Core.transpose(flat, rows) // anchors x attributes, one candidate per row
    val row = new Array[Float](attributes)
    (0 until anchors).flatMap: i =>
      val _ = rows.get(i, 0, row)
      var best = -1
      var bestScore = floor
      wanted.foreach: c =>
        val s = row(4 + c)
        if s > bestScore then
          best = c
          bestScore = s
      if best < 0 then Nil
      else
        val (cx, cy, w, h) = (row(0), row(1), row(2), row(3))
        val rect = Rect((cx - w / 2).toInt, (cy - h / 2).toInt, w.toInt, h.toInt)
        List(Detection(rect, best, bestScore))
```
]

#memory[
  `output.reshape` does not copy pixels; it returns a second `Mat` header onto the same buffer, with
  the reference count raised. It is still a native object you own, which is why it goes through
  `own`. The `Mat` that `Core.transpose` fills *is* a fresh allocation, and `Managed.scope` releases
  both in reverse order --- including when `get` throws part-way through the loop, which a
  `try`/`finally` written around a pair of bare `val`s would not.

  The `output` itself is safe to hold, which is not true of raw OpenCV: `Net.forward` hands back a
  header onto the layer's own buffer that the next pass overwrites in place. `Dnn.forward` copies
  before returning, so this frame's output and the previous frame's are genuinely two different
  things.
]

Suppression comes next, and it takes one decision that is easy to get wrong: NMS is a geometric
operation with no notion of class, so running it across all candidates at once lets a high-scoring
person delete the van they are standing in front of. Group by class first.

#example("Per-class non-maximum suppression through OpenCV's own implementation.")[
```scala
import scalacv.*
import org.opencv.core.{MatOfFloat, MatOfInt, MatOfRect2d, Rect2d}
import org.opencv.dnn.Dnn as CvDnn

def suppress(candidates: Seq[Detection], confidence: Float, iou: Float): Seq[Detection] =
  if candidates.isEmpty then Seq.empty
  else
    Managed.scope: own =>
      val boxes = own(MatOfRect2d(candidates.map { d =>
        Rect2d(d.box.x.toDouble, d.box.y.toDouble, d.box.width.toDouble, d.box.height.toDouble)
      }*))
      val scores = own(MatOfFloat(candidates.map(_.score)*))
      val keep = own(MatOfInt())
      CvDnn.NMSBoxes(boxes, scores, confidence, iou, keep)
      keep.toArray.toSeq.map(candidates) // plain data; nothing scoped escapes

def suppressPerClass(candidates: Seq[Detection], confidence: Float, iou: Float): Seq[Detection] =
  candidates.groupBy(_.classId).values.flatMap(suppress(_, confidence, iou)).toSeq
```
]

`MatOfRect2d`, `MatOfFloat` and `MatOfInt` are `Mat` subclasses, so the `Releasable[Mat]` given
covers all three and `own` accepts them without ceremony --- but they are native allocations like
any other. Three per class per frame is ninety allocations a second at 15 fps with two classes, each
of which has to be freed exactly once; that is what the scope is for.

#sect("Two thresholds, chosen from frames rather than from feel")

The confidence threshold and the NMS IoU threshold are the only two numbers in the pipeline you get
to choose, and they are frequently adjusted as though they were interchangeable. They are not.

#figure-table("What each threshold actually moves.")[
#tbl(
  columns: (1fr, 1.4fr, 1.4fr),
  [], [Raising it], [Lowering it],
  [Confidence],
  [Fewer boxes survive. Precision rises, recall falls. Monotone, and reversible after the fact if
   you keep the raw candidates.],
  [More boxes survive, including the ones the network was not sure about. Recall rises, precision
   falls.],
  [NMS IoU],
  [More overlapping boxes are kept, so a single object more often gets two or three boxes.],
  [Boxes are merged more aggressively, so two genuinely adjacent objects --- two people at a door,
   two vans in a row --- collapse into one and the loss shows up nowhere in any score.],
)
]

The asymmetry matters. Confidence is a filter on quality, and moving it trades precision against
recall along a curve you can measure. The IoU threshold is a statement about the geometry of your
scene: how much do the objects you care about genuinely overlap? A car park viewed from above has
almost no true overlap and tolerates an aggressive 0.3; a queue of people viewed from head height
overlaps constantly and needs 0.6 or more, at the cost of occasional duplicates. Reaching for the
IoU threshold because recall is too low is a category error --- it cannot recover a candidate that
suppression never saw.

Picking the confidence threshold by watching a preview and stopping when it "looks about right"
optimises for the twenty seconds of footage you happened to be looking at. The alternative costs an
afternoon and is worth it. Take forty frames spread deliberately across the conditions that vary
--- dawn, midday glare, headlights, rain on the lens --- and draw the true boxes for your two
classes by hand. Run the pipeline once with `floor = 0.05f`, keeping every candidate with its score
--- and drop the `confidence` you hand `NMSBoxes` to the same 0.05, because it is a score threshold
too and will otherwise quietly re-apply the number you were trying to measure. Then, offline, for
each threshold from 0.05 to 0.95 in steps of 0.05, match predictions to labels greedily by
descending score with a match counted at IoU 0.5 or better, and count true positives, false
positives and misses. That gives you a precision and a recall per threshold, and the choice
becomes a statement about the application rather than about the picture: a system that pages a human
wants precision, because a pager that cries wolf is switched off; a system that counts vehicles per
hour wants a stable operating point where the miss rate is predictable enough to correct for.

#tip[
  Pick the threshold per class. People and vans score differently --- large, high-contrast, common
  objects score higher than small ones almost regardless of the model --- and one number for both is
  a compromise nobody chose. A `Map[Int, Float]` from class id to threshold costs nothing and is
  applied at the same point in the loop.
]

If no threshold gives you an acceptable pair, stop turning it. Thresholds cannot manufacture
detections that were never proposed, and a recall ceiling that will not move is telling you the
input resolution is too low for the objects, or the model is wrong for the domain.

#sect("The whole loop")

Everything assembled: load once, letterbox, forward, decode against a class filter, suppress per
class, map back, and draw with a label plate readable against whatever is behind it --- the plate
from Chapter 14, #emph[Drawing and Annotation], sized from `Draw.textSize` so the descenders are not
clipped.

#example("Detection over a video, with the model loaded exactly once.")[
```scala
import scalacv.*
import org.opencv.dnn.Net

val labels = Map(0 -> "person", 7 -> "truck")
val wanted = labels.keySet
val colours = Map(0 -> Scalar(60, 220, 60), 7 -> Scalar(60, 160, 250))
val Side = 640
val Confidence = 0.35f
val Iou = 0.5f

def labelled(img: Image, box: Rect, text: String, colour: Scalar): Image =
  val m = Draw.textSize(text, scale = 0.5)
  val plateW = m.size.width.toInt + 8
  val plateH = m.size.height.toInt + m.baseline + 6
  val top = math.max(0, box.y - plateH)
  img
    .drawRect(box, colour, Thickness.Stroke(2))
    .drawRect(Rect(box.x, top, plateW, plateH), colour, Thickness.Filled)
    .drawText(text, Point((box.x + 4).toDouble, top + m.size.height + 3), Scalar.Black, scale = 0.5)

def annotate(net: Net, source: String, into: String): Either[CvError, Unit] =
  Camera.usingFile(source) { camera =>
    Recorder.using(into, camera.size, camera.fps) { recorder =>
      camera.foreach() { frame =>              // an owned Image, closed for you
        val (boxed, lb) = letterbox(frame.copy, Side)
        val found =
          try
            Dnn
              .blobFromImage(
                boxed.mat,                     // borrowed; boxed is still ours to close
                scaleFactor = 1.0 / 255,
                size = Some(Size(Side.toDouble, Side.toDouble)),
                swapRB = true
              )
              .use { blob =>                   // the blob outlives the forward, and only just
                Dnn.forward(net, blob).use { output =>
                  suppressPerClass(
                    decode(output, classCount = 80, wanted = wanted, floor = Confidence),
                    Confidence,
                    Iou
                  )
                }
              }
          finally boxed.close()

        val annotated = found.foldLeft(frame) { (img, d) =>
          labelled(img, toFrame(d.box, lb), f"${labels(d.classId)} ${d.score}%.2f",
                   colours(d.classId))
        }
        try recorder.write(annotated).fold(throw _, _ => ())
        finally annotated.close()
      }
    }
  }.flatMap(identity)                          // two Eithers nest: camera open, then recorder open

OpenCv.load()

val outcome: Either[CvError, Unit] =
  Dnn.fromOnnx("models/yolov8n-v3.onnx").flatMap { managedNet =>
    managedNet.use { net =>                    // loaded once, freed when this block ends
      annotate(net, "loading-bay.mp4", "annotated.avi")
    }
  }
```
]

Four things in that program are worth naming. `frame.copy` exists because `letterbox` consumes what
it is given and the original frame is still needed to draw on; branching a pipeline always costs one
copy, and Chapter 4, #emph[The Image Type], explains why the type refuses to let you forget it. The
`blob` is kept inside a `use` that outlives the `forward` call, because releasing it while a pass is
in flight is a crash rather than an exception. `found.foldLeft(frame)` threads the image through the
annotations precisely because each `draw` consumes its receiver --- with no detections the fold
returns the frame untouched, and `close` is idempotent, so the `finally` is correct either way. And
the failures stay values: `fromOnnx`, `usingFile` and `Recorder.using` each return an
`Either[CvError, …]`. The inner two nest into an `Either[CvError, Either[CvError, Unit]]` that
`flatMap(identity)` collapses, and the model's own `Left` joins them through the outer `flatMap`. A
model that will not load, a video that will not open and a codec this build cannot write are three
distinct `Left`s that arrive at the same place, and none of them throws.

There is one honesty left in the label map. The loading bay has vans in it and COCO has no van
class, so class 7, `truck`, is what a van is going to be called --- sometimes, with the rest split
between `car` and `bus` depending on the vehicle and the angle. The taxonomy belongs to whoever
labelled the training set, and no threshold repairs a class boundary that was drawn somewhere else.
Either fold the vehicle classes together into one count and say so, or accept that "van" is a
category your model does not have and fine-tune one that does.

#note[
  `camera.foreach` hands you an owned `Image` copied out of the capture and closes it for you. The
  cheaper `Video.frames` yields one reused borrowed `Mat` instead, which is the right choice when
  you only read and reduce --- but detection here annotates and re-encodes, so the copy is being
  paid for something.
]

#sect("From boxes to tracks")

What this pipeline produces is a set of boxes per frame and nothing else. It has no memory. The
person at the left of frame 400 and the person at the left of frame 401 are two unrelated
assertions, and the pipeline cannot tell you that they are the same person, cannot count how many
distinct people passed through the bay in an hour, and cannot survive the single frame where the
detector blinks and returns nothing.

That is not a defect in the detector; it is the boundary of what detection is. Chapter 28,
#emph[QR Codes, ArUco, and Augmented Reality], sidesteps it by printing the object you want to find
rather than training for it; Chapter 30, #emph[Tracking], closes it. `ObjectTracker.update` takes a bare `Seq[Rect]` --- `found.map(d =>
toFrame(d.box, lb))` and nothing else, because it never looks at the image --- matches those boxes
against its live tracks by IoU, and hands back `ObjectTrack` values carrying a stable `id`, a
`hits` count and an `age`. A track that goes
unmatched is not deleted on the spot: it survives `maxAge` frames of misses before it is retired, so
the frame where the detector blinks costs you one gap rather than a new identity, and `count` gives
you the running total of distinct objects the bay has seen. That is the step where a box finally
becomes an object with a history.
