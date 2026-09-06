#import "../lib/book.typ": *

#chapter("Deep Networks and ONNX", subtitle: [Running a trained model on the CPU, and the four numbers that decide whether it was worth running.])

Almost every technique in this book so far answered a question with geometry. A threshold asks whether
a pixel is brighter than a number; a contour asks which pixels touch; a Hough transform asks how many
edge points agree about a line. You can write down what each of them means, and when one is wrong you
can point at the step that made it wrong.

Some questions have no such answer. A camera on a bird feeder sees a small brown shape in a hedge,
and the useful question --- goldfinch or siskin --- is not a threshold, a contour, or a line. It is a
learned function of a hundred thousand training photographs that nobody, including the people who
trained it, can write down. The only way to get an answer is to run the function.

OpenCV can run it for you. The `dnn` module loads a graph that was trained elsewhere and executes one
forward pass over it on plain CPU, with no PyTorch on the classpath and no GPU runtime to install.
What it will not do is train anything: no autograd, no optimiser, no fine-tuning, no gradient of any
kind --- only weights that were fixed before your process started and arithmetic that reads them. If
the model is wrong for your data, `dnn` is not the place you fix it.

That division of labour shapes this entire chapter. Everything deciding whether the answer is correct
lives *outside* the API you are about to call: the size the model was trained at, the range its pixel
values were scaled into, the mean subtracted from them, and the channel order they arrived in. Get
one of those four wrong and nothing throws --- the model loads, the pass runs, the output tensor has
exactly the promised shape, and the numbers in it are confidently, quietly wrong. The API cannot fix
that, but it can name and document the parameters so they are hard to transpose, which is what
`scalacv.Dnn` is.

`Dnn` lives in `scalacv-vision`, the same module as the detectors of the last three chapters, so
that dependency has to be on the classpath; `Image`, `Managed`, `Scalar` and the drawing verbs come
from `scalacv-core` underneath it.

The chapter builds one thing: a bird feeder that names its visitors. It reads a frame, runs an ONNX
classifier over it, turns the output tensor into a species and a confidence, draws them on the
frame, and writes it out --- with every native object owned by something that will free it.

#sect("Inference only, and only ONNX")

OpenCV's importers can read Caffe, Darknet, TensorFlow, TFLite and Torch graphs as well as ONNX.
`scalacv` exposes exactly one of them.

That is a deliberate narrowing. Each of those importers carries its own private list of unsupported
layers and its own way of failing on them, and offering seven entry points would advertise a breadth
of support no small library can honestly stand behind. ONNX is the format the other frameworks
*export to* --- one line in PyTorch, one tool for TensorFlow, one for scikit-learn --- so a single
importer covers the realistic cases, and the conversion happens once, on your machine, where you can
watch it fail.

The work therefore starts before any Scala: get the model into a `.onnx` file, with a fixed input
shape unless you specifically intend a dynamic one, and read its model card while you are there. The
card is where the four numbers live.

#sect("Three functions and one owner")

The entire deep-learning surface is one object with three methods. Each stage takes what the previous
one produced, and each says in its return type who must free what it hands back.

#figure-table("The whole `Dnn` surface. Every result is caller-owned; no argument is consumed.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Stage*], [*Call*], [*Gives back*],
  [Load], [`Dnn.fromOnnx(path)`], [`Either[CvError, Managed[Net]]`],
  [Pre-process], [`Dnn.blobFromImage(mat, …)`], [`Managed[Mat]` --- the input tensor],
  [Infer], [`Dnn.forward(net, blob, …)`], [`Managed[Mat]` --- the output tensor],
)
]

Spelled out with the defaults the source declares, so that nothing below is a surprise:

#example("Three signatures. The defaults are OpenCV's, not any model's.")[
```scala
object Dnn:
  given Releasable[Net] = Releasable.nativeHandle

  def fromOnnx(path: String): Either[CvError, Managed[Net]]

  def blobFromImage(
      mat: Mat,
      scaleFactor: Double = 1.0,
      size: Option[Size] = None,
      mean: Scalar = Scalar(0, 0, 0),
      swapRB: Boolean = false,
      crop: Boolean = false
  ): Managed[Mat]

  def forward(net: Net, blob: Mat, outputName: Option[String] = None): Managed[Mat]
```
]

