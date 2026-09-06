#import "../lib/book.typ": *

#chapter("Degradation and Error Budgets", subtitle: [Deciding, in advance, what your pipeline does when it cannot keep up.])

A web service that runs out of capacity returns 503s. That is unpleasant to watch on a dashboard,
but it is honest: the failure is visible, it is counted, and everyone downstream learns about it in
the same second it happens. A vision service does not have that decency. It has three failure
stages, and only the last one looks like a failure.

First it gets slower. Chapter 22 priced that exactly: a 50 ms loop body against a 33.3 ms frame
budget falls behind by 16.7 ms per frame, forever, and after a minute you are analysing a frame that
is twenty seconds old. Every read succeeded. Every frame decoded. Nothing threw. Then it gets
*wrong*, because a face detector twenty seconds behind the world is not a slow detector --- it is a
detector reporting, with full confidence and a plausible score, on a room that emptied while it was
thinking. Then, eventually, it dies: native memory climbs past whatever ceiling exists, and the
process goes away either with a `java.lang.OutOfMemoryError` nothing in this library catches or with
a `SIGKILL` from the kernel that leaves exit code 137 and no stack trace at all.

The interval between "slower" and "dead" is where the decisions get made, and it is a bad time to be
making them. The alternative is a *degradation ladder*: an ordered list of things the service will
give up, written down before the incident, each rung with a trigger you can measure and a cost you
have agreed to pay. Climbing down one is a choice; being pushed off the top is an outage. The
repository has a page on this --- `docs/mdoc/degradation-and-error-budgets.md` --- and this chapter
is that page expanded, because none of it is improvisable at three in the morning.

The second half of the discipline is an *error budget*: a number, agreed in advance, saying how much
of a particular failure is normal. Without one, every dropped frame is either an emergency or
invisible, depending on who is looking, and the two states alternate unpredictably. With one, "we
dropped 0.4% of frames this hour against a 1% budget" is a sentence that ends a conversation.

#sect("Sort failures by what will catch them")

Chapter 6 sorted scalacv's failures by what they *mean* --- a value to handle, or a bug to fix.
Operationally you need a different sort: which of them your code will ever see.

#figure-table("The three tiers, by the thing that catches them. Tier 3 is the one that takes services down.")[
#tbl(
  columns: (0.4fr, 1.5fr, 1.5fr, 1.4fr),
  [Tier], [Arrives as], [Caught by], [Your move],
  [1], [`Either[CvError, A]`], [an ordinary `match` at your boundary], [branch, degrade, count it],
  [2], [thrown `IllegalArgumentException` or `IllegalStateException`], [a `catch` you should not write], [fix the call site],
  [3], [thrown `java.lang.Error`], [nothing in scalacv], [catch it yourself, at the outermost frame],
)
]

Tier 1 is a closed set. `CvError` is sealed with exactly six cases, so an exhaustive match over it
is also an exhaustive list of the labels your metrics can carry. Use one stable string per case for
the log line, the metric label and the budget row alike, so nobody has to translate between three
vocabularies at two in the morning.

#example("Six cases, six labels. `NativeCall` keeps its operation name; the others do not need one.")[
```scala
/** The label to attach to a metric or a log line for each failure the library can return. */
def budgetLabel(e: CvError): String = e match
  case CvError.DecodeFailed(_, _)   => "decode_failed"      // bytes that are not an image
  case CvError.EncodeFailed(_, _)   => "encode_failed"      // an unwritable path, an unknown extension
  case CvError.LoadFailed(_, _)     => "load_failed"        // a model, cascade, codec or capture source
  case CvError.CalibrationFailed(_) => "calibration_failed" // too few views, or no convergence
  case CvError.NativesMissing(_, _) => "natives_missing"    // a build problem, not a runtime one
  case CvError.NativeCall(op, _)    => s"native_call:$op"   // OpenCV rejected a call; `op` names which
```
]

