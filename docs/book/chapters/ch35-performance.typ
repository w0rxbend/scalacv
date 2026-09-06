#import "../lib/book.typ": *

#chapter("Performance", subtitle: [Where the time and the memory actually go, and how to find out rather than guess.])

A vision pipeline has two budgets, and they are not the same budget. The first is wall clock: at 30
frames per second a frame arrives every 33.3 milliseconds, and everything you intend to do to that
frame --- decode, convert, filter, detect, annotate, encode --- has to fit inside that window or
you fall behind in the way Chapter 22, _Streaming and Backpressure_, priced out. The second is
resident memory: a 1080p BGR frame is about 6 MB of off-heap pixels, so a loop that keeps one frame
too many alive per iteration is losing 180 MB per second of runtime. Neither budget bounds the
other. A pipeline can hit 60 fps and still be killed by the kernel at the four-minute mark, and a
pipeline whose footprint is flat at 200 MB can miss its deadline on every frame.

They need separate instruments for the same reason. Wall clock is measured with a clock, and the
JVM gives you a good one. Resident memory is not measured by anything the JVM offers, because the
bytes are not in the heap: the forty on-heap bytes of a `Mat` header are the only part of a
six-megabyte buffer that a heap profiler can see. Chapter 1, _Why This Library Exists_, gave the number --- 2000 unreleased
`Mat(1000, 1000, CV_8UC3)` instances left 5,865 MB resident, against 144 MB for the same 2000 with
`release()` called, a factor of 41 on both JDK 21 and JDK 25 --- and that ratio is what a heap
graph is blind to. You will need `/proc/self/statm`, or the RSS-based
`Pointer.physicalBytes()`, and you will need to watch it at the same time as you watch the clock.

The good news is that the two budgets usually fail for the same handful of reasons, and the reasons
are unglamorous. Almost nothing in a scalacv pipeline is slow because an inner loop is badly
written; OpenCV's inner loops are vectorised C++ that you are not going to improve from Scala. What
is slow is doing the work at four times the resolution it needed, converting colour spaces twice,
allocating a detector inside the loop, and copying a full frame across a module boundary that had a
borrowing form available. Every measured win in this repository removed a copy. Not one of them made
a kernel cleverer.

So this chapter is mostly about measurement, and about a short list of structural changes that are
worth making before you measure anything at all. It ends with an ordered recipe: what to look at
first when a pipeline is too slow, and what to look at first when it is too fat.

#sect("Two budgets, two instruments")

Keep them separate on the dashboard as well as in your head. A single "performance" number that
blends them will tell you nothing on the day one of them breaks.

#figure-table("The two budgets and how each one is actually read.")[
#tbl(
  columns: (0.8fr, 0.9fr, 1.3fr, 1.3fr),
  [Budget], [Unit], [Read it with], [What a violation looks like],
  [Wall clock], [ms per frame], [`System.nanoTime()` around the loop body; `Bench.measure` for one operation], [growing lag with no error --- reads still succeed, the frames keep getting older],
  [Resident memory], [bytes of process RSS], [`/proc/self/statm` field 2 × page size, else `Pointer.physicalBytes()`], [a steady climb under steady load, then the OOM killer],
)
]

The instrument that does _not_ work for the memory budget deserves naming, because it is the one
people reach for first. `Pointer.totalBytes()` and the `-Dorg.bytedeco.javacpp.maxBytes` ceiling
account for buffers JavaCPP allocated. scalacv wraps the official `org.opencv.core.Mat` JNI API,
whose pixel buffers come from OpenCV's own `cv::fastMalloc` --- outside that accounting entirely. A
`Mat` leak runs RSS to the moon while `totalBytes()` reads flat. The ceiling that _does_ see it is
`-Dorg.bytedeco.javacpp.maxPhysicalBytes`, which is RSS-based:

```bash
java -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar
```

#memory[
  Do not gate a leak test on `Pointer.totalBytes()`. It cannot see a scalacv `Mat`, so the test
  passes for exactly as long as it takes production to fall over. Snapshot RSS before and after N
  iterations, settle with `System.gc()` followed by `Pointer.deallocateReferences()`, and assert
  _bounded_ growth rather than zero --- allocator arenas and the JIT code cache never fully return.
  Chapter 40, _Testing_, has the harness this repository actually uses.
]