Three calls, three `Managed` results, three things to scope. Nothing takes ownership of an argument:
`blobFromImage` borrows the image and leaves it as it found it, and `forward` borrows both the
network and the blob --- the borrow-versus-consume split Chapter 4 established for `Image`, applied
to types that are not `Image`.

#sect("Loading the model")

`Dnn.fromOnnx` is the only way into the module, and every way it can fail is a `Left`.

#example("Load once, at startup. The Left names the file you asked for.")[
```scala
Dnn.fromOnnx("models/feeder-classifier.onnx") match
  case Left(e)    => sys.error(s"the model did not load: ${e.getMessage}")
  case Right(net) => net.use { n => /* the whole pipeline lives in here */ }
```
]

The failure modes are deliberately not teased apart. OpenCV reports a missing file, a file of
arbitrary bytes and a structurally invalid graph as three *different* `CvException`s from three
different lines of `onnx_importer.cpp`, and none of that is a stable interface worth
pattern-matching on. A missing, unreadable or layerless model arrives as `CvError.LoadFailed`, whose
`resource` field is the path you passed; a graph the importer throws on arrives as
`CvError.NativeCall`, whose operation reads `reading an ONNX model from '…'`. Both name the file, and
both mean the same thing to a caller: the model did not load, and the process is still alive.

Two checks bracket the native read. The path is examined *first*, with `java.io.File` --- not
because the native code would miss a missing file, but because that is the only way the message names
your file instead of quoting a C++ source location. Afterwards comes an `empty()` guard: 4.13.0's
importer throws rather than returning a layerless `Net`, but the sibling readers in the same header
do not all behave that way, and an empty `Net` that escapes the load fails much later, inside
`forward`, with nothing pointing back at the cause. If the guard fires, the handle is real even
though the graph is not, so it is released before the `Left` is built.

#warning[
  Loading is not cheap, and not something to do per frame: parsing a graph, allocating its weights
  and laying out its buffers costs orders of magnitude more than the forward pass. Load once at
  startup, keep the `Managed[Net]` alive for the life of the pipeline, and reuse it.
]

#memory[
  `Net` is one of the 185 `org.opencv.*` types with no public `release()` --- only `Mat`,
  `VideoCapture` and `VideoWriter` have one, out of the 188 that hold a native pointer. `scalacv`
  frees it anyway, through `Releasable.nativeHandle`: the address is read from the binding's own
  `nativeObj` field, the JVM finalizer is disarmed *before* the pointer is deleted, and the cached
  `MethodHandle` onto the private `delete(long)` does the deletion. If that bridge cannot be opened
  the library throws `CvError.NativesMissing` rather than degrading to a silent leak.

  A leaked `Net` costs what its weights cost: parsed parameters as large as the `.onnx` file that
  produced them, held behind a Java object the collector sees as a few dozen bytes. The measured
  shape of that mistake for a handle-freed type is in the README --- 4000 leaked `KalmanFilter`s
  reached 54 GB of RSS, against 86 MB when released. A model reloaded per frame gets there faster
  than anything else in this book.
]

If you obtain a `Net` some other way --- `org.opencv.dnn.Dnn.readNet`, `Net.quantize` --- `import Dnn.given` puts
the same `Releasable[Net]` in scope, so `Managed(raw)` frees it on identical terms. One release path
for networks, not two.

#sidebar("Getting the file onto the machine")[
  A model is a large binary that has to arrive from somewhere, and `Models` is the library's answer.
  A `ModelSpec` pins a file name, mirror URLs tried in order, a SHA-256 the bytes must match, and
  optionally `sizeBytes`; `Models.fetch(spec, into)` downloads to a temp file *beside* the target and
  moves it into place only after verification, so an interrupted run never leaves a truncated model
  behind, and an already-verified target is returned without touching the network.

  The size check is not redundant with the hash: it catches the mirror that answers with an HTML
  error page, or the Git LFS host serving a 131-byte pointer file, and it changes the *message* ---
  "expected 232589 bytes, got 131" says what happened, where "SHA-256 mismatch" invites the reader to
  suspect tampering. URLs may be `file://`, so a model already on disk is another mirror and an
  air-gapped build is a spec whose only URL is local.
]

#sect("The blob, and the four numbers")

A network does not take an image. It takes a *blob*: a four-dimensional `CV_32F` `Mat` in NCHW
layout, with sizes `(1, C, H, W)` --- batch, channels, height, width --- whose values have been
scaled into whatever range the model was trained on. `Dnn.blobFromImage` builds one.

