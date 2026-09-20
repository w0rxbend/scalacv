---
title: Sample inputs
description: The repository ships no photos or videos, so the docs draw their own. Here is how to generate every input the tutorials ask for — a scene, a clip, a marker, a calibration board — in a few lines each.
---

# Sample inputs for the tutorials

Several tutorials open a file: `Image.read("coins.jpg")`, `Camera.usingFile("clip.mp4")`,
`Image.reading("people.jpg")`. Those files are not in this repository, and they never will be.

**Nothing in scalacv ships a bitmap.** A photograph or a video clip carries a copyright and a model
release, and a computer-vision library that hands you one has quietly handed you a licensing problem
too. The library's own examples say so out loud — `examples/src/scalacv/Fixtures.scala` opens with
"there is no image asset in this repository (no bitmap fixture ships, for licensing reasons). So
every example generates its input here rather than reading a file."

That is not a limitation to work around. It is a technique, and it is a better one: an input you
*drew* has exactly the properties you meant it to have, so when a step misbehaves you know whether
the bug is in your code or in your picture. This page collects every generator the tutorials need.

**Everything below runs.** Each `scala` block is compiled and executed when the documentation is
built, and the values printed underneath are the real ones — not values someone typed in by hand.
Copy any block into a project with the same dependencies and you get the same result.

```scala mdoc:silent
import scalacv.graphs.*
import scalacv.vision.*
import scalacv.*

OpenCv.load()
```

:::note[Which modules you need]
The scenes, the video and the calibration board need `scalacv` (core). The QR code, the ArUco marker
and the face detector are in `scalacv-vision`. Both dependency lines are on
[Getting started](/getting-started).
:::

## A scene you can process

The workhorse. `Image.blank` gives you a canvas of a single colour; `drawCircle`, `drawRect` and
`drawText` paint onto it and hand the image straight back, so a whole scene is one chain.

Two things to know before you read it:

- **Colours are BGR, not RGB.** `Scalar(30, 30, 30)` is a dark grey (all three channels equal, so the
  order does not matter), but `Scalar.Red` is `Scalar(0, 0, 255)` — blue first. That is OpenCV's
  channel order and scalacv keeps it rather than silently reordering your pixels.
- **`Thickness.Filled` means solid.** Leave it out and you get a one-pixel outline, which is a
  perfectly good scene but a much weaker one to threshold.

Here is the scene the [counting tutorial](/tutorial) uses — a dark tray with five bright "coins" and
one speck of noise — wrapped in a `def` so every call gives you a fresh, unspent image:

```scala mdoc:silent
/** A dark tray with five bright discs and one speck. A new Image every call. */
def coinTray(): Image =
  Image
    .blank(320, 200, Scalar(30, 30, 30)) // dark background, 3-channel BGR
    .drawCircle(Point(60, 60), 25, Scalar.White, Thickness.Filled)
    .drawCircle(Point(150, 70), 30, Scalar.White, Thickness.Filled)
    .drawCircle(Point(240, 60), 20, Scalar.White, Thickness.Filled)
    .drawCircle(Point(90, 150), 22, Scalar.White, Thickness.Filled)
    .drawCircle(Point(200, 150), 28, Scalar.White, Thickness.Filled)
    .drawCircle(Point(280, 180), 2, Scalar.White, Thickness.Filled) // the speck
```

A `def`, not a `val`, on purpose. Transforms in scalacv *consume* the image they are called on — see
[Mat lifecycle](/mat-lifecycle) — so a shared `val` scene is good for exactly one pipeline before it
is spent. A `def` costs one small allocation and saves you the whole class of "use after move" error.

```scala mdoc:silent
val tray = coinTray()
```

```scala mdoc
(tray.width, tray.height, tray.channels)
```

And it behaves the way the tutorial claims. Threshold it, count the blobs, and count again with the
speck filtered out:

```scala mdoc:silent
val trayBinary = tray.copy.gray.threshold(128)
val trayBlobs = trayBinary.contours()
trayBinary.close()
```

```scala mdoc
trayBlobs.size
trayBlobs.count(_.area > 50.0)
```

Six blobs, five of them real. `tray.copy` is what keeps `tray` alive: `.gray` would otherwise
consume it and the rest of this page would have nothing left to draw on.

