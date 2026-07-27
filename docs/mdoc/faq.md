# FAQ

Quick answers to the questions people ask before (and just after) picking up scalacv. For *error messages* specifically, see [Troubleshooting](/troubleshooting).

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## Getting set up

### What is scalacv, exactly?
A Scala 3 wrapper over the official OpenCV 4.13 Java API (shipped by bytedeco). It gives you a fluent, typed, memory-safe surface — [`Image`](/image-api) and friends — instead of raw `org.opencv.*`. The native OpenCV code is unchanged underneath; scalacv is the ergonomics layer. See [Architecture](/architecture).

### Do I need to install OpenCV, or `apt-get` anything?
**No.** The native libraries ship inside per-platform jars and are extracted automatically on the first `OpenCv.load()`. You don't install OpenCV, and you don't need a GUI toolkit — it runs on a bare headless server. See [Getting Started](/getting-started).

### Which build tools work?
Any JVM build tool — the project itself uses Mill, but sbt/Gradle/Maven all work; you just add the dependency plus the native classifier for your platform. See [Getting Started](/getting-started) for the exact coordinates.

### How big is the download?
For one platform (say `linux-x86_64`): the OpenCV jar is ~31 MB, OpenBLAS ~20 MB, and the first load extracts ~196 MB into a cache. Prefer a single platform classifier over `opencv-platform` (which bundles every OS at ~408 MB). See [The native cache](/native-cache) to relocate or pre-warm it.

### Scala 2? Android? GraalVM native-image?
Scala **3** only (3.3.x LTS). Android and GraalVM native-image are **not** supported today — the native-image blockers are spelled out in [Mat lifecycle](/mat-lifecycle#graalvm-native-image-is-not-supported-today).

### Is there GPU/CUDA support?
The bytedeco natives publish `-gpu` classifier variants (CUDA) for some platforms — swap the classifier and OpenCV's CUDA-backed paths become available. See [The native cache](/native-cache#choosing-the-classifier-and-gpu-variants).

## Doing common things

### How do I actually *see* an image?
Three options. Save it and open the file:

```scala mdoc:compile-only
Image.blank(64, 64, Scalar.Red).write("out.png")
```

Convert to a `java.awt.image.BufferedImage` for Swing/`ImageIO`:

```scala mdoc:silent
val awt: java.awt.image.BufferedImage = Image.blank(32, 32, Scalar.Green).toBufferedImage
```

```scala mdoc
(awt.getWidth, awt.getHeight)
```

Or, in a Jupyter/Almond notebook, a `BufferedImage` renders inline automatically — see [Notebooks](/notebooks).

### How do I convert to/from `BufferedImage` or raw bytes?
`toBufferedImage` / `Image.fromBufferedImage` bridge AWT; `bytes` / `Image.decode` bridge an in-memory encoded file (PNG/JPG bytes):

```scala mdoc:silent
val png: Array[Byte] = Image.blank(16, 16, Scalar.White).bytes(".png").toOption.get
val roundTripped: Image = Image.decode(png).toOption.get
roundTripped.close()
```

```scala mdoc
png.length > 0
```

### Which formats are supported?
Images: whatever this OpenCV build's codecs cover — PNG, JPEG, WebP, BMP, TIFF, … Video: depends on the platform's videoio backends (FFmpeg, OS frameworks); `Codec.Mjpg` in an `.avi` is the always-available fallback. Models: ONNX (via the [DNN](/dnn) module), plus Haar/LBP cascade XML (bundled).

### Can I call an OpenCV function scalacv doesn't wrap?
Yes — borrow the raw `Mat` with `image.mat` and call any `org.opencv.*` function; adopt a raw `Mat` back with `Image.wrap(Managed(mat))`. Full story in [Working with the raw OpenCV API](/low-level) and [Coming from OpenCV](/opencv-java).

## Correctness & performance

### Is it thread-safe?
Native handles (`Mat`, detectors, captures) are **one-owner-per-thread**, exactly as in raw OpenCV — but detector *results* are immutable plain data, safe to share freely. The full matrix is in [Concurrency](/concurrency).

### Does it leak memory? The GC should handle it, right?
Off-heap pixel buffers are invisible to the GC, so no — you must release, and scalacv makes that a one-liner (a scope, or `close()`). This is the whole reason the library exists; the [Mat lifecycle](/mat-lifecycle) page has the (dramatic) numbers.

### How do I check I'm not leaking?
Run under an RSS ceiling and a leak fails fast:

```sh
java -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar
```

Note: gate on *physical* bytes / RSS — `Pointer.totalBytes()` is blind to OpenCV's Mats. See [Performance](/performance) and [Testing](/testing).

### How do I test vision code without shipping image files?
Draw synthetic scenes, and compare with a tolerance (PSNR/max-abs-diff), not byte-equality. See [Testing](/testing) and the [Tutorial](/tutorial).

### Is the API stable?
It's `0.x` under early-SemVer, so breaking changes are still allowed between minor versions. Pin a version and read the changelog before upgrading.

## Next

- [Getting Started](/getting-started) — install and first pipeline.
- [Glossary](/glossary) — any unfamiliar term.
- [Troubleshooting](/troubleshooting) — specific error messages.
