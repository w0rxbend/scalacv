---
title: Benchmark results
description: What scalacv's benchmarks actually measured — the four shipped wins, the optimisation that was measured and rejected, the known headroom left in place, and how to read a confidence interval before you quote one.
---

# Benchmark results

[Performance](/performance) explains the *shape* of scalacv's speed story — one live buffer per pipeline, borrowed video frames, AWT fast lanes — and lists the benchmarks that gate it. This page is the other half: **the numbers those benchmarks produced**, what each one means for code you are about to write, and, just as importantly, the optimisation that was measured and then *not* built because the measurement said it was noise.

A benchmark result is only worth reading if you know how it was produced, so this page starts with the method. Everything after that is a recovered record: the numbers lived only in commit messages, and this page is now where they live.

```scala mdoc:silent
import scalacv.graphs.*
import scalacv.*

OpenCv.load()
```

:::warning[These are one machine's microseconds, not yours]
Every absolute figure below came from a single developer machine running OpenJDK 25. The commits do not record the CPU model, and they would not help you if they did: a different core count, a different OpenCV build, a different memory-bandwidth ceiling all move these numbers. **The deltas reproduce; the absolutes do not.** Treat a µs figure here as a ratio between two rows, never as a budget for your service.
:::

## How to read these numbers

### What the harness does

The `benchmarks` module is a small plain-JVM harness — deliberately not [JMH](https://openjdk.org/projects/code-tools/jmh/), whose compile-time annotation processor is awkward to wire into Mill 1.1.7 alongside the JNI natives. **JNI** is the Java Native Interface, the bridge the JVM crosses to call OpenCV's C++ code; it matters here because every operation being timed spends most of its life on the far side of that bridge, where the JIT compiler cannot see or optimise it.

`Bench.measure(name, warmup, iterations)(body)` does four things, in this order:

1. **Warms up.** It runs `body` `warmup` times untimed (the default is 2000), so the JIT has compiled the hot path to steady state before the clock starts. A number taken from a cold JVM is measuring the interpreter, not the code.
2. **Measures.** It then runs `body` `iterations` times (default 5000), bracketing *each* call with `System.nanoTime()` and storing one sample per call. So `n` is the number of individual timings, not the number of runs.
3. **Summarises.** It computes the mean, the *sample* standard deviation (dividing by `n - 1`), and a 95% confidence half-width on the mean using a normal approximation: `ci95 = 1.96 × σ / √n`. The normal approximation is fair here only because `n` is in the thousands.
4. **Defeats dead-code elimination.** Every benchmark folds its result through `Bench.blackhole`, which XORs a hash of the value into a `@volatile var sink`. Without it the JIT is entitled to notice that nothing reads the output and delete the work you were trying to time — which produces a beautifully fast benchmark of nothing at all.

`Bench.report` prints a header from `Bench.environment` before the rows — JVM name and version, OS name and architecture, `availableProcessors`, and the names of the active garbage collectors — so a pasted table carries its own provenance.

### What one line means

Each result renders as one fixed-width line:

```
<name>            <mean> µs  ± <half-width> (95%)   cv=<spread>%  n=<iterations>
```

| Field | What it is | What it tells you |
|---|---|---|
| `mean` | arithmetic mean of the per-call timings, in microseconds | the headline cost of one call |
| `± … (95%)` | the 95% confidence **half-width on the mean** | how well the *average* is pinned down. Small here means "re-run it and you get the same mean" |
| `cv=` | coefficient of variation, `100 × σ / mean` | how noisy *individual iterations* were. A high `cv` with a tight `±` is normal: GC pauses scatter single samples while the average stays firm |
| `n=` | the timed iteration count | the sample size the half-width was computed from. The 4K cases run far fewer iterations to keep a run to a few seconds, which is exactly why their half-widths are the loosest numbers on this page |

### The reading rule

From the harness's own scaladoc: **two results are "distinguishable" when their confidence intervals do not overlap.** That is the whole test, and it is deliberately coarse. Written out:

```scala mdoc:silent
/** One row of a `Bench.report` table: the mean it printed, and the 95% half-width beside it. */
final case class BenchLine(name: String, meanMicros: Double, ci95Micros: Double):
  def low: Double  = meanMicros - ci95Micros
  def high: Double = meanMicros + ci95Micros

/** The reading rule: non-overlapping intervals, or you have not shown a difference. */
def distinguishable(a: BenchLine, b: BenchLine): Boolean =
  a.high < b.low || b.high < a.low

/** The number to put in a commit message, as a signed percentage of the baseline. */
def deltaPercent(beforeMicros: Double, afterMicros: Double): Double =
  100.0 * (afterMicros - beforeMicros) / beforeMicros
```

Applied to the largest win on this page — the translucent-shape blend at 1080p, whose before and after rows were `1287.5 ±2.6` and `76.4 ±0.2`:

```scala mdoc
distinguishable(
  BenchLine("draw translucent 1920x1080, before", 1287.5, 2.6),
  BenchLine("draw translucent 1920x1080, after", 76.4, 0.2)
)

deltaPercent(1287.5, 76.4).round
```

Those intervals are separated by a factor of sixteen, so the test is a formality. It earns its keep in the opposite case, which the [rejected arena](#measured-and-rejected-per-frame-destination-reuse) section below is entirely about.

### What the harness does not do

Being clear about this is what makes the numbers usable:

- **No JVM forking.** JMH runs each variant in a fresh JVM. This harness does not, so when a benchmark measures two variants in one run — `ArenaReuseBench` and `GrayBlurCloneBench` both do — the first variant's profile can influence how the JIT compiles the second. The effect is usually small and the direction is unpredictable, which is a reason to distrust a 1% delta, not a 40% one.
- **Two different comparison shapes appear below.** The interop and alpha wins were measured *across a code change*: one run before the patch, one run after. That avoids profile pollution but introduces run-to-run drift instead (machine state, thermal headroom). The clone-elimination and arena benchmarks code both variants side by side in one run, trading the opposite way.
- **The confidence interval is optimistic.** `1.96 σ/√n` assumes independent, identically distributed samples. GC pauses and OS scheduling give the sample distribution a heavy right tail, so the true uncertainty is wider than the printed half-width. This is another argument for the coarse non-overlap rule rather than a fine-grained significance test.
- **Statistical significance is not engineering significance.** With `n` in the thousands, a 0.4% difference can be perfectly "distinguishable" and still be worth nothing. A delta has to be big enough to pay for the API surface, the ownership complexity and the bug risk of the change that produces it.
- **Timing is not a CI gate.** The `benchmarks` module is never published and never run in CI. Only the *other* half of the project rule — bit-identical output — is enforced automatically. A performance regression will not turn the build red; a human running the harness is what catches it.

## Shipped wins

Four optimisations shipped with measured deltas. Each one removed a *copy*, which is the recurring theme: nothing here made an inner loop cleverer, and everything here stopped allocating or copying a full frame that nobody needed.

### 1. Translucent shapes now cost what opaque ones cost

The [scene-graph](/graphics) drawing layer used to render a translucent shape by cloning the **whole** `Mat` and running `addWeighted` over the **whole** `Mat`, no matter how small the shape was. A tiny dashed box on a 4K canvas paid for two full-image passes: the cost scaled with the canvas, not with the shape.

It now paints the shape opaquely at its real coordinates, then saves and blends back only a conservative bounding box of the pixels the paint could have touched — geometry, plus stroke width, plus a margin for anti-aliasing, caps and joins, clipped to the image.

Benchmark: a small rectangle drawn onto a growing canvas (`GraphicsAlphaBench`).

| Canvas | Before | After | Delta |
|---|---|---|---|
| 640×480 | 194.8 ±0.2 µs | 22.5 ±0.1 µs | **−88%** |
| 1920×1080 | 1287.5 ±2.6 µs | 76.4 ±0.2 µs | **−94%** |
| 3840×2160 | 15297 ±68 µs | 489.7 ±8.9 µs | **−97%** |

**What it means for you.** Overlay annotation — translucent detection boxes, heat overlays, dimmed regions — used to be something you rationed on large frames. It no longer is: a translucent draw now costs about the same as an opaque one, and the cost tracks the size of the *shape*. The one case that has not changed is a shape that genuinely fills the canvas, where the bounding box is the whole image and the new path degenerates to the old one.

### 2. `BufferedImage` → `Mat`: skip the redraw when the raster is already BGR

`Image.fromBufferedImage` always allocated a second `TYPE_3BYTE_BGR` `BufferedImage` and ran a full `Graphics2D.drawImage` into it before copying to the `Mat` — even when the source was *already* `TYPE_3BYTE_BGR`, which is exactly what `toBufferedImage` emits and what many `ImageIO` JPEG reads produce.

A `TYPE_3BYTE_BGR` source whose backing bytes are already the B,G,R interleaving that `CV_8UC3` wants is now copied straight in. An exact-length guard keeps the fast path to the simple contiguous raster; a translated or padded sub-raster (from `getSubimage`) and every other image type still take the safe redraw.

Benchmark: `ToMatBench`, `TYPE_3BYTE_BGR` source.

| Size | Before | After | Delta |
|---|---|---|---|
| 640×480 | 131.8 ±1.8 µs | 18.5 ±0.03 µs | **−86%** |
| 1920×1080 | 982.5 ±10.2 µs | 116.1 ±0.25 µs | **−88%** |
| 3840×2160 | 7103.8 ±75 µs | 1653.8 ±21 µs | **−77%** |

The `TYPE_INT_ARGB` path is unchanged within noise, as expected — it still takes the redraw fallback.

**What it means for you.** If you are pulling frames in from AWT, Swing or `ImageIO`, feeding scalacv a `TYPE_3BYTE_BGR` image is worth roughly an order of magnitude over any other type. See [Image I/O](/image-io) for the round-trip.

### 3. `Mat` → `BufferedImage`: write straight into AWT's array

`toBufferedImage` cloned the whole `Mat` purely to guarantee continuity, bulk-read into a fresh `byte[]`, then `arraycopy`'d that into the `BufferedImage` — three full-image copies and two allocations per call, on the per-frame display and [notebook](/notebooks) path.

It now writes the single bulk `get` straight into AWT's backing array, and clones only the rare non-continuous submat. (A 1- or 3-channel `Mat` is usually already continuous, so the clone is the exception rather than the rule.)

Benchmark: `ToBufferedImageBench`, across sizes and channel counts.

| Size / channels | Before | After | Delta |
|---|---|---|---|
| 640×480, ch=3 | 247.7 ±1.2 µs | 145.8 ±1.6 µs | **−41%** |
| 1920×1080, ch=1 | 550.6 ±3.7 µs | 295.0 ±1.9 µs | **−46%** |
| 1920×1080, ch=3 | 1893.7 ±14.5 µs | 976.3 ±9.2 µs | **−48%** |
| 1920×1080, ch=4 | 1976.5 ±15.2 µs | 1279.4 ±12.3 µs | **−35%** |
| 3840×2160, ch=3 | 12540.3 ±111 µs | 5446.1 ±68 µs | **−57%** |
| 3840×2160, ch=4 | 12965.6 ±134 µs | 8121.1 ±75 µs | **−37%** |

The commit records that the confidence intervals are non-overlapping at every point — this is the one place on the page where the reading rule was applied row by row and written down.

**What it means for you.** Displaying a live 1080p feed costs about half what it used to per frame. It is still ~1 ms of pure copying at 1080p, though, so on-screen display remains a real per-frame cost you should measure rather than assume away.

### 4. Motion detection: a clone that was freed unused

`MotionDetector.prepare` greyscaled-or-cloned the incoming frame and then blurred the result. For an already-grey input that meant cloning the borrowed frame — but `gaussianBlur` allocates its own destination and only *borrows* its receiver, so the clone was freed without ever being read. A full-frame copy per frame, on the [motion](/motion-detection) path, for nothing.

An already-grey frame with a blur is now blurred directly. The clone survives only in the already-grey, no-blur case, where it is the genuine ownership tax: the caller keeps the `Mat`, so a view of it cannot be handed back.

Benchmark: `GrayBlurCloneBench`, isolating clone-then-blur against blur.

| Size | Clone + blur (old) | Blur direct (new) | Delta |
|---|---|---|---|
| 640×480 | 46.3 ±0.3 µs | 28.5 ±0.3 µs | **−38%** |
| 1920×1080 | 218.9 ±1.4 µs | 126.8 ±1.7 µs | **−42%** |

**What it means for you.** Nothing to change in your code — but the shape of the bug is worth internalising, because it is easy to write yourself. A mid-level op that allocates its own destination does **not** need a defensive copy of its input; cloning "to be safe" in front of one buys a full-frame allocation and zero safety. [Mat lifecycle](/mat-lifecycle) is the page that tells you which ops borrow and which consume.

### The gate that makes these numbers mean something

The project rule is: **no optimisation ships without a benchmark delta *and* a bit-identical output hash.** A change that is faster because it quietly computes something different is not an optimisation, and a timing number alone cannot tell the two apart.

`BenchImages.hash` is the pixel-exact key — FNV-1a folded over the raw pixel bytes, read row by row so it is correct for non-continuous `Mat`s too. Where that comparison is enforced automatically:

| Win | How correctness is held |
|---|---|
| Translucent ROI blend | `GraphicsAlphaRoiTest` — a 16-case corpus (every primitive, thin and thick strokes, dashes, rotation, positions centred, against each edge, and partly off-canvas) compared pixel-for-pixel against an independent whole-image `addWeighted` of the opaque render. **A CI gate.** |
| Grey-blur clone removal | `PixelHashTest` — asserts the blur of a borrowed frame hashes identically to the blur of its clone, and that `gaussianBlur` leaves its source untouched. **A CI gate.** |
| Both AWT interop fast paths | Output hashes compared before and after at the time of the change, plus branch coverage in `InteropTest` for the non-continuous submat and 4-channel cases. **Not** a standing hash gate — honest caveat. |

See [Testing](/testing) for the tolerance-metric and property-based suites that back these up.

## Measured and rejected: per-frame destination reuse {#measured-and-rejected-per-frame-destination-reuse}

This is the most useful paragraph on the page, because it will stop you building something.

Received wisdom about OpenCV pipelines says that reusing destination `Mat`s across frames — a scratch "arena", so a 30 fps loop allocates once instead of once per stage per frame — is *usually the largest single win available*. scalacv's ownership model makes that awkward to offer (every mid-level op allocates a fresh destination and hands you something you own; an arena reverses that contract), so before designing the feature, `ArenaReuseBench` sized it.

The benchmark runs the canonical `gray → blur → canny` preprocessing chain two ways over the same frame: once through the pure API, where every stage allocates a fresh destination; once with three preallocated destination `Mat`s reused across "frames" — which is exactly what an opt-in arena would do, since OpenCV's `create` is a no-op when dimensions and type already match. Both produce bit-identical output, verified by hash, so the delta *is* the per-frame allocate-and-free cost and nothing else.

| Frame | Fresh allocation | Reused destinations | Delta |
|---|---|---|---|
| 640×480 | 145.3 µs | 139.1 µs | −4% |
| 1920×1080 | 574.2 µs | 576.3 µs | **+0.4% — reuse was *slower*** |
| 3840×2160 | 7556 µs | 7493 µs | −0.8% |

```scala mdoc
Seq(
  deltaPercent(145.3, 139.1),   // 640x480
  deltaPercent(574.2, 576.3),   // 1920x1080 — positive means reuse lost
  deltaPercent(7556.0, 7493.0)  // 3840x2160
).map(d => f"$d%+.1f%%")
```

**The conclusion, and why it holds.** The win does not exist at realistic sizes. OpenCV's own allocator plus the G1 garbage collector make per-frame `Mat` allocation a sub-1% cost next to the compute the pipeline is actually doing. Building an opt-in arena would have added a large amount of API surface and reversed the no-in-place ownership contract that the whole library rests on, in exchange for a delta inside the noise band. It was not built.

Note what the argument does **not** rest on. The commit recorded the means but not the half-widths, so the non-overlap test cannot be run on these rows at all. The evidence is the *shape* of the sweep: the sign of the delta flips as the frame grows, and the magnitude stays around or below 1% everywhere except the smallest frame, where the compute is smallest and the fixed costs therefore weigh most. A result you cannot get a consistent sign out of is a result you should not build a feature on — and that is a stronger argument than any single p-value would have been.

:::tip[What to do instead, if you are chasing per-frame cost]
Stop copying frames before you start reusing buffers. The measured wins are an order of magnitude larger and need no new API: use `Video.frames` (a single reused decode buffer, zero per-frame allocation) rather than a copying source when you only read the frame, and `Recorder.write`'s `Mat` overload to write a borrowed frame straight through without an `Image` clone. See [Video](/video) and [Performance](/performance).
:::

## Known headroom, deliberately left in place

`Picture.beside` folds a row of shapes by asking the row built so far for its bounding box at every step, and `bounds` re-walks the whole tree each time it is asked. Folding a row of *n* shapes is therefore **O(n²)**.

`PictureBoundsBench` measured it rather than assuming it:

| Row length | Build + `bounds` |
|---|---|
| n = 200 | 1.27 ms |
| n = 400 | 5.01 ms |

Doubling the count cost roughly four times the work, which is what quadratic looks like.

**Why it was left alone.** The obvious fix — a `lazy val bounds` memo on each node — does not actually work here. Look at what `boundsOf` does: it threads an accumulating affine transform down the tree, so a `Transformed` node's children must be measured *under a composed transform*, and rotated bounds cannot be derived from identity bounds. A per-node memo caches the wrong thing and is bypassed exactly where the recursion is expensive. The cost is also one-off (it happens while you build a scene, not while you render frames) and, for shapes, contains no JNI calls at all. Trading a correctness risk in the layout engine for a one-time multi-millisecond cost at 400+ elements was not a good trade.

**Guidance.** Below roughly 200 elements per fold, this is sub-millisecond and you can ignore it. Above that, build the row in one call instead of folding. `Picture.grid` measures each element **once** and translates it into place, which is linear:

```scala mdoc:silent
val spots = (0 until 50).map(i => Picture.circle(Point(0, 0), 5.0 + (i % 7)))

// Quadratic: every `beside` asks the accumulated row for its bounds, re-walking all of it.
val foldedRow: Picture = spots.reduceLeft((acc, p) => acc.beside(p))

// Linear: one call, each element measured once. `columns = spots.size` makes it a single row.
val gridRow: Picture = Picture.grid(spots, columns = spots.size)
```

```scala mdoc
gridRow.bounds.map(_.width)
```

The two are not pixel-identical, and you should choose knowingly: `beside` packs each shape against the previous one's actual width, while `grid` gives every element a uniform cell sized to the largest of them. If you want tight packing at large *n*, compute the offsets yourself and compose with `Picture.all`.

:::note[One caveat if your row contains text]
The "no JNI" part of the reasoning holds for shapes. Measuring a `Picture.text` node calls `Draw.textSize`, which goes through to OpenCV's `getTextSize` — so a quadratic fold over a row of *labels* pays a native call per node per walk, and gets expensive sooner than the shape numbers above suggest.
:::

## Reproduce on your hardware

Every benchmark is a `main` you can run directly. Nothing here needs an image file: `BenchImages` draws its fixtures from a fixed seed, so a run on any machine sees the same pixels.

```sh
./mill benchmarks.runMain scalacv.bench.GrayBlurCloneBench   # a wasted clone in Motion.prepare
./mill benchmarks.runMain scalacv.bench.ArenaReuseBench      # per-frame destination allocation
./mill benchmarks.runMain scalacv.bench.ToMatBench           # BufferedImage -> Mat fast path
./mill benchmarks.runMain scalacv.bench.ToBufferedImageBench # Mat -> BufferedImage fast path
./mill benchmarks.runMain scalacv.bench.GraphicsAlphaBench   # translucent-shape blend overhead
./mill benchmarks.runMain scalacv.bench.PictureBoundsBench   # scene-graph layout cost vs shape count
./mill benchmarks.runMain scalacv.bench.ConfigProbeBench     # how a heavy op scales with setNumThreads
```

The `benchmarks` module is developer-facing: never published, never a CI gate. It runs with the same JVM flags as `examples`, including `-Djava.awt.headless=true` so the AWT benchmarks cannot open a window.

`ConfigProbeBench` is the one with no numbers on this page, on purpose. It prints your OpenCV runtime configuration (`useOptimized`, `getNumThreads`, `getNumberOfCPUs`, and the parallel/IPP/OpenCL lines of the build information) and then measures how a heavy internally-parallel operation — a bilateral filter on a 1280×720 scene — scales as `Core.setNumThreads` is stepped through 1, 2, 4 and your CPU count. That result is a property of your machine, not of the library, so publishing one machine's answer would be actively misleading. Run it and read your own; [Performance](/performance) and [Concurrency](/concurrency) explain what to do with the answer.

:::tip[The rule for adding a number here]
**Quote the delta and the confidence interval, not the absolute microseconds.** A commit that says "−42%, CIs non-overlapping, hash unchanged" is reproducible on any machine. A commit that says "now 126 µs" is true on exactly one. Put the delta in the commit message, and add the row to this page in the same pull request.
:::

## What this page cannot tell you

Being explicit about the gaps, because a benchmark page that looks complete is more dangerous than one that admits what it skipped:

- **No end-to-end throughput.** Every number is one call to one operation over a synthetic scene. None of them is frames per second on your video, and you cannot add them up to get one — decode, colour conversion, your own logic and encode all interact.
- **No neural-network numbers.** The heaviest thing most real pipelines do is a forward pass, and it is not benchmarked at all, because its cost belongs to the model and the OpenCV DNN backend rather than to scalacv. Time your own model.
- **No GPU.** These run against the CPU OpenCV build on the classpath. Nothing here says anything about a CUDA or OpenCL path.
- **No thread-scaling figures.** See `ConfigProbeBench` above: that answer is hardware-specific by nature.
- **No memory figures.** Native memory needs a different instrument entirely — `Pointer.totalBytes()` is blind to the `cv::fastMalloc` buffers scalacv uses, so a leak has to be caught by process RSS. [Performance](/performance) covers the accounting, and [Testing](/testing) has the RSS-based leak harness.
- **No standing regression history.** Timing is not a CI gate, so there is no chart of these numbers over time. If a change regresses one of them, the harness has to be run by a person to notice.

## Next

- The mechanisms behind the wins: [Performance](/performance).
- Why "consumes" and "borrows" are the load-bearing words in every entry above: [Mat lifecycle](/mat-lifecycle).
- Proving an optimisation did not change the output: [Testing](/testing).
- Turning all of this into a service that stays up: [Deploying to production](/deploying-to-production).