`NativeCall` is the one case whose label deserves a dimension: `native_call:cvtColor(BGR2GRAY)`
tells you where to look, and a flat `native_call` rate tells you only that something is unhappy.
Split it by operation before you alert on it. Failures spread evenly across operations are load;
failures piled onto one operation are a shape or type bug that no amount of shedding will fix.

Tier 3 is the one that surprises people. `Cv.attempt` has exactly three `catch` clauses:
`org.opencv.core.CvException`, a `CvError` passing through unchanged, and a guarded
`case e: Exception if e.getClass == classOf[Exception]` matching a *bare* `java.lang.Exception` and
nothing else --- what OpenCV's `throwJavaException` degrades to for failures it cannot classify,
`std::bad_alloc` included. There is no `case e: Throwable` and no `case e: Error`, so
`OutOfMemoryError` and `UnsatisfiedLinkError` travel straight through every `flatMap` in your
pipeline and out of the thread. The worker thread dies, the request never completes, and with an
executor that swallows uncaught throwables nothing is logged. The pod stays ready and returns
timeouts.

#warning[
  The memory ceiling is tier 3. `-Dorg.bytedeco.javacpp.maxPhysicalBytes` is worth setting, but when
  it fires it throws `java.lang.OutOfMemoryError`, which extends `VirtualMachineError` extends
  `Error`: no `Either` in scalacv contains it. Set it comfortably *below* the container's own limit
  so JavaCPP reaches the limit before the kernel does --- one path gives you a message and a stack
  trace, the other gives you exit code 137.
]

#sect("The ladder, rung by rung")

A rung is only useful if it names three things: what it gives up, what triggers the climb down, and
which number you are watching. Vague rungs ("reduce load") do not survive contact with an incident.
Here is the ladder for a face-detection service on a live camera, cheapest sacrifice first.

#figure-table("A degradation ladder. Each rung costs something specific and is triggered by a number you already emit.")[
#tbl(
  columns: (0.9fr, 1.5fr, 1.2fr, 1.3fr),
  [Rung], [What it gives up], [Trigger], [Signal that drives it],
  [Half resolution], [the smallest faces --- a quarter of the pixels remain], [lag ratio above 1.1 for one second], [`LagMeter` ratio (Chapter 22)],
  [Frame skip], [temporal resolution: one frame in three], [lag ratio still above 1.1], [the same ratio],
  [Motion gate], [nothing, on a still scene; everything, on a busy one], [lag ratio still above 1.1], [the same ratio, plus `Motion.ratio`],
  [Cheap detector], [landmarks, accuracy, recall on angled faces], [detector error rate, or the model unavailable], [`Left(NativeCall)` rate, breaker state],
  [Reduced output], [image quality on the response], [egress or encode latency], [`bytes(".jpg")` timer],
  [Shed load], [new streams; existing ones continue], [RSS above 85% of the ceiling], [the RSS gauge (Chapter 38)],
  [Refuse work], [everything; the instance drains], [a tier-3 catch fired], [the health flag],
)
]

The order is not arbitrary. Every rung above the cheap detector is *lossy but not misleading*: a
half-resolution frame finds fewer faces, and the ones it finds are real. From the cheap detector
down, the output changes character rather than volume --- a much more serious thing to do quietly.

*Half resolution* is first because detection cost scales with pixels: halving each side quarters the
pixel count, which for a detector dominated by the image it scans often approaches a 4× speed-up.
The cost is exact --- the smallest objects now occupy a quarter of the pixels they did, and below
some size the detector stops seeing them. Write down what that size is for your camera before you
ship the rung.

*Frame skip* buys throughput linearly and costs latency-to-detection linearly. It is second because
a skipped frame is a blind interval, invisible in a way a missed small face is not. Note what it
breaks: Chapter 21's frame-difference detector, optical flow and any tracker assuming small motion
between frames all read a skipped frame as a large jump, the input they are least robust to. If you
skip, make elapsed time a parameter of the algorithm rather than an assumption inside it.

