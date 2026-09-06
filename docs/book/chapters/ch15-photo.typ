#import "../lib/book.typ": *

#chapter("Photographic and Stylisation Operations", subtitle: [The verbs judged by eye rather than by assertion, and what they cost you.])

A local-history archive hands you four thousand scanned prints and a flatbed that will not be
operated by a human again. The scans come off it faded, warm where the dye has shifted over sixty
years, and speckled with dust that the glass picked up somewhere around print six hundred. The
archive wants two things out of each file. It wants a picture a visitor will look at on a screen,
and it wants a record a program can search --- who is in it, what is written on the back, which of
the four thousand are the same building from different years.

Those two wants pull in opposite directions, and that tension is the whole subject of this chapter.
Every operation the book has covered so far was in service of measurement. A blur exists to make a
threshold behave; a threshold exists to make contours findable; contours exist to produce numbers.
You can write an assertion about each of them: this many components, that bounding box, this angle.
The operations in this chapter have no such test. `sepia` is correct when
it looks like sepia. `stylize` is correct when a person says the picture looks painted. The only
honest acceptance criterion is somebody's eye, and the library says so by giving them defaults that
look reasonable and leaving the tuning to you.

That does not make them decorative --- `inpaint`, `blend` and `seamlessCloneInto` repair and
composite pixels for reasons unrelated to taste, and `colorMap` is the only way to make a
single-channel result visible at all. But it does mean the group needs a different kind of care.
Several of these calls are among the most expensive things you can ask scalacv to do, and they are
exactly the calls somebody tries once on a still, likes, and drops into a thirty-frames-a-second
loop. Several destroy, on purpose, the structure a detector downstream was relying on. Neither
failure announces itself.

Everything here is a transform in the sense Chapter 4, #emph[The Image Type], set out: the call consumes
the `Image` it was made on, hands back a new one, and releases the old `Mat` on the way through. A
chain of eight grades holds one live buffer, not eight. Where a call takes a second image --- a
mask, a background, the other side of a blend --- that argument is #emph[borrowed], and closing it
stays your job.

#note[
The identifiers follow OpenCV's American spelling while the prose follows the library's own
documentation, so the page says "colour map" and the code says `colorMap`. The same split catches
`gray`, `stylize`, `posterize` and `Colormap`.
]

#sect("Tone and colour")

The tone grades are the cheap half of the chapter. Most of them are a single pass over the pixel
data with no neighbourhood at all, which is why you can afford them per frame when you cannot afford
anything in the next section.

`gamma(g)` and `posterize(levels)` are both a 256-entry lookup table, built in Scala and applied to
every channel with one `Core.LUT` call. `gamma` fills the table with
`pow(i / 255.0, 1.0 / g) * 255`, so `g` below 1 darkens the mid-tones and above 1 lifts them, while
pure black and pure white stay where they were --- the curve is pinned at both ends. That is the
difference between it and `adjust(brightness, contrast)`, which multiplies and adds and therefore
clips as soon as it runs out of range. Useful values sit between about 0.7 and 1.4; the method
`require`s only that `g` is positive, so `gamma(12)` runs and gives you a white rectangle.

`posterize(levels)` quantises each channel to `levels` evenly-spaced tones, with a step of
`255.0 / (levels - 1)`. It `require`s `levels` in `[2, 256]`, and 256 is the identity. Four to eight
reads as a deliberate screen-printed look; below four it reads as a bug.

`saturate(factor)` is not an HSV round trip. It converts to grey, converts that back to three
channels, and then takes a weighted sum of the original against the grey: `factor` at the source,
`1 - factor` at the grey version. So `saturate(0)` is a three-channel greyscale --- still `CV_8UC3`,
which matters more than it sounds --- `saturate(1.0)` is the identity, and anything above 1 is an
extrapolation #emph[away] from grey rather than an interpolation towards it. Extrapolation clips, so
1.2 to 1.5 is vivid and 3.0 is a poster of six colours. The `require` is only that `factor` is
non-negative.

`temperature(shift)` is a fixed 3×3 colour matrix run through `Core.transform`, and the matrix is
smaller than the name suggests: it scales blue by `1 - 0.3 * shift`, scales red by
`1 + 0.3 * shift`, and leaves green alone. `shift` is `require`d to be in `[-1, 1]`, so the extreme
setting is a thirty-per-cent swing on two channels. Positive is warm, negative is cool, and the
honest description is a white-balance nudge, not a correction: it has no idea where your white
point is.