#sect("Measure before you change anything")

The repository ships a `benchmarks` module for this. It is deliberately not JMH: JMH's compile-time
annotation processor is awkward to wire into Mill 1.1.7 alongside the JNI natives, and the wins this
project chases --- whole-`Mat` allocations and full-image pixel copies removed --- are large
relative to timer noise. What it does instead is spelled out in `Bench.scala`, and it is worth
knowing before you read a number it printed.

`Bench.measure(name, warmup, iterations)(body)` runs `body` `warmup` times untimed --- the default
is 2000 --- then `iterations` times with `System.nanoTime()` bracketing each individual call, the
default being 5000. It reports the mean, the sample standard deviation, and a 95% confidence
half-width on the mean using a normal approximation, `ci95 = 1.96 × σ / √n`. Every result is folded
through `Bench.blackhole`, which XORs a hash of the value into a `@volatile var sink`, because
otherwise the JIT is entitled to notice that nothing reads the output and delete the work you were
trying to time. `Bench.report` prints `Bench.environment` --- JVM name and version, OS and
architecture, `availableProcessors`, and the names of the active collectors --- above the rows, so a
pasted table carries its own provenance.

Each benchmark is a plain `main`. Nothing needs an image file: `BenchImages` draws its fixtures from
a fixed seed, so a run on any machine sees the same pixels.

#example("Every benchmark in the repository, and what each one isolates.")[
```bash
./mill benchmarks.runMain scalacv.bench.GrayBlurCloneBench   # a wasted clone in Motion.prepare
./mill benchmarks.runMain scalacv.bench.ArenaReuseBench      # per-frame destination allocation
./mill benchmarks.runMain scalacv.bench.ToMatBench           # BufferedImage -> Mat fast path
./mill benchmarks.runMain scalacv.bench.ToBufferedImageBench # Mat -> BufferedImage fast path
./mill benchmarks.runMain scalacv.bench.GraphicsAlphaBench   # translucent-shape blend overhead
./mill benchmarks.runMain scalacv.bench.PictureBoundsBench   # scene-graph layout cost vs shape count
./mill benchmarks.runMain scalacv.bench.ConfigProbeBench     # how a heavy op scales with setNumThreads
```
]

The module is never published and never a CI gate. A timing regression will not turn the build red;
a person running the harness is what catches it. The half of the project rule that _is_ automated is
the other half: no optimisation ships without a measured delta #emph[and] a bit-identical output
hash from `BenchImages.hash`, so that a change which is faster because it quietly computes something
different fails before it lands.

The reading rule comes from the harness's own scaladoc and is deliberately coarse: two results are
distinguishable when their confidence intervals do not overlap. That is the whole test. It is worth
holding onto the caveats too --- the harness does not fork a JVM per variant, so a benchmark that
times two variants in one run lets the first pollute the second's JIT profile; and `1.96 × σ / √n`
assumes independent, identically distributed samples, which GC pauses and OS scheduling make
optimistic. Both are arguments for distrusting a 1% delta, not a 40% one.

#sect("What the benchmarks actually measured")

Four optimisations shipped with recorded deltas. Every one of them deleted a copy.

#figure-table("The four shipped wins, at the sizes they were measured at.")[
#tbl(
  columns: (1.5fr, 1.1fr, 0.85fr, 0.85fr, 0.6fr),
  [What changed], [Case], [Before], [After], [Delta],
  [Translucent shapes blend a bounding box, not the canvas], [640×480], [194.8 µs], [22.5 µs], [−88%],
  [], [1920×1080], [1287.5 µs], [76.4 µs], [−94%],
  [], [3840×2160], [15297 µs], [489.7 µs], [−97%],
  [`BufferedImage` → `Mat` skips the redraw for a BGR raster], [640×480], [131.8 µs], [18.5 µs], [−86%],
  [], [1920×1080], [982.5 µs], [116.1 µs], [−88%],
  [], [3840×2160], [7103.8 µs], [1653.8 µs], [−77%],
  [`Mat` → `BufferedImage` writes into AWT's array], [1920×1080, ch=3], [1893.7 µs], [976.3 µs], [−48%],
  [], [3840×2160, ch=3], [12540.3 µs], [5446.1 µs], [−57%],
  [Motion detection stops cloning a frame it only blurs], [640×480], [46.3 µs], [28.5 µs], [−38%],
  [], [1920×1080], [218.9 µs], [126.8 µs], [−42%],
)
]

