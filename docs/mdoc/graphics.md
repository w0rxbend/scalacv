# 2D graphics & creative coding

scalacv draws with a small compositional graphics language: a **`Picture`** is an immutable value you build,
style, and combine, then render onto an image. It is inspired by
[Doodle](https://github.com/creativescala/doodle) — the picture-as-a-value idea, `on`/transform composition,
`strokeColor`/`fillColor`/`strokeDash`, `beside`/`above` layout — adapted to scalacv's world: **image
(pixel) coordinates**, OpenCV rendering, and the same resource-safe [`Image`](/image-api) everything else
uses. The payoff is one vocabulary for **annotating a detection**, **plotting data**, and **making
generative art**.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
lazy val detector: org.opencv.objdetect.FaceDetectorYN = ??? // a YuNet model, from FaceDetect.create
```

## A picture is a value

Build it, then draw it — onto a fresh canvas with `render`, or onto an existing image with `image.draw`
(which consumes the image and returns the annotated one):

```scala mdoc
{
  val badge = Picture.circle(Point(50, 50), 30).fillColor(Color.Orange).strokeColor(Color.White).strokeWidth(3)
  badge.render(100, 100, Color.Black).bytes(".png").fold(_.getMessage, b => s"${b.length} bytes")
}
```

Nothing is drawn until `render`/`draw`; up to that point you are only building and combining values, so the
same picture can be reused, transformed, or laid out freely.

## The primitives

Every primitive is a `Picture`, so they all style, transform, and compose the same way.

| Kind | Factories |
| --- | --- |
| Basic | `circle`, `rectangle`, `roundedRectangle`, `line`, `polyline`, `polygon`, `text` |
| Round | `ellipse`, `arc`, `sector` (pie slice) |
| Curves | `curve` (cubic Bézier), `quadraticCurve` |
| Markers | `dot`, `marker`, `cross`, `arrow` |
| Shapes | `regularPolygon`, `star` |
| Composite | `label` (text on a filled box), `all` (group) |

The round shapes and curves are drawn as fine polylines, so they fill, dash, and transform with the same
uniform styling as anything else:

```scala mdoc
{
  val flower = Picture.all((0 until 6).map { i =>
    Picture.ellipse(Point(50, 50), 30, 12, rotation = i * 30).fillColor(Color.Pink.withAlpha(120)).noStroke
  })
  flower.render(100, 100, Color.DarkGray).bytes(".png").fold(_.getMessage, b => s"ellipse flower, ${b.length} bytes")
}
```

## Dashed and dotted strokes

OpenCV has no dashed line; `Picture` draws one by segmenting the path, so `dashed`, `dotted`, and any custom
`strokeDash(Dash(on, off))` just work — exactly what a **dotted detection box** wants:

```scala mdoc
{
  val box = Picture.roundedRectangle(Rect(20, 20, 60, 40), radius = 10).strokeColor(Color.Green).strokeWidth(2).dashed
  box.render(100, 80, Color.Black).bytes(".png").fold(_.getMessage, b => s"dashed rounded box, ${b.length} bytes")
}
```

## Labelling a detection

The most common annotation is a box with a readable tag. `Picture.label` draws text on a filled background
box (sized to the text), and everything composes into one overlay you draw on the frame:

```scala mdoc:silent
def annotate(box: Rect, tag: String): Picture =
  Picture.all(Seq(
    Picture.rectangle(box).strokeColor(Color.Green).strokeWidth(2).dashed,
    Picture.label(tag, Point(box.x, box.y - 20), Color.Black, Color.Green)
  ))
```

```scala mdoc:compile-only
// On a real detection:
Image.reading("crowd.jpg") { img =>
  val faces = img.faces(detector)
  img.draw(Picture.all(faces.map(f => annotate(f.box, f"${f.score}%.2f"))))
    .write("annotated.png")
}
```

## Composition, transforms & layout

`on`/`under` overlay; `at`/`translate`/`rotate`/`scale` move; styling set on a group is a default its members
inherit unless they override it. On top of that, scalacv adds Doodle-style **layout** — `beside`, `above`,
and `Picture.grid` — which measure each picture's [bounding box](/api/core/scalacv/Bounds.html) and place
them relative to one another, so you never hand-compute offsets:

```scala mdoc
{
  val a = Picture.circle(Point(20, 20), 18).fillColor(Color.Red).noStroke
  val b = Picture.rectangle(Rect(0, 0, 36, 36)).fillColor(Color.Blue).noStroke
  val c = Picture.star(Point(20, 20), 5, 18, 8).fillColor(Color.Yellow).noStroke
  val row = a.beside(b, gap = 12).beside(c, gap = 12)
  row.render(160, 60, Color.Black).bytes(".png").fold(_.getMessage, s => s"a laid-out row, ${s.length} bytes")
}
```

`Picture.grid(pictures, columns)` arranges a sequence into a grid — handy for a contact sheet of variations.

## Colour & palettes

[`Color`](/api/core/scalacv/Color.html) is RGBA with named colours, an `hsl` constructor, and a full set of
transforms: `lighten`/`darken`/`fadeOut`/`blend`, and `spin`/`complement`/`saturate`/`desaturate` for hue
work. Two palette generators cover the common needs — `Color.wheel(n)` for **distinct categorical** colours
and `Color.ramp(from, to, n)` for a **sequential** scale:

```scala mdoc
{
  val swatches = Color.wheel(6).zipWithIndex.map { (col, i) =>
    Picture.rectangle(Rect(i * 26 + 4, 10, 22, 40)).fillColor(col).noStroke
  }
  Picture.all(swatches).render(164, 60, Color.Black).bytes(".png").fold(_.getMessage, b => s"palette, ${b.length} bytes")
}
```

Alpha gives real transparency when a picture is drawn over an image — the highlight below tints the pixels
beneath it rather than replacing them:

```scala mdoc
{
  val highlight = Picture.rectangle(Rect(10, 10, 80, 40)).fillColor(Color.Yellow.withAlpha(90)).noStroke
  highlight.render(100, 60, Color.Blue).bytes(".png").fold(_.getMessage, b => s"translucent highlight, ${b.length} bytes")
}
```

## Data visualisation

Because pictures compose, charts are just pictures. [`Chart`](/api/core/scalacv/Chart$.html) covers `bars`,
`line`, `area`, `scatter`, `pie`, and `histogram` — render them standalone or drop one into a corner of a
frame with `chart.at(Point(x, y))`:

```scala mdoc
{
  val pie = Chart.pie(Seq(5, 3, 2, 4), 100, 100, Color.wheel(4))
  pie.render(100, 100, Color.DarkGray).bytes(".png").fold(_.getMessage, b => s"pie chart, ${b.length} bytes")
}
```

```scala mdoc
{
  val hist = Chart.histogram(Seq(1.0, 2, 2, 3, 3, 3, 3, 4, 4, 5), bins = 5, width = 200, height = 80)
  hist.render(200, 80, Color.DarkGray).bytes(".png").fold(_.getMessage, b => s"histogram, ${b.length} bytes")
}
```

## Animation

An animation is a `Picture` valued by frame number. `Animation.record` renders each frame and writes a video
through a [`Recorder`](/video); `Animation.gif` writes a shareable animated GIF; `Animation.frames` yields
owned `Image`s instead:

```scala mdoc:compile-only
// A shareable loop as an animated GIF:
Animation.gif("spin.gif", frames = 60, width = 320, height = 240, fps = 20) { i =>
  Picture.star(Point(160, 120), points = 5, outer = 90, inner = 40, rotation = i * 6)
    .strokeColor(Color.hsl(i * 6, 0.8, 0.6)).strokeWidth(3)
}

// Or a full-colour video, for longer or richer clips:
Animation.record("spin.mp4", frames = 300, width = 320, height = 240) { i =>
  Picture.regularPolygon(Point(160, 120), sides = 6, radius = 80, rotation = i * 2).strokeColor(Color.Cyan)
}
```

## On the Doodle inspiration

Doodle is a beautiful functional-graphics library, and its core idea — a picture is an immutable value you
compose, not a sequence of side-effecting draw calls — is the one worth borrowing. scalacv adapts rather than
copies it: coordinates are image pixels with y down (not Doodle's centred, y-up plane), rendering is OpenCV
onto a `Mat`, styling is contextual over the same `Picture` tree, layout measures bounding boxes in pixel
space, and the whole thing folds back into the move-semantics [`Image`](/image-api). What's added for *this*
library's job — annotating computer-vision output — is the part Doodle's canvas backends give for free but
OpenCV does not: hand-rolled dashed strokes, per-shape alpha compositing, and label boxes sized to their
text.