`sepia` is the same mechanism with a matrix nobody tunes --- the classic
`0.131, 0.534, 0.272 / 0.168, 0.686, 0.349 / 0.189, 0.769, 0.393`, in BGR order --- and takes no
argument at all. `emboss` is a 3×3 `filter2D` with the directional kernel
`-2, -1, 0 / -1, 1, 1 / 0, 1, 2`, at `OutputDepth.SameAsSource`. That kernel sums to 1, which is a
deliberate choice with a visible consequence: a flat region keeps its own brightness instead of
collapsing to mid-grey, so scalacv's emboss is a relief cut into the original tones rather than the
grey plate you may be expecting from other tools.

#figure-table("The tone verbs, their arguments, and the range that is actually useful.")[
#tbl(
  columns: (auto, auto, auto, 1fr),
  [Verb], [Rejects], [Useful range], [Reach for it when],
  [`gamma(g)`], [`g <= 0`], [0.7 -- 1.4], [shadows are blocked up and the highlights are fine],
  [`posterize(levels)`], [outside `[2, 256]`], [4 -- 8], [you want flat banding as a look],
  [`saturate(factor)`], [`factor < 0`], [0 -- 1.5], [colour has faded, or you want grey that still chains],
  [`temperature(shift)`], [outside `[-1, 1]`], [-0.5 -- 0.5], [the cast is warm or cool and you know which],
  [`adjust(brightness, contrast)`], [nothing], [±30, 0.8 -- 1.4], [you want a linear level change, clipping and all],
  [`sepia`], [---], [---], [a period wash, once],
  [`emboss`], [---], [---], [relief, or a cheap directional derivative],
)
]

#subsect("Colour maps, and why the default matters")

`colorMap(map: Colormap)` is the odd one here, because it is not a look. It takes a single-channel
image --- a disparity map, a motion field, a distance transform, any scalar field --- and
false-colours it so a person can see it. Without it, the output of `StereoDepth.disparity` or a
`MotionDetector` is a grey rectangle that conveys almost nothing at a glance.

`Colormap` has exactly ten cases, matching OpenCV's `COLORMAP_*` set, and the choice between them is
not cosmetic. Five --- `Viridis`, `Magma`, `Inferno`, `Plasma` and `Turbo` --- are perceptually
uniform: equal steps in the value produce equal-looking steps in colour, and they degrade sensibly to
greyscale. `Jet` does not. Its rainbow has bright bands at cyan and yellow that the eye reads as
boundaries, so a smooth ramp acquires edges that are not in the data, and it is close to unreadable
for a colour-blind viewer or on a monochrome printout. The enum's own scaladoc says it plainly: the
perceptually-uniform ones are the honest choice for data, `Jet` is the classic-but-misleading
rainbow.

#figure-table("The ten colour maps and what each is for.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Case], [Family], [Use it for],
  [`Viridis`], [perceptually uniform], [the default honest choice for data],
  [`Magma`], [perceptually uniform], [dark ground, high drama],
  [`Inferno`], [perceptually uniform], [heat and energy fields --- what `Filter.heatmap` uses],
  [`Plasma`], [perceptually uniform], [bright, high-contrast data],
  [`Turbo`], [perceptually uniform], [a rainbow when you must have one, corrected],
  [`Jet`], [legacy rainbow], [matching an older tool's screenshot, and nothing else],
  [`Hot`], [sequential], [thermal imagery],
  [`Bone`], [sequential grey-blue], [the X-ray look],
  [`Ocean`, `Autumn`], [sequential], [stylistic],
)
]

There is one mistake here that everybody makes once, and it is not a colour choice. `applyColorMap`
accepts `CV_8UC1` and `CV_8UC3` only. A float response --- a Sobel derivative taken at
`OutputDepth.Float32`, a distance transform --- is `CV_32F`, and handing it straight to `colorMap`
aborts inside native code:

```scala
// Wrong: the response is CV_32F, and applyColorMap does not take it.
response.colorMap(Colormap.Viridis)
```

The fix is one call, and it is the reason `normalize` defaults to `OutputDepth.Unsigned8` rather
than to OpenCV's own `dtype = -1`:

#example("Normalising is what makes a float result viewable, not merely rescaled.")[
```scala
response.normalize(0, 255).colorMap(Colormap.Viridis).write("response.png")
```
]

With `-1` the rescale would hand back a `CV_32F` holding the values 0 to 255, which looks like it
worked and is not viewable by anything: `toBufferedImage` rejects it and `colorMap` still aborts.
For an already-8-bit image the default is the same conversion either way, so a plain contrast
stretch is unaffected by it.

