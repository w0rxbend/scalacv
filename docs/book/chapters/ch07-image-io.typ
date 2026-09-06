#import "../lib/book.typ": *

#chapter("Reading, Writing, and Interop", subtitle: [The boundary where the outside world becomes pixels, and pixels become bytes again.])

Every vision program is a sandwich. The filling is the interesting part --- the blurs, the contours,
the detectors --- but it is held together by two slices that nobody writes home about: getting pixels
in, and getting them back out. A file on disk. An upload body. A BLOB in Postgres. A `BufferedImage`
that came from `ImageIO` because the rest of the service was written before anyone said the word
OpenCV. The filling is where the algorithms live; the bread is where the bugs live.

They live there because this is the one edge of OpenCV where the error reporting is genuinely
inconsistent, and the half you reach for first fails without a sound. `imread` on a path that does not exist
returns a `Mat`. `imread` on a directory returns a `Mat`. `imread` on a text file you renamed to
`.png` returns a `Mat` --- and prints a `findDecoder` warning to stderr that nothing in your process
can silence. All three of those `Mat`s have `empty() == true` and none of them throws. Miss the check
and you do not fail at the read; you fail four calls later, inside a `gaussianBlur` that had nothing
to do with your mistake, with a `CvException` whose message is about kernel sizes. Meanwhile
`imencode` with an extension nobody registered an encoder for does throw --- so the two halves of
the same subsystem disagree about what failure even is.

There is a second problem underneath the first, and it is the one this book keeps returning to.
A decoded image is native memory. Whoever creates it has to decide who frees it, and OpenCV's Java
bindings decide nothing: `imdecode` hands you a `Mat` and forgets you existed. In a request handler
that decodes one upload per request, "forgets you existed" is a leak measured in megabytes per
request, on a heap that never grows enough to trigger a collection.

scalacv answers both in the same object. Every function on the I/O boundary returns
`Either[CvError, ?]` --- the silent failures and the loud one flattened into a single value you
handle where you made the call --- and every function that produces an image says, in its return
type, who owns it. Those functions, the flags that steer the decoder, the formats OpenCV will and
will not write, and the bridge to `java.awt.image.BufferedImage` are what follows.

One example runs through it: a thumbnail endpoint that takes an uploaded photo as a byte array,
produces a small edge-map preview, and returns it as PNG bytes without ever touching the filesystem
--- because a service that writes temp files to make a thumbnail is a service that fills a disk at
three in the morning. It ends up four lines long, every one of them load-bearing.

#sect("Four functions, symmetric in pairs")

`Images` is the object at the boundary. It has four public functions, and their shape is the whole
design: file or memory, in or out.

#figure-table("The `Images` surface: two axes, four functions.")[
#tbl(
  columns: (auto, 1fr, 1fr),
  [], [*Read (in)*], [*Write (out)*],
  [*File*], [`read(path, flags)`], [`write(path, mat)`],
  [*Memory*], [`decode(bytes, flags)`], [`encode(mat, ext)`],
)
]

Spelled out, with the defaults the source actually declares:

#example("The whole I/O surface. Four signatures, one error type.")[
```scala
object Images:
  def read(path: String, flags: ImreadFlags = ImreadFlags.Color): Either[CvError, Managed[Mat]]
  def decode(bytes: Array[Byte], flags: ImreadFlags = ImreadFlags.Color): Either[CvError, Managed[Mat]]
  def write(path: String, mat: Mat): Either[CvError, Unit]
  def encode(mat: Mat, ext: String = ".png"): Either[CvError, Array[Byte]]
```
]

The two inbound functions return `Managed[Mat]`, so ownership is stated rather than assumed. The two
outbound ones take a bare `Mat`, borrow it, and leave it exactly as they found it --- neither `write`
nor `encode` releases the image you handed it. A function that produces native memory must say who
frees it; a function that only reads pixels must not surprise you by freeing anything.

The failures collapse into two `CvError` cases. Anything that stops an image from becoming pixels is
`CvError.DecodeFailed(path, details)`; anything that stops pixels from becoming an image file is
`CvError.EncodeFailed(path, details)`. Both are case classes, and both extend `RuntimeException`
through the sealed `CvError` hierarchy --- so you can match on them as data, or throw them at the
edge of a service, whichever the surrounding code already does.

#sect("Why a missing file is a `Left` and not an empty `Mat`")

`Images.read` does not call `imread`. It reads the file itself, with `java.nio.file.Files`, and hands
only the codec work to OpenCV through `decode`. That one decision buys three things.