*The motion gate* has the best ratio and the worst worst case. A
`MotionDetector.frameDifference()` costs a greyscale conversion, a blur, an absolute difference, a
threshold, a dilation and a contour pass; the project's `GrayBlurCloneBench` puts the blur at the
front of that chain at 126.8 µs on an already-grey 1080p frame, and the rest of the chain is the
same order of magnitude --- which against a convolutional detector is nothing. On a corridor camera at night it removes
essentially all the work. But it degrades to zero benefit exactly when you need it most: a busy
scene sets `moving` on every frame, and you have added the gate's cost to a pipeline already over
budget. Gate on motion because most frames are boring, not because you hope the busy ones will be.

#sect("A ladder you can read")

Rungs written in a design document are aspirations. Rungs written as a type are a thing you can
test, log and alert on. The enum below is the entire policy; the controller after it is the entire
mechanism. Only the output-quality rung is missing, because it belongs at the response boundary
rather than in the frame loop.

#example("The rungs as a type. The ordinal is the ladder, so climbing is arithmetic.")[
```scala
/** The rungs, best first. Climbing down always costs something; the comment says what. */
enum Rung:
  case Full     // every frame, full resolution, the YuNet detector
  case Half     // every frame at half resolution — a quarter of the pixels
  case Sampled  // half resolution, one frame in three
  case Gated    // motion decides which frames reach the detector at all
  case Cheap    // the bundled Haar cascade: no landmarks, more false positives
  case Shedding // existing streams only; a new one gets a 503
  case Refusing // nothing is accepted; the instance is draining

/** The three signals the ladder reads. None of them comes from scalacv: `lagRatio` is the
  * lag meter of Chapter 22, `nativeErrorRate` your own counter over `Left(CvError.NativeCall)`,
  * `rssFraction` the resident-set gauge of Chapter 38 over your configured ceiling.
  */
final case class Reading(lagRatio: Double, nativeErrorRate: Double, rssFraction: Double)
```
]

The controller needs hysteresis in both directions, with different constants for each. Climbing
down on a single bad reading turns a garbage-collection pause into a policy change; climbing back up
quickly turns the ladder into an oscillator. Down after a second of consistent trouble, up after
half a minute of consistent calm, is a reasonable starting pair for a 30 fps loop.

#example("The whole controller: one rung at a time, slow up, quick down.")[
```scala
final class Ladder(downAfter: Int = 30, upAfter: Int = 900):
  private val rungs = Rung.values
  private var index = 0
  private var over  = 0
  private var under = 0

  def current: Rung = rungs(index)

  /** Feed one reading per frame. Returns the rung to run the next frame at. */
  def observe(r: Reading): Rung =
    val struggling  = r.lagRatio > 1.1 || r.nativeErrorRate > 0.01 || r.rssFraction > 0.85
    val comfortable = r.lagRatio < 0.9 && r.nativeErrorRate == 0.0 && r.rssFraction < 0.6
    if struggling then
      over += 1
      under = 0
    else if comfortable then
      under += 1
      over = 0
    else
      over = 0
      under = 0
    if over >= downAfter && index < rungs.length - 1 then
      index += 1
      over = 0
    else if under >= upAfter && index > 0 then
      index -= 1
      under = 0
    current
```
]

Applying a rung to a frame is where Chapter 5's move semantics matter, and where the wrong version
is easy to write. The frame `Camera.foreach` hands you is closed when the body returns; a query such
as `faces` or `detect` borrows it, while `scale` *consumes* it and hands back a new `Image` that is
yours. So this is wrong:

```scala
case Rung.Half =>
  frame.scale(0.5).faces(yunet).map(_.box)  // leaks the scaled frame, every frame
```

`scale` returned an `Image` nobody closed. `foreach` will close the original --- which `scale`
already spent, so that release is a no-op --- and the half-size copy leaks at the frame rate of your
camera. Give it a name and a `finally`.

