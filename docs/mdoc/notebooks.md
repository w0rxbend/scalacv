# Notebooks & ecosystem interop

scalacv images are OpenCV `Mat`s under the hood, but the rest of the JVM speaks
`java.awt.image.BufferedImage` — so scalacv bridges the two. That bridge is what makes images
**display automatically in a notebook** and interoperate with `ImageIO`, Swing, and any `Graphics2D`
you already have. Alongside it, a small model **registry** downloads the detector/recogniser weights
the [detection](/object-detection) and [recognition](/face-recognition) APIs need.

If you are here to *see pixels in a Jupyter cell*, skip to [Displaying in Almond /
Jupyter](#displaying-in-almond--jupyter) — it is a one-liner. If you are here to fetch a model file
once and cache it, skip to [The model registry](#the-model-registry). Everything else is the
plumbing that makes those two things work.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

## The bridge in one picture

Two methods span the whole AWT boundary, and both **copy** — so ownership is never shared and never
ambiguous:

| Direction | Method | Input | Output | Notes |
| --- | --- | --- | --- | --- |
| scalacv → AWT | `Image.toBufferedImage` | 8-bit `Image`, 1/3/4 channels | `BufferedImage` | grey → `TYPE_BYTE_GRAY`, colour → `TYPE_3BYTE_BGR`; 4-channel BGRA is flattened to BGR |
| AWT → scalacv | `Image.fromBufferedImage` | any `BufferedImage` type | 3-channel BGR `Image` | works for ARGB, indexed, custom rasters — normalised through BGR |

Because both copy, the source you pass in stays yours to keep or dispose, and the result is a fresh,
independently owned object. That is the whole reason the bridge is safe to sprinkle through
notebook cells without thinking about the [Mat lifecycle](/mat-lifecycle).

:::warning `toBufferedImage` needs an **8-bit** image
It supports depth `CV_8U` only. A 16-bit or float image — a depth map, a disparity map, a raw filter
response — throws. Bring it to 8-bit first: `normalize` rescales it to 0–255 grey, `colorMap` renders
it in false colour. See [Showing non-8-bit results](#showing-non-8-bit-results) below.
:::

## BufferedImage in and out

`Image.toBufferedImage` copies the image into a `BufferedImage`; `Image.fromBufferedImage` does the
reverse. Here is the full round-trip — draw something, hand it to AWT, and bring it back:

```scala mdoc
{
  val img = Picture.star(Point(50, 50), 5, 40, 18).fillColor(Color.Orange).noStroke.render(100, 100, Color.DarkGray)
  val awt = img.toBufferedImage
  img.close()
  val back = Image.fromBufferedImage(awt)
  try s"${awt.getWidth}x${awt.getHeight} BufferedImage, back to a ${back.channels}-channel Image"
  finally back.close()
}
```

Note the ownership dance: `toBufferedImage` **borrows** the image (it stays alive), so `img` is
closed explicitly; `fromBufferedImage` produces a new owned `back`, closed in the `finally`. In a
notebook you rarely close anything by hand — the cell ends and the JVM reclaims it — but the same
snippet in library code must, and mdoc runs this code, so it closes.

### Coming the other way: from AWT into scalacv

Anything AWT can produce is a valid input. Build a canvas with `Graphics2D` the way ordinary Swing
code would, then process it as a scalacv `Image`:

```scala mdoc:silent
import java.awt.image.BufferedImage
import java.awt.Color as AwtColor

// Build a BufferedImage exactly as AWT/Swing code would.
val canvas = BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB)
val g = canvas.createGraphics()
try
  g.setColor(AwtColor.CYAN)
  g.fillRect(0, 0, canvas.getWidth, canvas.getHeight)
finally g.dispose()

// From here it is an ordinary scalacv Image: edge-detect it, then hand it back to AWT.
val edges = Image.fromBufferedImage(canvas).gray.canny(50, 150)
val shownEdges = edges.toBufferedImage
edges.close()
```

```scala mdoc
shownEdges.getWidth
```

The point is that scalacv slots into an existing imaging pipeline rather than replacing it: pull a
frame from `ImageIO`, process it with the [Image API](/image-api), hand the result back to whatever
consumes `BufferedImage`s.

## Displaying in Almond / Jupyter

[Almond](https://almond.sh) (the Scala Jupyter kernel) renders a `BufferedImage` inline
automatically. So the one-liner to *see* a scalacv image in a notebook cell is just
`toBufferedImage` on the last expression:

```scala
// In an Almond cell — the last expression displays as an image:
import scalacv.*
OpenCv.load()

Image.read("photo.jpg").map(_.gray.canny(80, 160).toBufferedImage)
```

A tiny helper makes it habitual — return `toBufferedImage` from any step and the cell shows it:

```scala mdoc:compile-only
def show(img: Image): java.awt.image.BufferedImage = img.toBufferedImage

Image.reading("photo.jpg")(img => show(img.gray.canny(80, 160)))
```

:::tip Show a branch without consuming the pipeline
Transforms **move** the image, so `show(img.blur(5))` consumes `img` and you cannot keep processing
it. To display an intermediate step and continue, `show` a `.copy`:

```scala mdoc:compile-only
def show(img: Image): java.awt.image.BufferedImage = img.toBufferedImage

Image.reading("photo.jpg") { img =>
  val preview = show(img.copy.gray) // a throwaway copy for the cell
  val result  = img.blur(9).canny(50, 150) // the real pipeline, still owns img
  (preview, result.toBufferedImage)
}
```
:::

## Showing non-8-bit results

Many interesting outputs are **not** 8-bit — a distance transform, a disparity/depth map, a gradient
magnitude. `toBufferedImage` rejects them on purpose (it would silently truncate). Bring them to
8-bit first, and you get a choice of how:

| Want | Do | Result |
| --- | --- | --- |
| Plain intensity | `normalize()` | float/16-bit rescaled to 0–255 grey |
| Legible false colour | `.gray.colorMap(map)` | a 3-channel [`Colormap`](/api/core/scalacv/Colormap.html) rendering |

The false-colour path is what turns an unreadable single-channel response into something a human can
actually parse in a cell:

```scala mdoc:silent
import java.awt.image.BufferedImage as Bi

val heat = Picture.star(Point(50, 50), 5, 40, 18)
  .fillColor(Color.White).noStroke
  .render(100, 100, Color.Black)
  .gray                       // 1-channel, 8-bit
  .colorMap(Colormap.Viridis) // 3-channel false colour
val heatAwt: Bi = heat.toBufferedImage
heat.close()
```

```scala mdoc
heatAwt.getWidth
```

The available maps are `Autumn`, `Bone`, `Jet`, `Ocean`, `Hot`, `Magma`, `Inferno`, `Plasma`,
`Viridis`, and `Turbo` — the same set OpenCV ships. `Viridis` and `Turbo` are the perceptually
uniform ones; prefer them over `Jet` for anything you will read quantitatively.

## The model registry

The neural models — [YuNet](/object-detection#faces) for detection, [SFace](/face-recognition) for
recognition — are files you fetch once. [`Models`](/api/core/scalacv/Models$.html) generalises
`FaceDetect.downloadModel`: it downloads to a temp file beside the target and moves it into place
only after it verifies, so an interrupted run never strands a truncated model; and it is idempotent,
so calling it at start-up is free once the file is present.

```scala mdoc:compile-only
import java.nio.file.Paths

// Fetch the bundled specs into a cache directory:
val cache = Paths.get(sys.props("user.home"), ".cache", "scalacv-models")
for
  yunet <- Models.fetch(FaceDetect.modelSpec, cache)
  sface <- Models.fetch(FaceRecognizer.modelSpec, cache)
yield (yunet, sface)
```

### What `fetch` guarantees

| Property | What it means for you |
| --- | --- |
| **Atomic** | Downloads to a sibling `.part` temp file and `move`s into place only after verifying — a crashed download never leaves a half-written model the next load trips on. |
| **Idempotent** | A target that already exists (and, if a hash is pinned, still matches) is returned without touching the network. Safe to call every start-up. |
| **Verifying by default** | A pinned SHA-256 is checked on every download *and* every cache hit — bit rot and tampering both fail loudly. |
| **Multi-mirror** | `urls` are tried in order; the first that downloads and verifies wins, and a `Left` names every URL that failed. |
| **`file://` aware** | A model already on disk is just another source — no HTTP client involved. |

The result is `Either[CvError, Path]`, so failures — an uncreatable directory, every mirror down, a
checksum mismatch — travel in the [error model](/error-model) rather than as exceptions.

### Built-in specs

Both detector specs live next to their detectors and pin a checksum (`FaceDetect`'s is the same one
[`FaceDetect`](/object-detection) verifies internally):

| Spec | For | Verified? |
| --- | --- | --- |
| `FaceDetect.modelSpec` | YuNet face detection | yes (pinned SHA-256) |
| `FaceRecognizer.modelSpec` | SFace embeddings | yes (pinned SHA-256) |

### Rolling your own spec

For any other model, build a [`ModelSpec`](/api/core/scalacv/ModelSpec.html) with a file name,
mirror URLs (`http(s)://` or `file://`), and its SHA-256 — verification is the default:

```scala mdoc:silent
val custom = ModelSpec(
  fileName = "my_model.onnx",
  urls = Seq("https://example.com/my_model.onnx"),
  sha256 = "…"
)

// Only when a model has no published checksum, opt out explicitly:
val trusted = ModelSpec.unverified("other.onnx", Seq("https://example.com/other.onnx"))
```

:::note `unverified` is a named opt-out, not a shortcut
It loses the tamper/corruption guard, so a corrupt or swapped download loads without complaint.
Reach for it only when a model genuinely has no published checksum — and prefer publishing one
yourself (`sha256Of` the file once) over trusting bytes forever.
:::

Once the path is in hand, feed it to whichever loader wants it — `Dnn.fromOnnx(path)` for a custom
net, or `FaceDetect`/`FaceRecognizer` for the bundled ones. See [DNN inference](/dnn) for the ONNX
side.

## Next

- [Image I/O](/image-io) — reading and writing files, and the `bytes`/encode paths that pair with the AWT bridge.
- [Object detection](/object-detection) and [Face recognition](/face-recognition) — the APIs the model registry feeds.
- [DNN inference](/dnn) — loading a fetched `.onnx` model and running it.
