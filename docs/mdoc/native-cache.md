# The native cache & deployment

scalacv is a thin Scala layer over OpenCV's **native** libraries — real `.so`/`.dylib`/`.dll` files that
have to exist on disk before any pixel is touched. Those libraries (plus the Haar/LBP cascade XML and any
downloaded model files) are not on your classpath as loadable code; they are payloads *inside* the
bytedeco jars, extracted or fetched **once** and cached. This page explains where those caches live, how
to relocate them, and how to make container cold-starts instant and air-gapped runs possible.

:::tip The one-line version
The first `OpenCv.load()` unpacks ~196 MB of libraries into `~/.javacpp` and reuses it forever after.
For a lean, fast deployment: add exactly **one** platform classifier, and **pre-warm or relocate** that
cache so cold starts don't pay the unpack. The [checklist](#checklist-for-a-lean-fast-deployment) at the
bottom is the whole thing.
:::

## What the first `OpenCv.load()` does

The first `OpenCv.load()` in a fresh environment extracts the platform's native libraries out of the bytedeco jars into a cache directory, once. On Linux that's about **196 MB** under `~/.javacpp`, and every later run reuses it. The extraction is idempotent and **content-addressed**, so re-running never re-extracts and two processes can share the same directory safely.

The libraries are then loaded by absolute path, resolving dependencies on demand — which is what keeps a headless box (no GTK) working (see [Troubleshooting](/troubleshooting#headless)). `load()` is idempotent and thread-safe, so calling it at the top of every entry point costs nothing after the first:

```scala mdoc:silent
import scalacv.*

OpenCv.load()   // extracts + loads on the first call…
OpenCv.load()   // …a no-op on every call after
```

```scala mdoc
OpenCv.isLoaded
```

:::note Why not `Loader.load(classOf[opencv_java])`?
The obvious javacpp one-liner initialises the whole preset graph, and `opencv_highgui` is GTK2-linked on
Linux — so on a headless box it throws and takes `objdetect`, `calib3d`, `features2d` and `video` down
with it. `OpenCv.load()` brings javacpp up through a GUI-free preset and loads only the JNI shim plus what
it actually asks for. Do not "simplify" it back. See [`architecture`](/architecture) and
[`troubleshooting`](/troubleshooting).
:::

## Where the caches live

Three different things get cached, in two different places, by two different mechanisms:

| What | Where | Mechanism | Ships in |
|---|---|---|---|
| Native `.so`/`.dylib`/`.dll` | `~/.javacpp` (relocatable) | extracted by javacpp on first `load()` | the `opencv` + `openblas` classifier jars |
| Cascade XML (Haar/LBP) | javacpp resource cache | extracted on first `Cascades.load` | the bytedeco jars you already depend on |
| Downloaded models (DNN, SFace) | a directory you choose | fetched by `Models.fetch`, SHA-256 verified | nothing — downloaded or `file://`-supplied |

The native cache is the big one and the one worth relocating; the cascade XML is tiny and needs no
management; the models you control entirely.

## Relocating the cache

If `~/.javacpp` doesn't suit you — a read-only home, a container layer you want thin, a cache shared across CI builds — point javacpp elsewhere:

```sh
java -Dorg.bytedeco.javacpp.cachedir=/var/cache/javacpp -jar your-app.jar
```

The directory must be writable on first use; after that it can be mounted read-only. The equivalent env
form, handy for containers where you don't control the `java` invocation:

```sh
export JAVA_TOOL_OPTIONS="-Dorg.bytedeco.javacpp.cachedir=/var/cache/javacpp"
```

## Pre-warming a container image

Because extraction is content-addressed and idempotent, pre-warming the cache in a base image makes the first `load()` in every container **instant** — no 196 MB unpack on cold start. Run a trivial load at build time:

```dockerfile
# ... build your app fat-jar into /app ...
ENV JAVACPP_CACHE=/opt/javacpp
RUN java -Dorg.bytedeco.javacpp.cachedir=$JAVACPP_CACHE \
     -cp /app/app.jar scalacv.smoke   # or any main that calls OpenCv.load()
# The extracted libs are now baked into the image layer.
ENV JAVA_TOOL_OPTIONS="-Dorg.bytedeco.javacpp.cachedir=/opt/javacpp"
```

To keep the *application* layer thin instead, do the opposite: relocate the cache to a mounted volume so it lives outside the image and is shared across replicas.

:::tip Two strategies, one trade-off
**Bake** the cache into the image → fat image, instant and self-contained cold start (best for autoscaling
where a fresh pod must be ready immediately). **Mount** the cache on a shared volume → thin image, cold
start waits on the volume being warm (best when image size or registry cost dominates). Pick per workload.
:::

## Cascade classifier data

Haar/LBP cascade XML is handled the same way: [`Cascades`](/object-detection) extracts the requested XML from the bytedeco payload on demand, and it needs no native library loaded to do it. So a cascade-based detector has nothing extra to ship — the data travels in the jars you already depend on.

```scala mdoc:silent
// Cascade names are typed, not strings. The XML is extracted from the payload on first use.
Cascades.load(CascadeName.FrontalFaceAlt).foreach(_.close())
```

Every bundled cascade is a `CascadeName` case, so a typo is a compile error rather than a silent empty
detector. The full set:

| Domain | `CascadeName` cases |
|---|---|
| Faces | `FrontalFaceAlt`, `FrontalFaceAlt2`, `FrontalFaceDefault`, `ProfileFace` |
| Eyes / features | `Eye`, `EyeTreeEyeglasses`, `LeftEye2Splits`, `RightEye2Splits`, `Smile` |
| Body | `FullBody`, `UpperBody`, `LowerBody` |
| Plates | `RussianPlateNumber` |

If you only need `resolve` (the extracted file, not a loaded classifier), that too needs no native load —
useful for shipping the XML somewhere else. See [`object-detection`](/object-detection) for the detector
side and [`face-recognition`](/face-recognition) for the DNN alternative.

## Downloaded model files (DNN, face recognition)

Models that are too large or too licence-encumbered to bundle are **downloaded and cached** by [`Models.fetch`](/dnn), which the DNN and face-recognition helpers use. It:

- verifies a pinned SHA-256 on every download **and** every cache hit (a corrupt or tampered file is rejected),
- writes to a temp file and moves it into place only after it verifies, so an interrupted run never leaves a truncated model,
- is idempotent — an already-present, still-matching file is returned without touching the network,
- accepts `http(s)://` **and** `file://` URLs, so a model you already have on disk is just another source.

A `ModelSpec` is the fixed name, the mirror URLs to try in order, and the checksum. Integrity checking is
the default — you pass a `sha256`, and it is checked on download and on every cache hit:

```scala mdoc:silent
import java.nio.file.Paths

val yunet = ModelSpec(
  fileName = "face_detection_yunet_2023mar.onnx",
  urls = Seq(
    "file:///opt/models/face_detection_yunet_2023mar.onnx", // try the local copy first…
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/" +
      "models/face_detection_yunet/face_detection_yunet_2023mar.onnx" // …then the network
  ),
  sha256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
)
```

`Models.fetch` returns the verified path (or a `Left` naming the stage that failed — the directory, every
URL tried, or the checksum):

```scala mdoc:compile-only
val fetched: Either[CvError, java.nio.file.Path] =
  Models.fetch(yunet, Paths.get("/opt/models"))
```

The detectors carry their own specs, so you rarely write one by hand — `FaceDetect.modelSpec` and
`FaceRecognizer.modelSpec` are ready-made with pinned checksums:

```scala mdoc:silent
val faceSpec: ModelSpec = FaceDetect.modelSpec        // YuNet, checksum pinned
val sfaceSpec: ModelSpec = FaceRecognizer.modelSpec   // SFace, checksum pinned
```

```scala mdoc
faceSpec.fileName
```

:::note Opting out of the checksum
For a model with no published hash, `ModelSpec.unverified(name, urls)` builds a spec with **no** integrity
check. It is a deliberate, named opt-out — you lose the tamper/corruption guard — so prefer the verifying
`ModelSpec(...)` whenever a checksum exists.
:::

That `file://` support is the key to **air-gapped / offline** runs: place the model where the spec expects it (or list a `file://` URL first, as above), and no network access is needed at all.

## Choosing the classifier (and GPU variants)

The classifier jar you add decides the size and capabilities of the payload:

| Want | Add |
|---|---|
| CPU, one platform | `opencv:…;classifier=linux-x86_64` (~31 MB) + `openblas:…;classifier=linux-x86_64` (~20 MB) |
| CUDA acceleration | the `-gpu` variant, e.g. `classifier=linux-x86_64-gpu` (much larger) |
| "just make it work anywhere" | `org.bytedeco:opencv-platform:4.13.0-1.5.13` — every platform, ~408 MB |

GPU variants exist for `linux-x86_64`, `linux-arm64` and `windows-x86_64`. There is **no** `windows-arm64` build. For CI and most services, a single CPU classifier is the right, lean choice — see [Getting Started](/getting-started).

:::warning Both lines, always
`libopencv_core` links `libopenblas`, so the `openblas` classifier is not optional — omit it and `load()`
fails with an `UnsatisfiedLinkError` that scalacv turns into a `CvError.NativesMissing` telling you exactly
what to add. `opencv-platform` bundles both for every platform, at the ~408 MB cost above.
:::

## Checklist for a lean, fast deployment

- Add exactly the **one** classifier pair (`opencv` + `openblas`) for your target platform, not `opencv-platform`.
- Relocate or pre-warm `~/.javacpp` so cold starts don't pay the 196 MB unpack.
- Ship pinned models via `file://` or a pre-populated cache for offline runs.
- Set a `-Dorg.bytedeco.javacpp.maxPhysicalBytes` ceiling in production so a leak fails fast rather than getting OOM-killed (see [Performance](/performance#measuring-memory-do-it-right)).
- Remember `OpenCv.load()` is idempotent and thread-safe — call it at the top of each entry point, don't gate it behind your own flag.

## Next

- [`getting-started`](/getting-started) — the classifier lines to add, end to end.
- [`troubleshooting`](/troubleshooting) — what a missing or wrong-platform native looks like, and the fix.
- [`mat-lifecycle`](/mat-lifecycle) — the other half of "why scalacv exists": freeing native memory, not just loading it.