#example("One rung, one frame. Queries borrow, `scale` consumes, and the copy it makes is yours.")[
```scala
import org.opencv.objdetect.{CascadeClassifier, FaceDetectorYN}
import scalacv.*

def boxes(
    rung: Rung,
    frame: Image,
    n: Long,
    gate: MotionDetector,
    yunet: Managed[FaceDetectorYN],
    haar: CascadeClassifier
): Seq[Rect] =
  def scaleBack(r: Rect, f: Int) = Rect(r.x * f, r.y * f, r.width * f, r.height * f)
  rung match
    case Rung.Full =>
      frame.faces(yunet).map(_.box) // `faces` borrows; there is nothing to close
    case Rung.Half =>
      val small = frame.scale(0.5) // consumes `frame`; `foreach`'s close becomes a no-op
      try small.faces(yunet).map(face => scaleBack(face.box, 2))
      finally small.close() // `small` is ours, on every path
    case Rung.Sampled =>
      if n % 3 != 0 then Seq.empty else boxes(Rung.Half, frame, n, gate, yunet, haar)
    case Rung.Gated =>
      // `detect` borrows the frame and returns plain data, so the frame is still ours to scale.
      if !gate.detect(frame).moving then Seq.empty
      else boxes(Rung.Half, frame, n, gate, yunet, haar)
    case Rung.Cheap =>
      frame.detectHaar(haar) // a box and nothing else: no landmarks, no score
    case Rung.Shedding | Rung.Refusing =>
      Seq.empty
```
]

`detectHaar` takes the raw `CascadeClassifier` rather than the `Managed[CascadeClassifier]` that
`Cascades.load` returns, because a defaulted overload for the `Managed` is not expressible alongside
the `scaleFactor`, `minNeighbors` and `minSize` defaults. Keep the classifier inside its `use` block
for the life of the loop --- `Cascades.load(name).map(_.use(c => ...))` --- so the spent-handle
guard is still there.

#memory[
  A rung change must not change who owns what. The version above closes `small` in a `finally` on
  every branch that creates one, and never closes `frame`, which belongs to `foreach`. A ladder that
  gets this wrong leaks only under load --- only at the moment it was added to help --- and the leak
  is a full frame per iteration: about 6 MB at 1080p, 180 MB per second at 30 fps.
]

#sect("Budgets: the numbers that make a rung acceptable")

A rung is a promise to lose something; an error budget is the agreement saying how much of that loss
is fine. Without the second, nobody can approve the first.

The distinction that matters most is between a frame you *dropped* and a frame you *got wrong*. A
dropped frame is a decision: you know how many, you chose the policy, and the budget line writes
itself. A wrong frame is an unbounded liability --- it costs the same latency, produces a
confident-looking answer, is indistinguishable from a right one in your logs, and the only thing
that reveals it is a labelled ground truth you probably do not have in production. Budget the two at
wildly different levels. One percent of frames dropped is a Tuesday; one percent silently served by
a fallback detector for three weeks is an accuracy regression nobody declared.

#figure-table("Budget lines, and the signal each one is computed from. Every signal is yours to emit: scalacv has no metrics of its own.")[
#tbl(
  columns: (1.1fr, 1.5fr, 0.9fr, 1.4fr),
  [Budget line], [Computed from], [A start], [When it burns],
  [Frames dropped], [`Sampled` and `Gated` skips over frames offered], [under 1%], [the pipeline is slower than the source],
  [Frames unprocessed], [`recordTo`'s `Right(n)` over `CaptureInfo.frameCount` (advisory)], [under 1% per clip], [shrink the transform or the resolution],
  [Degraded detections], [share served by the fallback rung], [under 1%], [accuracy is quietly worse than you think],
  [Capture reopens], [reopens per camera-hour], [under 1], [a flaky link or a contended device],
  [Native-call errors], [`Left(NativeCall)` rate, split by `operation`], [near 0], [one operation dominating is a bug, not load],
  [Decode failures], [`Left(DecodeFailed)` share of uploads], [under 2%], [a client bug --- a 4xx, not a page],
  [Model-fetch failures], [`Left(LoadFailed)` per boot], [0], [a mirror outage or a checksum mismatch],
  [OOM sheds], [503s from the tier-3 `catch`], [0], [page immediately: an instance quarantined itself],
  [RSS headroom], [RSS over `maxPhysicalBytes`], [under 70%], [a steady climb under steady load is a leak],
)
]