#figure-table("What each parameter does, and what it defaults to.")[
#tbl(
  columns: (auto, auto, auto, 1fr),
  [*Parameter*], [*Type*], [*Default*], [*Effect*],
  [`mat`], [`Mat`], [---], [the source image, borrowed; must be non-empty],
  [`scaleFactor`], [`Double`], [`1.0`], [multiplier applied *after* the mean subtraction],
  [`size`], [`Option[Size]`], [`None`], [spatial size `(width, height)` to resize to],
  [`mean`], [`Scalar`], [`Scalar(0, 0, 0)`], [subtracted per channel, in the *blob's* channel order],
  [`swapRB`], [`Boolean`], [`false`], [swaps the first and third channels, BGR to RGB],
  [`crop`], [`Boolean`], [`false`], [`true` resizes the short side and centre-crops the rest away],
)
]

Three of those interact in ways that produce a working program with wrong answers.

*The arithmetic is `(pixel - mean) * scaleFactor`, in that order,* and the mean is not scaled. A
model trained on `[0, 1]` inputs wants `scaleFactor = 1.0 / 255` with a `mean` expressed in
`[0, 255]`, not a mean already divided by 255. The tests pin the order with numbers whose two
readings cannot agree: at `scaleFactor = 2.0` and `mean = Scalar(4, 0, 0)`, a channel of 10 becomes
`(10 - 4) * 2 = 12`, never `10 * 2 - 4 = 16`.

*`mean` is applied in the blob's channel order, which is after `swapRB`, not before.* Measured
against 4.13.0: OpenCV swaps first and subtracts second, so with `swapRB = true` on a BGR image the
first component of `mean` meets red, not blue --- exactly what published per-model triples assume.
The two readings differ by the amount that makes a model quietly *worse* rather than visibly broken,
which is far harder to notice.

*`Size` is `(width, height)`, but the blob's trailing dimensions are `(height, width)`.*
`Size(300, 200)` yields sizes `(1, 3, 200, 300)`. Transposing that is the usual cause of a network
that runs and returns nonsense rather than one that reports an error.

`size = None` keeps the source's own spatial size, correct only for a network with a dynamic input
shape. A `size` that is given but not strictly positive is rejected with an
`IllegalArgumentException`, as is an empty `mat`: OpenCV reads a zero extent as "do not resize",
which would silently build a blob of the wrong shape instead of failing.

Here is the mistake almost everyone makes first --- taking the defaults, which belong to OpenCV and
to no model:

#example("Wrong. Values in [0, 255], channels in BGR, and a model trained on neither.")[
```scala
// The graph accepts this blob and returns a full tensor of confident nonsense.
Dnn.blobFromImage(frame.mat, size = Some(Size(640, 640)))
```
]

And the same call with the model card's numbers in it:

#example("Right. Every argument is copied from the model's documentation, not guessed.")[
```scala
Dnn.blobFromImage(
  frame.mat,
  scaleFactor = 1.0 / 255,          // the model was trained on [0, 1] inputs
  size = Some(Size(640, 640)),      // (width, height)
  mean = Scalar(0, 0, 0),           // this family subtracts nothing
  swapRB = true,                    // trained on RGB; OpenCV decodes BGR
  crop = false                      // stretch both axes rather than centre-crop
)
```
]

Naming every argument, including the ones that match the default, turns a review of the
preprocessing into a diff against the model card rather than an exercise in remembering what `false`
meant in position five.

#figure-table("Normalisation recipes for families you will meet. Always confirm against the card.")[
#tbl(
  columns: (1fr, auto, auto, auto, auto),
  [*Model family*], [*`scaleFactor`*], [*`mean`*], [*`swapRB`*], [*`size`*],
  [Caffe classics (ResNet, VGG, GoogLeNet)], [`1.0`], [`Scalar(104, 117, 123)`], [`false`], [224×224],
  [TorchVision / ImageNet normalise], [`1.0 / 255`], [`Scalar(123.675, 116.28, 103.53)`], [`true`], [224×224],
  [MobileNet-SSD (Caffe)], [`1.0 / 127.5`], [`Scalar(127.5, 127.5, 127.5)`], [`false`], [300×300],
  [YOLO (Darknet or ONNX export)], [`1.0 / 255`], [`Scalar(0, 0, 0)`], [`true`], [416×416 or 640×640],
)
]

