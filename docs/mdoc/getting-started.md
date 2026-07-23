# Getting Started

## Add the dependency

scalacv depends on the OpenCV **Java API**, which has no native code in it. The natives ship in
per-platform classifier jars, and **no build tool can put a classifier into a published POM** — so
you add the one for your platform yourself:

```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.1.0",
  mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64",
  mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64"
)
```

Pick the classifier that matches where the code will run:

| Platform | classifier |
|---|---|
| Linux x86-64 | `linux-x86_64` |
| Linux ARM64 | `linux-arm64` |
| macOS Apple Silicon | `macosx-arm64` |
| macOS Intel | `macosx-x86_64` |
| Windows x86-64 | `windows-x86_64` |

There are `-gpu` variants of `linux-x86_64`, `linux-arm64` and `windows-x86_64` if you need CUDA.
There is **no** `windows-arm64` build.

Don't want to choose? `mvn"org.bytedeco:opencv-platform:4.13.0-1.5.13"` bundles every platform and
works anywhere — for about **408 MB** instead of 36–80 MB.

If you forget the natives, `OpenCv.load()` does not fail with a link error — it tells you the exact
two lines to add for the platform you are actually on.

## Load the natives

Once, at the top of your program. It is idempotent, so calling it again is free:

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## Your first pipeline

The high-level [`Image`](/image-api) API reads, transforms and writes in a single chain — every
intermediate is freed for you:

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

Here it is end to end on a scene we draw ourselves, so it runs with no image file:

```scala mdoc:silent
val edges: Either[CvError, Array[Byte]] =
  Image
    .blank(160, 120, Scalar.White)
    .drawRect(Rect(30, 30, 90, 60), Scalar.Black)
    .gray
    .canny(50, 150)
    .bytes(".png")
```

Everything is typed — no raw `int` constants anywhere:

```scala mdoc
ColorConversion.BgrToGray.cvValue
```

Next: the full [Image API](/image-api) for the high-level story, [Image processing](/image-processing)
for the operation catalogue, or [Working with the raw OpenCV API](/low-level) to drop to the bindings.
