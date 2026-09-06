#import "../lib/book.typ": *

#chapter("Concurrency and Thread Safety", subtitle: [What you may share between threads, what you must not, and why the difference is a dead process rather than a wrong answer.])

Every concurrency bug you have debugged in Scala had one merciful property: the program stayed
alive. A lost update, a torn read, a `HashMap` corrupted by two writers --- all of them produce
wrong answers, and a wrong answer can be printed, logged, asserted on, and eventually understood.
The JVM's memory model is a contract about visibility and ordering that holds no matter how badly
you break it, and the worst outcome it permits is nonsense.

None of that applies below the JNI boundary. An `org.opencv.core.Mat` is about forty bytes of Java
object holding a pointer to a `cv::Mat` that owns megabytes of C++ heap. The memory model governs
the forty bytes. It has nothing to say about the megabytes, because they are not Java memory, were
not allocated by the JVM, and are not visible to anything the JVM knows how to synchronise. Two
threads writing the same pixel buffer is not a race in the Java sense at all. It is a race in C++,
and the way C++ reports one is `SIGSEGV`: no stack trace, no exception, no failing test, just a
process that stops existing mid-sentence.

That asymmetry is why this chapter is blunt rather than nuanced. There is no "probably fine", no
"it only corrupts a few frames", no flag that turns the failure into something catchable. You are
either inside the rules or you are gambling with the lifetime of the process, and because the
failure is a crash rather than a fault, the gamble usually pays off in testing and stops paying off
under load.

The good news is that the rule fits in a sentence, and the shape of parallel image work fits the
rule almost by accident. Detection is embarrassingly parallel over frames or files; each unit of
work wants its own buffers anyway; and everything a detector hands back is plain Scala data with no
pointer behind it. What follows is the rule, the reason `Managed` makes one part of it safer than
you might expect, and the two patterns --- one detector per worker, one owner per handle --- that
cover almost every real deployment.

#sect("The rule in one line")

*One owner per handle.* A `Mat`, a `Managed`, an `Image`, a detector, a `VideoCapture`: keep it on
one thread, or guard it with a lock you wrote yourself. scalacv does not use JavaCPP's
`PointerScope`, so there is no thread-local scope to reason about and no way for ownership to be
implicit. Ownership is always the `Managed` you are holding, wherever that handle travels.

The reason the rule is livable rather than crippling is the other half of the design: *detector
results are plain, immutable Scala data, copied out of native memory before they reach you.* A
`Seq[Face]`, a `Seq[Contour]`, an `ObjectTrack`, a `FaceMatch`, an `Int` --- once computed, these
belong to no thread and to every thread. So the natural pattern, do the native work on a worker and
hand the *result* back, needs no synchronisation whatsoever. Chapter 24, _Face Detection_, described
`FaceDetect.detect` decoding OpenCV's result `Mat` into `Face` values and releasing it before
returning; that decision, which looked like ergonomics at the time, is what makes fan-out safe at
all.

Here is the classification in full. Read the middle column as a verdict, not a preference.

#figure-table("What scalacv lets you share, and what it does not.")[
#tbl(
  columns: (1.35fr, 0.75fr, 1.5fr),
  [Thing], [Concurrent use], [Why],
  [`Contour`, `Rect`, `Scalar`, `Face`, `Pose`, `FaceMatch`, `ObjectTrack`, `QrCode`, `Gallery`, and every `Seq[…]` a detector returns], [free], [Plain Scala values copied out of native memory. No pointer behind them.],
  [`Managed.release()` / `close()`], [safe], [A `getAndSet` on an `AtomicReference`. Frees exactly once even under a race.],
  [`OpenCv.load()`], [safe], [Double-checked on a `@volatile` flag inside a `synchronized` block. Idempotent.],
  [A shared `Mat`, read by several threads and written by none], [safe], [Concurrent reads of a buffer nobody mutates are fine.],
  [A shared `Mat` with any writer at all], [*unsafe*], [OpenCV's refcount ops are atomic; pixel writes are not.],
  [`Image`], [one owner], [Move semantics --- a transform spends the handle, and the next use throws wherever it happens.],
  [`FaceDetectorYN`, `Net`, `FaceRecognizer`, `Tracker`, `Kalman`], [one per worker], [Stateful native objects. A second thread corrupts the in-flight call.],
  [`MotionDetector`, `ObjectTracker`, `Odometry`, `LoopDetector`], [one per worker], [Mutable state in Scala *and* in C++; documented single-thread.],
  [`VideoCapture`, `Camera`, `Recorder`], [one owner], [A decode or encode in progress is not reentrant.],
)
]