The first is that the filesystem questions get answered by the filesystem. `imread` reports "no such
file", "that is a directory", "the file is zero bytes" and "those bytes are not an image" with the
same empty `Mat`; `read` distinguishes all four, and the distinction lands in the error's `details`
field:

#example("Four causes, four messages --- and every one of them names the file.")[
```scala
Images.read("/does/not/exist.png").left.map(_.getMessage)
// could not decode an image from '/does/not/exist.png': there is no file at this path

Images.read("/tmp").left.map(_.getMessage)
// could not decode an image from '/tmp': this path is a directory, not a file

Images.read("empty.png").left.map(_.getMessage)
// ... : the file is empty

Images.read("notes.txt").left.map(_.getMessage)
// ... : the bytes are not an image in a format OpenCV can decode
```
]

An error message that hedges across four causes is wrong three times out of four, and the library's
own test suite pins that these stay distinct. Note that `read` puts the path *back* into the error on
the way out: `decode` never saw a filename, so on its own it names its source `<N bytes>`.

The second is a Windows bug that stops existing. The JNI narrows a Java `String` with
`GetStringUTFChars`, and OpenCV's imgcodecs hands the resulting modified UTF-8 straight to `fopen`,
which on Windows reads it in the process's ANSI code page --- so any non-ASCII character in a path
resolves to a different, nonexistent name. `фото.png` is not missing; it is misreported as missing,
and `imwrite` on the same path returns a bare `false`. Upstream has not fixed it (`opencv#4292` is
still open against 4.13) and offers no wide-character entry point instead. Doing the file I/O on the
JVM removes the narrowing altogether, which is why the library's test suite round-trips a file
whose name mixes Cyrillic, a diaeresis and CJK characters.

The third is that `write` encodes fully before it touches the destination, so a failed encode can no
longer leave a half-written file behind.

The price is worth stating: the encoded file passes through a JVM byte array, so peak heap grows by
the file's size, and a file above 2 GB is out of reach because `Files.readAllBytes` cannot return an
array that long.

#warning[
  The rerouting covers `Images` only. Every other `String`-path native call in the library ---
  `VideoCapture`, `VideoWriter`, `CascadeClassifier.load`, `Dnn.readNet` --- still goes through the
  same narrowing, because none of them has an in-memory equivalent to reroute through. On Windows,
  keep those paths ASCII.
]

#sect("Who frees the Mat")

A `Managed[Mat]` coming out of `read` or `decode` is caller-owned: nothing else holds a reference to
it and nothing else will free it. The staging buffers used inside --- the `MatOfByte` that `encode`
writes into, the one `decode` reads from, and the empty `Mat` a failed decode hands back --- are all
released before the function returns, so exactly one native object is left alive on the success path
and none at all on the failure path.

#memory[
  `Images.read` and `Images.decode` hand you native memory with your name on it. Prefer
  `Images.read(p).map(_.use(...))` --- which releases when the block returns, on success, on failure
  and on exception --- over storing the `Managed[Mat]` in a `val` and hoping a later line frees it.
  The failure path leaks nothing either, which matters more than it sounds: that is the path a retry
  loop takes thousands of times.
]

#example("Consume in place; the Mat is gone by the time the expression has a value.")[
```scala
Images.read("photo.jpg").map(_.use { mat =>
  mat.rows * mat.cols          // work here; released when `use` returns
})
```
]

#sect("`read`, `reading`, and the leak between them")

Most code should not be naming `Mat` at all. `Image` is the fluent layer: it wraps one `Managed[Mat]`
and gives you move semantics, where each transform consumes the image it was called on and returns a
fresh one. `Image.read` and `Image.decode` are `Images.read` and `Images.decode` with an `Image`
around the result, and `write` and `bytes` are terminals --- they encode *and* release, in one step.

That leaves one trap, and it is worth showing wrong first, because it is the mistake people actually
make. `width` is a query. Queries borrow; they do not consume:

#example("Wrong. Nothing here ever releases the image.")[
```scala
val w: Either[CvError, Int] = Image.read("photo.jpg").map(_.width)
```
]

The `Either` carries an `Int`, the `Image` is unreachable, and its `Mat` is still resident: the chain
never reached a terminal, so nothing freed it. `Image.reading` exists so that this cannot happen. It
reads the path, runs your body, and closes the image afterwards --- even when the body already
consumed it, because release is idempotent, and even when the body throws.