Two rows carry warnings. `CaptureInfo.frameCount` is a `CAP_PROP_*` query answered by a backend
allowed to guess --- a live camera commonly reports `0` --- so it is a denominator to watch over
time, never a loop bound. And keep OpenCV's stderr out of the budget: some of that output is the
library working correctly. Alert on your own `CvError` rate instead.

#sidebar("The failure with no counter")[
  Every budget line above is a division you can compute from numbers your service already emits.
  "Frames processed wrongly" is not, and pretending otherwise is the most common way a degradation
  design fails review.

  Two things work. The first is a *held-out sample*: a few hundred labelled frames from your real
  cameras, replayed through the production configuration on every deploy, with recall and
  false-positive rate recorded per rung. That gives you the honest cost of each degradation, which
  is exactly the number you need to argue about where the cheap detector belongs in the ladder.

  The second is a *shadow comparison*: on a small share of frames --- one in a hundred is plenty ---
  run both the current rung and the one above it, and record the disagreement rate. It costs a fixed
  fraction of your budget, needs no labels, turns "the fallback has been serving for three weeks"
  from a discovery into a graph, and catches what a held-out sample cannot: a scene that has drifted
  away from your labelled data.
]

#sect("Timeouts, and the thing you cannot have")

Every other chapter about resilience would put a timeout on the expensive call here. You cannot, and
it is worth being exact about why rather than discovering it under load.

A JNI call in flight cannot be interrupted. `Thread.interrupt()` sets a flag that a thread parked in
OpenCV's C++ never looks at, and there is no cancel and no timeout parameter on `read`. Chapter 37's
ZIO integration is the only path in the library that even attempts interruption ---
`frameStream` wraps its read in `ZIO.attemptBlockingInterrupt` --- and its own documentation says
plainly that this parks a blocked read on the blocking pool instead of the compute executor and does
not buy cancellation.

A timeout on a native call is therefore a decision about the *caller*, never about the callee. You
can stop waiting; the work continues, on a thread you no longer control, holding native buffers you
can no longer see. Three things remain that you can actually do.

#memory[
  Abandoning a native call does not abandon its memory. A future you gave up on is still executing,
  still holds every `Mat` its pipeline allocated, and --- if it was written with `Managed` ---
  releases them only when it finishes. Two abandoned 4K pipelines are hundreds of megabytes no gauge
  attributes to anything. Bound how many calls you are willing to abandon, or do not abandon them.
]

*Bound the input so the call cannot take long.* This is the substitute that works, because it makes
duration a property of the data rather than a race. Cost scales with pixels, so a pixel gate at the
boundary is a latency gate --- and it is the cheapest check in the service, since a decoded `Image`
answers `width` and `height` without touching a pixel.

#example("A pixel budget at the boundary. The only reliable bound on a native call's duration.")[
```scala
/** Twelve megapixels — about 34 MiB of BGR pixels, and a decode-plus-detect that fits a budget. */
val MaxPixels = 4000L * 3000L

/** Admits an image or rejects it, closing it either way if it is not admitted. */
def admit(image: Image): Either[CvError, Image] =
  val pixels = image.width.toLong * image.height // queries borrow: nothing is consumed here
  if pixels <= MaxPixels then Right(image)
  else
    image.close()
    Left(CvError.DecodeFailed("<upload>", s"$pixels pixels exceeds the $MaxPixels this service accepts"))
```
]

*Bound the source, where a backend will honour it.* `CaptureOptions.withTimeout(5.seconds)` sets
`CAP_PROP_OPEN_TIMEOUT_MSEC` and `CAP_PROP_READ_TIMEOUT_MSEC`, which FFmpeg and GStreamer honour for
network sources and which V4L2, AVFoundation and the built-in MJPEG reader ignore entirely, with
nothing in the API to say which you got. Set it for `rtsp://` and `http://` only.

