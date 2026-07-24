# Screen analysis

The staple of screen automation, visual testing and RPA is not "what is in this picture?" but two much
cheaper questions: **is this button / icon on screen, and where?**, and **what changed since the last
capture?**. Neither needs a model — both are ordinary template matching and differencing, and `Screen`
wraps them so every answer crosses the boundary as plain immutable data (a `TemplateMatch`, a `Rect`) that
outlives the images it came from.

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

> **The template needs contrast.** The helper *outlines* the square rather than filling a flat block on
> purpose: `Screen` matches with normalised correlation, which is **undefined for a template of one uniform
> colour** (a zero-variance patch has nothing to correlate against). A real button, icon or piece of text
> has plenty of variance; a solid rectangle of a single colour does not.

## `Screen` borrows, so you close

Every method on `Screen` **borrows** the images you pass — it reads them and returns data, it never takes
ownership. The images are still yours afterwards, and still yours to `close()`. That is the one rule to
keep in mind on this page: **close every `Image` you create**.

## Locating a single hit

`Screen.locate` finds the single best occurrence of a template and hands back an `Option[TemplateMatch]` —
`Some` when something matched at or above `minScore`, `None` when nothing did. A `TemplateMatch` is just a
`location` (the `Rect` the template was found at) and a `score`.

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

### `minScore` — how sure is sure

`minScore` is a normalised correlation in `[-1, 1]`: `1.0` is a pixel-perfect match, `0` is no linear
relationship, negatives are anti-correlation. In practice **`0.8` and up is a confident hit**, and it is
the default; drop it toward `0.6` to tolerate mild rescaling, compression or anti-aliasing, at the cost of
false positives. Two hard requirements sit underneath it:

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

`locate` is exactly `findAll(..., maxMatches = 1).headOption`, so reach for it whenever you only care about
the single best occurrence.

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
captures and returns the regions that changed — plain `Rect`s, **largest first** — with two knobs:
`threshold` (the per-pixel intensity delta that counts as changed) and `minArea` (changed blobs smaller
than this are noise and dropped).

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

```scala mdoc:invisible
before.close(); after.close()
```

The single reported `Rect` bounds the icon that appeared — the shared icon at `(40, 30)` is identical in
both frames and so contributes nothing. Feed captures that differ in size and `diff` throws rather than
guessing an alignment.

### One-shot diff vs. a running stream

`Screen.diff` is **stateless**: two captures in, changed regions out, nothing remembered. That is the right
tool for a before/after assertion in a visual test, or for polling a screen every few seconds. For a
*continuous* feed — a camera or screen recorder where you want motion tracked frame after frame against an
adaptive background — use the stateful [`MotionDetector`](/motion-detection) instead, which retains the
previous frame (or a background model) between calls.

---

**See also:** [Image API](/image-api) for reading captures and the transform/annotate chain ·
[Motion detection](/motion-detection) for tracking change across a live stream · [Drawing](/drawing) for
overlaying the hits · [Object detection](/object-detection) for when you need *what*, not *where* ·
[Cookbook](/cookbook) for copy-paste recipes.
