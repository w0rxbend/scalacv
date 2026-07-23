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
