# scalacv — agent context

Scala 3 wrapper for OpenCV 4.13 (official Java API via bytedeco javacpp-presets).

## Build
- Mill (latest). `./mill __.compile`, `./mill core.test`, `./mill examples.runMain <fqcn>`
- JDK 17+. Natives: `OpenCv.load()` → `cacheResources` + `loadGlobal` + `System.load` (see `OpenCv.scala`).
  `Loader.load(classOf[opencv_java])` does **not** work headless — do not "simplify" to it.

## Conventions
- Latest stable Scala 3; no effects in core; ZIO 2 only in `zio` module
- **Scala-first, not Java-facing.** The public surface returns `Seq`/`Option`/`Either` and leans on extension
  methods; Java ergonomics are not a goal. Consumers `import scalacv.*`.
- Two-tier public API, on purpose:
  - **High-level (`Image`)** — manages Mats, hides raw int constants, errors as the `CvError` ADT at
    boundaries. This is the tier the "no raw constants / no unmanaged Mats" rule governs.
  - **Mid-level** — deliberately borrows raw `org.opencv.*` types (`mat`, a `CascadeClassifier`, a
    `FaceDetectorYN`) as a documented escape hatch ("not a wall"). Prefer the `Managed`-taking overload where
    one exists.
- Domain verbs on `Image` (faces, marker AR, pose/track overlays, OCR prep, background) are **extension
  methods** in their domain file, not members of `Image` — keep `Image` itself lean when adding features.
- scalafmt + scalafix pass before every commit; conventional commits
- ROADMAP.md checkbox flips ship in the same commit as the work

## Verification
- Never claim an OpenCV symbol/version without checking 4.13 docs or resolved jar
- Camera/GUI tests: `assume(sys.env.contains("SCALACV_CAMERA"))` (munit `assume`), so they skip when the
  hardware/env is absent rather than fail — run with `SCALACV_CAMERA=1` locally

## Do not
- Re-vendor natives in `lib/`
- Drop attribution to mcallisto/scalacv
- Change license or publish coordinates without asking
- Add `Co-Authored-By` or any AI-attribution trailer to commits — the repo owner is the sole author