:::tip[Tuning a scene instead of tuning a threshold]
If a step is not doing what you expect, change the *picture* first. Move two discs until they touch
and watch the count drop from five to four — that is the merged-contour problem the tutorial warns
about, reproduced in one line, with no photograph and no guesswork about whether your camera is at
fault.
:::

### Lines, arrows and polygons

`Image` carries the drawing verbs that come up in annotation — rectangles, circles, text, contours.
Lines, arrows and filled polygons live one level down, as verbs on the raw `Mat`. You reach them
through `image.mat`, which **borrows** the Mat: it draws in place and leaves the `Image` live rather
than consuming it.

```scala mdoc:silent
val lineDemo = Image.blank(200, 120, Scalar(30, 30, 30))
lineDemo.mat.drawLine(Point(10, 110), Point(190, 20), Scalar.White, Thickness.Stroke(2))
```

```scala mdoc:silent
val lineBinary = lineDemo.copy.gray.threshold(128)
val lineBlobs = lineBinary.contours()
lineBinary.close()
```

```scala mdoc
lineBlobs.size
```

One blob — the stroke is the only bright thing in the frame. The full set of `Mat` drawing verbs is
in [Drawing](/drawing); the borrow-versus-consume distinction is in
[Working with the OpenCV Java API](/opencv-java).

## A video, with no media file

Every snippet in the [video tutorial](/tutorial-video) opens `"clip.mp4"`. You can make that file
yourself in about ten lines: a `Recorder` is a video writer that takes `Image`s, and the images can
be drawn ones.

**Use `Codec.Mjpg` with an `.avi` extension.** Motion-JPEG is served by OpenCV's *built-in* writer,
so it needs no FFmpeg, no GStreamer and no system codec — it is the one combination that opens on
every build, which is why it is `Recorder`'s default. The container is part of the deal: MJPG will
not open inside an `.mp4` or `.mkv`, so the codec and the file extension have to agree. `Codec.Mp4v`
and `Codec.Avc1` give much smaller files when the platform can encode them, and a `Left` from
`Recorder.open` when it cannot.

```scala mdoc:silent
import java.nio.file.{Files, Path}

val sampleDir: Path = Files.createTempDirectory("scalacv-sample-inputs")
sampleDir.toFile.deleteOnExit()

val clip: Path = sampleDir.resolve("clip.avi")
val clipSize = Size(320, 240)

val recorded: Either[CvError, Unit] =
  Recorder.using(clip.toString, clipSize, fps = 25.0, codec = Codec.Mjpg) { rec =>
    (0 until 50).foreach { i =>
      // A green disc sliding left to right — 50 frames of honest motion.
      val f = Image
        .blank(320, 240, Scalar(20, 20, 20))
        .drawCircle(Point(20 + i * 5, 120), 18, Scalar.Green, Thickness.Filled)
      try rec.write(f).fold(e => throw e, identity)
      finally f.close()
    }
  }
```

Three things in that block are worth naming, because each one is a rule you will hit again:

- **`Recorder.using` closes the writer for you**, on the success path and on an exception. A
  `VideoWriter` that is never released leaves a truncated, unplayable file.
- **`rec.write(image)` borrows.** It does not consume the frame, so the `try`/`finally` closing `f`
  is yours to write. Every frame is allocated and freed inside the loop, so a 50-frame clip and a
  50,000-frame clip use the same memory.
- **Every frame must match the recorder's size and be 8-bit.** A mismatch throws
  `IllegalArgumentException` rather than writing a file of noise.

```scala mdoc
recorded.isRight
Files.size(clip) > 0
```

Now every `Camera.usingFile(...)` snippet in the video tutorial has something to open. Reading it
back:

```scala mdoc:silent
val frameTally: Either[CvError, Int] =
  Camera.usingFile(clip.toString) { cam =>
    var n = 0
    cam.foreach(attemptsPerFrame = 1) { _ => n += 1 }
    n
  }
```

```scala mdoc
frameTally
Camera.usingFile(clip.toString)(_.info.size)
```

Fifty frames at 320×240 — exactly what went in. `attemptsPerFrame = 1` is the right setting for a
file: the default of 3 exists so a flaky live camera can drop a frame without ending the stream, and
on a finite file it only costs you two extra blocking reads at the end.

:::note[`info` is advisory]
`cam.info` asks the backend, and backends guess. `frameCount` and `fps` are frequently wrong and are
`0` for a live camera that has not delivered a frame yet — never use `frameCount` as a loop bound.
See [Video & capture](/video).
:::