#sect("Detail and texture")

`edgePreserving`, `enhance`, `stylize` and `sketch` are a different kind of animal. They do not map
pixels; they run an edge-aware solver across the whole image, and every one of them wants an 8-bit
three-channel input. Give one a greyscale `Mat` and OpenCV asserts, which reaches you as
`CvError.NativeCall("stylization", …)` --- the operation name comes from `Mats.produce`, so the
error tells you which call it was even in the middle of a chain.

```scala
// Wrong: gray leaves one channel, and all four of these need three.
scan.gray.stylize()
```

If what you wanted was a monochrome stylisation, desaturate without dropping channels ---
`scan.saturate(0).stylize()` --- which is exactly why `Filter.grayscale` is defined as `saturate(0)`
rather than as `gray`.

`edgePreserving(strength: Float = 60, detail: Float = 0.4f)` is the foundation of the other three and
the most useful of them on its own. It smooths flat regions while leaving edges intact, which makes
it a gentle denoise you can run before a threshold or a contour pass when you want to kill texture
without softening boundaries. scalacv fixes OpenCV's filter choice at `Photo.RECURS_FILTER`; the
alternative, `Photo.NORMCONV_FILTER`, is one `Photo.edgePreservingFilter` call away on a borrowed
`mat` if you need it.

`enhance(strength: Float = 10, detail: Float = 0.15f)` --- `detailEnhance` at the mid level --- runs
the same smoothing and adds the difference back, boosting local contrast. It is the "clarity"
slider, and what `Filter.vivid` and `Filter.dramatic` are built from.
`stylize(strength: Float = 60, detail: Float = 0.45f)` pushes the smoothing much harder and saturates
the result. Raising `strength` towards 80 and dropping `detail` towards 0.3 makes it more abstract,
not more detailed --- the opposite of what the parameter name suggests the first time.

`sketch(strength: Float = 60, detail: Float = 0.07f, shade: Float = 0.02f)` is the pencil rendering.
Note how much smaller its `detail` default is than the others: 0.07 against 0.45. These are not the
same scale across the four operations, and the only way to find your value is to render a few.

#sidebar("The sketch OpenCV throws away")[
`Photo.pencilSketch` is the one call in this family with two outputs. It fills a greyscale sketch
#emph[and] a colour one, in a single pass, from the same intermediate. scalacv's `sketch` returns
only the colour version --- and the mid-level `pencilSketch` does the same --- so the grey plate is
allocated, written, and released, every call.

The implementation is honest about it: `Managed.use(Mat())` owns the discarded output for exactly
the duration of the native call and frees it whether the call succeeds or throws. The cost is one
extra buffer for the length of one operation, and the alternative --- returning a tuple that
almost every caller destructures and discards half of --- would have made the common case worse to
read. If you want the grey sketch, `Photo.pencilSketch` on a borrowed `mat` gives you both, and the
two `Mat`s are then yours to release.
]

#figure-table("The photo-module operations and their defaults.")[
#tbl(
  columns: (auto, auto, 1fr),
  [`Image` verb], [Mid-level `Mat` op], [Signature],
  [`edgePreserving`], [`edgePreserving`], [`(strength: Float = 60, detail: Float = 0.4f)`],
  [`enhance`], [`detailEnhance`], [`(strength: Float = 10, detail: Float = 0.15f)`],
  [`stylize`], [`stylize`], [`(strength: Float = 60, detail: Float = 0.45f)`],
  [`sketch`], [`pencilSketch`], [`(strength: Float = 60, detail: Float = 0.07f, shade: Float = 0.02f)`],
)
]

#sect("Repair and composition")

`inpaint(mask: Image, radius: Double = 3.0)` reconstructs whatever lies under a mask from the pixels
around it. The mask is `CV_8UC1`, and non-zero marks the pixels to #emph[throw away and
reconstruct]. `applyMask` reads non-zero as the pixels to #emph[keep] --- the same convention aimed
at the opposite outcome, and worth saying out loud, because the two sit next to each other in the
API and neither will complain about the other's mask. Hand `inpaint` one built for `applyMask` and
it dutifully reconstructs everything except the damage. `radius` is how far out from each damaged
pixel OpenCV looks for material; 3 to 5 covers dust and thin scratches, and much higher smears
rather than repairs, because the algorithm has no model of what should be there, only of what is
nearby.