Two rows do most of the work. The top row is why the "detect on a worker, combine on the caller"
pattern below needs no locks. The `Managed.release()` row is subtler than it looks, and it is worth
being precise about what it buys you.

#sidebar("What the crash actually looks like")[
  The reason this chapter refuses to hedge is that the failure mode leaves you almost nothing to
  work with. `FaceDetect`'s own test suite produced this, before the release path was made to
  disarm the binding's unconditional `finalize()`:

  ```text
  SIGSEGV (0xb)  C  [libopencv_java.so+0x163155]  Java_org_opencv_objdetect_FaceDetectorYN_delete
  Current thread: JavaThread "Finalizer"
  ```

  Note the thread. The mistake was made on a worker; the crash surfaced on the collector's
  finalizer thread, minutes later, in a frame that names no code of yours. A JVM crash log is what
  you get instead of a stack trace, the offending call site does not appear in it, and the reason
  the collector was running at that moment was that a DNN had allocated enough to trigger it. This
  is what "undefined behaviour" costs in practice: not a wrong answer you can chase, but a
  post-mortem in a language you were not writing.
]

#sect("What `Managed` actually promises")

`Managed[A]` holds its object in a `java.util.concurrent.atomic.AtomicReference[A | Null]`, and
every path that ends the handle's life --- `release()`, `close()`, and the internal `take()` that
`Image`'s move semantics run on --- goes through `ref.getAndSet(null)` and branches on what came
back. `null` means someone else got there first, and the caller does nothing at all. A non-null
value means this caller won, and it is this caller that runs the `Releasable`.

That gives you one guarantee, exactly, and it is worth stating in full because it is the only
concurrency guarantee in the library:

#quote(block: true)[
  If two threads race to release the same `Managed`, the underlying object is freed exactly once,
  by exactly one of them, and the loser is a silent no-op.
]

A double `delete` in C++ is undefined behaviour that merely *often* happens to survive, which makes
it the worst possible class of bug: it passes CI, it passes staging, and it takes down the
production process on the Tuesday you deploy something unrelated. The compare-and-set removes that
possibility entirely. It is why a `LatestFrame` slot can hand ownership between a capture thread
and a worker (Chapter 22, _Streaming and Backpressure_) with no lock, and why a `finally` block in a
worker and a shutdown hook on the main thread can both close the same handle without either of them
needing to know about the other.

Now the part people over-read. The atomic reference protects the *handle*, not the *object*. It
says nothing about two threads calling `detector.get` and then using the same `FaceDetectorYN`
concurrently: both `get` calls succeed, both hand back the same live pointer, and what happens next
is between them and OpenCV. It says nothing about one thread writing a `Mat` while another reads
it. It does not make `Managed` a lock, a mutex, or a synchronisation point of any kind. It makes
exactly one operation --- the last one --- safe under a race, and leaves every other operation
exactly as unsafe as the native object underneath it.

#memory[
  Release being idempotent under a race is not a licence to be vague about ownership. A handle that
  *no* thread releases still leaks: the compare-and-set makes a second release harmless, not a
  first release optional. In a fan-out, name the thread that owns each handle before you write the
  code, and prefer `Managed.use` or `Managed.scope` so that the release is in a `finally` you did
  not have to remember to write.
]