#subsect("The checklist")

When a model that works in Python returns garbage here, the cause is on this list far more often
than anywhere else. Walk it against the model card before debugging anything downstream:

+ *Input size.* Does `size` match the shape the graph declares, and is it written
  `(width, height)`?
+ *Range.* Does `scaleFactor` put pixels into the trained range --- `1.0` for `[0, 255]`,
  `1.0 / 255` for `[0, 1]`, `1.0 / 127.5` for `[-1, 1]` with a matching mean?
+ *Mean.* Is it expressed in `[0, 255]` (pre-scaling), and is it written in the *blob's* channel
  order, meaning RGB whenever `swapRB` is `true`?
+ *Channel order.* Was the model trained on RGB? Almost all were, and OpenCV decodes BGR, so
  `swapRB = true` is the common case even though the default is `false`.
+ *Aspect ratio.* Did training stretch the image to a square, centre-crop it, or letterbox it?
  `crop = false` stretches, `crop = true` centre-crops, and letterboxing is neither --- you would
  pad the image yourself before building the blob.
+ *Coordinate space.* If the model emits boxes, they are in the *blob's* pixel space. Whatever
  reshaping you chose above has to be inverted when you map them back onto the frame.

#sect("The forward pass")

`Dnn.forward` sets the blob as the network's input and runs the graph --- in one call, on purpose.
The two operations are not independently useful: a `Net` whose input has been set but not forwarded
is a half-applied mutation, and a `forward` with no preceding `setInput` reads whatever the last
caller left behind. Fusing them makes the stateful pair atomic, and makes the window between them one
no caller can accidentally widen.

`outputName` selects which blob to retrieve. `None` runs to the last layer, which is what a
single-output classifier wants; a multi-output graph needs a name, and those are *blob* names ---
for an ONNX import, the graph's declared outputs --- not the `onnx_node!…` layer names the importer
generates. A name that matches nothing is a `CvError.NativeCall` mentioning `Net.forward`, not a
crash, and an empty net or blob is an `IllegalArgumentException` raised before any native code runs.
Retrieving several outputs from one graph gets its own listing further down.

The return value deserves a paragraph of its own, because OpenCV's behaviour here is a trap.
`Net.forward` hands back a header onto the layer's *own* output buffer --- the generated JNI wraps
the returned `cv::Mat` with a constructor that shares pixels, and the `dnn` blob manager reuses that
allocation for the next pass of the same shape. Measured on 4.13.0, two forwards from one `Net`
returned the identical `dataAddr`, and the second rewrote the first result's pixels in place: anyone
diffing this frame's heatmap against the previous frame's got zero, every time, with nothing thrown.
`Dnn.forward` copies before returning and releases the borrowed header immediately, so holding two
results from one network means what it looks like it means. The cost is one memcpy of the output
blob, negligible beside the pass that produced it.

#memory[
  `blob` is borrowed, not consumed, but it must stay alive until `forward` returns. Releasing it
  while a pass is in flight is a segmentation fault, not an exception --- native code is reading the
  buffer you just freed. Keep the blob in a `Managed` whose scope strictly encloses the call, as
  every example here does. Releasing it twice afterwards is harmless: `release()` is idempotent, and
  a released handle throws `IllegalStateException` from `get` rather than handing JNI a dangling
  pointer.
]

#sidebar("One Net per thread")[
  A `Net` is not a pure function. `setInput` mutates it and `forward` reads that mutation back, so
  one network cannot be driven from two threads at once. `Dnn.forward` narrows the window by fusing
  the pair, but it is not a lock and does not pretend to be.

  The rule is one `Net` per thread, or serialised access that you own. For a pool of workers, build
  one network per worker at startup and never share: the cost is one copy of the weights per thread,
  which is a price to decide on knowingly rather than discover through corrupted output. Give each
  worker a throwaway `forward` at startup too --- the first pass allocates the graph's intermediate
  buffers, so an unwarmed network makes the first real request the slowest one you will ever serve.
]

#sect("Backends and targets")

By default OpenCV runs the graph on its own CPU backend. To ask for anything else, the raw `Net`
exposes `setPreferableBackend` and `setPreferableTarget`, taking `int` constants from
`org.opencv.dnn.Dnn`. Call them once, after loading and before the first `forward`.