scalacv fixes the algorithm at `Photo.INPAINT_TELEA`, the fast marching method. OpenCV's other
choice, `Photo.INPAINT_NS`, solves a Navier--Stokes formulation instead and tends to carry
long-range structure --- a continuing straight edge --- a little further across a wide gap, at more
cost. It is not exposed as a parameter; if you want it, call `Photo.inpaint` yourself on a borrowed
`mat`, which is a two-line drop out of the high-level API and back.

For the archive scans, the mask is the interesting part. Dust on a print is bright detail smaller
than any real feature, which is precisely what a top-hat morphology isolates.

#example("A dust mask from the scan itself: bright specks, thresholded, grown to cover their edges.")[
```scala
def dustMask(scan: Image): Image =
  scan.gray
    .morphology(MorphOp.TopHat, radius = 3)
    .threshold(40)
    .dilate(radius = 2)
```
]

`morphology(MorphOp.TopHat, radius = 3)` is the source minus its own opening, so anything brighter
than its surroundings and smaller than the 7×7 kernel survives and everything else goes to zero.
`threshold(40)` turns that into the binary mask `inpaint` requires, and the `dilate` grows each speck
by two pixels so the repair covers its soft halo rather than leaving a ring. The argument is a copy,
because `dustMask` consumes what it is given.

#memory[
`inpaint`, `applyMask`, `blend` and `seamlessCloneInto` all follow one rule, and it is not the rule
the rest of the chapter follows. The receiver is #emph[consumed] and becomes the result; every
`Image` you pass as an argument is #emph[borrowed] and is still yours to close. Nothing in the type
signature says so, and a leaked mask is a full-size native buffer that the collector has no reason
to reclaim --- exactly the shape of leak Chapter 1 measured at 5,865 MB. Wrap the mask in
`scala.util.Using`, or close it on the line after the call. Do not let it fall out of scope.
]

`blend(other: Image, weight: Double = 0.5)` is the ordinary weighted average:
`this * weight + other * (1 - weight)`, with `weight` `require`d to be in `[0, 1]` and `other`
borrowed. Both images must match in size and type, which is the constraint that bites --- a
three-channel scan and a one-channel mask are not blendable, and the failure arrives from native
code rather than from a `require`. It is a cross-fade, a watermark, a two-exposure merge; nothing
clever, and cheap.

`seamlessCloneInto(background: Image, mask: Image, center: Point)` is the clever one. Pasting an
object into a photograph fails on the seam: however good the cut-out, the boundary carries the
lighting of the image the object came from, and the eye finds that line immediately. Poisson
cloning solves the problem by discarding the object's absolute colours and keeping only its
gradients, then solving for the pixel values that reproduce those gradients while matching the
background exactly along the boundary. There is no seam because the boundary values are, by
construction, the background's own.

The receiver is the foreground object, `mask` marks which of its pixels are the object, `center` is
where in the background the object's centre lands, and the result is background-sized. scalacv fixes
the mode at `Photo.NORMAL_CLONE`; OpenCV also offers `Photo.MIXED_CLONE`, which takes the stronger
gradient of foreground and background at each pixel and so lets background texture show through a
thin object, and `Photo.MONOCHROME_TRANSFER`. Both are reachable through `Photo.seamlessClone` on
borrowed `mat`s.

#sect("What these cost")

The two halves of this chapter are not in the same league, and the difference is large enough to
decide your architecture rather than your style.

The tone grades --- `gamma`, `posterize`, `saturate`, `temperature`, `sepia`, `emboss`, `adjust`,
`colorMap` --- are one or two passes over the pixels with no iteration: a table lookup, a per-pixel
matrix, a 3×3 convolution, or in `saturate`'s case two colour conversions and a weighted sum. Any of
them can go on a video path without much thought.

The four edge-aware operations --- `edgePreserving`, `enhance`, `stylize`, `sketch` --- run a solver
over the entire frame and are heavier by a wide margin. `inpaint` and `seamlessCloneInto` are
heavier again and, worse, data-dependent: `inpaint` costs what the masked area costs, and Poisson
cloning solves a linear system over the pasted region. A larger hole is not a little slower; it is a
different amount of work.

#warning[
Do not take a number from this page, or from any other page, for those six. The library publishes
measured figures in `docs/mdoc/benchmark-results.md` for what it has actually benchmarked, and the
stylisation operations are not among them --- so the honest statement is the ordering, not a
millisecond count. They are also among the operations OpenCV parallelises internally, which means
their cost on your machine depends on `getNumThreads` and on whether the build has IPP.
]

