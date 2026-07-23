# Reading & writing images

```scala mdoc:invisible
import scalacv.*
OpenCv.load()
```

Reading and writing pixels is the one edge of the library that touches the outside world — the
filesystem, an HTTP body, a database BLOB — and it is also the one place where OpenCV's own error
reporting is genuinely inconsistent. `Images` is the thin object that flattens that inconsistency into a
single `Either[CvError, ?]`, so a missing file or a corrupt JPEG is a value you handle, never a surprise
several call frames away.

Everything here comes in two flavours: the mid-level [`Images`](#the-images-object) functions that hand
back an owned [`Managed[Mat]`](/mat-lifecycle), and the high-level [`Image`](/image-api) entry points that
give you the fluent, self-freeing wrapper instead. Reach for `Image` first for read → process → write;
drop to `Images` when you already hold a raw `Mat`.

## Why reading returns an `Either`

`imread` and `imdecode` **never throw** for a file that is missing, is a directory, or holds bytes that
are not a decodable image. They return a `Mat` whose `empty()` is `true` — and print a `findDecoder`
warning to stderr that nothing can silence. Anyone who forgets the `empty()` check does not fail there;
they fail later, with a `CvException` from some innocent `Imgproc` call that had nothing to do with the
mistake.

scalacv makes the check for you and turns that empty Mat into a `Left`, so the failure is impossible to
forget:

```scala mdoc
Images.read("/does/not/exist.png").isLeft
```

The `Left` is a [`CvError.DecodeFailed`](/error-model), and all three unreadable cases — absent,
directory, undecodable — report identically, because that is all the information `imread` gives us:

```scala mdoc
Images.read("/does/not/exist.png").left.map(_.getMessage)
```

## The `Images` object

### Reading from a file

`Images.read` resolves a filesystem path itself — it does not understand classpath resources or URLs —
and hands back an owned Mat you are responsible for:

```scala mdoc:compile-only
Images.read("photo.jpg")                             // Either[CvError, Managed[Mat]]
Images.read("scan.png", ImreadFlags.Grayscale)       // decode straight to one channel
```

Because the result is owned, prefer consuming it in place over holding it:

```scala mdoc:compile-only
Images.read("photo.jpg").map(_.use { mat =>
  // work with `mat` here; it is released when `use` returns
  mat.rows * mat.cols
})
```

### Decoding from memory

`Images.decode` is `read`'s in-memory twin — for an HTTP response body, a BLOB, or a test fixture you
already hold as bytes. It rejects an empty array before it ever reaches OpenCV (there is nothing there to
decode), and otherwise behaves exactly like `read`, including the empty-Mat-to-`Left` translation:

```scala mdoc:compile-only
Images.decode(httpBody)                              // Either[CvError, Managed[Mat]]
Images.decode(httpBody, ImreadFlags.Unchanged)       // keep any alpha channel
```

```scala mdoc:invisible
// placeholder so the compile-only snippet above type-checks without a real request
val httpBody: Array[Byte] = Array.emptyByteArray
```

### Writing to a file

`Images.write` picks the encoder from the path's extension. It does **not** modify or release the Mat you
pass. `imwrite` has two failure modes and `write` covers both:

- an unwritable destination — a missing parent directory, no permission — which `imwrite` reports by
  returning `false`, surfaced as [`CvError.EncodeFailed`](/error-model);
- an extension with no registered encoder, which `imwrite` reports by **throwing** `CvException`,
  surfaced as `CvError.NativeCall`.

Both are `Left`s, so a single check catches either:

```scala mdoc:silent
import org.opencv.core.{CvType, Mat}

// A synthetic image, so this page needs no fixture file. `source` is released at the end.
val source = Mat(64, 64, CvType.CV_8UC3)
```

```scala mdoc
Images.write("/no/such/directory/out.png", source).isLeft   // returns false -> EncodeFailed
```

### Encoding to memory

`Images.encode` is `write` without the filesystem: it returns the encoded image file as a plain JVM
`Array[Byte]` (the staging buffer is released before returning, so there is no native memory left for you
to think about). `ext` selects the format the way a filename extension would — `".png"`, `".jpg"`,
`".webp"`.

A **leading period matters**: `imencode` silently fails without one. `encode` adds it for you if you
forget, so both of these are the same call:

```scala mdoc
(Images.encode(source, ".png").isRight, Images.encode(source, "png").isRight)
```

An extension with no encoder yields a `Left` rather than the `CvException` OpenCV throws:

```scala mdoc
Images.encode(source, ".not-a-format").isLeft
```

## `ImreadFlags`

Both `read` and `decode` take an `ImreadFlags`, defaulting to `ImreadFlags.Color`. It is a typed
bitmask, not a bare `int` — a `Mode` plus any number of `Modifier`s:

| Value | Meaning |
|---|---|
| `ImreadFlags.Color` | force 3-channel BGR (the default) |
| `ImreadFlags.Grayscale` | force single-channel greyscale |
| `ImreadFlags.Unchanged` | as stored, alpha channel and all |
| `ImreadFlags.Mode.AnyDepth` | keep 16-bit / 32-bit depth instead of downcasting to 8-bit |

Modifiers combine with a mode via `ImreadFlags(mode, modifiers)` — `IgnoreOrientation` to skip the EXIF
rotation, `ReducedHalf` / `ReducedQuarter` to decode a downscaled image cheaply. The resolved OpenCV int
is `cvValue`:

```scala mdoc
ImreadFlags.Grayscale.cvValue
```

```scala mdoc:silent
import scalacv.ImreadFlags.{Mode, Modifier}

// 16-bit depth preserved, and decode at half resolution
val hdrThumbnail = ImreadFlags(Mode.AnyDepth, Set(Modifier.ReducedHalf))
```

## Round-tripping through memory

`encode` and `decode` compose into a full in-memory round trip — the pattern behind serving an image
over HTTP, or stashing one in a cache — with no file ever touched:

```scala mdoc
val roundTrip: Either[CvError, (Int, Int)] =
  Images
    .encode(source, ".png")                                  // Mat -> PNG bytes
    .flatMap(png => Images.decode(png))                      // bytes -> owned Mat
    .map(_.use(mat => (mat.rows, mat.cols)))                 // read it, then release
roundTrip
```

```scala mdoc:invisible
source.release()
```

## Prefer `Image` for read → process → write

`Images` is the right layer when you already hold a raw `Mat`. For the far more common
read-something, transform-it, write-it-back shape, the high-level [`Image`](/image-api) is nicer: its
`read`/`decode` return an `Either[CvError, Image]`, and `write`/`bytes` encode **and release** in one
step, so a whole pipeline never names a `Mat` or a `release`:

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

`Image.decode(bytes)` mirrors it for in-memory input, and `Image.bytes(".png")` mirrors `encode` on the
way out. See the [Image API](/image-api) for the full story, [Image processing](/image-processing) for
the operation catalogue, and the [Cookbook](/cookbook) for worked recipes.
```