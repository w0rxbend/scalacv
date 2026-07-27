# Image basics — the 5-minute primer

New to computer vision? Start here. This page explains the handful of ideas every other page assumes — what an image *is* to a computer, what pixels and channels are, why OpenCV says **BGR** and not RGB, and how coordinates and data types work — with tiny runnable examples. No prior CV knowledge needed.

```scala mdoc:silent
import scalacv.*
import org.opencv.core.CvType

OpenCv.load()
```

## An image is a grid of numbers

A digital image is a rectangular **grid of pixels**, and each pixel is just a number (or a few numbers). A greyscale image is one number per pixel — `0` is black, `255` is white, everything between is a shade of grey. In scalacv that grid lives in a native `Mat` (short for *matrix*), wrapped by an [`Image`](/image-api).

Let's make the smallest possible image — a blank canvas — and ask it about itself:

```scala mdoc:silent
val canvas = Image.blank(width = 320, height = 240)
val w = canvas.width
val h = canvas.height
val ch = canvas.channels
canvas.close()
```

```scala mdoc
(w, h, ch) // 320 wide, 240 tall, 3 channels (colour)
```

:::note Width × height, but rows × cols underneath
You think in `width × height` (320 × 240). The underlying `Mat` thinks in `rows × cols` (240 rows × 320 cols) — rows are the height. scalacv's high-level API speaks width/height; you only meet rows/cols if you drop to the raw `Mat`.
:::

## Channels: greyscale, colour, and alpha

A pixel can carry more than one number. The **channel count** tells you how many:

| Channels | Meaning | Example |
|---|---|---|
| **1** | greyscale — one intensity | a mask, an edge map, a depth map |
| **3** | colour — blue, green, red | a normal photo |
| **4** | colour + alpha (transparency) | a PNG with transparency |

```scala mdoc:silent
val grey = Image.blank(10, 10, channels = 1)   // one number per pixel
val colour = Image.blank(10, 10, channels = 3) // three
val greyCh = grey.channels
val colourCh = colour.channels
grey.close(); colour.close()
```

```scala mdoc
(greyCh, colourCh)
```

## Why BGR, not RGB

Here is the single most common beginner surprise: **OpenCV orders colour channels Blue, Green, Red — not Red, Green, Blue.** It's a historical quirk, and scalacv keeps OpenCV's convention so nothing is silently reordered behind your back. So "pure red" is the *third* channel:

```scala mdoc:silent
// Scalar is (channel0, channel1, channel2, channel3). In BGR: blue, green, red.
val red = Scalar.Red     // == Scalar(0, 0, 255): zero blue, zero green, full red
val green = Scalar.Green  // == Scalar(0, 255, 0)
val blue = Scalar.Blue    // == Scalar(255, 0, 0)
```

```scala mdoc
(red, green, blue)
```

:::tip You rarely hand-write channel order
Use the named `Scalar.Red` / `Green` / `Blue` / `Black` / `White` constants and you never have to remember the order. You only think about BGR when reading raw pixel values or converting to another library's RGB.
:::

## Coordinates: origin top-left, y grows downward

Pixel coordinates start at `(0, 0)` in the **top-left** corner. `x` grows to the right, `y` grows **down** (not up, like a maths graph). A [`Point`](/geometry) is `(x, y)`; a [`Rect`](/geometry) is `(x, y, width, height)` from its top-left corner.

```scala mdoc:silent
val topLeft = Point(0, 0)
val box = Rect(x = 10, y = 20, width = 100, height = 50) // starts 10 right, 20 down
val corner = box.bottomRight
```

```scala mdoc
(box.area, corner) // area is a Long; bottom-right is (x+width, y+height)
```

## Reading a single pixel

Drop to the raw `Mat` with `.mat` (a borrow — the `Image` still owns it) and `get(row, col)` returns that pixel's channels as an `Array[Double]`. Remember: `get` takes **(row, col)** = **(y, x)**.

```scala mdoc:silent
val filled = Image.blank(4, 4, Scalar.Green) // all-green 4x4
val pixel = filled.mat.get(0, 0)             // the top-left pixel's [B, G, R]
val greenValue = pixel(1)                     // channel 1 is green
filled.close()
```

```scala mdoc
greenValue // 255.0 — full green
```

## Data types: 8-bit is the default, but not the only one {#data-types}

Each channel value is stored in a numeric type. The everyday one is **8-bit unsigned** (`CV_8U`, range 0–255) — what photos and most processing use. Some operations produce wider types: a derivative can be **16-bit signed** (`CV_16S`, allows negatives), and intermediate maths often uses **32-bit float** (`CV_32F`). The type string reads as `depth` + `C` + `channelCount`:

```scala mdoc
CvType.typeToString(CvType.CV_8UC3) // 8-bit Unsigned, 3 Channels — a normal colour image
```

:::warning A 16-bit or float image won't display directly
Anything that isn't 8-bit has to be brought back to `CV_8U` before you save or show it — `normalize` rescales the range to 0–255, `colorMap` renders it in false colour. See [Image processing](/image-processing).
:::

## "Processing" = turning one grid into another

Almost everything in this library takes an image and produces a new one: greyscale conversion, blurring, edge detection, thresholding. On an [`Image`](/image-api) you *chain* these, and each step hands the pixels to the next:

```scala mdoc:silent
// A tiny real pipeline: colour -> grey -> blurred -> edges, encoded to PNG bytes.
val edgesPng: Either[CvError, Array[Byte]] =
  Image.blank(120, 90, Scalar.White)
    .drawRect(Rect(30, 25, 60, 40), Scalar.Black)
    .gray        // 3 channels -> 1
    .blur(2)     // smooth
    .canny(50, 150) // find edges
    .bytes(".png")
```

```scala mdoc
edgesPng.map(_.length).getOrElse(0) > 0 // true: we produced a PNG
```

That's the whole mental model: **an image is a grid of numbers, operations turn one grid into another, and you chain them.** Everything else is which operation to pick.

## Next

- [Getting Started](/getting-started) — add the dependency and run your first pipeline.
- [The Image API](/image-api) — the full high-level surface you just used.
- [Glossary](/glossary) — every term on one page.
- [Image processing](/image-processing) — the catalogue of operations.
