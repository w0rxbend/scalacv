# Geometry & typed values

Every OpenCV call speaks in coordinates, sizes, boxes and pixel values. scalacv gives those a small family of
**immutable Scala value types** — `Point`, `Point3`, `Size`, `Rect`, `Scalar` — plus a set of **typed enums**
that stand in for OpenCV's raw `int` constants. These are the vocabulary the rest of the library is written
in: a detector hands back `Seq[Rect]`, a drawing call takes a `Scalar` and a `Thickness`, a resize takes an
`Interpolation`. Learn them once here and every other page reads more easily.

Everything on this page is a plain Scala value: no natives are touched, so the snippets simply run and print
their results.

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

## Why value types

`org.opencv.core.Point` and its siblings are mutable Java objects with public fields, and a
`Seq[org.opencv.core.Rect]` handed back from a detector is really a set of live handles into native memory.
Keep one around after the `Mat` it came from is freed and you are reading a dangling object.

scalacv copies at the boundary instead. A `Rect` is four ints in an ordinary case class, so a `Seq[Rect]` from
[object detection](/object-detection) stays valid — and stays *yours* — long after the source image is
released. Copying is cheap and it turns detector output into ordinary immutable data you can pattern-match, put
in a `Map`, or send across threads without a second thought (see [concurrency](/concurrency)).

```scala mdoc
val boxes = Seq(Rect(10, 10, 40, 30), Rect(80, 12, 25, 25))
boxes.sortBy(-_.area).map(_.topLeft)
```

Here is the whole family at a glance:

| Type | Fields | Element type | Guards its invariant? |
|---|---|---|---|
| `Point` | `x`, `y` | `Double` | no |
| `Point3` | `x`, `y`, `z` | `Double` | no |
| `Size` | `width`, `height` | `Double` | yes — no negative extent |
| `Rect` | `x`, `y`, `width`, `height` | `Int` | yes — no negative extent |
| `Scalar` | `v0`–`v3` | `Double` | no |

## Point

A 2-D coordinate, origin top-left, `x` right and `y` down. The fields are `Double`, matching OpenCV, so the
sub-pixel positions that feature and contour work produce survive intact.

```scala mdoc
Point(12, 8)
```

## Point3

A point in 3-D space — a model coordinate for [AR / pose](/pose-estimation) work, in the same units you give a
marker's side length (metres is the usual choice). `z` points out of the marker plane toward the camera.

```scala mdoc
Point3(0.05, 0.05, 0.0)
```

You rarely build these by hand except to describe a known 3-D object (the corners of a marker, a calibration
rig); [pose estimation](/pose-estimation) and [marker AR](/marker-ar) are where they show up.

## Size

Width and height, and one of the two types here that guards its invariant: a negative extent is rejected at
construction (a *zero* extent is allowed — an empty size is meaningful).

```scala mdoc
Size(640, 480)
```

```scala mdoc:crash
Size(-1, 10) // require fails: a Size cannot be negative
```

`Size` is what `resize`/`gaussianBlur` kernels take; the fields are `Double` because OpenCV's `cv::Size` is,
though for pixel dimensions you will pass whole numbers.

## Rect

An axis-aligned box — `x`, `y`, `width`, `height` — with three derived values you keep reaching for. The origin
`(x, y)` may be negative (a region of interest can extend past the top-left of the image); the extent may not.

```scala mdoc
val r = Rect(10, 10, 40, 30)
r.area
```

```scala mdoc
(r.topLeft, r.bottomRight)
```

- **`area: Long`** — `width * height`. It is a `Long`, not an `Int`, because the product overflows a signed
  `Int` past roughly a 46340-pixel side — an easy limit to hit on a full-frame ROI of a large image.
- **`topLeft: Point`** — the corner `(x, y)`.
- **`bottomRight: Point`** — `(x + width, y + height)`, one past the last enclosed pixel.

`Rect` is what [`crop`](/image-api) takes and what the detectors return, so it is the type you will see most
often. Note the corner is `(x, y)` and the size is `(width, height)` — a different shape from `Point`/`Size`,
which trips people up until it doesn't.

## Scalar