#sect("A spent `Image` is spent everywhere")

`Image`'s move semantics were introduced in Chapter 4, _The Image Type_, as a way of keeping
exactly one live `Mat` in a pipeline. They have a second property that only becomes visible once
threads are involved: the failure they produce is the *same* failure whichever thread makes the
mistake, and it is an exception rather than a crash. Which thread loses is a coin toss; what the
loser gets is not.

A transform spends its receiver by taking the `Mat` out of the handle. Any later access ---
from the same thread, from a worker, from a thread that started before the transform even ran ---
finds `null` in the atomic reference and throws `IllegalStateException` from Scala, before anything
crosses JNI. The `getAndSet` is what decides the race: exactly one caller walks away with the `Mat`,
and every other caller gets the exception rather than a second reference to a buffer that is about
to be rewritten underneath it.

So the mistake that would be a use-after-free in C++ is a caught exception here, and the classic
version of it looks like this:

#example("Wrong. One image, two workers --- and only one of them can win.")[
```scala
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import scalacv.*

// One worker's whole job: consume the Image it was handed, answer with an Int.
def edgeCount(image: Image): Int =
  val edges = image.gray.canny(80, 160)
  try edges.contours().size
  finally edges.close()

val img = Image.read("street.jpg").fold(throw _, identity)

// Both futures are handed the SAME Image. `gray` spends it, so whichever
// future reaches it second throws IllegalStateException -- and which one
// that is changes from run to run.
val a = Future(edgeCount(img))
val b = Future(edgeCount(img))
```
]

Be careful about what the guarantee covers, because the obvious repair is also wrong. `get` is a
read, not a lock. A thread that reads the handle a microsecond before another thread's transform
takes it holds a `Mat` reference that was perfectly valid when it was read, is being written
somewhere else now, and carries no way of finding that out. So writing `Future(edgeCount(img.copy))`
does not fix the example, it only narrows the window: `copy` clones through `handle.get`, which
still races the `gray` that is spending the same handle, and the outcome is either the exception you
were trying to avoid or a clone taken from a buffer mid-write.

The branch has to happen on one thread, before the fan-out, which is also the version that reads
correctly:

#example("Right. The split happens before either worker starts; only plain data comes back.")[
```scala
val branch = img.copy            // one clone, on this thread, before anything forks

val a = Future(edgeCount(branch))   // owns `branch` end to end
val b = Future(edgeCount(img))      // owns `img` end to end
```
]

Two handles now, one per worker, each spent and closed by the worker that owns it. Note what is
*not* in the fixed version: no lock, no `synchronized`, no `AtomicReference` of your own. Two `Int`s come back
and they compose freely, because an `Int` is not a handle. That is the shape to aim for
everywhere --- native work strictly inside a worker, plain data across the boundary, and every
handle given its owner before the first thread starts.

#tip[
  When the `IllegalStateException` arrives from a worker, the stack points at the *reuse*, which is
  rarely the interesting line and is now on the wrong thread as well. Start the JVM with
  `-Dscalacv.trackOwnership=true` and the exception carries, as its cause, the stack of the
  transform or terminal that actually spent the handle. It is off by default because it allocates a
  `Throwable` every time a handle is spent; the check that reads it lives only on the
  already-failing path.
]

#sect("Detectors, nets and matchers: one per worker")

The stateful native objects are the ones that tempt you, because they are the expensive ones. A
loaded ONNX `Net` costs a file read and a graph build; an SFace `FaceRecognizer` is a 37 MB model.
Everything in your instincts says: build it once, share it, save the memory. Do not.