Step 3 of the video tutorial counts "busy" frames — frames with more than twenty edge contours. On
this clip:

```scala mdoc:silent
val busy: Either[CvError, Int] =
  Camera.usingFile(clip.toString) { cam =>
    var n = 0
    cam.foreach(attemptsPerFrame = 1) { frame =>
      val edged = frame.copy.gray.canny(threshold1 = 80, threshold2 = 160)
      try if edged.contours().size > 20 then n += 1
      finally edged.close()
    }
    n
  }
```

```scala mdoc
busy
```

Zero, and that is the correct answer: one clean disc per frame produces one edge contour, nowhere
near twenty. Being able to tell "the threshold rejected everything" apart from "my setup is broken"
is the entire reason to run a tutorial on an input you drew.

Step 4 writes a new video out of the old one:

```scala mdoc:silent
val edgesOut: Path = sampleDir.resolve("edges.avi")

val framesWritten: Either[CvError, Long] =
  Camera
    .usingFile(clip.toString) { cam =>
      cam.recordTo(edgesOut.toString, codec = Codec.Mjpg, attemptsPerFrame = 1) { frame =>
        frame.gray.canny(threshold1 = 80, threshold2 = 160).convert(ColorConversion.GrayToBgr)
      }
    }
    .flatMap(identity)
```

```scala mdoc
framesWritten
```

The `.flatMap(identity)` flattens the two layers of `Either` — one from opening the source, one from
recording. The `GrayToBgr` step at the end is not decoration: `canny` returns a single-channel image
and the recorder was opened in colour, so the frame has to be widened back to three channels before
it will be accepted.

This same clip is a working input for [Motion detection](/motion-detection) — a disc that moves five
pixels a frame is exactly what a frame-difference detector is looking for.

## A detectable code, with no photo

Fiducial markers and QR codes are *generated*, not photographed, so these need no compromise at all:
you can produce a pixel-perfect one and decode it straight back.

### An ArUco marker

`Aruco.generateMarker` renders a marker from a dictionary as an 8-bit single-channel image. It comes
out with the marker's own black border but **no quiet zone**, and the detector will not find it
without one — it hunts for a dark quadrilateral on a light background, so a marker that runs to the
edge of the image has no background to be dark against. `pad` supplies the white margin:

```scala mdoc:silent
val marker: Image =
  Image
    .wrap(Aruco.generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 200))
    .pad(60, color = Scalar.White)
```

```scala mdoc
marker.arucoMarkers().map(_.id)
marker.arucoMarkers().flatMap(_.corners).size
```

The id round-trips and each marker reports four corners. `Image.wrap` takes ownership of the
`Managed[Mat]` the generator returned, so the marker is freed on the same terms as any other
`Image` — see [Mat lifecycle](/mat-lifecycle).

`arucoMarkers()` defaults to `Dict4x4_50`, the same dictionary we generated from. Ask for a
different one and you get an empty `Seq` rather than a wrong answer: a 5×5 tag carries more bits than
a 4×4 pattern can satisfy. Pick the *smallest* dictionary with enough ids for your job — fewer
markers means a larger Hamming distance between them, which means more robust detection. All of them
are listed in [Enums & constants](/enums-reference); the AR maths is in [Markers & AR](/marker-ar).

### A QR code

OpenCV ships an encoder as well as a detector, so the round trip is free. Two adjustments make the
result detectable: upscale it (the encoder emits one pixel per module, far below the detector's
resolution floor) with **nearest-neighbour** interpolation so the modules stay square-edged rather
than blurred, and give it three channels, since detection expects a BGR image.

```scala mdoc:silent
import org.opencv.objdetect.QRCodeEncoder

/** A QR code carrying `payload`, upscaled and quiet-zoned so the detector can read it. */
def qrScene(payload: String): Image =
  val bitmap = org.opencv.core.Mat()
  QRCodeEncoder.create().encode(payload, bitmap)
  Image
    .wrap(Managed(bitmap))
    .convert(ColorConversion.GrayToBgr)
    .scale(10.0, Interpolation.Nearest)
    .pad(40, color = Scalar.White)
```

```scala mdoc:silent
val poster = qrScene("https://github.com/w0rxbend/scalacv")
```

```scala mdoc
poster.qrCodes.map(_.text)
```

