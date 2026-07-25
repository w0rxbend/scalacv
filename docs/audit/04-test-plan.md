# Phase 4 — Test coverage gap analysis

*Read-only. The goal is **contract** coverage, not line coverage.*

Commit `fcf898d`. Baseline: **470 tests green** (Phase 0); 416 uniquely-named `test(...)` cases in the core suite (munit's 454 total includes table/parametrised expansions).

---

## 1. Coverage measurement (Scoverage) — feasibility, honestly

**Not run in this phase, and numbers are not faked.** Producing a Scoverage baseline requires mixing a coverage module into `build.mill`, which is a **source modification** — barred until the investigation report is approved (ground rule). So here is the verified feasibility instead:

- **Scala 3.3.8 supports coverage natively** via the compiler's built-in `-coverage-out:<dir>` instrumentation (in-tree since Scala 3.2) — **no** external compiler plugin needed. This is the mechanism the tooling drives.
- **Mill 1.1.7** exposes it through `mill.contrib.scoverage.ScoverageModule` (report generation via `scoverage-reporter`/`scalac-scoverage-*`). The wiring is: mix `ScoverageModule` into `ScalacvModule`, add the test module's `ScoverageTests`, run `mill __.scoverage.htmlReport`.
- **Caveat that must be validated at wiring time:** the coverage flag adds instrumentation that has occasionally conflicted with `-Xfatal-warnings` + `-Wnonunit-statement` (instrumented calls can read as discarded statements). The build's strict flags may need relaxing *for the coverage run only*. This is a known integration wrinkle, not a blocker.

**Recommendation:** wire Scoverage as the first task after report approval; gate on a **measured** baseline, never a guessed one. Until then, the API-surface map in §3 is the coverage proxy.

## 2. Existing tests, classified

Counts are **keyword tallies over the 416 named tests** (buckets overlap — one test can be both ownership and error-path), reproducible via the greps in the appendix. They characterise *emphasis*, not a partition.

| Bucket | Tests referencing it | Read |
|---|---|---|
| **Happy path** | ~all suites have a worked case | the default |
| **Error / negative path** | **114** (`fail`/`Left`/`throw`/`empty`/`reject`/`require`/`mismatch`/…) | strong |
| **Ownership-contract** | **56** (`release`/`close`/`consume`/`move`/`double-free`/`idempotent`/`borrow`/`spent`) | **strong — see below** |
| **Concurrency** | **2** (`ManagedTest` concurrent-release race; one more) | **weak — gap** |
| **Platform / native-load / skip** | ~42 (incl. `OpenCvLoaderTest` ×15; `assume(SCALACV_CAMERA)` gating in 5 suites) | adequate |

### Ownership-contract coverage is a **strength here, not near-zero**

The plan's prior ("expect ownership-contract coverage near zero") does **not** hold for this codebase. Concretely:

- **`ManagedTest`** (13) asserts: `release` frees the buffer; `release` idempotent (no double-free); **`release` under a concurrent N-thread race frees exactly once** (`CountDownLatch` + threads); access-after-release throws; the spent-handle message; `use` releases on happy path and on throw; `Using.Manager` releases every Mat in reverse order; the `delete(long)` bridge frees + is idempotent; **"after release the Mat owns no native memory (the primary leak assertion)"**.
- **`DoubleFreeTest`** (5) churns **300 allocate-release cycles** of `CascadeClassifier`/`QRCodeDetector`/`ArucoDetector`/`Net` as a crash-canary, and asserts **directly** that `release` zeroes `nativeObj` (the finalizer-disarm mechanism).
- **Woven throughout:** `ImageTest` asserts move semantics ("reused after a transform throws", "query borrows / terminal releases", "mat borrows without consuming"); `VideoTest` the borrow/retire contract; `CameraTest`, `TrackingTest`, `LoopDetector`/`Motion` close-idempotency.

This is genuinely good, and the audit should **credit** it rather than propose re-inventing it.

## 3. Untested surface — the real gaps

### 3a. Paths behind the Phase 1–3 findings (highest value — each is a fix's proof)

| Gap | The finding it proves | Proposed test |
|---|---|---|
| `blurBackground`/`replaceBackground` receiver leak on throw | **P2-1** | `image.blurBackground(mask=blank(2,2))` on a bigger image; assert source Mat released (`totalBytes` returns to baseline) |
| `Animation.gif` eager-render error-path leak | **§3.2** | `gif(frames=5){ i => if i==3 then throw … else pic }`; assert `totalBytes` baseline restored |
| `Intrinsics.cameraMatrix`/`distCoeffs` per-call release | **§3.4** | a leak test looping `undistort`/`Ar.project`; assert bounded growth |
| `withPolygons` upstream residue | **§3.1** | a leak test looping `drawContours` over many frames; **document** the residue slope (may be a known-fail / xfail) |
| pathological-dim int overflow | **P3-1** | unit test asserting a typed error (or `Long` result) for a huge synthetic dimension |

### 3b. Untested public API / error branches (sampling)

- `Recorder` codec-unavailable `Left` path (`Camera.scala:247`) — negative path likely uncovered.
- `Models.fetch` mirror-fallback + checksum-mismatch branches (`Models.scala:77–107`) — one happy `file://` case exists; failure branches (all-mirrors-fail, bad checksum, undownloadable) need cases.
- `OpenCv.satisfy` demand-load recursion / `missingSoname` regexes (`OpenCv.scala:87–158`) — `missingSoname` is `private[scalacv]`, testable directly against the three platform message shapes; likely thin.
- `Cv.attempt`'s bare-`Exception` branch (`Cv.scala:33`) for `std::bad_alloc`-style failures.

### 3c. Test-category gaps

- **Property-based (ScalaCheck): none.** `SlamPropertiesTest` is hand-rolled random sampling (fixed-seed `scala.util.Random` loops), not ScalaCheck — no generators, no shrinking. `munit-scalacheck` is not on the classpath.
- **Golden-image with tolerance: none.** All pixel tests (`PixelHashTest`, `GraphicsAlphaRoiTest`) are **bit-exact** FNV hashes — correct same-platform, fragile cross-platform (Phase 3 §7).
- **Counter-based leak tests: none** (`Pointer.totalBytes`/`physicalBytes` appear in zero tests) → Phase 5.
- **Concurrency failure-mode: thin.** The `AtomicReference` release race is tested, but the *documented single-thread contracts* of the stateful wrappers (`FrameDiff`, `Odometry`, `ObjectTracker`, `Dnn`) have no test that concurrent misuse fails loudly rather than corrupting.

## 4. Proposed test plan (named cases)

### Aliasing tests (prove the ownership docs)
- `crop returns an independent copy — mutating the crop leaves the parent unchanged` (`Image.crop`).
- `copy is independent — mutating the copy leaves the original unchanged`.
- `mat borrows — a draw on the borrowed mat DOES change the parent Image` (the deliberate in-place contract).
- `an Ops result does not alias its receiver — releasing one leaves the other readable` (assert distinct `dataAddr`).

### Property tests (add `munit-scalacheck`)
Generators over `(type ∈ CV_8U..CV_64F) × (channels 1..4) × (rows,cols incl. 1×1, 1×N, N×1) × optional ROI`:
- `imencode(".png") → imdecode` is lossless (8-bit) — PNG roundtrip identity.
- `flip∘flip == identity`; `rotate(R90)∘rotate(R270) == identity`; `transpose∘transpose == identity`.
- `cvtColor BGR→RGB→BGR == identity`.
- `resize up-then-down preserves dimensions`.
- `every Ops op preserves its declared type/channel invariant` (e.g. `canny` ⇒ `CV_8UC1`, `cvtColor` channel count follows the conversion).
- `an unsupported (type,channels) yields CvError.NativeCall, never a crash` (assert typed, run in a forked JVM — §use-after-close).

### Golden-image tests (tolerance, not bytes)
- A small fixed set of transforms (`gray`, `canny`, `blur`, a filter) against committed PNG goldens, asserting **max-abs-diff ≤ T** or **PSNR ≥ T**. Keep the existing bit-exact hashes as *same-platform* regression keys; add these for the *cross-platform* set.

### Use-after-close tests (forked JVM, segfault ⇒ nonzero exit)
- `using a Managed/Image/Camera/Recorder after close throws IllegalStateException` — already partly covered; **add a dedicated forked-JVM test module** (`def testForkGrouping` per-suite, or a separate `leak`/`crash` module) so a regression that *does* segfault is reported as a nonzero exit rather than silently taking down a shared worker. Today `DoubleFreeTest` shares the core JVM.

### Leak tests (Phase 5 harness)
- Per the register in §3a, each looped ≥200× with `LeakAssertions` asserting bounded `totalBytes` growth.

### Concurrency tests
- `N threads sharing one FrameDiff/ObjectTracker fail loudly (or are documented-unsafe) — not silent corruption` — at minimum a test that pins the single-thread contract, ideally with `-Dscalacv.trackOwnership` surfacing the misuse.
- Extend the `Managed` concurrent-release race to `Image`/`Camera`.

## 5. Mutation testing (Stryker4s) — feasibility

**Report, not assume.** Stryker4s's Scala 3 support has historically lagged (it drives sbt/Mill and mutates source; Scala 3 + Mill 1.1.7 is not a documented-supported combination). **Recommendation:** do **not** commit to Stryker4s in the plan. If mutation coverage is wanted, target only the ownership/guard core (`Managed`, `Releasable`, `Cv`) and treat it as an experiment that reports "workable / not" before any gate — never a CI requirement until proven on this exact toolchain. The higher-ROI investment is the property + leak + forked-crash tests above.

---

## Appendix — classification commands

```bash
grep -rhoE 'test\("[^"]+"' core/test/src/scalacv/*.scala | sed -E 's/test\("//' > tests.txt
grep -icE "releas|close|consum|move|double.free|leak|idempot|borrow|spent" tests.txt   # 56 ownership
grep -icE "fail|error|invalid|reject|missing|empty|Left|throw|negativ|require" tests.txt # 114 error-path
grep -icE "concurren|thread|race|parallel|atomic|simultaneous" tests.txt                 # 2 concurrency
grep -rln "totalBytes\|physicalBytes" core/test/ zio/test/                                # none (leak counters)
grep -rn "scalacheck\|forAll\|Gen\." core/test/                                          # none (property tests)
```