A pixel value: up to four channel components `v0`–`v3`, with the trailing ones defaulting to `0`. The important
thing to internalise is the channel order — **OpenCV Mats are BGR by default, not RGB.** The named constants
are ordered accordingly.

```scala mdoc
Scalar.Red    // (0, 0, 255) — blue and green zero, red full
```

```scala mdoc
(Scalar.Black, Scalar.White, Scalar.Green, Scalar.Blue)
```

So the "red" you pass to a [drawing](/drawing) call is `Scalar(0, 0, 255)`; write `Scalar(255, 0, 0)` and you
get blue. Build your own for any value — a single component works for a grey/1-channel image, and the fourth
channel is the alpha of a BGRA image:

```scala mdoc
Scalar(200) // mid-grey for a single-channel Mat
```

```scala mdoc
Scalar(0, 0, 255, 128) // semi-transparent red in a BGRA image
```

:::warning BGR, not RGB
This is the single most common colour bug. If your reds come out blue, you almost certainly passed
`Scalar(255, 0, 0)` thinking in RGB. Use the named `Scalar.Red`/`Scalar.Blue`/… constants where you can, and
remember the order is **B, G, R (, A)**.
:::

Like the others, `Scalar` is copied *out of* the native object at the boundary, so the value you hold is never
a view onto a `Mat` that might change or be freed underneath you.

## Typed enums: no raw int constants

OpenCV's Java API is a wall of bare integers — `Imgproc.COLOR_BGR2GRAY`, `Imgproc.INTER_LINEAR`,
`Imgproc.RETR_TREE`. They are untyped, unchecked, and trivially swappable: nothing stops you passing a
line-type constant where a font was wanted. scalacv's public API takes typed enums instead, and only converts
to the underlying `int` at the last moment. That `int` is always available as `.cvValue` if you need to drop to
a raw `org.opencv.*` call — the escape hatch is a door, not a wall.

```scala mdoc
ColorConversion.BgrToGray.cvValue
```

```scala mdoc
(Interpolation.Linear.cvValue, LineType.AntiAliased.cvValue, Font.Simplex.cvValue)
```

### The true enumerations

Most of these are genuine enumerations — a value is exactly one of the cases, and `.cvValue` is a plain `Int`:

| Enum | Stands in for | Used by |
|---|---|---|
| `ColorConversion` | `Imgproc.COLOR_*` | [`cvtColor` / `gray`](/image-processing) |
| `Interpolation` | `Imgproc.INTER_*` | resize and [warps](/transforms) |
| `LineType` | `Imgproc.LINE_*` | [drawing](/drawing) |
| `Font` | `Imgproc.FONT_HERSHEY_*` | [`putText` / `drawText`](/drawing) |
| `ContourRetrieval` | `Imgproc.RETR_*` | [`findContours`](/contours) |
| `ContourApproximation` | `Imgproc.CHAIN_APPROX_*` | [`findContours`](/contours) |
| `BorderType` | `Core.BORDER_*` | padded [image processing](/image-processing) |
| `Flip` | flip codes `1`/`0`/`-1` | [transforms](/transforms) — mirror |
| `Rotation` | `Core.ROTATE_*` | [transforms](/transforms) — lossless quarter-turns |
| `MorphShape` | `Imgproc.MORPH_RECT/ELLIPSE/CROSS` | erode / dilate / [morphology](/image-processing) |
| `MorphOp` | `Imgproc.MORPH_OPEN/CLOSE/…` | compound [morphology](/image-processing) |
| `AdaptiveMethod` | `Imgproc.ADAPTIVE_THRESH_*` | adaptive [threshold](/image-processing) |
| `Colormap` | `Imgproc.COLORMAP_*` | false-colour [image processing](/image-processing) |

Because they are Scala 3 `enum`s you get the usual perks — exhaustive `match`, `.values`, and a name for every
case:

```scala mdoc
ContourRetrieval.values.map(c => c.toString -> c.cvValue).toList
```

```scala mdoc
Interpolation.values.map(_.toString).toList
```

:::tip Some enums are named for the *effect*, not OpenCV's code
`Flip` is `Horizontal` / `Vertical` / `Both` — the visible result — rather than OpenCV's axis-centric flip code
`1` / `0` / `-1`, which nobody remembers. `Rotation` is `Clockwise` / `CounterClockwise` / `Half`. The typed
name is chosen so the call site reads as what it does; `.cvValue` still gives you the raw code.
:::