#warning[
  Every absolute figure above came from one developer machine running OpenJDK 25. The commits do not
  record the CPU model, and it would not help you if they did: core count, OpenCV build and memory
  bandwidth all move these numbers. #emph[The deltas reproduce; the absolutes do not.] Treat a µs
  figure as a ratio between two rows, never as a budget for your service. The full record, including
  the half-widths, is `docs/mdoc/benchmark-results.md`.
]

Read the third row group as a warning rather than a victory. Displaying a live 1080p feed now costs
about half what it used to, and it is still roughly a millisecond per frame of pure copying. On-screen
display stays a real per-frame cost even after the fast path.

#sidebar("The optimisation that was measured and not built")[
Received wisdom says the largest single win available in an OpenCV pipeline is reusing destination
`Mat`s across frames --- a scratch arena, so a 30 fps loop allocates once instead of once per stage
per frame. scalacv's ownership model makes that awkward to offer, since every mid-level op allocates
a fresh destination and hands you something you own. So before designing the feature,
`ArenaReuseBench` sized it: the `gray → blur → canny` chain run two ways over the same frame, once
through the pure API and once with three preallocated destinations reused across frames, with
bit-identical output verified by hash.

At 640×480 reuse won 4%. At 1920×1080 it #emph[lost] 0.4%. At 3840×2160 it won 0.8%. The sign of
the delta flips as the frame grows and the magnitude never leaves the noise band, which is a
stronger argument than any single p-value: OpenCV's allocator plus G1 make per-frame `Mat`
allocation a sub-1% cost next to the compute. The arena would have added a large API surface and
reversed the no-in-place ownership contract the whole library rests on, in exchange for nothing. It
was not built.

If you are chasing per-frame cost, stop copying frames before you start reusing buffers. The
measured wins there are an order of magnitude larger and need no new API.
]

#sect("Where the time goes")

#subsect("Resolution, which is quadratic")

Cost is proportional to pixel count, and pixel count is quadratic in linear size. Halving the width
of a frame quarters the work. This is the single largest lever in the chapter and it is available in
almost every pipeline, because almost every pipeline is doing detection at a resolution chosen by
the camera rather than by the algorithm.

The `ArenaReuseBench` sweep of the plain `gray → blur → canny` chain shows the scaling, and shows
that it is not clean:

#figure-table("The same three-stage chain across three frame sizes. The last column is derived.")[
#tbl(
  columns: (1fr, 0.8fr, 1fr, 1.2fr),
  [Frame], [Megapixels], [Fresh allocation], [Per megapixel],
  [640×480], [0.31], [145.3 µs], [473 µs],
  [1920×1080], [2.07], [574.2 µs], [277 µs],
  [3840×2160], [8.29], [7556 µs], [911 µs],
)
]

1080p has 6.75 times the pixels of VGA and takes only 3.95 times as long --- fixed per-call costs
dominate at small sizes, so the small frame looks inefficient per pixel. 4K has 4 times the pixels
of 1080p and takes 13.2 times as long, which is the opposite failure: an 8-megapixel buffer no
longer fits in cache, and every stage pays for memory bandwidth instead of arithmetic. The lesson is
not "cost is quadratic" so much as "cost gets worse than quadratic once you leave cache", and the
practical consequence is that the step down from 4K to 1080p buys more than the arithmetic predicts.

#subsect("Colour conversion, kernels, and copies")

After resolution, the recurring costs are ordinary:

- *Colour conversion* touches every pixel and produces a new buffer. Converting to grey and back, or
  converting inside a loop what could have been converted once outside it, is a full-frame pass you
  did not need. Chapter 8, _Typed Constants and Colour Spaces_, is the reference for which
  `ColorConversion` you actually want.