The reason is not caution, it is mechanism. `Net.setInput` mutates the network and `Net.forward`
reads that mutation back --- `Dnn.forward` performs both in one call precisely so that a caller
cannot accidentally widen the window between them, but that is not a lock and was never claimed to
be one. `FaceDetect.detect` calls `setInputSize` on the detector for every single frame,
unconditionally, because YuNet enforces `CV_CheckEQ(input_image.size(), input_size)` and any other
caller holding the same detector can change that size out from under you. And the results are
stateful too: `FaceRecognizerSF.feature` writes into an internal buffer that the next call
overwrites, which is why `FaceRecognizer.embed` copies the row out before returning, and why
`Dnn.forward` copies its output `Mat` before handing it back.

Two threads inside any of those sequences do not get slightly wrong answers. They get a corrupted
in-flight native call.

The pattern is one detector per worker, constructed once, released when the worker retires. A
`ThreadLocal` expresses it directly for a pool you did not build yourself:

#example("One net per thread, built on first use --- and never shared.")[
```scala
import org.opencv.dnn.Net

import scalacv.*

val perThreadNet = ThreadLocal.withInitial[Either[CvError, Managed[Net]]] { () =>
  Dnn.fromOnnx("model.onnx")
}
```
]

`Dnn.fromOnnx` hands back an `Either[CvError, Managed[Net]]`, so each thread gets its own handle and
its own load failure, and the `Either` is still there to be dealt with at the call site rather than
thrown from a static initialiser you cannot see. What the type does *not* give you is a retirement
hook: a `ThreadLocal` has no destructor, so a thread that dies still holding a `Managed[Net]` leaks
the network, and there is nowhere in the declaration above to put the `close()`. That makes this the
right shape for a fixed pool whose threads outlive the work, and the wrong shape for threads that
come and go.

For a pool you *do* build, `Managed.scope` is the better tool, because a worker usually needs more
than one native object and wants all of them released on every exit path --- a normal shutdown, an
exception in the detector, a model that fails to load half way through construction. `scope`
registers each object the moment it is created and releases them in reverse order however the block
ends, which is the guarantee a `val`-then-`try`/`finally` cannot give you: anything allocated before
the `try` is unguarded.

#example("A worker's entire native footprint, scoped to the worker's lifetime.")[
```scala
import java.util.concurrent.{BlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import scalacv.*

final case class Result(path: String, faces: Seq[Face])

def worker(jobs: BlockingQueue[String], results: java.util.Queue[Result],
           modelPath: String, running: AtomicBoolean): Unit =
  Managed.scope: own =>
    // `adopt` takes over a handle that arrived already wrapped, so the detector is
    // still released exactly once -- here, when this worker's scope ends.
    val detector = own.adopt(FaceDetect.create(modelPath, Size(320, 320)).fold(throw _, identity))

    while running.get do
      jobs.poll(50, TimeUnit.MILLISECONDS) match
        case null => ()                            // idle tick; re-check `running`
        case path =>
          // Native work begins and ends inside this line. `reading` closes the image
          // on success, on failure and on exception; only `Seq[Face]` escapes.
          val faces = Image.reading(path)(_.faces(detector)).getOrElse(Seq.empty)
          results.add(Result(path, faces))
```
]

The `poll` with a timeout rather than a blocking `take` is the shutdown mechanism, and it is the
part worth copying. A worker parked forever in `take()` cannot notice that `running` went false; a
worker that wakes every 50 ms notices within 50 ms, leaves the `while` loop normally, and falls out
of the bottom of the `Managed.scope`, which is what releases the detector. Interrupting a thread
parked in a *native* call does nothing at all --- the interrupt flag is set and the C++ code
carries on --- so a timeout you chose beats an interrupt you cannot deliver.