#example("Right. The image is closed when the block returns, whatever the block did.")[
```scala
val w: Either[CvError, Int] = Image.reading("photo.jpg")(_.width)
```
]

#figure-table("The two entry points differ in exactly one thing: who closes.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Entry point*], [*Returns*], [*Closing*],
  [`Image.read(path, flags)`], [`Either[CvError, Image]`], [yours --- a terminal (`write`, `bytes`, `close`) must be reached],
  [`Image.reading(path, flags)(use)`], [`Either[CvError, A]`], [automatic, on every exit path],
  [`Image.decode(bytes, flags)`], [`Either[CvError, Image]`], [yours, as with `read`],
)
]

There is a second, quieter reason to prefer `reading`. Its whole `use` body runs inside `Cv.attempt`,
so a `CvError.NativeCall` thrown by a transform in the middle of the chain comes back as a `Left`
rather than escaping past the `Either`. With `read`, only the read itself is covered by the
`Either`; a `canny` that OpenCV rejects still throws.

#tip[
  Do not let the `Image`, or anything derived from it, escape the `use` block. It is closed when the
  block returns, so a chain that ends in `img` rather than in bytes hands back a corpse. Return
  pixels (`bytes`), a measurement, or a `BufferedImage` --- never the `Image`.
]

#sect("Deciding what comes out of the decoder")

Both `read` and `decode` take an `ImreadFlags`, defaulting to `ImreadFlags.Color`. It is a case class
with three fields --- `color`, `scale` and `ignoreOrientation` --- and three named constants cover
most calls: `ImreadFlags.Color` forces 3-channel BGR, `ImreadFlags.Grayscale` forces one channel, and
`ImreadFlags.Unchanged` gives you the pixels exactly as stored, alpha channel and all.

It looks like a bitmask and is not, which is the reason it is a type at all. Each `IMREAD_REDUCED_*`
constant already bakes in its colour bit, and `IMREAD_UNCHANGED` is `-1`, whose bits swamp anything
you OR onto it. So `ImreadFlags` maps the `(color, scale)` pair *totally* onto exactly one named
constant, exposed as `cvValue`, and only `ignoreOrientation` --- which skips the EXIF rotation --- is
genuinely independent and OR-ed on top.

#figure-table("`ImreadColor`, and what each case asks the decoder for.")[
#tbl(
  columns: (auto, 1fr),
  [*`ImreadColor`*], [*Effect*],
  [`Grayscale`], [one channel],
  [`Color`], [3-channel BGR --- the default],
  [`ColorRgb`], [3-channel RGB, with no `BgrToRgb` step afterwards],
  [`Unchanged`], [exactly as stored, alpha and all],
  [`AnyDepth`], [keep 16- or 32-bit depth instead of downcasting to 8-bit],
)
]

`ImreadScale` is the one that pays for itself. `Full`, `Half`, `Quarter` and `Eighth` decode at 1/1,
1/2, 1/4 and 1/8 of full resolution, and each is strictly cheaper than reading full and resizing
afterwards, because the codec skips the detail it is going to discard rather than producing it first.
A 4000×3000 source at `Quarter` decodes to 1000×750 and never materialises the full image at all.

Combinations OpenCV has no constant for are rejected at construction rather than quietly OR-ed into
something else --- reduced-size decode exists only for `Grayscale` and `Color`, and `Unchanged` can
carry no extra bit:

```scala
ImreadFlags(ImreadColor.AnyDepth, ImreadScale.Half)   // require fails
ImreadFlags(ImreadColor.Unchanged, ImreadScale.Half)  // require fails
```

That is an `IllegalArgumentException`, not a `Left`, and correctly so: it is a programmer error, not
a data-dependent failure. It is also the first line of the thumbnail endpoint, since a preview has no
use for the other fifteen-sixteenths of the pixels:

#example("The thumbnail endpoint, first draft: decode small, and never at full size.")[
```scala
val preview = ImreadFlags(ImreadColor.Grayscale, ImreadScale.Quarter)

def thumbnail(upload: Array[Byte]): Either[CvError, Image] =
  Image.decode(upload, preview)
```
]

#sect("Formats, and what the extension decides")

On the way out, the format is chosen by the filename extension, and by nothing else. `Images.write`
splits on the last `.` --- the same thing OpenCV's own `findEncoder` does with `strrchr`, so the
format chosen cannot drift from the format that was checked. `Images.encode` takes that extension
directly: `".png"`, `".jpg"`, `".webp"`. A leading period is added for you if you leave it off,
because `imencode` fails silently without one and the mistake is easy to make. `encode(m, "png")`
and `encode(m, ".png")` produce byte-identical output.

