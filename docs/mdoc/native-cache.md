# The native cache & deployment

Everything scalacv needs at runtime that isn't on the classpath — the native libraries, the cascade XML, downloaded model files — is extracted or fetched **once** and cached. This page covers where those caches live, how to relocate them, and how to make container startup instant and air-gapped runs possible.

## What the first `OpenCv.load()` does

The first `OpenCv.load()` in a fresh environment extracts the platform's native libraries out of the bytedeco jars into a cache directory, once. On Linux that's about **196 MB** under `~/.javacpp`, and every later run reuses it. The extraction is idempotent and **content-addressed**, so re-running never re-extracts and two processes can share the same directory safely.

The libraries are then loaded by absolute path, resolving dependencies on demand — which is what keeps a headless box (no GTK) working (see [Troubleshooting](/troubleshooting#it-fails-on-a-headless-server--ci-runner-no-gtk)).

## Relocating the cache

If `~/.javacpp` doesn't suit you — a read-only home, a container layer you want thin, a cache shared across CI builds — point javacpp elsewhere:

```sh
java -Dorg.bytedeco.javacpp.cachedir=/var/cache/javacpp -jar your-app.jar
```

The directory must be writable on first use; after that it can be mounted read-only.

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

## Cascade classifier data

Haar/LBP cascade XML is handled the same way: [`Cascades`](/object-detection) extracts the requested XML from the bytedeco payload on demand, and it needs no native library loaded to do it. So a cascade-based detector has nothing extra to ship — the data travels in the jars you already depend on.

```scala mdoc:silent
import scalacv.*

OpenCv.load()

// Cascade names are typed, not strings. The XML is extracted from the payload on first use.
Cascades.load(CascadeName.FrontalFaceAlt).foreach(_.close())
```

## Downloaded model files (DNN, face recognition)

Models that are too large or too licence-encumbered to bundle are **downloaded and cached** by [`Models.fetch`](/dnn), which the DNN and face-recognition helpers use. It:

- verifies a pinned SHA-256 on every download **and** every cache hit (a corrupt or tampered file is rejected),
- writes to a temp file and moves it into place only after it verifies, so an interrupted run never leaves a truncated model,
- is idempotent — an already-present, still-matching file is returned without touching the network,
- accepts `http(s)://` **and** `file://` URLs, so a model you already have on disk is just another source.

That last point is the key to **air-gapped / offline** runs: place the model where the spec expects it (or point the spec at a `file://` URL), and no network access is needed at all.

## Choosing the classifier (and GPU variants)

The classifier jar you add decides the size and capabilities of the payload:

| Want | Add |
|---|---|
| CPU, one platform | `opencv:…;classifier=linux-x86_64` (~31 MB) + `openblas:…;classifier=linux-x86_64` (~20 MB) |
| CUDA acceleration | the `-gpu` variant, e.g. `classifier=linux-x86_64-gpu` (much larger) |
| "just make it work anywhere" | `org.bytedeco:opencv-platform:4.13.0-1.5.13` — every platform, ~408 MB |

GPU variants exist for `linux-x86_64`, `linux-arm64` and `windows-x86_64`. There is **no** `windows-arm64` build. For CI and most services, a single CPU classifier is the right, lean choice — see [Getting Started](/getting-started).

## Checklist for a lean, fast deployment

- Add exactly the **one** classifier for your target platform, not `opencv-platform`.
- Relocate or pre-warm `~/.javacpp` so cold starts don't pay the 196 MB unpack.
- Ship pinned models via `file://` or a pre-populated cache for offline runs.
- Set a `-Dorg.bytedeco.javacpp.maxPhysicalBytes` ceiling in production so a leak fails fast rather than getting OOM-killed (see [Performance](/performance#measuring-memory-do-it-right)).
