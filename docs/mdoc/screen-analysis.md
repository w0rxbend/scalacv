# Screen analysis

The staple of screen automation, visual testing and RPA is not "what is in this picture?" but two much
cheaper questions: **is this button / icon on screen, and where?**, and **what changed since the last
capture?**. Neither needs a model — both are ordinary template matching and differencing, and `Screen`
wraps them so every answer crosses the boundary as plain immutable data (a `TemplateMatch`, a `Rect`) that
outlives the images it came from.

:::tip The two questions, the two calls
`Screen.locate(screen, button)` answers *where is it?* with an `Option[TemplateMatch]`.
`Screen.diff(before, after)` answers *what changed?* with a `Seq[Rect]`. Everything else on this page is
tuning those two.
:::

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
// A synthetic "screenshot": grey with a distinctive outlined white square (contrast to match on).
def screen(x: Int, y: Int): Image =
  Image.blank(160, 120, Scalar(50, 50, 50))
    .drawRect(Rect(x, y, 20, 20), Scalar.White, Thickness.Filled)
    .drawRect(Rect(x, y, 20, 20), Scalar.Black, Thickness.Stroke(2))
```

Every runnable example below builds its scene with one helper — a dark 160×120 "screenshot" with a
20×20 white square, outlined in black, at a position you choose — so the code runs against real pixels with
no fixture file. In your own program the captures come from [`Image.read("screenshot.png")`](/image-api) or
a live camera / screen grab; the synthetic screens here just make the page executable.

```scala
def screen(x: Int, y: Int): Image =
  Image.blank(160, 120, Scalar(50, 50, 50))
    .drawRect(Rect(x, y, 20, 20), Scalar.White, Thickness.Filled)
    .drawRect(Rect(x, y, 20, 20), Scalar.Black, Thickness.Stroke(2))
```

:::warning The template needs contrast
The helper *outlines* the square rather than filling a flat block on purpose: `Screen` matches with
normalised correlation, which is **undefined for a template of one uniform colour** (a zero-variance patch
has nothing to correlate against). A real button, icon or piece of text has plenty of variance; a solid
rectangle of a single colour does not.
:::

## What each method gives you

| Method | Answers | Returns | Throws when |
| --- | --- | --- | --- |
| `Screen.locate(img, tmpl)` | where is the single best hit? | `Option[TemplateMatch]` | template larger than image |
| `Screen.findAll(img, tmpl)` | where is *every* hit? | `Seq[TemplateMatch]` (best first) | template larger than image; `maxMatches < 1` |
| `Screen.diff(before, after)` | what regions changed? | `Seq[Rect]` (largest first) | captures differ in size |

A `TemplateMatch` is just a `location` (the `Rect` the template was found at) and a `score`.

## `Screen` borrows, so you close

Every method on `Screen` **borrows** the images you pass — it reads them and returns data, it never takes
ownership. The images are still yours afterwards, and still yours to `close()`. That is the one rule to
keep in mind on this page: **close every `Image` you create**. (Contrast with [`Image` transforms](/mat-lifecycle),
which *consume* their receiver — `Screen` methods are queries, not transforms.)

## Locating a single hit

`Screen.locate` finds the single best occurrence of a template and hands back an `Option[TemplateMatch]` —
`Some` when something matched at or above `minScore`, `None` when nothing did.

Here we cut a known icon out of a copy of the screen and then locate it back — the round trip a visual test
makes when it asks "is this control where I left it?":

```scala mdoc:silent
val shot = screen(70, 50)
// Cut the icon out of a *copy* so `shot` stays alive to search in.
val template = shot.copy.crop(Rect(66, 46, 28, 28))
```

```scala mdoc
val best = Screen.locate(shot, template)
```

The result is real `mdoc` output: a `Some(TemplateMatch(...))` whose `location` sits where the icon really
is and whose `score` is close to `1.0`, because the template was cut from these very pixels.

```scala mdoc
best.map(m => (m.location, m.score))
```

Since the template came from these exact pixels, the score is a near-perfect `1.0`:

```scala mdoc
best.map(_.score > 0.99).getOrElse(false)
```

### `minScore` — how sure is sure

`minScore` is a normalised correlation in `[-1, 1]`: `1.0` is a pixel-perfect match, `0` is no linear
relationship, negatives are anti-correlation.

| `minScore` | Behaviour | Use when |
| --- | --- | --- |
| `0.95`+ | near-exact only | the template is a byte-for-byte crop of the same asset |
| `0.8` (default) | a confident hit | the normal case — same widget, same theme |
| `0.6`–`0.75` | tolerant | mild rescaling, JPEG compression, anti-aliasing differ |
| `< 0.6` | loose | expect false positives; usually a sign the template is wrong |

Two hard requirements sit underneath `minScore`:

- the template must be **no larger** than the image (`locate` throws otherwise — a template bigger than the
  haystack is a programmer error, not a miss), and
- the template must have **contrast** (see the note above).

## Finding every hit

When the same icon can appear more than once — a row of identical buttons, every instance of a status
light — `Screen.findAll` returns all of them, best first, as a `Seq[TemplateMatch]`. After each hit its
footprint is suppressed so the next iteration finds a *different* location rather than reporting the same
peak twice; `maxMatches` caps how many it will return.

This desktop has two copies of the icon:

```scala mdoc:silent
val desktop = screen(30, 20)
  .drawRect(Rect(110, 80, 20, 20), Scalar.White, Thickness.Filled)
  .drawRect(Rect(110, 80, 20, 20), Scalar.Black, Thickness.Stroke(2))