An extension with no registered encoder is the failure that OpenCV throws for rather than reports.
`Images` heads it off by asking `Imgcodecs.haveImageWriter` first, so it comes back as
`CvError.EncodeFailed` --- the same case as an unwritable destination. That one case covers every way
the *destination* can fail: an unknown extension, a path this filesystem cannot represent, a missing
parent directory, a directory you may not write to.

What it does not cover is a codec that rejects the *pixels*. `imencode` throws a `CvException` for
those, and `Cv.attempt` turns the throw into `CvError.NativeCall("imencode('.jpg')", cause)` rather
than into an `EncodeFailed`, because it is a data-dependent native failure like any other. Handing a
4-channel image to a format with no fourth channel is the usual way to meet it, which makes the next
table more load-bearing than it looks.

#figure-table("The extensions worth knowing, and what each trades.")[
#tbl(
  columns: (auto, auto, auto, 1fr),
  [*`ext`*], [*Lossy?*], [*Alpha*], [*Use it for*],
  [`".png"`], [no], [yes], [screenshots, masks, anything you re-process],
  [`".jpg"` / `".jpeg"`], [yes], [no], [photos, where a smaller file wins],
  [`".webp"`], [yes, here], [yes], [modern web delivery],
  [`".bmp"`], [no], [no], [a raw, decoder-free dump],
)
]

Alpha is the trap in that table. Decode a transparent PNG with `ImreadFlags.Unchanged` and you are
holding a 4-channel BGRA image --- `img.channels` says `4` --- and the formats without a fourth
channel will not take it. Flatten it deliberately, with `img.convert(ColorConversion.BgraToBgr)`, so
the loss is a line in your code rather than a `NativeCall` from the encoder.

The other thing the table does not offer is a dial. `Images.encode` takes `(mat, ext)` and nothing
else: no JPEG quality, no PNG compression level, no WebP lossless switch. Every file comes out with
whatever OpenCV's default is for that format, and `".webp"` is lossy for the same reason --- the
lossless mode lives behind an encoder parameter this surface does not expose. When one of those
knobs genuinely matters, the escape hatch is the one the library uses everywhere: borrow the `Mat`
and call OpenCV directly.

#example("Encoder parameters, on the layer below. `img.mat` borrows; the Image stays yours to close.")[
```scala
import org.opencv.core.{MatOfByte, MatOfInt}
import org.opencv.imgcodecs.Imgcodecs

def jpegAt(img: Image, quality: Int): Either[CvError, Array[Byte]] =
  Managed.use(MatOfByte()): buffer =>
    Managed.use(MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality)): params =>
      if Imgcodecs.imencode(".jpg", img.mat, buffer, params) then Right(buffer.toArray)
      else Left(CvError.EncodeFailed(".jpg", "imencode returned false"))
```
]

Both staging objects are `Mat` subclasses, so `Managed.use` frees them on the way out and the borrowed
`img` is untouched --- exactly the arrangement `Images.encode` has inside it, with one argument more.

#warning[
  `write` does not create parent directories. A path into a folder that does not exist is a `Left`
  whose details read "the parent directory does not exist" --- told apart from "the destination is
  not writable", which `imwrite` signals with the same bare `false` --- but it is still a failure.
  `flatMap` the result; do not assume success.
]

#sect("The round trip that never touches a disk")

`encode` and `decode` compose into a full in-memory round trip, and that is the shape of every HTTP
image service: bytes arrive, pixels happen, bytes leave. At the `Image` layer the round trip never
names a `Mat` and never names a `release`, because `bytes` is a terminal --- it encodes and releases
in the same call.

#example("The thumbnail endpoint, finished. No file is created; nothing is left to free.")[
```scala
val preview = ImreadFlags(ImreadColor.Grayscale, ImreadScale.Quarter)

def thumbnail(upload: Array[Byte]): Either[CvError, Array[Byte]] =
  Image
    .decode(upload, preview)                     // bytes -> Image, at 1/4 resolution
    .flatMap(_.blur(2).canny(80, 160).bytes(".png"))
```
]

Four lines, and the interesting property is what is *not* in them. No temp file, so nothing to clean
up and no disk to fill. No `Mat`, so nothing to release. No `try`/`finally`, because `bytes` releases
whether the encode succeeded or not. And no exception: a truncated upload, a PDF someone renamed to
`.jpg`, an empty body --- each comes back as `Left(DecodeFailed(...))` naming which it was. The empty
array is rejected before it ever reaches OpenCV, so "no bytes arrived" is never confused with "the
bytes were not an image".