*Supervise at the process level.* A watchdog that has seen no progress for N seconds, a readiness
probe that goes false, and an orchestrator that drains and restarts the instance is the real timeout
for work that cannot be cancelled. It is coarse, slow, and the only item here that always works.

#sect("Circuit-breaking a detector")

A model that has started failing is worse than no model: each call costs its full latency and
returns nothing. The point of a breaker is to stop paying that toll once the evidence is in, and to
pay it again occasionally in case the situation has changed.

#example("A breaker with a cool-off. Every failure at or past the threshold restarts the clock.")[
```scala
/** Opens after `threshold` consecutive failures; lets one call through every `coolOffMillis`
  * afterwards, so a recovered dependency is noticed without a flood of retries.
  */
final class Breaker(threshold: Int = 5, coolOffMillis: Long = 30_000L):
  private var consecutive = 0
  private var openedAt    = 0L

  def closed(nowMillis: Long): Boolean =
    consecutive < threshold || nowMillis - openedAt >= coolOffMillis

  def record(failed: Boolean, nowMillis: Long): Unit =
    if !failed then consecutive = 0
    else
      consecutive += 1
      if consecutive >= threshold then openedAt = nowMillis

/** The primary detector while the breaker is closed, the bundled cascade while it is open.
  * The first element of the pair is the tag that must travel with the result.
  */
def facesOrFallback(
    image: Image,
    yunet: Managed[FaceDetectorYN],
    haar: CascadeClassifier,
    breaker: Breaker,
    nowMillis: Long
): (String, Seq[Rect]) =
  if !breaker.closed(nowMillis) then ("degraded", image.detectHaar(haar))
  else
    val attempt = Cv.attempt("FaceDetectorYN.detect")(image.faces(yunet).map(_.box))
    breaker.record(attempt.isLeft, nowMillis)
    attempt.fold(_ => ("degraded", image.detectHaar(haar)), found => ("primary", found))
```
]

The fallback deserves the honesty the model ladder in Chapter 23 demands. YuNet is a 232 kB
convolutional network that is both more accurate and faster than the Haar cascades, and it returns
five facial landmarks per face --- eyes, nose tip, mouth corners --- that the cascade cannot give
you at all. `FrontalFaceAlt` is the best of the shipped cascades and still produces visibly more
false positives, finds fewer faces at an angle or in poor light, and returns a bounding box and
nothing else. A degraded detection is not interchangeable with a primary one: the `"degraded"` tag must travel
with the result to whatever consumes it, and any landmark-dependent feature --- alignment, the
recognition of Chapter 25, an AR overlay --- must be *disabled* rather than fed a guess.

#caution[
  The cascade fallback does not exist on Windows. The `windows-x86_64` bytedeco jar ships an empty
  `share/` directory and no cascade XML, so `Cascades.load` returns `Left(CvError.LoadFailed)` there
  and says so. On Windows the bottom rung is not "no network needed", it is "no detector". Ship
  `haarcascade_frontalface_alt.xml` with your application and load it with `Cascades.loadFrom(path)`,
  which takes a filesystem path and is otherwise identical.
]

#sect("Bulkheads")

A bulkhead stops one bad stream from sinking the service. Here the resource that needs partitioning
is not threads but native memory --- the one resource with no per-request accounting anywhere in the
JVM.

Three partitions are worth building. Give each stream a fixed cap on live `Image` values, enforced
by refusing to start the next frame rather than by queueing it, so a camera that renegotiates to 4K
mid-stream cannot claim four times its share. Give each stream its own `Ladder` --- deliberately a
per-stream object with per-stream counters --- so the rung a struggling RTSP feed has fallen to does
not degrade the six healthy cameras beside it. And give each stream its own capture thread, which
Chapter 36's ownership rule requires anyway: a `VideoCapture` has exactly one owner because a decode
in progress is not reentrant, and closing it from another thread mid-decode is undefined behaviour
in C++ --- a crash rather than an exception.