#example("The pool: N workers, N detectors, one bounded queue, one clean shutdown.")[
```scala
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters.*

def analyseAll(paths: Seq[String], modelPath: String, workers: Int): Seq[Result] =
  val jobs    = ArrayBlockingQueue[String](workers * 4)   // bounded: submission blocks
  val results = ConcurrentLinkedQueue[Result]()
  val running = AtomicBoolean(true)

  val threads = (1 to workers).map: n =>
    val t = Thread(() => worker(jobs, results, modelPath, running), s"scalacv-worker-$n")
    t.start()
    t

  try paths.foreach(jobs.put)                             // backpressure lives here
  finally
    while !jobs.isEmpty do Thread.sleep(5)                // let the queue drain
    running.set(false)                                    // then ask the workers to stop
    threads.foreach(_.join())                             // and wait for every scope to close

  results.iterator.asScala.toVector                       // plain data, safe to read now
```
]

The queue is bounded on purpose. An unbounded queue of file paths is harmless, but the same
skeleton with frames in it is not: every queued frame is a native allocation of several megabytes
that no collector will reclaim, so an unbounded queue in front of a slow worker is a native memory
leak with a schedule. Chapter 22 priced exactly that, and its latest-frame-wins slot is the version
to use when the producer is a camera you cannot slow down.

Shutdown order is the other thing to copy verbatim: drain, *then* stop, *then* join, and only read
`results` after the last `join()` has returned. The queue publishes each `Result` safely on its own,
which is what a `ConcurrentLinkedQueue` is for, so the `join` is not there for visibility. It is
there for two other things: it is the only evidence that no worker is still adding, and it is the
only evidence that every `Managed.scope` has run its releases. Return before the join and you have
returned while detectors are still open.

#warning[
  Do not shut the pool down by setting the flag and walking away without the `join()`. A worker
  abandoned between `FaceDetect.create` and the end of its scope still owns a native detector, and
  nothing will ever take it back: a thread's death frees no native memory, and neither does the
  collector. In a service that builds a pool per job the leak compounds, and the compounding has
  been measured --- 4000 leaked `KalmanFilter` instances cost *54 GB* of resident memory against
  *86 MB* for the same run with releases.
]

#sect("OpenCV is already using your cores")

Every discussion of "how many workers should I run" in this library has a second party to it that
you did not invite. OpenCV runs its own internal thread pool, and OpenBLAS runs another. A
bilateral filter, a resize, a DNN forward pass --- all of them already fan out across cores without
being asked. So a pool of `availableProcessors` workers, each running a kernel that itself spawns
`availableProcessors` threads, is not eight-way parallelism on eight cores. It is sixty-four
runnable threads contending for eight cores, plus the context-switching and cache thrash that
implies, and it is routinely *slower* than one thread doing the work sequentially.

Think of it as a single budget. Total useful threads should be roughly the core count, and you get
to decide which layer spends it:

- *One pipeline at a time* --- a single video being processed, a batch job with no outer
  parallelism. Leave OpenCV's threading alone. It parallelises the heavy kernels for you, and it is
  usually the faster default.
- *You fan out yourself* --- a worker pool, a request handler, a per-file batch. Cap the inner
  pools so your outer parallelism owns the cores: `org.opencv.core.Core.setNumThreads(1)` before
  you spread the work, or from the environment:

```bash
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 java -jar your-app.jar
```

`Core.setNumThreads(-1)` puts OpenCV's default back, which matters if you cap it for one phase of a
job and want the sequential phase to go fast again.

Which of the two wins on your hardware is not something this book can tell you, and it is not
something the library's benchmark page will tell you either --- deliberately. `ConfigProbeBench`
prints your actual runtime configuration (`useOptimized`, `getNumThreads`, `getNumberOfCPUs`, and
the parallel, IPP and OpenCL lines of `Core.getBuildInformation`) and then measures a bilateral
filter on a 1280×720 scene with `setNumThreads` stepped through 1, 2, 4 and your CPU count:

```bash
./mill benchmarks.runMain scalacv.bench.ConfigProbeBench
```

Its numbers are the one set the project refuses to publish, because thread scaling is a property of
the machine rather than of the library, and quoting one laptop's answer would be worse than
quoting none. Run it on the box you deploy to. Chapter 35, _Performance_, has the rest of
the measurement discipline.