#example("Ask for OpenCL. Whether you get it is a property of the host, not of this code.")[
```scala
import org.opencv.dnn.Dnn as CvDnn

Dnn.fromOnnx("models/feeder-classifier.onnx").foreach { managed =>
  managed.use { net =>
    net.setPreferableBackend(CvDnn.DNN_BACKEND_OPENCV)
    net.setPreferableTarget(CvDnn.DNN_TARGET_OPENCL)
    // ... every forward from here on prefers the GPU, if there is one to prefer ...
  }
}
```
]

#figure-table("What the pairings mean, and whether the bundled natives can actually run them.")[
#tbl(
  columns: (1fr, auto, auto, auto),
  [*Goal*], [*Backend*], [*Target*], [*Reachable?*],
  [Default CPU], [`DNN_BACKEND_OPENCV`], [`DNN_TARGET_CPU`], [yes --- the default],
  [CPU with FP16 math], [`DNN_BACKEND_OPENCV`], [`DNN_TARGET_CPU_FP16`], [ARM v8 only],
  [GPU via an OpenCL driver], [`DNN_BACKEND_OPENCV`], [`DNN_TARGET_OPENCL`], [yes, with an ICD installed],
  [NVIDIA GPU via CUDA], [`DNN_BACKEND_CUDA`], [`DNN_TARGET_CUDA`], [no],
)
]

The honest answer about GPUs here has three parts. OpenCL works: the `libopencv_dnn` inside the
ordinary bytedeco classifier is compiled with OpenCV's `ocl4dnn` backend, so nothing extra goes on
the classpath --- but the host needs an OpenCL driver, an ICD such as `libOpenCL.so.1`, which is not
bundled because it is a driver for hardware nobody can predict, and layers `ocl4dnn` does not
implement run on the CPU regardless.

CUDA does not work, and not because of anything in your code. The classifier this library tells you
to add is a CPU-only build --- ask `Core.getBuildInformation()` and you will find `cudaarithm` on its
`Unavailable:` line on every platform. Real `-gpu` classifiers exist, but they keep their payload
under `org/bytedeco/opencv/linux-x86_64-gpu/` while `OpenCv.load()` extracts from
`org/bytedeco/opencv/linux-x86_64/`; that jar alone throws `CvError.NativesMissing`, and alongside
the ordinary one it silently runs on the CPU. If you need CUDA, run inference as a separate service
and keep `scalacv` for the image work around it.

GPU acceleration of ordinary operations --- `blur`, `resize`, `cvtColor` --- is out of reach too, and
that boundary is not this library's doing: it needs `cv::UMat`, and the official OpenCV *Java*
bindings ship no `UMat` class at all.

#warning[
  An accelerator you did not get is silent, not loud. A backend or target the build was not compiled
  with, or whose driver is missing, falls back to the CPU and returns a perfectly good answer: no
  exception, no `Left`, no log line. Setting a target is not evidence that it engaged. The only
  reliable check is a timing comparison --- load once, warm up, time N forwards on one blob with
  `DNN_TARGET_CPU`, then the same N with the target you hope for, and compare the means.
]

What you do get without asking is the CPU acceleration that was on all along: runtime-dispatched
SIMD up to AVX-512 where the hardware supports it, and OpenCV's thread pool across your cores.

#sect("Decoding: from a tensor to an answer")

`forward` gives you a `Mat` whose shape is entirely the model's business, and the cheapest thing to
do with a model you have not run before is print that shape. A transposed `size`, a graph exported
with an axis order you did not expect, a rank of three where you assumed two --- all of them show up
here in one line, before any arithmetic has had a chance to make them plausible.

#example("The first thing to run against a model you did not export yourself.")[
```scala
import org.opencv.core.{CvType, Mat}

def describe(output: Mat): String =
  val dims  = output.dims()
  val sizes = (0 until dims).map(output.size).mkString("(", ", ", ")")
  s"$dims-D $sizes of ${CvType.typeToString(output.`type`())}"
```
]

Ask `dims()` and `size(i)`, never `rows()` and `cols()`: OpenCV reports both of the latter as `-1`
for any `Mat` of more than two dimensions, so a YOLOv8-shaped `(1, 84, 8400)` output answers `-1` to
every two-dimensional question, and answers it without complaint. The element type is almost always
`CV_32F`; anything else means the graph was quantised and its outputs need dequantising before the
numbers mean what the model card says they mean.

