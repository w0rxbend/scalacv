<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="website/static/img/logo-dark.svg">
  <img src="website/static/img/logo.svg" alt="scalacv" width="140" height="140">
</picture>

# scalacv

**An eloquent Scala 3 API for OpenCV 4.13 — a high-level image toolkit over the complete Java bindings. Typed, headless, and honest about native memory.**

[![CI](https://github.com/w0rxbend/scalacv/actions/workflows/ci.yml/badge.svg)](https://github.com/w0rxbend/scalacv/actions/workflows/ci.yml)
[![Scala 3.3 LTS](https://img.shields.io/badge/scala-3.3%20LTS-DC322F.svg)](https://www.scala-lang.org)
[![JDK 17+](https://img.shields.io/badge/jdk-17%2B-blue.svg)](https://adoptium.net)
[![OpenCV 4.13.0](https://img.shields.io/badge/opencv-4.13.0-5C3EE8.svg)](https://docs.opencv.org/4.13.0/)
[![License](https://img.shields.io/badge/license-Apache--2.0-green.svg)](LICENSE)

</div>

---

## ✨ Features

- **Typed everything.** No raw `int` constants. `ColorConversion.BgrToGray`, not `6`.
- **Resource-safe by construction.** `Managed[A]` releases exactly once and throws on use-after-release, in Scala, before anything reaches JNI — where the same mistake is a SIGSEGV with no stack trace.
- **Genuinely headless.** `OpenCv.load()` needs no GUI toolkit and no `apt-get` on any runner.
- **Errors as values where they belong.** `Either[CvError, A]` for the failures you can expect; exceptions for the bugs you cannot.
- **Two levels, one library.** A high-level `Image` pipeline for the common cases, and the full typed `org.opencv.*` surface underneath — never hidden.
- **Batteries included.** Filters and morphology, geometric transforms, contours and Hough, colour segmentation; a composable **2D graphics layer** (`Picture`) for overlays, dashed strokes, charts and animation; Haar/YuNet faces, QR, ArUco, ONNX inference; a high-level `Camera`/`Recorder`; **motion detection** for a static/MJPEG cam; **pose estimation** (skeletons, hands, head pose); **gesture recognition**; **video-conferencing** background blur & virtual backgrounds; **screen analysis** (template matching, change detection); **OCR** preprocessing (deskew + a pluggable engine); **camera calibration** (chessboard intrinsics + lens undistortion); and a **visual-navigation front end** — optical flow, ORB features, stereo depth & obstacles, and visual odometry.

## 🚀 Quick start

```scala
// build.mill  (or the equivalent for your build tool)
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.1.0",         // the OpenCV wrapping: Image, Managed, filters, contours…
  // Optional layers, each depending only on the core — add the ones you use:
  //   mvn"com.worxbend::scalacv-vision:0.1.0"  // detectors, DNN, pose/tracking, OCR, calibration, SLAM
  //   mvn"com.worxbend::scalacv-graphs:0.1.0"  // the Picture scene graph, charts, GIF animation

  // Natives for YOUR platform. A build tool cannot express a per-platform classifier in a
  // published POM, so this line is yours to pick — see "Why two lines?" below.
  mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64",
  mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64"
)
```

**High-level — the `Image` pipeline.** `Image.reading` scopes the image for you and is the entry
point that cannot leak: the block's image is released when it returns — on success, on failure, and
on exception — so start here unless you have a reason not to.

```scala
import scalacv.*

OpenCv.load()

// Every intermediate frees itself; `reading` guarantees the source is freed too, no matter what.
Image.reading("photo.jpg") { img => img.gray.blur(2).canny(80, 160).write("edges.png") }
```

The `read`/`flatMap` form is there when you want to thread the `Either` yourself, and each terminal
(`write`, `bytes`, `close`) still releases — but `reading` is the one that forgets nothing:

```scala
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

Detect, annotate, and drop to plain data just as fluently:

```scala
Image.reading("street.jpg") { img =>
  val codes = img.qrCodes                       // Seq[QrCode] — decoded, immutable
  img.markFaces(img.copy.faces(detector))       // draw boxes + landmarks
     .drawText(s"${codes.size} codes", Point(10, 30))
     .write("annotated.png")
}
```

**Low-level — never walled off.** `mat` borrows the underlying handle; the full typed `org.opencv.*`
surface and the mid-level `Managed[Mat]` extension ops are always one step away:

```scala
Image.reading("photo.jpg") { img =>
  img.mat.cvtColor(ColorConversion.BgrToGray)   // mid-level extension → Managed[Mat]
     .pipe(_.gaussianBlur(Size(5, 5)))
     .pipe(_.canny(80, 160))
     .use(Images.encode(_, ".png"))
}
```

### Why two lines?

`scalacv` depends on the OpenCV **Java API** jar, which contains no native code. The natives ship in per-platform classifier jars, and no build tool can put a classifier into a published POM — so if we picked one for you, it would be wrong for everyone else.

| Your platform | classifier |
|---|---|
| Linux x86-64 | `linux-x86_64` |
| Linux ARM64 | `linux-arm64` |
| macOS Apple Silicon | `macosx-arm64` |
| macOS Intel | `macosx-x86_64` |
| Windows x86-64 | `windows-x86_64` |

Don't want to choose? `mvn"org.bytedeco:opencv-platform:4.13.0-1.5.13"` bundles every platform and works anywhere — for about **408 MB** instead of 36–80 MB.

`scalacv` alone compiles fine without either native line, but nothing runs: the OpenCV symbols are absent until you add them. Get it wrong and `OpenCv.load()` does not fail with a link error — it prints a copy-pasteable fix naming the platform you are actually on.

**Footprint, so nothing surprises you.** For `linux-x86_64` the `opencv` jar is ~31 MB and `openblas` ~20 MB; on the **first** `OpenCv.load()` these are extracted once into `~/.javacpp` (~196 MB on Linux), and every later run reuses that cache. Point it elsewhere with `-Dorg.bytedeco.javacpp.cachedir=…` for a read-only home or a thin container layer.

> **Scala-first.** scalacv targets Scala 3 consumers: the API returns `Seq`/`Option`/`Either` and reaches you through extension methods brought in by `import scalacv.*`. It wraps a Java library but is not designed to be called _from_ Java.

## 🧠 Why this exists

**OpenCV's `Mat` holds megabytes off-heap behind about forty bytes on-heap.** Heap pressure is the only thing that triggers a collection, and it is uncorrelated with native pressure — so a frame loop exhausts native memory while the heap stays small and the collector never runs.

Measured on this project's own test machine: 2000 × `Mat(1000, 1000, CV_8UC3)`, references dropped, no explicit `System.gc()`.

| | final RSS |
|---|---|
| unreleased | **5 865 MB** |
| `release()` | **144 MB** |

The same 41× on JDK 21 and JDK 25. It is not that reclamation is impossible — it is that nothing makes it happen in time.

It is worse for detectors. Of the 188 `org.opencv.*` types that own native memory, exactly **three** expose a public `release()`. `CascadeClassifier`, `Net`, `QRCodeDetector`, `ArucoDetector` and 181 others do not. scalacv frees them anyway: 4000 leaked `KalmanFilter`s measured **54 GB**, against **86 MB** released.

That is the library. The typed API is the pleasant part; the lifetime handling is the part that keeps your process alive.

## 📚 Documentation

Full guide, API reference and cookbook: **[w0rxbend.github.io/scalacv](https://w0rxbend.github.io/scalacv)**

## 🤝 Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Briefly: `./mill __.compile`, `./mill __.test`, and both style gates before you push.

## ⚖️ License

[Apache-2.0](LICENSE). See [`NOTICE`](NOTICE) and [`THIRD-PARTY.md`](THIRD-PARTY.md).

**Credits.** The scalacv name and original spark come from [`mcallisto/scalacv`](https://github.com/mcallisto/scalacv) by Mario Càllisto; two example ideas trace to [`rladstaetter/isight-java`](https://github.com/rladstaetter/isight-java) and [`chimpler/blog-scala-javacv`](https://github.com/chimpler/blog-scala-javacv). None of those repositories carried a license, so this is a clean-room library that shares no code with them — the credit is for the inspiration, recorded here and in [`NOTICE`](NOTICE).
