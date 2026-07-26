# Testing vision code

Vision code has two properties that make naive tests flaky: it depends on **image assets** that bloat the repo, and its output is **not bit-identical across platforms** (SIMD paths and OpenCV builds differ). This page shows the patterns scalacv's own suite uses to stay fast, asset-free, and portable.

```scala mdoc:silent
import scalacv.*
import org.opencv.core.Core

OpenCv.load()
```

## Draw your fixtures — don't ship them

Committing PNGs is the usual first instinct and the usual first regret: they bloat the repo, drift, and hide *what* about the image the test depends on. Draw a deterministic scene instead — it's explicit, versionable, and diffs as code:

```scala mdoc:silent
/** A deterministic BGR scene: shapes at fixed positions so a test can assert on known pixels. */
def scene(): Image =
  Image
    .blank(160, 120, Scalar(20, 40, 60))
    .drawRect(Rect(20, 20, 60, 40), Scalar.Green, Thickness.Filled)
    .drawCircle(Point(120, 80), 20, Scalar.Red, Thickness.Filled)
```

For repeatable *randomness* (many small shapes, textures), seed a `scala.util.Random` with a fixed value — the scene is then random-looking but identical every run.

## Compare with a tolerance, never byte-for-byte

Exact pixel equality is correct only on the machine that produced the golden. Across SIMD paths or OpenCV versions it breaks on rounding. Compare within a threshold using **PSNR** (higher = closer) or **max-abs-diff** (0 = identical):

```scala mdoc:silent
def psnr(a: Image, b: Image): Double       = Core.PSNR(a.mat, b.mat)
def maxAbsDiff(a: Image, b: Image): Double  = Core.norm(a.mat, b.mat, Core.NORM_INF)
```

```scala mdoc:silent
// A lossy JPEG roundtrip is never bit-exact, but stays well above a quality floor.
val src = scene()
val jpeg = Image.decode(src.copy.bytes(".jpg").toOption.get).toOption.get
val db = psnr(src, jpeg)
src.close(); jpeg.close()
```

```scala mdoc
db > 30.0 // assert this, not equality
```

Keep bit-exact hashes only as *same-platform* regression keys (fast, catches "did anything change at all"); use tolerance for anything a multi-OS/arch CI matrix will run. See [Performance](/performance) for why exactness is fragile here.

## Assert use-after-close throws — in a forked JVM

The ownership guard turns a would-be segfault into an `IllegalStateException` you can assert on. Test that a consumed handle is actually dead:

```scala mdoc:crash
val img = scene()
img.gray        // consumes img
img.width       // throws IllegalStateException, not a crash
```

In a real suite, wrap that in `intercept[IllegalStateException] { ... }`. **Run these in a forked JVM** so that if a regression ever does segfault, it's reported as a nonzero exit rather than silently taking the whole test process down. In Mill, test modules fork by default; keep crash-prone ownership tests in their own suite so a failure names the offender.

## Property tests over shapes and types

The laws a regression is most likely to break — roundtrips and identities — are best expressed as properties over generated sizes and channel counts (this needs `munit-scalacheck` on the test classpath):

```scala
// in your test module (munit-scalacheck):
property("double flip is the identity") {
  forAll(genDims, genChannels) { (dims, ch) =>
    val src  = build(dims, ch)
    val back = src.copy.flip(Flip.Horizontal).flip(Flip.Horizontal)
    try samePixels(src, back) finally { src.close(); back.close() }
  }
}
```

Good laws to pin: `imencode`/`imdecode` PNG roundtrip is lossless; `flip∘flip` / `rotate 90 ∘ rotate 270` / `BGR→RGB→BGR` are identities; every op preserves its declared type/channel invariant (`canny` ⇒ `CV_8UC1`); an unsupported type/channel combination yields a typed `CvError`, never a crash.

## Guard against native leaks with an RSS assertion

A leaked `Mat` never fails an assertion — it just grows RSS. Gate it directly. Because scalacv's Mats live outside JavaCPP's `Pointer` accounting, **measure RSS, not `Pointer.totalBytes()`** (which is blind — see [Performance](/performance#measuring-memory-do-it-right)):

```scala
// Sketch of an RSS-based leak assertion (run it as its own suite in its own JVM):
def rssBytes(): Long =
  val fields = String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/proc/self/statm")))
  fields.trim.split("\\s+")(1).toLong * 4096   // resident pages * page size

def assertBounded(n: Int)(work: () => Unit): Unit =
  for _ <- 0 until 40 do work()                // warm up arenas/JIT
  System.gc(); Thread.sleep(150)
  val before = rssBytes()
  for _ <- 0 until n do work()
  System.gc(); org.bytedeco.javacpp.Pointer.deallocateReferences(); Thread.sleep(150)
  assert((rssBytes() - before) / (1024 * 1024) <= 48)  // bounded, not zero
```

Point it at the paths you worry about — an error branch that must free the receiver, a per-frame loop, a detector you construct repeatedly. The clean high-level pipeline is flat under this; a per-iteration leak clears any sane bound within a few hundred iterations. Isolate the suite in its own JVM so process RSS isn't contaminated by other tests running in parallel.

## Skip what the environment can't provide

Camera and GUI tests should *skip*, not *fail*, when the hardware or display isn't there — so the suite stays green on a headless CI runner. Gate them on an env var with munit's `assume`:

```scala
test("captures a webcam frame") {
  assume(sys.env.contains("SCALACV_CAMERA"))  // skips unless you opt in
  // ... open camera, grab a frame ...
}
```

## Next

- The measurement details behind the leak assertion: [Performance](/performance).
- The ownership guarantees these tests exercise: [Mat lifecycle](/mat-lifecycle).