What you cannot partition is the resident set. Chapter 38's RSS gauge is a whole-process number, so
an instance serving twelve streams reports 85% of its ceiling without saying which stream took the
memory. The answer is arithmetic rather than instrumentation: multiply the per-stream frame cap by
the frame size and the stream count, and if that product is anywhere near the ceiling, run fewer
streams per instance. Process-per-stream is a legitimate bulkhead, and where native memory is
invisible to the collector it is often the honest one.

#sect("When the process dies anyway")

Some failures are not degradable. A `SIGSEGV` takes the process with it: no stack trace, no `catch`,
nothing in the log but a truncated line. A supervisor and a fast restart are therefore part of the
design, not an admission of defeat --- and the design is yours: state outside the process, so a
restart costs a warm-up rather than data; a `~/.javacpp` cache already populated, so the restart
does not pay the roughly 196 MB extraction again; models warmed before readiness flips true.

This is why `Managed` exists at all. It releases exactly once and throws `IllegalStateException` on
use-after-release --- in Scala, before anything reaches JNI, where the same mistake is a
segmentation fault with no stack trace. Of the 188 `org.opencv.*` types that own native memory,
exactly three expose a public `release()`; scalacv frees the rest anyway. Chapter 5's ownership
model is a machine for turning the crash you cannot supervise into an exception you can, and the
supervisor covers what it cannot reach: a bug in OpenCV, a corrupt frame from a driver, a native
library mismatch.

#tip[
  Distinguish a restart that helps from one that cannot. `CvError.NativesMissing` is a packaging
  problem no restart fixes: report it as *not ready* rather than *not alive*, or a liveness probe
  turns it into a crash loop. A tier-3 shed is the opposite --- that instance has a leak or a
  workload it cannot serve, and should drain rather than limp.
]

The last rung runs after everything else has failed: catch `OutOfMemoryError` at the outermost frame
you own, shed the request with a 503, and clear the health flag so the instance stops taking new
work instead of failing each request in turn.

#example("The bottom of the ladder: shed one request, quarantine the instance.")[
```scala
import java.util.concurrent.atomic.AtomicBoolean
import scalacv.*

/** Flipped to false once this instance has shed. The readiness probe reads it. */
val healthy = AtomicBoolean(true)

def handleUpload(upload: Array[Byte]): (Int, String) =
  try
    Image.decode(upload).flatMap(admit).flatMap(_.gray.canny(80, 160).bytes(".jpg")) match
      case Right(jpeg)                   => (200, s"${jpeg.length} bytes")
      case Left(e: CvError.DecodeFailed) => (400, budgetLabel(e)) // the caller's problem
      case Left(e)                       => (500, budgetLabel(e))
  catch
    case e: OutOfMemoryError =>
      // Tier 3. Nothing below this line caught it, and nothing was going to.
      healthy.set(false)
      (503, s"shed: ${e.getMessage}")
```
]

Catch `OutOfMemoryError`, not `Throwable`, which would also swallow tier 2 --- the programmer errors
you want to see --- and turn a bug into a quiet 503. Do not retry inside the handler: the process is
at its ceiling, and a retry allocates again and fails again, faster. Gate readiness on
`OpenCv.isLoaded && healthy.get()`, so the shed is visible to the thing that can act on it.

#sect("What comes next")

The ladder in this chapter is a set of claims: that half resolution costs you faces below a
particular size, that the motion gate pays for itself on your cameras, that the cheap detector is
acceptable for a bounded share of frames, that the breaker opens when you think it does. Every one
is testable, and an untested ladder is a second failure mode wearing the costume of a mitigation ---
the code that runs least often, on the worst day, is the code you have exercised least.

Chapter 40, #emph[Testing], is where those claims become assertions: the tolerance metrics that compare a
degraded output against a reference without demanding bit-equality, the property-based suites that
hold the ownership rules `boxes` depends on, and the resident-set assertion that turns "no leak
under load" from an intention into a build gate.