`qrCodes` builds and frees its own detector, so there is nothing to manage — the result is plain
Scala data. A code that OpenCV locates but cannot decode comes back with an empty `text` and usable
`corners`; more on that in [Object detection](/object-detection).

### A calibration board

[Camera calibration](/calibration) wants photographs of a chessboard. The board itself is just
rectangles, so you can draw a flat one and check that the corner finder agrees with you before you
point a real camera at anything.

`ChessboardPattern(columns, rows)` counts **inner** corners, not squares — a 9×6 pattern is a board
of 10×7 squares. Draw one more square than corners in each direction, and leave a white margin for
the same reason the ArUco marker needed one:

```scala mdoc:silent
/** A flat black-and-white chessboard with a white quiet zone. */
def chessboard(columns: Int = 9, rows: Int = 6, square: Int = 60, margin: Int = 60): Image =
  val squaresX = columns + 1
  val squaresY = rows + 1
  val dark =
    for
      r <- 0 until squaresY
      c <- 0 until squaresX
      if (r + c) % 2 == 0
    yield Rect(margin + c * square, margin + r * square, square, square)
  val canvas =
    Image.blank(squaresX * square + 2 * margin, squaresY * square + 2 * margin, Scalar.White)
  dark.foldLeft(canvas)((img, cell) => img.drawRect(cell, Scalar.Black, Thickness.Filled))
```

```scala mdoc:silent
val boardPattern = ChessboardPattern(columns = 9, rows = 6)
val board = chessboard()
val boardCorners = Calibration.findCorners(board, boardPattern)
```

```scala mdoc
(board.width, board.height)
boardCorners.map(_.size)
```

Fifty-four inner corners, which is `9 * 6` — the detector is all-or-nothing, so a `Some` here means
it found the *whole* board. `findCorners` borrows the image rather than consuming it.

The `foldLeft` is there because `drawRect` consumes the image it draws on and returns a new one: each
square is painted into the accumulator and the accumulator moves along. That is the general shape of
"draw N things" in scalacv.

:::warning[A drawn board calibrates nothing]
`Calibration.fromChessboard` needs several views of the board *at different angles*, because it
recovers focal length and lens distortion from how the perspective changes between them. Every view
of a flat drawn board is the same view. Use the drawn board to check that corner detection works and
that your pattern dimensions are right; use a printed board and a real camera to get numbers you can
trust.
:::

## A face photo — the one case you cannot fake

Haar cascades and the YuNet neural detector both look for the light-and-dark structure of a
*photographed* human face: the way brows sit darker than the forehead, how the bridge of the nose
catches light, the texture of skin. A drawing has none of that. It is the one input on this page you
have to bring yourself.

Try it and see. Here is a face as a cartoon — a disc, two dots and a mouth — run through the classic
frontal-face cascade:

```scala mdoc:silent
val drawnFace: Image =
  Image
    .blank(200, 200, Scalar(210, 210, 210))
    .drawCircle(Point(100, 100), 70, Scalar(180, 160, 140), Thickness.Filled)
    .drawCircle(Point(78, 85), 8, Scalar.Black, Thickness.Filled)
    .drawCircle(Point(122, 85), 8, Scalar.Black, Thickness.Filled)
    .drawRect(Rect(85, 130, 30, 6), Scalar.Black, Thickness.Filled)

val facesFound: Either[CvError, Int] =
  Cascades.load(CascadeName.FrontalFaceAlt).map { detector =>
    detector.use(classifier => drawnFace.detectHaar(classifier).size)
  }
```

```scala mdoc
facesFound
```

The detector loads and runs; what it makes of a cartoon is not a face count you can trust either way.
Whatever number comes back, it tells you nothing about whether the detector works — which is exactly
why the face tutorial asks for a real photograph.

:::note[Windows has no bundled cascade]
`Cascades.load` reads the XML out of the OpenCV jar. The `windows-x86_64` payload ships an empty
`share/` directory, so on Windows this returns `Left(CvError.LoadFailed(...))` and you download the
cascade yourself. [Detecting faces](/tutorial-faces) has the two-line workaround.
:::

### Where to get a photograph you are allowed to use

Pick an image that is explicitly public domain or openly licensed, and keep it *outside* the
repository — the same reasoning that keeps bitmaps out of this one applies to yours.