#subsect("A classifier: one row of scores")

An image classifier emits one score per class, in a `(1, N)` output. The prediction is the argmax,
which `Core.minMaxLoc` finds directly:

#example("Top-1 from a classifier, with the label list the model shipped with.")[
```scala
import org.opencv.core.Core
import org.opencv.dnn.Net

def classify(net: Net, frame: Image, labels: Seq[String]): (String, Double) =
  Dnn
    .blobFromImage(frame.mat, scaleFactor = 1.0 / 255, size = Some(Size(224, 224)),
                   mean = Scalar(123.675, 116.28, 103.53), swapRB = true)
    .use { blob =>
      Dnn.forward(net, blob).use { output =>
        val mm = Core.minMaxLoc(output)
        (labels(mm.maxLoc.x.toInt), mm.maxVal)
      }
    }
```
]

`minMaxLoc` reads a two-dimensional single-channel array, which is exactly what a `(1, N)` score row
is, and reports the winning column as `maxLoc.x`. The label list is your responsibility: it ships
beside the model as a text file, one class per line, and its order is the order of the output
columns. A label file whose order does not match the export gives you a model that runs and lies
just as convincingly as a wrong `mean` does.

#note[
  `maxVal` is whatever the last layer emits. Many exported graphs stop *before* the softmax, so those
  numbers are unbounded logits rather than probabilities in `[0, 1]`. The argmax is identical either
  way; apply a softmax yourself only if you need a calibrated confidence to threshold on.
]

#subsect("A detector: many rows, most of them duplicates")

A detector's output is a grid of candidates rather than a row of scores, and it is far larger than
the answer you want. A YOLOv5-shaped export gives `(1, N, 5 + K)` --- for each of N candidate boxes,
four numbers describing the box in the *blob's* pixel space, one objectness score, and K per-class
scores. A YOLOv8-shaped export gives `(1, 4 + K, N)`: transposed, with the objectness column gone.
Decoding one as though it were the other produces boxes rather than an error, which is why the
`describe` call above is worth its two lines.

Whichever the layout, the same three steps follow, and none of them is deep-network work. Candidates
below a confidence floor are dropped, which removes the overwhelming majority of the N rows.
Duplicates are suppressed, because a detector fires on one bird from several neighbouring grid
cells. And the survivors are mapped out of blob space back into frame space, inverting whatever
`size` and `crop` did on the way in.

That last step is where the quiet bugs live: a broken coordinate map still draws boxes, still draws
them near the objects, and still looks roughly right on the frame you happened to test with.
Chapter 27, #emph[Object Detection], is entirely about the code between `forward` and the drawn box ---
letterbox padding and the two coordinate systems it creates, decoding while filtering rather than
after, per-class non-maximum suppression through `org.opencv.dnn.Dnn.NMSBoxes`, and picking the
confidence and IoU thresholds from frames rather than from feel. This chapter stops at the tensor.

#subsect("More than one output")

A graph with several declared outputs needs `outputName`, and the valid names are the graph's own
rather than any you invent. Ask the network for them:

#example("Reading the shape of every declared output of a multi-output graph.")[
```scala
import scala.jdk.CollectionConverters.*
import org.opencv.core.Mat
import org.opencv.dnn.Net

def outputShapes(net: Net, blob: Mat): Map[String, Seq[Int]] =
  net.getUnconnectedOutLayersNames.asScala.toSeq.map { name =>
    Dnn.forward(net, blob, outputName = Some(name)).use { out =>
      name -> (0 until out.dims()).map(out.size).toSeq
    }
  }.toMap
```
]

That runs the graph once per name, and it is worth being explicit about the cost rather than hiding
it. OpenCV's plural overload, `net.forward(outputs, names)`, fills a `java.util.List[Mat]` in a
single pass --- but every element of that list is a borrowed header onto the network's own buffers,
which is the exact hazard `Dnn.forward` copies to remove. If a multi-output graph sits on a hot path
and the extra passes hurt, drop to that overload through the raw binding and copy each element
yourself before anything forwards the network again.

#sect("Batching, and why it rarely pays here")

`Dnn.blobFromImage` builds a blob whose batch dimension is always one --- a real constraint, and
rarely the one that matters.

