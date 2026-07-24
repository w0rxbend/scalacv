# 2D graphics & creative coding

scalacv draws with a small compositional graphics language: a **`Picture`** is an immutable value you build,
style, and combine, then render onto an image. It is inspired by
[Doodle](https://github.com/creativescala/doodle) — the picture-as-a-value idea, `on`/transform composition,
`strokeColor`/`fillColor`/`strokeDash` — adapted to scalacv's world: **image (pixel) coordinates**, OpenCV
rendering, and the same resource-safe [`Image`](/image-api) everything else uses. The payoff is one vocabulary
for annotating a detection, plotting data, and making generative art.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
lazy val detector: org.opencv.objdetect.FaceDetectorYN = ??? // a YuNet model, from FaceDetect.create
```

## A picture is a value

Build it, then draw it — onto a fresh canvas with `render`, or onto an existing image with `image.draw`:

```scala mdoc
{
  val badge = Picture.circle(Point(50, 50), 30).fillColor(Color.Orange).strokeColor(Color.White).strokeWidth(3)
  badge.render(100, 100, Color.Black).bytes(".png").fold(_.getMessage, b => s"${b.length} bytes")
}
```

The primitives — `circle`, `rectangle`, `line`, `polyline`/`polygon`, `text`, `dot`/`marker`/`cross`, `arrow`,
`regularPolygon`, `star`, and `all` to group — are all `Picture`s, so they compose the same way.

## Dashed and dotted strokes

OpenCV has no dashed line; `Picture` draws one by segmenting the path, so `dashed`, `dotted`, and any custom
`strokeDash(Dash(on, off))` just work — exactly what a **dotted detection box** wants:

```scala mdoc
{
  val box = Picture.rectangle(Rect(20, 20, 60, 40)).strokeColor(Color.Green).strokeWidth(2).dashed
  box.render(100, 80, Color.Black).bytes(".png").fold(_.getMessage, b => s"dashed box, ${b.length} bytes")
}
```

## Composition, styling & transforms

`on` overlays; `at`/`translate`/`rotate`/`scale` move; styling set on a group is a default its members
inherit unless they set their own. Here is a labelled, dashed overlay — the face-detection annotation:

```scala mdoc:silent
def annotate(box: Rect, label: String): Picture =
  Picture.all(Seq(
    Picture.rectangle(box).dashed,
    Picture.text(label, Point(box.x.toDouble, box.y - 6.0)).fontScale(0.5)
  )).strokeColor(Color.Green).strokeWidth(2) // green is the group default
```

```scala mdoc:compile-only
// On a real detection:
Image.reading("crowd.jpg") { img =>
  val faces = img.faces(detector)
  img.draw(Picture.all(faces.map(f => annotate(f.box, f"${f.score}%.2f"))))
    .write("annotated.png")
}
```

## Colour

`Color` is RGBA with named colours, an `hsl` constructor for generating palettes, and `lighten` / `darken` /
`fadeOut` / `blend`. Alpha gives real transparency when a picture is drawn over an image:

```scala mdoc
{
  val highlight = Picture.rectangle(Rect(10, 10, 80, 40)).fillColor(Color.Yellow.withAlpha(90)).noStroke
  highlight.render(100, 60, Color.Blue).bytes(".png").fold(_.getMessage, b => s"translucent highlight, ${b.length} bytes")
}
```

## Data visualisation

Because pictures compose, charts are just pictures — `Chart.bars`, `Chart.line`, `Chart.scatter` — that you
can render standalone or overlay in a corner of a frame:

```scala mdoc
{
  val chart = Chart.bars(Seq(3.0, 7.0, 4.0, 9.0, 5.0), 200, 80, Color.Cyan)
  chart.render(200, 80, Color.DarkGray).bytes(".png").fold(_.getMessage, b => s"bar chart, ${b.length} bytes")
}
```

`Chart.line(values, …)` and `Chart.scatter(points, …)` work the same way, and `chart.at(Point(10, 10))` drops
one onto a live frame.

## Animation

An animation is a `Picture` valued by frame number. `Animation.record` renders each frame and writes a video
through a [`Recorder`](/video); `Animation.frames` yields owned `Image`s instead:

```scala mdoc:compile-only
Animation.record("spin.mp4", frames = 60, width = 320, height = 240) { i =>
  Picture.star(Point(160, 120), points = 5, outer = 90, inner = 40, rotation = i * 6)
    .strokeColor(Color.hsl(i * 6, 0.8, 0.6)).strokeWidth(3)
}
```

## On the Doodle inspiration

Doodle is a beautiful functional-graphics library, and its core idea — a picture is an immutable value you
compose, not a sequence of side-effecting draw calls — is the one worth borrowing. scalacv adapts rather than
copies it: coordinates are image pixels with y down (not Doodle's centred, y-up plane), rendering is OpenCV
onto a `Mat`, styling is contextual over the same `Picture` tree, and the whole thing folds back into the
move-semantics `Image`. What's added for *this* library's job — annotating computer-vision output — is the
part Doodle's canvas backends give for free but OpenCV does not: hand-rolled dashed strokes and per-shape
alpha compositing.