- **[Wikimedia Commons](https://commons.wikimedia.org)** — filter by licence; the "public domain"
  and CC0 sets include a great many portraits and crowd scenes. The licence is stated on every file
  page.
- **[Unsplash](https://unsplash.com)** — the Unsplash licence permits free use including
  commercially, no attribution required. Search for "portrait" or "crowd".

Save one as `people.jpg` next to your program and the tutorial runs unchanged:

```scala mdoc:compile-only
Cascades.load(CascadeName.FrontalFaceAlt).foreach { detector =>
  detector.use { classifier =>
    Image.reading("people.jpg") { photo =>
      println(s"found ${photo.detectHaar(classifier).size} face(s)")
    }
  }
}
```

On a well-lit, front-facing single-subject portrait at 400 pixels or wider, expect
`found 1 face(s)`. If you get `0`, the usual causes are a face turned more than about 20° away from
the camera, a face smaller than roughly 30×30 pixels, or heavy backlighting. If you get more than
one, `minNeighbors` is too low — raise it from the default of `3` and the duplicates merge. The
tuning knobs are on [Detecting faces](/tutorial-faces).

:::info[What a drawn input will never exercise]
Synthetic scenes have clean edges, no sensor noise, no motion blur, no JPEG compression artefacts, no
lens distortion, and lighting that is perfectly even. Every one of those is a thing a real pipeline
has to survive. Use drawn inputs to prove your *logic* is right; use real captures to find out what
your thresholds actually need to be. [Testing](/testing) covers where to draw that line in a test
suite.
:::

## A model file

The [neural-network tutorial](/tutorial-dnn) needs an `.onnx` file, and face *recognition* needs one
too. Models are downloaded and cached rather than vendored — for the same licensing reason images
are not shipped — and `Models.fetch` verifies a SHA-256 checksum so a corrupt or substituted file
fails loudly instead of producing quiet nonsense.

That whole topic, including which model to use for which capability and the pre-processing numbers
each one expects, is on [Getting models](/models).

## What you should see

A quick reference for telling a working setup apart from a broken one. Every value in this table is
what the tutorial prints when it is run on the input it describes.

| Tutorial | Step | Expected |
|---|---|---|
| [Count the objects](/tutorial) | 4 — `allBlobs.size` | `6` (five discs plus the speck) |
| | 5 — `coins.size` | `5` |
| | 6 — PNG length `> 0` | `true` |
| [Track a coloured object](/tutorial-color-tracking) | 3 — largest blob's area `> 0.0` | `true` |
| | 4 — centroid | roughly `Some((210, 120))` |
| | 5 — annotated PNG length `> 0` | `true` |
| [Process a video](/tutorial-video) | 2 — `snapshot()` on the clip above | a `Right`, one 320×240 frame |
| | 3 — `busyFrames` on the clip above | `0` — one clean disc is not twenty contours |
| | 4 — `recordTo` on the clip above | `Right(50)` |
| [Detect faces](/tutorial-faces) | 2 — single-subject portrait | `found 1 face(s)` |
| | 2 — a drawn cartoon face | meaningless; use a photograph |
| [Run a neural network](/tutorial-dnn) | 4 — `output.dims` | model-specific; check it against the model card |
| This page | ArUco round trip | `List(7)` |
| | QR round trip | the payload string, unchanged |
| | Chessboard corners | `Some(54)` = `9 * 6` inner corners |

If a number here does not match on your machine, the input is the first thing to check, not the
algorithm — re-run the generator above and compare its printed size and channel count before you
start adjusting thresholds.

## Next

- [Tutorial: count the objects in an image](/tutorial) — the drawn scene put to work.
- [Tutorial: process a video frame by frame](/tutorial-video) — now runnable with the clip above.
- [Mat lifecycle](/mat-lifecycle) — why the scene generators are `def`s and not `val`s.
- [Drawing](/drawing) — the full set of drawing verbs, including the `Mat`-level ones.
- [The Image API](/image-api) — every transform used on this page.
- [Reading & writing images](/image-io) — when you do have files, how to load them without leaking.
- [Cookbook](/cookbook) — task-first recipes built on the same generated inputs.

```scala mdoc:invisible
Seq(tray, lineDemo, marker, poster, board, drawnFace).foreach(_.close())
Seq(edgesOut, clip, sampleDir).foreach { p =>
  Files.deleteIfExists(p)
  ()
}
```
