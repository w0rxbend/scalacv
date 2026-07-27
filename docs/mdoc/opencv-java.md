# Coming from OpenCV (Java or Python)

Already know OpenCV from Python or the Java bindings? Then you know the operations — this page maps them onto scalacv and calls out the three things scalacv does *differently* (on purpose). The short version: same OpenCV underneath, but ownership is explicit, constants are typed, and expected failures are values.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## Three differences worth internalising first

**1. Ownership is explicit, and that's a feature.** In Python the GC (and `cv2`'s own refcounting) hides native memory; in Java you either leak or call `release()` by hand. scalacv makes it a handle: an [`Image`](/image-api) / [`Managed`](/mat-lifecycle) frees exactly once, and a scope (`Image.reading`, `Managed.use`, `Camera.using`) does it for you. You get Python's convenience *and* deterministic freeing. (Why it matters: the JVM GC can't see off-heap pressure, so "just let it collect" leaks — [the numbers](/mat-lifecycle) are stark.)

**2. Constants are typed enums, not `int`s.** No more `cv2.COLOR_BGR2GRAY` magic numbers or `cv.CV_8UC3` you can pass to the wrong argument. `ColorConversion.BgrToGray`, `Threshold.Binary`, `BorderType.Reflect101` — the compiler checks them.

**3. Expected failures are `Either`, not empty Mats or exceptions.** `cv2.imread` returns `None`/an empty array on a missing file and you find out three calls later. `Image.read` returns `Left(CvError.DecodeFailed)` you can't forget to check. Programmer mistakes still throw; see [the error model](/error-model).

Also unchanged, so no surprise: **colour order is still BGR**, and coordinates are still `(x, y)` from the top-left. See [Image basics](/basics).

## Idiom map

| OpenCV-Python | scalacv (high-level) |
|---|---|
| `img = cv2.imread("p.jpg")` | `Image.read("p.jpg")` → `Either[CvError, Image]` |
| `cv2.imwrite("o.png", img)` | `image.write("o.png")` |
| `g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)` | `image.gray` (or `image.convert(ColorConversion.BgrToGray)`) |
| `cv2.GaussianBlur(img, (5,5), 0)` | `image.gaussianBlur(Size(5, 5))` (or `image.blur(2)`) |
| `cv2.Canny(img, 80, 160)` | `image.canny(80, 160)` |
| `_, t = cv2.threshold(img, 128, 255, cv2.THRESH_BINARY)` | `image.threshold(128)` |
| `cv2.resize(img, (w, h))` | `image.resize(w, h)` |
| `img[y0:y1, x0:x1]` (ROI copy) | `image.crop(Rect(x0, y0, w, h))` (independent copy) |
| `cnts, _ = cv2.findContours(...)` | `image.contours()` → `Seq[Contour]` |
| `cv2.rectangle(img, ...)` | `image.drawRect(Rect(...), Scalar.Green)` |
| `cap = cv2.VideoCapture(0)` | `Camera.open(0)` / `Video.open(0)` |
| manual `img.release()` | scope it (`Image.reading`, `Managed.use`) — or `image.close()` |

## The escape hatch: you never lose the raw API

scalacv wraps the **official `org.opencv.*` Java API**, and it's one method away at all times. Two moves cover everything.

**Borrow the raw `Mat`** for any `org.opencv.*` call scalacv doesn't wrap. `image.mat` hands you the underlying `org.opencv.core.Mat`; the `Image` keeps ownership, so read from it, pass it to any OpenCV function — just don't release it:

```scala mdoc:silent
import org.opencv.imgproc.Imgproc

val img = Image.blank(64, 64, Scalar.White)
val corners = new org.opencv.core.Mat()
// Call a raw Imgproc function scalacv doesn't surface, on the borrowed Mat:
Imgproc.cornerHarris(img.gray.mat, corners, 2, 3, 0.04)
corners.release()
img.close()
```

**Adopt a raw `Mat`** produced by some OpenCV call back into the managed world with `Image.wrap(Managed(mat))` — from then on it's owned and scoped like anything else:

```scala mdoc:silent
val raw = new org.opencv.core.Mat(48, 48, org.opencv.core.CvType.CV_8UC3)
// ... a raw OpenCV call fills `raw` ...
val adopted: Image = Image.wrap(Managed(raw)) // now managed; close()/Using frees it
adopted.close()
```

The mid-level [extension methods on `Mat`](/low-level) (`mat.cvtColor(...)`, `mat.canny(...)`, `mat.findContours()`) are the same operations as the `Image` methods, one tier down — use them when you're already holding a raw `Mat`.

## Gotchas that bite migrants

:::warning You can't reuse a value after a transform
`Image` has [move semantics](/mat-lifecycle): `image.gray` *consumes* `image`. In Python `g = cv2.cvtColor(img, ...)` leaves `img` usable; here `image` is spent. To use one image two ways, take `image.copy` first. This is what makes a chain leak-free.
:::

```scala mdoc:crash
val im = Image.blank(8, 8)
val g = im.gray  // consumes im
im.width         // throws — im was spent (in Python this would just work)
```

- **`imread` doesn't throw and doesn't return null** — it returns `Left(DecodeFailed)`. Pattern-match or `flatMap` the `Either`; you can't accidentally run a pipeline on nothing.
- **A detector isn't a plain object you drop** — `CascadeClassifier`, a DNN `Net`, and 180-odd other types have no public `release()`; scalacv frees them through a safe bridge when you `close()` the `Managed`. Don't `new` them and forget them (that leaks in Python/Java too — here it's just visible).
- **Threading** — a `Mat`/detector shared across threads is a C++ data race, exactly as in Python/Java. See [Concurrency](/concurrency).
- **No NumPy view tricks** — there's no `img[::2, ::2]` stride slicing. `crop` returns an independent copy; for channel/row work drop to the raw `Mat`.

## Next

- [Architecture](/architecture) — the two-tier design you're mapping onto.
- [Working with the raw OpenCV API](/low-level) — the full escape-hatch story.
- [Mat lifecycle](/mat-lifecycle) — the ownership model in depth.