### The structured types: `ImreadFlags` and `Threshold`

Not every OpenCV flag family is a single *choice*, but the two that are not need different shapes — one is a
genuine bitmask, the other only looks like one.

**`ImreadFlags`** — a decode `color` (how many channels / what depth) with an optional `scale` (decode at
reduced resolution) and an `ignoreOrientation` flag (skip the EXIF rotation). OpenCV's `IMREAD_*` constants
*look* OR-able but are not: each `IMREAD_REDUCED_*` value already bakes in its colour bit and `IMREAD_UNCHANGED`
is `-1`, so OR-ing a colour with a reduced-size flag silently decodes the wrong image. So the `(color, scale)`
pair maps *totally* onto exactly one named constant rather than composing, and only `ignoreOrientation` (bit
128) is a real independent flag OR-ed on top:

```scala mdoc
ImreadFlags(ImreadColor.Color, ImreadScale.Half, ignoreOrientation = true).cvValue
```

Reduced-size decode exists only for `Grayscale` and `Color`, and `Unchanged` can carry no extra bit at all —
the combinations OpenCV has no constant for are rejected at construction rather than quietly OR-ed into
something else:

```scala mdoc:crash
ImreadFlags(ImreadColor.Unchanged, ImreadScale.Half) // require fails: -1 admits no reduction
```

The common cases have ready-made constants:

```scala mdoc
(ImreadFlags.Color.cvValue, ImreadFlags.Grayscale.cvValue, ImreadFlags.Unchanged.cvValue)
```

`ImreadScale` is a small enum in its own right — the fraction of full resolution to decode at, cheaper than a
full read followed by a resize because the codec skips the discarded detail:

```scala mdoc
ImreadScale.values.map(s => s.toString -> s.denom).toList
```

**`Threshold`** — a `Mode` (binary, truncate, to-zero, …) OR-ed with *at most one* automatic-threshold `Auto`
modifier (Otsu or Triangle). The modifier is an `Option`, because the two auto methods are mutually exclusive
and most calls use neither:

```scala mdoc
Threshold(Threshold.Mode.Binary).cvValue // a fixed threshold
```

```scala mdoc
val t = Threshold.otsu() // Binary | THRESH_OTSU — let OpenCV pick the level
(t.cvValue, t.computesThreshold)
```

`computesThreshold` is `true` exactly when an `Auto` is present — that is when OpenCV computes the level itself
and the value it returns (surfaced as `ThresholdResult`) actually means something, rather than echoing back the
fixed number you supplied. See [image processing](/image-processing) for `threshold` in action.

Neither of these is a single `enum` on purpose, for opposite reasons. `Threshold` is a real bitmask:
`THRESH_BINARY | THRESH_OTSU` is a *combination*, not an alternative, and a single-choice enum could not express
it without also admitting nonsense like a bare `THRESH_MASK` leaking into the public API. `ImreadFlags` is the
reverse trap — its constants look OR-able but are not, so instead of exposing bits it maps a small structured
value totally onto one named constant and rejects the combinations OpenCV cannot represent.

## Thickness — the drawing companion

One more typed value lives with [drawing](/drawing) but belongs to the same family: `Thickness`, which is
either a `Stroke(pixels)` outline or the `Filled` sentinel. OpenCV encodes "filled" as a thickness of `-1`, a
value ordinary arithmetic can produce by accident and which aborts native code if handed to a line or text.
Splitting the two into distinct types means the mistake stops compiling instead of crashing:

```scala mdoc
(Thickness.Default.cvValue, Thickness.Stroke(3).cvValue, Thickness.Filled.cvValue)
```

```scala mdoc:crash
Thickness.Stroke(0) // require fails: a stroke must be at least one pixel wide
```

## Next

- [The Image API](/image-api) — where `Rect`, `Scalar`, and the enums are consumed
- [Drawing](/drawing) — `Scalar`, `Thickness`, `Font`, `LineType` in action
- [Image processing](/image-processing) — `ColorConversion`, `Threshold`, `BorderType`, morphology