- *Kernel size* multiplies. A separable Gaussian is linear in the kernel's side length, but
  `bilateralFilter(9, 75, 75)` --- the operation `ConfigProbeBench` uses precisely because it is
  heavy --- is not separable, and its cost grows with the square of the diameter. Doubling a
  neighbourhood is rarely a small change.
- *Per-frame native objects.* Not the destination `Mat`s --- the sidebar above established those are
  free at realistic sizes --- but detectors, nets, matchers and models, which are expensive to build
  and, in most cases, cannot be freed at all by OpenCV's own API.
- *Copies at module boundaries.* Video decode, AWT display, recorder input. Each of these has a
  borrowing form and a copying form in scalacv, named so you choose deliberately.

#sect("Resize early")

The wrong version is the one that reads most naturally, which is why it is so common. Detect on the
frame you were handed, then shrink for display:

#example("Detection at the camera's resolution, because that is the frame that arrived.")[
```scala
// WRONG at 4K: every detection pass does 8.3 megapixels of work because the
// camera chose the resolution and nobody overruled it.
var n = 0
camera.foreach() { frame =>
  n += 1
  frame.markFaces(frame.faces(detector)).write(f"out/frame-$n%05d.png")
}
```
]

Shrink first, detect on the small frame, and map the results back:

#example("Detect small, annotate large. One extra buffer, a quarter of the detection work.")[
```scala
val factor = 0.5

def scaledBox(r: Rect, k: Double): Rect =
  Rect((r.x * k).round.toInt, (r.y * k).round.toInt,
       (r.width * k).round.toInt, (r.height * k).round.toInt)

var n = 0
camera.foreach() { frame =>
  n += 1
  // `.copy` branches: `small` gets its own buffer, `frame` stays alive to be drawn on.
  val small = frame.copy.scale(factor)
  val faces =
    try small.faces(detector)
    finally small.close()

  // Those boxes are in `small`'s coordinates. Map them back before they touch `frame`.
  frame.drawRects(faces.map(f => scaledBox(f.box, 1.0 / factor)))
       .write(f"out/frame-$n%05d.png")
}
```
]

Two things about that second listing. `scale(factor)` takes a single factor applied to both axes and
consumes its receiver, which is why the `.copy` is there: the branch is the deliberate cost that
Chapter 4, _The Image Type_, described, and it is one allocation per frame against three quarters of
a detection pass saved. And the coordinate mapping is not optional. Every box, landmark and contour
that comes back is in the coordinate system of the image you ran the detector on. Chapter 27,
_Object Detection_, made this the central caveat of downscaled inference, and it is the bug you will
actually ship: boxes that are consistently half the size they should be and clustered in the
top-left quadrant are always this mistake, never a bad model.

#tip[
  Do not assume the detector is already shrinking the frame for you. `FaceDetect.detect` sets the
  detector's input size to the incoming image's size on every call --- that is what lets one
  `FaceDetectorYN` handle frames of differing sizes at all --- so a 4K frame really is detected at
  4K, whatever `inputSize` you passed to
  `FaceDetect.create(modelPath, inputSize, scoreThreshold, nmsThreshold)`. Downscaling first
  therefore cuts the model's own work, not only the pipeline around it. The floor is the smallest
  face you must find: the detector cannot report what the shrunken frame no longer resolves.
]

#sect("Hoist the natives out of the loop")

A `CascadeClassifier` load parses an XML model. A `Net` load parses weights. Both are measured in
tens of milliseconds and both are trivially loop-invariant, so the mistake is easy to see once
stated --- and easy to write, because the constructor sits right next to the code that uses it.

#example("A cascade loaded, and leaked, once per frame.")[
```scala
// WRONG: reparses the model XML thirty times a second, and drops a native
// handle each time it does.
var n = 0
camera.foreach() { frame =>
  n += 1
  Cascades.load(CascadeName.FrontalFaceAlt).foreach { cascade =>
    frame.drawRects(frame.detectHaar(cascade.get)).write(f"out/frame-$n%05d.png")
  }
}
```
]

