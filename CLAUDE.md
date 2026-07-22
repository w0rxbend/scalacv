# scalacv — agent context

Scala 3 wrapper for OpenCV 4.13 (official Java API via bytedeco javacpp-presets).

## Build
- Mill (latest). `./mill __.compile`, `./mill core.test`, `./mill examples.runMain <fqcn>`
- JDK 17+. Natives: `OpenCv.load()` → `Loader.load(classOf[opencv_java])`

## Conventions
- Latest stable Scala 3; no effects in core; ZIO 2 only in `zio` module
- Public API: no raw int constants, no unmanaged Mats, errors as `CvError` ADT
- scalafmt + scalafix pass before every commit; conventional commits
- ROADMAP.md checkbox flips ship in the same commit as the work

## Verification
- Never claim an OpenCV symbol/version without checking 4.13 docs or resolved jar
- Camera/GUI tests: tag `Hardware`, auto-skip when unavailable

## Do not
- Re-vendor natives in `lib/`
- Drop attribution to mcallisto/scalacv
- Change license or publish coordinates without asking
- Add `Co-Authored-By` or any AI-attribution trailer to commits — the repo owner is the sole author