```

```scala mdoc
val hits = Screen.findAll(desktop, template, minScore = 0.8, maxMatches = 10)
```

Both are found, and their locations line up with where we drew the squares:

```scala mdoc
hits.map(_.location)
```

The count matches the two icons we painted:

```scala mdoc
hits.size
```

:::note `locate` is `findAll` capped at one
`locate(img, tmpl)` is exactly `findAll(img, tmpl, maxMatches = 1).headOption`, so reach for it whenever
you only care about the single best occurrence. `maxMatches` (default `20`) exists so a noisy image with
many near-peaks does not run the suppression loop forever.
:::

## Annotating what you found

Because a `TemplateMatch` carries a plain `Rect`, drawing the hits is a one-liner: pull the locations out
and hand them to [`drawRects`](/drawing). Take a `copy` first so the original capture stays intact for
whatever comes next:

```scala mdoc
desktop.copy.drawRects(hits.map(_.location)).bytes(".png").map(_.length)
```

```scala mdoc:invisible
shot.close(); template.close(); desktop.close()
```

`drawRects` consumes the copy and `bytes` releases it, so nothing leaks; `shot`, `template` and `desktop`
are closed once the examples above are done with them.

## Change detection

The other half of screen analysis is spotting *what moved*. `Screen.diff` compares two **same-size**
captures and returns the regions that changed — plain `Rect`s, **largest first**.

| Knob | Default | What it does |
| --- | --- | --- |
| `threshold` | `25` | per-pixel intensity delta that counts as changed — lower catches subtler change, higher ignores noise/compression |
| `minArea` | `100` | changed blobs smaller than this many pixels² are dropped as noise |

Under the hood `diff` takes the absolute difference of the two frames, greyscales it, thresholds it,
dilates to merge neighbouring changed pixels, then returns the bounding rectangles of the resulting blobs.

Here `after` is the same screen as `before` with one extra icon painted on; `diff` isolates just that new
region and leaves the unchanged icon alone:

```scala mdoc:silent
val before = screen(40, 30)
val after = screen(40, 30)
  .drawRect(Rect(110, 80, 20, 20), Scalar.White, Thickness.Filled)
  .drawRect(Rect(110, 80, 20, 20), Scalar.Black, Thickness.Stroke(2))
```

```scala mdoc
val changed = Screen.diff(before, after)
```

Exactly one region changed — the icon that appeared — since the shared icon at `(40, 30)` is identical in
both frames:

```scala mdoc
changed.size
```

```scala mdoc:invisible
before.close(); after.close()
```

The single reported `Rect` bounds the icon that appeared. Feed captures that differ in size and `diff`
throws rather than guessing an alignment.

:::tip Tuning the two knobs
Getting *too many* regions from a live capture? Raise `threshold` (compression and sub-pixel jitter add
low-amplitude noise) or `minArea` (drop the specks). Getting *too few*, or one giant merged blob? Lower
`threshold` to catch subtler change; the internal dilation merges anything close, so widely-separated
changes stay separate while adjacent ones fuse.
:::

## One-shot diff vs. a running stream

`Screen.diff` is **stateless**: two captures in, changed regions out, nothing remembered. That is the right
tool for a before/after assertion in a visual test, or for polling a screen every few seconds:

```scala
// Poll pattern: capture, diff against the last frame, act on change.
var last: Image = grabScreenshot()
while running do
  val now = grabScreenshot()
  val regions = Screen.diff(last, now)
  if regions.nonEmpty then react(regions)
  last.close()
  last = now
```

For a *continuous* feed — a camera or screen recorder where you want motion tracked frame after frame
against an adaptive background — use the stateful [`MotionDetector`](/motion-detection) instead, which
retains the previous frame (or a background model) between calls.

| You want | Reach for |
| --- | --- |
| a before/after assertion in a test | `Screen.diff` |
| poll a screen every few seconds | `Screen.diff` in a loop |
| track motion continuously against an adapting background | [`MotionDetector`](/motion-detection) |
| find *what* the object is, not just where it changed | [object detection](/object-detection) |

## A worked pattern: wait for an element

Template matching plus polling is the whole of "wait until this button appears, then click it" — the core
loop of any UI automation. `locate` returns `None` until the element is on screen, `Some` once it is:

```scala
def waitFor(template: Image, timeoutMs: Long): Option[TemplateMatch] =
  val deadline = System.currentTimeMillis() + timeoutMs
  var found = Option.empty[TemplateMatch]
  while found.isEmpty && System.currentTimeMillis() < deadline do
    val shot = grabScreenshot()
    found = Screen.locate(shot, template, minScore = 0.85)
    shot.close()
  found

// waitFor(buttonTemplate, 5000).foreach(m => clickAt(m.location.topLeft))
```

A `Rect` gives you `topLeft` and `bottomRight` as `Point`s, so a hit hands you the corners to aim a click
at directly.

## Next

- [Image API](/image-api) — reading captures and the transform / annotate chain the results feed into.
- [Motion detection](/motion-detection) — the stateful counterpart to `diff` for a live stream.
- [Object detection](/object-detection) — for when you need *what* is on screen, not just *where* it changed.