#memory[
  The leak is the worse half. Of the 188 `org.opencv.*` types that own native memory, exactly three
  expose a public `release()`, and `CascadeClassifier` is not one of them --- scalacv frees it
  anyway, through the `Managed[CascadeClassifier]` that `Cascades.load` returns, but only if
  something releases that handle. Dropped on the floor thirty times a second, it is thirty native
  objects a second that nothing will ever reclaim. Chapter 5, _Lifetimes: Managed, Releasable, and Scope_, has the numbers: 4000
  leaked `KalmanFilter` instances measured 54 GB against 86 MB released.
]

Load once, outside; scope the handle around the whole loop; let the scope release it on every exit
path.

#example("One load, one release, whatever happens inside.")[
```scala
Managed.scope { own =>
  // `adopt`, not `apply`: Cascades.load already hands back a Managed, and the
  // scope takes that handle over rather than wrapping it a second time.
  val cascade = own.adopt(Cascades.load(CascadeName.FrontalFaceAlt).toTry.get)
  var n = 0
  camera.foreach() { frame =>
    n += 1
    frame.drawRects(frame.detectHaar(cascade)).write(f"out/frame-$n%05d.png")
  }
}
```
]

The same reasoning covers the copying at module boundaries. `Video.frames` decodes into a single
reused `Mat`, refilled in place, so per-frame allocation is zero however long the video runs;
`Video.framesCopied` clones per frame, lazily as you pull, for when you must keep a frame past its
turn. `Recorder.write` is overloaded on `Mat` as well as `Image` for exactly this reason --- a
borrowed frame goes straight to the encoder with no intervening `Image` clone.

#figure-table("The frame sources, and what each one costs you per frame.")[
#tbl(
  columns: (1.1fr, 1.2fr, 0.8fr, 1.4fr),
  [API], [What you get], [Cost], [Reach for it when],
  [`Camera.foreach`], [owned `Image`, closed for you], [one clone], [you transform or annotate the frame],
  [`Camera.snapshot` / `take` / `taking`], [owned `Image`(s)], [one clone each], [you want a handful of frames as images],
  [`Video.frames`], [*borrowed* reused `Mat`], [*zero*], [you only read the frame, or run mid-level ops over it],
  [`Video.framesCopied`], [owned `Managed[Mat]` per pull], [one clone per pull], [you must keep specific frames],
)
]

The trade on the borrowed form is the borrowing contract from Chapter 19, _Video I/O_: do not
retain the frame past its turn, and do not feed it to an `Iterator` combinator that retains.
`toList`, `sliding` and `buffered` all hand you N references to one buffer holding the last frame.

#sect("What the JVM contributes, and what it cannot fix")

#subsect("The first hundred frames lie")

The JIT compiles a method after it has been interpreted enough times to be worth compiling, and it
recompiles when its speculation turns out wrong. A frame loop's first few hundred iterations are
therefore measuring the interpreter and the C1 tier, not the code you shipped. `Bench.measure`
defaults to 2000 untimed warmup iterations for precisely this reason, and your own instrumentation
needs the same discipline: discard the opening frames before you compute a mean, or you will chase a
regression that was warm-up all along. Native code inside OpenCV does not warm up --- it was
compiled by a C++ compiler years ago --- so the warm-up you observe belongs entirely to the Scala
around the JNI calls, which makes it small in absolute terms and easy to mistake for something else.

#subsect("`-Xmx` does not bound your footprint")

`-Xmx2g` bounds the Java heap. It says nothing whatever about the pixel buffers, which are the part
of your process that is measured in gigabytes. A container limit of 2 GB against `-Xmx2g` and a
`Mat` leak gives you a process killed by the kernel with no `OutOfMemoryError`, no heap dump, and a
heap graph that is flat and healthy right up to the last sample. Size the container against RSS, and
set `-Dorg.bytedeco.javacpp.maxPhysicalBytes` below that so you get a Java exception rather than a
SIGKILL --- Chapter 39, _Degradation and Error Budgets_, is about what to do with that exception
when it arrives.