Chapter 35 settles this properly: the benchmark harness, how to read a confidence interval, and
`ConfigProbeBench`, which prints your runtime's `useOptimized`, `getNumThreads` and build information
and then measures how a heavy internally-parallel operation scales as `Core.setNumThreads` is stepped
through 1, 2, 4 and your core count. That answer is a property of your hardware. The project's rule
applies here more than anywhere: no optimisation without a measured delta #emph[and] a bit-identical
output hash, because a change that is faster by computing something slightly different is not an
optimisation, and a timing number alone cannot tell the two apart.

#sect("A look you can repeat")

Back to the four thousand scans. The grade is three tone operations, in this order: cool the warm
cast the dye shift introduced, restore the colour that faded with it, and pull the mid-tones back
down where the print has gone flat and light. Each is cheap, each has one argument, and together
they are a house look --- which is exactly what `Filter` exists to name.

#example("Three verbs, one name, applied four thousand times.")[
```scala
val archive = Filter("archive")(_.temperature(-0.2).saturate(1.3).gamma(0.9))

Image.reading("scans/0417.tif") { scan =>
  Using.resource(dustMask(scan.copy)) { mask =>
    scan.inpaint(mask, radius = 4.0).filter(archive).write("graded/0417.jpg")
  }
}
```
]

`Filter` is nothing but a `String` and an `Image => Image`, so `archive` is a value you can log,
show in a picker, and compose: `archive.andThen(Filter.sharpen)` has the `name` `"archive+sharpen"`.
The seventeen built-in looks in `Filter.all` are built the same way from the verbs in this chapter
--- `noir` is `saturate(0).adjust(contrast = 1.3).gamma(0.9)`, `vintage` is
`sepia.saturate(0.85).gamma(0.9)` --- and reading that catalogue teaches the grades faster than any
table.

#memory[
A contact sheet is the natural next thing to build, and it is where a filter catalogue turns into a
leak. `Filter.all.map(f => image.filter(f))` throws on the second filter, because the first consumed
`image`. The fix that people reach for --- `Filter.all.map(f => image.copy.filter(f))` --- runs, and
strands seventeen full-size buffers unless every result reaches a terminal. `bytes(".png")` or
`write(…)` on each one closes it; anything else does not.
]

Now the other half of the archive's request: the searchable records. Run the grade first and feed
the graded file to the face detector, and you will get a worse index than you would have got from
the raw scan --- possibly a much worse one, with no error anywhere to tell you.

#caution[
Photographic operations are subtractive with respect to features. `edgePreserving` and `stylize`
exist to remove texture, and texture is precisely what an ORB keypoint is: `Features.detect` will
find fewer corners on a stylised frame and match them worse. `posterize` replaces smooth gradients
with flat plateaus separated by hard steps, so `canny` finds the banding boundaries instead of the
object. `emboss` is a directional derivative, so everything downstream is looking at a gradient
field that merely resembles a picture. `gamma` and `saturate` move the intensity distribution a
detector was trained on.

Grade a copy for the human. Run the detector on the original. When both must happen, `copy` before
the first transform is the cheapest correct answer, and it costs one buffer.
]

One exception runs the other way: `edgePreserving` before a threshold or a contour pass often
#emph[helps], because there the texture it removes is noise and the edges it keeps are the
boundaries you were about to look for. The rule is not "never before analysis". It is that a
photographic operation is a decision about what to discard, and the only safe version of that
decision is one you made deliberately, knowing what comes next.

#sect("Where this goes next")

The grade is now a value with a name, applied to a still and written to disk. That closes out the
pixel-processing half of the book. Every verb from Chapter 7 onward has had the same shape: it takes
an `Image`, does something to the pixels, and hands back an `Image`, and the interesting question
was always which pixels and at what cost.

Chapter 16 keeps the pixels and changes what you build on top of them. The annotation over a graded
frame --- the box, the label, the legend --- was a sequence of mutating `drawRect` and `drawText`
calls in Chapter 14; `scalacv-graphs` makes it a `Picture`, an immutable value you can compose,
transform and test before a single pixel moves. Nothing in this chapter changes there. The grade is
still applied to the pixels underneath, and the overlay is composited on top of the result.

The cost ordering above comes due later, in Chapter 19 and Chapter 20, when the still becomes a
frame arriving thirty times a second from `Video.frames` or `Camera.taking` rather than once from a
flatbed, and `Recorder.write` borrows that frame's `Mat` so a recorded pipeline copies nothing per
frame. Every look you have built here can run on that path. Most of them should not.
