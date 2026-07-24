# Geometry & typed values

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

Every OpenCV call speaks in coordinates, sizes, boxes and pixel values. scalacv gives those a small
family of **immutable Scala value types** — `Point`, `Size`, `Rect`, `Scalar` — plus a set of **typed
enums** that stand in for OpenCV's raw `int` constants. Everything on this page is a plain Scala value:
no natives are touched, so the snippets simply run and print their results.

## Why value types

`org.opencv.core.Point` and its siblings are mutable Java objects with public fields, and a
`Seq[org.opencv.core.Rect]` handed back from a detector is really a set of live handles into native
memory. Keep one around after the `Mat` it came from is freed and you are reading a dangling object.

scalacv copies at the boundary instead. A `Rect` is four ints in an ordinary case class, so a
`Seq[Rect]` from [object detection](/object-detection) stays valid — and stays *yours* — long after the
source image is released. Copying is cheap and it turns detector output into ordinary immutable data you
can pattern-match, put in a `Map`, or send across threads without a second thought.

```scala mdoc
val boxes = Seq(Rect(10, 10, 40, 30), Rect(80, 12, 25, 25))
boxes.sortBy(-_.area).map(_.topLeft)
```

## Point

A 2-D coordinate. The fields are `Double`, matching OpenCV, so sub-pixel positions survive.

```scala mdoc
Point(12, 8)
```

## Size

Width and height, and the one type here that guards its invariant: a negative extent is rejected at
construction.

```scala mdoc
Size(640, 480)
```

```scala mdoc:crash
Size(-1, 10) // require fails: a Size cannot be negative
```

## Rect

An axis-aligned box — `x`, `y`, `width`, `height` — with the three derived values you keep reaching for.
`area` is an `Int`; `topLeft` and `bottomRight` come back as `Point`s.

```scala mdoc
val r = Rect(10, 10, 40, 30)
r.area
```

```scala mdoc
(r.topLeft, r.bottomRight)
```

`Rect` is what [`crop`](/image-api) takes and what the detectors return, so it is the type you will see
most often.

## Scalar

A pixel value: up to four channel components `v0`–`v3`, with the trailing ones defaulting to `0`. The
important thing to internalise is the channel order — **OpenCV Mats are BGR by default, not RGB.** The
named constants are ordered accordingly.

```scala mdoc
Scalar.Red    // (0, 0, 255) — blue and green zero, red full
```

```scala mdoc
(Scalar.Black, Scalar.White, Scalar.Green, Scalar.Blue)
```

So the "red" you pass to a [drawing](/drawing) call is `Scalar(0, 0, 255)`; write `Scalar(255, 0, 0)`
and you get blue. Build your own for any value — a single component works for a grey/1-channel image:

```scala mdoc
Scalar(200) // mid-grey for a single-channel Mat
```

Like the others, `Scalar` is copied *out of* the native object at the boundary, so the value you hold is
never a view onto a `Mat` that might change or be freed underneath you.

## Typed enums: no raw int constants

OpenCV's Java API is a wall of bare integers — `Imgproc.COLOR_BGR2GRAY`, `Imgproc.INTER_LINEAR`,
`Imgproc.RETR_TREE`. They are untyped, unchecked, and trivially swappable: nothing stops you passing a
line-type constant where a font was wanted. scalacv's public API takes typed enums instead, and only
converts to the underlying `int` at the last moment. That `int` is always available as `.cvValue` if you
need to drop to a raw `org.opencv.*` call.

```scala mdoc
ColorConversion.BgrToGray.cvValue
```

```scala mdoc
(Interpolation.Linear.cvValue, LineType.AntiAliased.cvValue, Font.Simplex.cvValue)
```

### The true enumerations

Six of these are genuine enumerations — a value is exactly one of the cases, and `.cvValue` is a plain
`Int`:

| Enum | Stands in for | Used by |
|---|---|---|
| `ColorConversion` | `Imgproc.COLOR_*` | [`cvtColor` / `gray`](/image-processing) |
| `Interpolation` | `Imgproc.INTER_*` | resize and warps |
| `LineType` | `Imgproc.LINE_*` | [drawing](/drawing) |
| `Font` | `Imgproc.FONT_HERSHEY_*` | [`putText` / `drawText`](/drawing) |
| `ContourRetrieval` | `Imgproc.RETR_*` | [`findContours`](/object-detection) |
| `ContourApproximation` | `Imgproc.CHAIN_APPROX_*` | [`findContours`](/object-detection) |
| `BorderType` | `Core.BORDER_*` | padded [image processing](/image-processing) |

Because they are Scala 3 `enum`s you get the usual perks — exhaustive `match`, `.values`, and a name for
every case:

```scala mdoc
ContourRetrieval.values.map(c => c.toString -> c.cvValue).toList
```

### The structured types: `ImreadFlags` and `Threshold`

Not every OpenCV flag family is a single *choice*, but the two that are not need different shapes — one is
a genuine bitmask, the other only looks like one.

**`ImreadFlags`** — a decode `color` (how many channels / what depth) with an optional `scale` (decode at
reduced resolution) and an `ignoreOrientation` flag (skip the EXIF rotation). OpenCV's `IMREAD_*`
constants *look* OR-able but are not: each `IMREAD_REDUCED_*` value already bakes in its colour bit and
`IMREAD_UNCHANGED` is `-1`, so OR-ing a colour with a reduced-size flag silently decodes the wrong image.
So the `(color, scale)` pair maps *totally* onto exactly one named constant rather than composing, and
only `ignoreOrientation` (bit 128) is a real independent flag OR-ed on top:

```scala mdoc
ImreadFlags(ImreadColor.Color, ImreadScale.Half, ignoreOrientation = true).cvValue
```

Reduced-size decode exists only for `Grayscale` and `Color`, and `Unchanged` can carry no extra bit at
all — the combinations OpenCV has no constant for are rejected at construction rather than quietly
OR-ed into something else:

```scala mdoc:crash
ImreadFlags(ImreadColor.Unchanged, ImreadScale.Half) // require fails: -1 admits no reduction
```

The common cases have ready-made constants:

```scala mdoc
(ImreadFlags.Color.cvValue, ImreadFlags.Grayscale.cvValue, ImreadFlags.Unchanged.cvValue)
```

**`Threshold`** — a `Mode` (binary, truncate, to-zero, …) OR-ed with *at most one* automatic-threshold
`Auto` modifier (Otsu or Triangle). The modifier is an `Option`, because the two auto methods are
mutually exclusive and most calls use neither:

```scala mdoc
Threshold(Threshold.Mode.Binary).cvValue // a fixed threshold
```

```scala mdoc
val t = Threshold.otsu() // Binary | THRESH_OTSU — let OpenCV pick the level
(t.cvValue, t.computesThreshold)
```

`computesThreshold` is `true` exactly when an `Auto` is present — that is when OpenCV computes the level
itself and the value it returns (surfaced as `ThresholdResult`) actually means something, rather than
echoing back the fixed number you supplied. See [image processing](/image-processing) for `threshold` in
action.

Neither of these is a single `enum` on purpose, for opposite reasons. `Threshold` is a real bitmask:
`THRESH_BINARY | THRESH_OTSU` is a *combination*, not an alternative, and a single-choice enum could not
express it without also admitting nonsense like a bare `THRESH_MASK` leaking into the public API.
`ImreadFlags` is the reverse trap — its constants look OR-able but are not, so instead of exposing bits it
maps a small structured value totally onto one named constant and rejects the combinations OpenCV cannot
represent.