#tip[
  One scalacv-specific flag has a per-frame cost of its own. `-Dscalacv.trackOwnership=true` makes
  every spent `Managed` handle allocate a `Throwable`, so that a later use-after-move can name the
  transform that consumed the handle rather than only the line that touched it. That is exactly what
  you want for the half hour you spend chasing an `IllegalStateException`, and exactly what you do not
  want in a frame loop, where every transform and every terminal spends a handle. It is read once at
  class load and off by default; leave it off in production.
]

#subsect("GC tuning does not help native pressure")

This one is worth stating flatly because it is where tuning effort most often goes to die. Heap
pressure is the only thing that triggers a collection, and it is uncorrelated with native pressure.
A frame loop can exhaust native memory while the heap stays small and the collector never runs at
all. Switching collectors, raising the young generation, lowering a pause target --- none of them
change when a `Mat` is freed, because none of them can see one. The 41× from Chapter 1 is the whole
argument: reclamation was never impossible, nothing made it happen in time. Releasing the handle is
what makes it happen. The collector is not a participant.

#sect("Native threading fights your thread pool")

OpenCV runs its own internal thread pool, and OpenBLAS runs another. For a single sequential
pipeline that is exactly what you want: the heavy kernels --- bilateral filter, DNN forward pass,
resize --- are parallelised for you, for free. The moment you fan pipelines out across your own
executor, the two layers oversubscribe: every kernel spawns workers that contend with your own
parallelism for the same physical cores, and the total throughput goes down. Cap the inner pools so
your outer parallelism owns the cores:

```bash
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 java -jar your-app.jar
```

or, from code, `org.opencv.core.Core.setNumThreads(1)` before you spread work.

How much this is worth is a property of your machine, not of the library, which is why no number for
it appears in `docs/mdoc/benchmark-results.md`. `ConfigProbeBench` is the one benchmark you are
expected to run rather than read: it prints `Core.useOptimized`, `Core.getNumThreads`,
`Core.getNumberOfCPUs` and the parallel, IPP and OpenCL lines of the build information, then times a
`bilateralFilter(9, 75, 75)` on a 1280×720 scene at 1, 2, 4 and your CPU count of threads, restoring
`Core.setNumThreads(-1)` afterwards. Run it before you decide anything about threading. Chapter 36,
_Concurrency and Thread Safety_, covers the ownership rules that decide what you are even allowed to
fan out.

#sect("A profiling recipe")

In order, because the order is most of the value:

+ *Confirm which budget is broken.* Time the loop body and watch RSS, in the same run. Slow and flat
  is a compute problem; fast and climbing is a lifetime problem; both is two problems.
+ *Time the stages, not the pipeline.* Wrap each transform in `System.nanoTime()` and print a
  breakdown per hundred frames after the first few hundred. In practice one stage is 80% of the
  budget and the answer is visible in the first table you print.
+ *Check the resolution before anything else.* If the dominant stage is running at the camera's
  native size and does not need to be, stop here and fix that first; nothing else on this list
  pays as well.
+ *Count the copies.* Look for `.copy` inside the loop, `Seq[Image]` of intermediates, a `Camera`
  frame source where `Video.frames` would do, and `Recorder.write(image)` where a borrowed `Mat` was
  already to hand.
+ *Sample, with wall-clock and not CPU sampling.* JFR or async-profiler will show you where the Java
  side sits, and both have the same blind spot: a JNI call appears as a single frame. You will learn
  what `cvtColor` costs; you will not see inside it, and there is nothing inside it for you to
  change anyway. Wall-clock sampling at least attributes the blocked time honestly.
+ *Only then think about threads.* Run `ConfigProbeBench`, read your own scaling curve, and decide
  whether to cap the inner pools or leave them alone.

The step most often skipped is the second one, and skipping it is how an afternoon disappears into
tuning a stage that was 3% of the frame. Measure the breakdown, fix the one that is 80%, and
re-measure --- the profile will have moved.

#sect("Next")

Everything in this chapter assumed one thread doing one pipeline at a time, and the last two
sections started to strain against that assumption. Chapter 36, _Concurrency and Thread Safety_,
lifts it properly: which native handles may be shared, which must be owned by exactly one thread,
why a `Managed` release is safe under a race when using the same handle is not, and what "share
results, never handles" costs you when the result is a loaded model.
