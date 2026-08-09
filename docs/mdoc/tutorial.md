# Tutorial: count the objects in an image

Let's build something real, one small step at a time: a program that **finds and counts the blobs in an image and draws a box around each**. It's the "hello world" of computer vision — counting coins on a table, cells under a microscope, parts on a conveyor — and it teaches the core loop you'll reuse everywhere: **clean up → separate foreground from background → find shapes → measure them**.

Every step below runs (mdoc compiles it), and we draw our own test scene so you need no image file to follow along. At the end you'll swap the drawn scene for `Image.read("photo.jpg")` and it just works.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## Step 1 — get an image

In a real program you'd read a photo:

```scala mdoc:compile-only
Image.read("coins.jpg") // Either[CvError, Image]
```

For the tutorial we'll *draw* a scene — a dark tray with five bright "coins" (circles) of different sizes, plus one tiny speck of noise. Drawing our own input means the test is explicit and repeatable:

```scala mdoc:silent
val photo =
  Image.blank(320, 200, Scalar(30, 30, 30)) // dark background
    .drawCircle(Point(60, 60), 25, Scalar.White, Thickness.Filled)
    .drawCircle(Point(150, 70), 30, Scalar.White, Thickness.Filled)
    .drawCircle(Point(240, 60), 20, Scalar.White, Thickness.Filled)
    .drawCircle(Point(90, 150), 22, Scalar.White, Thickness.Filled)
    .drawCircle(Point(200, 150), 28, Scalar.White, Thickness.Filled)
    .drawCircle(Point(280, 180), 2, Scalar.White, Thickness.Filled) // a speck — noise to reject later
```

Five coins and one speck. Our program should report **5**, not 6 — rejecting the speck is the last step.

## Step 2 — simplify to greyscale

Colour doesn't help us *count* — shape does. So the first move in almost every pipeline is to drop to one channel. It's cheaper and it's what the next steps expect.

We'll need the original later (to draw boxes on it), and a transform *consumes* the image it's called on (that's [move semantics](/mat-lifecycle)). So we work on a `.copy` and leave `photo` untouched:

```scala mdoc:silent
val grey = photo.copy.gray // `photo` survives; `grey` is a new one-channel image
```

## Step 3 — separate foreground from background (threshold)

**Thresholding** turns the greyscale image black-and-white by a cutoff: brighter than the cutoff → white (foreground), darker → black (background). Our coins are bright on a dark tray, so a mid cutoff cleanly isolates them:

```scala mdoc:silent
val binary = grey.threshold(value = 128) // consumes `grey`; result is 0/255 black-and-white
```

:::tip[When lighting is uneven]
A single global cutoff struggles if one corner is brighter than another (scanned documents, angled light). `adaptiveThreshold` computes the cutoff *per neighbourhood* instead — see [Image processing](/image-processing).
:::

## Step 4 — find the shapes (contours)

A **contour** is the outline of a connected white blob. `contours()` returns one per blob as plain data. It's a *query* — it reads the image without consuming it, so `binary` stays alive:

```scala mdoc:silent
val allBlobs = binary.contours() // Seq[Contour] — one per white region
```

```scala mdoc
allBlobs.size // 6 — five coins plus the speck
```

## Step 5 — measure and filter out the noise

Each `Contour` can report its enclosed **area**. The speck's area is tiny, so we keep only blobs above a sensible minimum. This "measure, then filter" move is how you turn raw detections into a real answer:

```scala mdoc:silent
val coins = allBlobs.filter(_.area > 50.0) // drop anything smaller than the smallest real coin
```

```scala mdoc
coins.size // 5 — the speck is gone
```

That's the count. Everything else is presentation.

## Step 6 — annotate the original and save

Now we use the `photo` we kept back in step 2. Each contour gives a **bounding box** (its upright rectangle); `drawRects` paints them all in one call. Drawing consumes `photo`, and `bytes` encodes and releases it — so this is the terminal step:

```scala mdoc:silent
val boxes = coins.map(_.boundingRect)
binary.close() // we're done with the black-and-white image

val outputPng: Either[CvError, Array[Byte]] =
  photo.drawRects(boxes, Scalar.Green).bytes(".png")
```

```scala mdoc
outputPng.map(_.length).getOrElse(0) > 0 // true — a PNG with five green boxes
```

In a real program you'd `.write("counted.png")` instead of `.bytes(".png")`.

## The whole thing

Put together, the counter is short — and reads as the pipeline it is:

```scala mdoc:compile-only
def countObjects(path: String, minArea: Double = 50.0): Either[CvError, Int] =
  Image.reading(path) { photo =>
    val binary = photo.copy.gray.threshold(128)
    try binary.contours().count(_.area > minArea)
    finally binary.close()
  }
```

`Image.reading` opens the file and closes it for you on every path — success, failure, or exception. We branch off `photo.copy` so the reading-scope's image isn't consumed early, and we close the `binary` we created. Nothing leaks.

## Make it yours

- **Real photos** — swap the drawn scene for `Image.read(...)`. If your objects are *dark on a light* background, flip the threshold mode: see [Dark objects on a light background](#dark-objects-on-a-light-background) below.
- **Touching objects** — if two coins touch, they come back as **one** contour and your count is short. `erode` shrinks every white blob by a few pixels, which pulls the join apart; `dilate` afterwards grows them back. The [cookbook recipe](/cookbook#separate-two-touching-blobs) does it end to end. Both verbs are on `Image`, so you stay in the same chain.
- **Not just counting** — you already have each `boundingRect`; crop each one (`photo.crop(rect)`) to run a classifier, read text with [OCR](/ocr), or measure it.
- **Live video** — wrap the same function in [`Camera.foreach`](/video) to count objects in every frame of a stream.

### Dark objects on a light background

Our coins were bright on a dark tray, so the default threshold mode — keep everything **brighter** than the cutoff — was the one we wanted. Photograph dark objects on a white sheet and that same rule keeps the *sheet* and throws the objects away. You want the opposite rule: keep everything **darker** than the cutoff.

That mode is called `BinaryInv` — "binary, inverted". It is a case of the `Threshold.Mode` enumeration, and `threshold`'s `kind` parameter takes a `Threshold`, a small case class pairing a mode with an optional automatic method. So the value to pass is `Threshold(Threshold.Mode.BinaryInv)`:

```scala mdoc:silent
val darkOnLight =
  Image.blank(200, 120, Scalar.White) // a white sheet…
    .drawCircle(Point(60, 60), 25, Scalar.Black, Thickness.Filled) // …with one dark object on it
    .gray
    .threshold(128, kind = Threshold(Threshold.Mode.BinaryInv)) // dark pixels become white

darkOnLight.close() // nothing below uses it, so release it here
```

`Threshold.Binary` is the default you have been using all along. `Threshold.otsu()` and `Threshold.triangle()` go one better and pick the cutoff value for you — handy when lighting varies between photos. See [Image processing](/image-processing).

## Next

- [Image basics](/basics) — if any term above was new.
- [Contours](/contours) — everything a `Contour` can tell you (area, centroid, hull, approximation).
- [Cookbook](/cookbook) — more ready-to-adapt recipes.
- [The Image API](/image-api) — the full high-level surface.