#sect("Virtual threads change the scheduler, not the boundary")

The JDK's virtual threads are the obvious modern answer to "I have a blocking workload and want a
lot of it", and video decoding plus native inference certainly looks like a blocking workload. Be
precise about what they change here.

What they change: the cost of *waiting*. A worker parked on a bounded queue, a handler waiting for
a request, a fan-out over a thousand files that is mostly I/O --- those get cheaper, because a
parked virtual thread costs a heap object rather than a megabyte of stack. A `StructuredTaskScope`
fanning out over files and joining is a nicer way to write the pool above, and the ownership rules
in it do not change at all.

What they do not change: anything about the native boundary. A virtual thread executing a native
frame is *pinned* to its carrier platform thread for the duration of that call --- the scheduler
cannot unmount a stack that has C++ frames on it. So a virtual thread inside
`FaceDetectorYN.detect` occupies a real OS thread for as long as the detection runs, exactly as a
platform thread would. Ten thousand virtual threads all calling into OpenCV at once do not give you
ten thousand concurrent detections. They give you as many detections as the scheduler has carriers,
which is the core count by default, and ten thousand minus that many threads waiting for one ---
having consumed a scheduler's worth of bookkeeping to arrive at what a fixed pool of carrier-count
size does directly.

Virtual threads are excellent for the *structure* around the native work and irrelevant to the
native work itself. Size the layer that actually touches OpenCV to your cores, whichever kind of
thread it runs on, and let virtual threads carry the waiting.

#note[
  A ZIO fiber has the same relationship to the rule, for the same reason. "One owner per handle" is
  a statement about the handle, not about the concurrency primitive: a native object belongs to
  exactly one fiber at a time, and `Scope` is how you make that ownership survive interruption ---
  which `try`/`finally` cannot promise once an interrupt is in play. Chapter 37, _ZIO Integration_,
  has the details.
]

#sect("The mistakes, collected")

Every one of these compiles.

#figure-table("Five ways to break the rule, and what each of them costs.")[
#tbl(
  columns: (1.3fr, 1.2fr, 1fr),
  [You wrote…], [What happens], [Fix],
  [Shared one `Net` or detector across futures], [In-flight native call corrupted, then a `SIGSEGV`], [One per worker, or a `ThreadLocal`],
  [Collected borrowed `Video.frames` `Mat`s into a `Seq` and processed them off-thread], [N references to one reused buffer, racing the decoder], [`Video.framesCopied`],
  [Two workers `crop` the same `Image`], [One of them throws use-after-move --- which one varies], [Branch with `.copy` on the calling thread, before either starts],
  [Two threads write the same `Mat`], [Undefined behaviour], [Give each its own buffer],
  [Locked around an immutable detector result], [Nothing --- it is harmless and pointless], [Delete the lock],
)
]

The last row is there on purpose. It is the cheapest mistake in the table and the most common one
in review, and it is a sign that the boundary has been drawn in the wrong place: if you feel the
need to lock a `Seq[Face]`, the thing you are actually worried about is upstream, where a handle
crossed a thread it should not have.

#sect("Where this leaves you")

Three sentences carry the chapter. Share results, never handles. One owner per handle, one detector
per worker, constructed once and released by the scope that made it. Count your threads across
every layer, not only the one you wrote.

Everything here has been expressed with threads, queues and `AtomicBoolean` flags, which is the
right vocabulary when the surrounding application is written that way --- and it has one hole in it
that this chapter has not closed. A `try`/`finally` releases on success and on exception. It does
not release on *interruption*, and once an effect system is cancelling fibers, interruption is a
routine event rather than an emergency. Chapter 37, _ZIO Integration_, shows how `Scope` closes that
hole, what the borrowing contracts look like once frames arrive as a `ZStream`, and the one place
where the synchronous end-of-stream semantics do not carry over.
