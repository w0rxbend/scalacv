---
slug: /
title: scalacv
description: A Scala 3 wrapper for the OpenCV 4.13 Java API — a fluent, headless image pipeline that is honest about native memory.
hide_title: true
---

<!-- SOURCE OF TRUTH for the Docusaurus landing page. mdoc type-checks the example below, then the
     docs assemble step (./mill docs.mdoc + scripts/assemble-docs.sh) writes this file to
     website/docs/index.md. Do not edit website/docs/index.md — edit this file. The VitePress
     landing (docs/mdoc/index.md) is separate and retires when the migration flips. -->

# scalacv

A Scala 3 wrapper for the [OpenCV 4.13](https://opencv.org) Java API, built on the
[Bytedeco JavaCPP presets](https://github.com/bytedeco/javacpp-presets). It gives you a fluent,
high-level `Image` pipeline over the complete OpenCV Java bindings — typed (no raw `int` constants),
genuinely headless (no GUI toolkit, no `apt-get`), and honest about native memory: every
intermediate frees itself, and the full `org.opencv.*` surface stays one method call away.

Read, transform, detect and write as a single chain — this runs headless, with no image file:

```scala mdoc:silent
import scalacv.*

OpenCv.load()

val edges: Either[CvError, Array[Byte]] =
  Image
    .blank(160, 120, Scalar.White)
    .drawRect(Rect(30, 30, 90, 60), Scalar.Black)
    .gray
    .canny(50, 150)
    .bytes(".png")
```

`Image.reading` scopes a file to the block and releases it on success, failure, and exception:

```scala mdoc:compile-only
Image.reading("photo.jpg") { img => img.gray.blur(2).canny(80, 160).write("edges.png") }
```

Add the dependency (Mill shown; sbt and scala-cli are on the install page), picking the natives
classifier for your platform:

```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.1.0",
  mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64"
)
```

**Start here:** [Getting Started](/getting-started) · [The Image API](/image-api) ·
[Mat lifecycle & resource safety](/mat-lifecycle) · [API reference](/api/core/index.html)
