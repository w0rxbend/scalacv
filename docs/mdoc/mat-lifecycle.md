# Mat lifecycle — why scalacv exists

## The problem

An OpenCV `Mat` holds megabytes of pixel data off-heap, behind about forty bytes of Java object
on-heap. The garbage collector only runs under **heap** pressure, and heap pressure is
uncorrelated with **native** pressure. So a loop that allocates Mats and drops them exhausts native
memory — or trips the cgroup limit and gets OOM-killed — while the heap stays small and no
collection ever runs.

Measured on this project's own test machine: 2000 × `Mat(1000, 1000, CV_8UC3)`, references dropped,
no explicit `System.gc()`.

| | final RSS |
|---|---|
| unreleased | **5 865 MB** |
| `release()` | **144 MB** |

The same 41× on JDK 21 and JDK 25. It is not that reclamation is impossible — it is that nothing
makes it happen in time. And the mechanism that *would* eventually help, `finalize()`, is deprecated
for removal.

It is worse for detectors. Of the 188 `org.opencv.*` types that own native memory, exactly **three**
expose a public `release()`. `CascadeClassifier`, `Net`, `QRCodeDetector`, `ArucoDetector` and 181
others do not — and each carries a finalizer that would free its pointer a second time if you freed
it yourself naively.

## The answer: `Managed`

`Managed[A]` owns a native object and releases it exactly once. Prefer the scoped form:

```scala mdoc:silent
import scalacv.*
import org.opencv.core.{CvType, Mat}

OpenCv.load()

val rows = Managed.use(Mat(1080, 1920, CvType.CV_8UC3)) { m =>
  m.rows
}
```

```scala mdoc
rows
```

After the block, the Mat is freed — on success, on exception, either way. Using it afterwards is an
error scalacv catches in Scala, with an `IllegalStateException`, rather than letting it become a
segfault from native code with no stack trace:

```scala mdoc:crash
val leaked = Managed(Mat(8, 8, CvType.CV_8UC1))
leaked.release()
leaked.get // throws: already released
```

## The ownership contract

**The rule, in one sentence:** you own every `Managed` a scalacv call hands back and close it exactly
once — a scope (`Managed.use`, `Image.reading`, `Video.framesCopied`) does that for you — with a single
exception: a `Mat` yielded by `Video.frames` is *borrowed*, owned by the loop, and must not outlive its
iteration.

Operations that produce a new Mat return it **caller-owned**, wrapped in `Managed`. They never touch
the receiver. To chain them without stranding the intermediates, use `pipe`:

```scala mdoc:silent
val edges: Either[CvError, Array[Byte]] =
  Managed.use(Mat(64, 64, CvType.CV_8UC3)) { src =>
    src
      .cvtColor(ColorConversion.BgrToGray)
      .pipe(_.gaussianBlur(Size(3, 3)))
      .pipe(_.canny(50, 150))
      .use(Images.encode(_, ".png"))
  }
```

Each stage's output is released as the next stage consumes it. The original `src` is never modified.

## The one place a Mat is borrowed, not owned: `Video.frames`

Everything above hands you *owned* Mats. There is exactly one aliasing surface in the public API, and
it is the one people get wrong: `Video.frames` streams frames through a **single reused buffer**. The
`Mat` you get each iteration is the *same object*, refilled in place — so keeping a reference to it
past its turn, or collecting the iterator, leaves you holding one buffer that shows only the last
frame (and is freed when the loop ends).

There is no `row`/`col`/`submat` view API to trip over here — `Image.crop` returns an independent
copy, not a view. This borrowed frame is the only alias you have to reason about.

```scala mdoc:compile-only
import scalacv.*

// WRONG — `frames` yields a BORROWED Mat (one buffer, refilled each step). Collecting the iterator
// keeps N references to that single buffer; after the block they alias freed memory.
Video.open(0).map { capture =>
  capture.use { c =>
    Video.frames(c) { it => it.toList } // every element is the same buffer — a use-after-free in waiting
  }
}
```

```scala mdoc:compile-only
import scalacv.*

// RIGHT (a) — consume each frame fully inside the loop, before the next iteration overwrites it.
Video.open(0).map { capture =>
  capture.use { c =>
    Video.frames(c) { it => it.map(_.rows).sum }
  }
}

// RIGHT (b) — to keep frames past their turn, ask for owned copies: each is a `Managed[Mat]` you own
// (and close). `framesCopied` clones per frame so there is nothing aliased to get wrong.
Video.open(0).map { capture =>
  capture.use { c =>
    Video.framesCopied(c) { it => it.take(10).toList } // owned copies; close them when done
  }
}
```

The ZIO module mirrors this exactly: `frameStream` borrows one buffer (same contract), `framesCopied`
gives you owned `Managed[Mat]` per frame.

## Thread safety

Native handles are **not** safe to share across threads without your own synchronization: a `Mat`, a
`Managed`, a detector, or a `VideoCapture` touched from two threads at once is a data race in C++, not
just in the JVM. Keep one owner per handle, or guard it. scalacv does **not** use JavaCPP
`PointerScope`, so there is no thread-local-scope caveat to reason about — ownership is always the
explicit `Managed` you hold, wherever it travels. OpenCV also runs its own internal thread pool
(and OpenBLAS another); if you fan pipelines out across your own threads, cap theirs
(`cv::setNumThreads`, `OMP_NUM_THREADS`/`OPENBLAS_NUM_THREADS`) so the two layers don't oversubscribe
the cores.

## Verify you aren't leaking

Don't wait for RSS to climb in production. Put a hard cap on JavaCPP's off-heap budget and run your
workload — a leak then fails *fast and loud* (a JavaCPP `OutOfMemoryError` naming the byte count)
instead of slowly eating the machine:

```sh
java -Dorg.bytedeco.javacpp.maxBytes=256M -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar
```

If it throws almost immediately, something is allocating without releasing; if it completes, your
release discipline holds under that ceiling. Bisect by wrapping suspects in `Managed.use` until it
passes. (Run the same workload under both a low and a generous cap to tell a genuine leak from a
ceiling that is simply too small for the working set.)

## GraalVM native-image is not supported today

This is a known limitation, not yet worked around. The very mechanisms that let scalacv free the 185
bindings without a public `release()` are the ones a static image forbids by default, and the natives
are loaded in a way an image has no way to reproduce. Three concrete blockers:

1. **The release layer reflects a private `delete(long)` per binding class.** Freeing a
   `CascadeClassifier`, `Net`, `KalmanFilter` and the rest goes through a cached `MethodHandle` onto
   that class's `private static native void delete(long)`. A native image sees no reflective use at
   build time, so every binding class you free would need an entry in `reflect-config.json`.
2. **The finalizer disarm writes a `final` field via reflection.** Before freeing, scalacv zeroes the
   binding's `nativeObj` field so the generated `finalize()` cannot `delete` the same address a second
   time. That is a reflective write to a final field — again invisible to the image's static analysis
   and needing explicit configuration.
3. **Natives are extracted from a jar at runtime and `System.load`ed by absolute path.**
   `OpenCv.load()` unpacks the platform libraries out of the bytedeco jars into `~/.javacpp` and loads
   them by absolute path. A static image has no jar on disk to read, so this last step has nowhere to
   extract from.

If you need native-image today, the honest answer is that scalacv does not run under it yet.