Most exported graphs pin the batch dimension to 1 anyway; a dynamic batch axis is something you ask
for at export time. Batching pays off by keeping a GPU's many cores fed, and in the general case
there is no GPU --- OpenCV's CPU backend already parallelises a single forward across your cores, so
a batch of eight mostly serialises the same work through the same thread pool while multiplying peak
native memory by eight. And a camera, a request handler, or any pipeline answering frames as they
arrive has nothing to batch: the second frame does not exist when the first needs an answer.

If you have *measured* a win --- a small model, many images in hand, a machine where OpenCL genuinely
engaged --- the door is not locked. OpenCV's plural sibling of the call this chapter wraps,
`org.opencv.dnn.Dnn.blobFromImages(images, scaleFactor, size, mean, swapRB, crop)`, takes a
`java.util.List[Mat]` and returns a blob whose leading dimension is the list's length. Wrap that
`Mat` in a `Managed` and hand it to `Dnn.forward`, which cares only that the blob is non-empty and
shaped the way the graph accepts. The output's leading dimension is then N as well, and splitting it
back into N answers is yours to write --- which is the other half of why batching so rarely pays
here.

#sect("The feeder, end to end")

Everything above in one program: load once, then per frame preprocess, infer, decode, draw, write.
Read it for who frees what as much as for what it computes.

#example("The whole pipeline. Every native object is owned by a scope that outlives its use.")[
```scala
import scalacv.*
import org.opencv.core.Core
import org.opencv.dnn.Net

OpenCv.load()

val labels: Seq[String] =
  scala.io.Source.fromFile("models/species.txt").getLines().toSeq

def identify(net: Net, frame: Image): (String, Double) =
  Dnn
    .blobFromImage(
      frame.mat,                       // borrowed: the frame is still alive afterwards
      scaleFactor = 1.0 / 255,
      size = Some(Size(224, 224)),     // (width, height)
      mean = Scalar(123.675, 116.28, 103.53),
      swapRB = true,
      crop = false
    )
    .use { blob =>                     // blob outlives the forward, and only just
      Dnn.forward(net, blob).use { out =>
        val mm = Core.minMaxLoc(out)
        (labels(mm.maxLoc.x.toInt), mm.maxVal)
      }
    }

val named: Either[CvError, String] =
  Dnn.fromOnnx("models/feeder-classifier.onnx").flatMap { model =>
    model.use { net =>                 // one Net, loaded once, freed at the end of this block
      Image.reading("feeder.jpg") { frame =>
        val (species, score) = identify(net, frame)
        frame
          .drawText(f"$species $score%.2f", Point(12, 28), Scalar.Green)
          .write("feeder-annotated.png")
          .map(_ => species)
      }.flatMap(identity)
    }
  }
```
]

Four owned native objects exist at the deepest point of that program --- the network, the frame, the
blob and the output tensor --- and each is released by the construct that acquired it, innermost
first, on success and on exception alike. `frame.mat` borrows, so building the blob does not disturb
the image; `drawText` consumes and returns, so the annotated image is the value the chain hands on
rather than a mutation of `frame`; and `write` is a terminal, so the frame is already released when
`Image.reading` closes it again --- which is exactly why `close` is idempotent.

The two `flatMap`s are load-bearing rather than decorative. `Image.reading` wraps whatever its body
returns in its own `Either`, so a body that already returns one produces
`Either[CvError, Either[CvError, String]]`; `flatMap(identity)` flattens the read failure and the
write failure into one channel, which is the shape a caller can actually match on.

Everything model-specific in that listing sits in two places: the arguments to `blobFromImage`, and
the handful of lines that turn `out` into an answer. Swap the model and those are the only lines that
change --- a detector would replace the second with a decode of the kind the next chapter builds,
and would leave the first alone but for its numbers.

#sect("Next")

This chapter kept the model at arm's length: you brought the `.onnx`, read its card, chose the four
numbers, ran one pass, and read a `Mat`. For a classifier that is the whole job --- the answer is
one argmax away from the tensor. For a detector it is barely half of it, and the missing half is not
deep-learning work at all but coordinate arithmetic that fails without saying so.

Chapter 27, #emph[Object Detection], spends its whole length there, on these same three calls ---
`fromOnnx`, `blobFromImage`, `forward` --- with everything interesting on either side of them: an
input transform that does not lie about the frame's aspect ratio, a decode that filters eight
thousand candidate rows as it reads them rather than afterwards, suppression done per class, and a
map back into the frame's own coordinates that you can check rather than hope about.