#memory[
  `bytes` and `write` consume the `Image`. After `img.bytes(".png")` the `img` handle is spent, and
  touching it throws `IllegalStateException` from Scala rather than reading freed memory from JNI.
  That is the point of move semantics: a long chain holds exactly one live `Mat` at a time, never a
  pile of intermediates. Run the JVM with `-Dscalacv.trackOwnership=true` and the exception will
  point at the call that consumed it.
]

#sect("Crossing into AWT")

The rest of the JVM does not speak `Mat`. It speaks `java.awt.image.BufferedImage` --- `ImageIO`
reads and writes one, Swing paints one, `Graphics2D` draws into one, and Almond, the Scala Jupyter
kernel, renders one inline when it is the value of a cell. Two methods span that boundary, and both
*copy*, so ownership is never shared and never ambiguous.

#figure-table("The AWT bridge. Both directions copy.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Direction*], [*Method*], [*Behaviour*],
  [scalacv → AWT],
  [`img.toBufferedImage`],
  [borrows; `img` stays alive. 1 channel → `TYPE_BYTE_GRAY`, 3 → `TYPE_3BYTE_BGR`, 4 → flattened to BGR],
  [AWT → scalacv],
  [`Image.fromBufferedImage(bi)`],
  [returns a new owned 3-channel BGR `Image`; the source stays yours],
)
]

`toBufferedImage` requires depth `CV_8U`. That is a `require`, so a 16-bit or float image throws
`IllegalArgumentException` with a message telling you what to do about it --- and the restriction is
not fussiness. A disparity map, a distance transform, a raw Sobel response: all float, and silently
truncating one to bytes produces a picture that looks like noise and says nothing about why. Bring it
down deliberately instead: `normalize()`, whose `depth` already defaults to `OutputDepth.Unsigned8`,
rescales into 0--255 grey, and `colorMap(Colormap.Viridis)` renders it as legible false colour.

A 4-channel image is not rejected; it is converted with `ColorConversion.BgraToBgr` on the way
across, because AWT's `TYPE_3BYTE_BGR` has nowhere to put the fourth channel. Transparency is
therefore lost at the AWT boundary --- worth knowing before you go looking for it in the rendered
PNG.

Coming the other way, `fromBufferedImage` accepts every `BufferedImage` type there is. A
`TYPE_3BYTE_BGR` source already stores bytes in the interleaving `CV_8UC3` wants, so its backing
array copies straight in; anything else --- ARGB, indexed, a custom raster, a translated sub-raster
from `getSubimage` --- is normalised by drawing it once through a known BGR layout. The result is
always 3-channel, whatever went in.

#example("Into AWT and back. Note which side owns which object.")[
```scala
Image.reading("photo.jpg") { img =>
  val grey = img.copy.gray                  // a copy, so `img` survives the branch
  val awt =
    try grey.toBufferedImage                // borrows: `grey` is still alive afterwards
    finally grey.close()                    // ... so `grey` is yours to close
  val back = Image.fromBufferedImage(awt)   // a fresh, independently owned Image
  try back.width
  finally back.close()
}
```
]

Three objects, three owners, and only one of them is `reading`'s to clean up: the block closes
`img`; `grey` and `back` are closed by the code that made them. That is the price of branching.

#memory[
  `toBufferedImage` borrows: the `Image` it was called on is still alive and still yours to close.
  `fromBufferedImage` produces a new owned `Image`, and it will not be closed by anything else. In a
  notebook you can be casual --- the cell ends, the JVM reclaims --- but the same two lines in a
  request handler need the `close`, and `Image.reading` is the way to stop needing to remember.
]

In an Almond cell, this is why the bridge exists at all. Return a `BufferedImage` as the value of the
cell and the picture appears:

```scala
Image.reading("photo.jpg")(img => img.gray.canny(80, 160).toBufferedImage)
```

Because transforms move the image, displaying an intermediate step means displaying a `.copy`, as in
the listing above: `img.blur(5).toBufferedImage` consumes `img`, and the next line of the cell throws
`IllegalStateException`. `img.copy.blur(5).toBufferedImage` gives the cell a throwaway to render and
leaves the pipeline intact.

#sect("BGR, and the picture that comes out wrong")

