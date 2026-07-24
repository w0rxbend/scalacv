# Notebooks & ecosystem interop

scalacv images are OpenCV `Mat`s under the hood, but the rest of the JVM speaks `java.awt.image.BufferedImage`
— so scalacv bridges the two. That bridge is what makes images **display automatically in a notebook** and
interoperate with `ImageIO`, Swing, and any `Graphics2D` you already have. Alongside it, a small model
**registry** downloads the detector/recogniser weights the [detection](/object-detection) and
[recognition](/face-recognition) APIs need.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

## BufferedImage in and out

`Image.toBufferedImage` copies the image into a `BufferedImage` (grey → `TYPE_BYTE_GRAY`, colour →
`TYPE_3BYTE_BGR`); `Image.fromBufferedImage` does the reverse, from any `BufferedImage` type, into a
3-channel BGR image. Both are copies, so ownership is never ambiguous.

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

## Displaying in Almond / Jupyter

[Almond](https://almond.sh) (the Scala Jupyter kernel) renders a `BufferedImage` inline automatically. So the
one-liner to *see* a scalacv image in a notebook cell is just `toBufferedImage`:

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

Because `fromBufferedImage` accepts anything AWT can produce, you can also pull a frame in from `ImageIO`,
process it, and hand it back — scalacv slots into an existing imaging pipeline rather than replacing it.

## The model registry

The neural models — [YuNet](/object-detection#faces) for detection, [SFace](/face-recognition) for
recognition — are files you fetch once. [`Models`](/api/core/scalacv/Models$.html) generalises
`FaceDetect.downloadModel`: it downloads to a temp file beside the target and moves it into place only after
it verifies, so an interrupted run never strands a truncated model; and it is idempotent, so calling it at
start-up is free once the file is present.

```scala mdoc:compile-only
import java.nio.file.Paths

// Fetch the bundled specs into a cache directory:
val cache = Paths.get(sys.props("user.home"), ".cache", "scalacv-models")
for
  yunet <- Models.fetch(Models.YuNet, cache)
  sface <- Models.fetch(Models.SFace, cache)
yield (yunet, sface)
```

`Models.YuNet` pins a checksum (the same one [`FaceDetect`](/object-detection) verifies); `Models.SFace` has
none pinned and is downloaded as-is. For any other model, build your own [`ModelSpec`](/api/core/scalacv/ModelSpec.html)
with a file name, mirror URLs (`http(s)://` or `file://`), and an optional SHA-256:

```scala mdoc:silent
val custom = ModelSpec(
  fileName = "my_model.onnx",
  urls = Seq("https://example.com/my_model.onnx"),
  sha256 = Some("…")
)
```