If a scalacv image ever renders with an orange sky and blue skin, stop and check channel order.
OpenCV stores colour as *blue, green, red*. Almost everything else on the JVM --- AWT's packed `int`
pixels, `ImageIO`'s PNG writer, every hex colour you have ever typed --- is RGB. The two are the same
three bytes in the opposite order, which is why the bug is so hard to see: nothing crashes, nothing
is empty, the image is the right size, and the colours are wrong.

The library states the convention rather than hiding it, in the scaladoc and in the constants:

#example("Wrong, then right. The first line is blue.")[
```scala
val red = Scalar(255, 0, 0)   // wrong: that is 255 in the BLUE channel
val ok  = Scalar(0, 0, 255)   // right --- and this is exactly Scalar.Red
```
]

Use `Scalar.Red`, `Scalar.Green` and `Scalar.Blue` and the question never arises. Where you do need
the other order, ask for it at the boundary instead of shuffling channels by hand: decode with
`ImreadFlags(ImreadColor.ColorRgb)` and the decoder gives you RGB directly, with no conversion step;
convert an image you already hold with `img.convert(ColorConversion.BgrToRgb)`.

#warning[
  The AWT bridge does *not* need a conversion. `toBufferedImage` writes into `TYPE_3BYTE_BGR`, which
  stores bytes in the same B, G, R order a `CV_8UC3` `Mat` does, and `fromBufferedImage` normalises
  back through the same layout. A round trip through AWT preserves your pixels exactly --- the
  library's tests assert it to within one greylevel. Inserting a `BgrToRgb` "to be safe" is how you
  *create* the swapped-colour bug, not how you avoid it.
]

#sidebar("Why BGR, of all orders?")[
  The usual explanation is that OpenCV inherited it from the hardware and file formats of the late
  1990s: Windows BMP stores its pixels blue-first, and so did much of the frame-grabber software of
  the era, so the cheapest thing an imaging library could do was leave the bytes where the capture
  card had put them. By the time RGB had won everywhere else, changing it would have broken every
  program ever written against the library.

  A defensible decision, then, and one that has cost the world an enormous number of debugging hours
  since. What a wrapper can do is make the convention *visible* --- a
  `Scalar.Red` that is literally `Scalar(0, 0, 255)`, a `ColorConversion.BgrToRgb` you have to name,
  an `ImreadColor.ColorRgb` you can ask for at the decoder --- rather than leaving it as folklore you
  are expected to have absorbed.
]

#sect("Paths, and what a path is not")

`Images.read` resolves a filesystem path with `java.nio.file.Path.of`, and that is all it does. It is
not a classpath resource, not a URL, and not a glob. A relative path resolves against the JVM's
working directory, which in a `mill` or `sbt` run is the module or project root and in a container is
whatever the image's `WORKDIR` says --- so a relative path that works in your tests and fails in
production has usually not moved at all; the process has.

A path the filesystem cannot represent is a `Left` rather than a throw, which takes a little care to
arrange: `Path.of` raises `InvalidPathException` for an embedded NUL byte, and for the characters
NTFS forbids on Windows. That is a plain `RuntimeException`, which `Cv.attempt` deliberately does not
catch, so `Images` catches it explicitly --- a function whose whole contract is to return an `Either`
does not get to throw.

For a resource that lives inside a jar --- a test fixture, a bundled watermark, a template you ship
with the application --- there is no `Images` function to call, and there does not need to be. Read
the bytes with the JDK and decode them:

#example("A classpath resource is bytes, and bytes already have an entry point.")[
```scala
def fixture(name: String): Either[CvError, Image] =
  Option(getClass.getResourceAsStream(name)) match
    case Some(in) => try Image.decode(in.readAllBytes()) finally in.close()
    case None => Left(CvError.DecodeFailed(name, "no such classpath resource"))
```
]

That is the general lesson of this chapter, in four lines. `decode` is the real entry point and
`read` is a convenience on top of it; anything you can turn into an `Array[Byte]` --- a jar entry, an
S3 object, a `bytea` column, a WebSocket frame --- is already an image source.

#sect("What comes next")

Pixels are in memory now, owned by something that will free them, in a channel order you can name.
`ImreadFlags` was the first of this library's answers to OpenCV's integer constants, and it will not
be the last: Chapter 8, #emph[Typed Constants and Colour Spaces], is about the rest of them ---
`ColorConversion`, `Interpolation`, `BorderType`, `Threshold` --- and about why a total mapping onto
named cases catches, at compile time, the class of mistake that `ImreadFlags` catches at
construction. Everything the following chapters do to pixels is written in those enums.
