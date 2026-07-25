# Phase 0 — Baseline

*Audit of `scalacv` — a Scala 3 wrapper for the OpenCV 4.13 Java API (bytedeco javacpp-presets), built with Mill.*

Commit audited: `fcf898d` (branch `master`). All commands run locally on Linux x86_64; see the [command appendix](#appendix--commands-run) for exact invocations.

---

## Toolchain & versions

| Fact | Value | Source |
|---|---|---|
| Language | Scala **3.3.8** (LTS, deliberately not latest — TASTy forward-compat) | `build.mill:113` |
| Build tool | **Mill 1.1.7** | `.mill-version` |
| JavaCPP preset | **1.5.13** | `build.mill:14` (`opencv = "4.13.0-1.5.13"`) |
| OpenCV | **4.13.0** | `build.mill:14`; runtime warnings quote `OpenCV(4.13.0)` |
| OpenBLAS preset | **0.3.31-1.5.13** | `build.mill:15` |
| Mill JVM (runs Mill) | `zulu:25` | `build.mill:1` (`//| mill-jvm-version: zulu:25`) |
| Module JVM (compile/fork) | `zulu:25` default, overridable via `MILL_JVM_ID` per CI rung | `build.mill:123`, `162` |
| JDK actually present locally | **GraalVM 25.0.3+9 (Oracle)** | `java -version` |
| JDK floor for consumers | **17** (`-java-output-version 17`) | `build.mill:148` |

### Native platform classifiers — **one platform, not all**

The build resolves natives via a **single, machine-derived classifier** (`Deps.platform`, `build.mill:29–46`), i.e. `linux-x86_64` on this host. It deliberately does **not** use `org.bytedeco:opencv-platform` (which would bundle every OS/arch at ~408 MB). Three coordinates are pulled together (`build.mill:61–65`):

- `org.bytedeco:opencv:4.13.0-1.5.13` (classifier-less Java API — the only one that reaches a published POM),
- `org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64` (natives),
- `org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64` (`libopencv_core` has a `NEEDED` on `libopenblas.so.0`).

Consequence for the audit: leak/profiling runs exercise exactly one native build; cross-platform determinism (SIMD paths, thread counts) is **out of scope of what a local run can prove** and is flagged for Phase 3/8.

Native loading is **not** `Loader.load(classOf[opencv_java])` (breaks headless — GTK2-linked `highgui`). It is a bespoke `OpenCv.load()`: GUI-free preset → `cacheResources` → demand-driven `System.load`/`loadGlobal` (`OpenCv.scala:36–128`). `load()` is idempotent and double-checked-locked (`OpenCv.scala:26–31`).

---

## Build & test run

| Step | Command | Exit | Wall time | Notes |
|---|---|---|---|---|
| Full compile | `./mill __.compile` | **0** | first run cold; incremental re-run **0 s** (cached) | no compiler warnings escaped `-Xfatal-warnings` |
| Core suite | `./mill core.test` | **0** | **~8 s** (compile cached) | **454** tests, 0 failed, 0 ignored, across **45** suites |
| Examples suite | `./mill examples.test` | **0** | **~1 s** | **6** tests, 0 failed |
| ZIO suite | `./mill zio.test` | **0** | **~0.6 s** | **10** tests passed, 0 failed |

The `core.test` module is where the whole library is tested: its `moduleDeps` pull in `vision` and `graphs` (`build.mill:225`), so those two published modules are covered by the same 454-test suite even though they have no `test/` directory of their own. `zio` and `examples` are tested separately.

**Total green:** 454 (core+vision+graphs) + 10 (zio) + 6 (examples) = **470 tests, 0 failures**. The suite compiles clean under the strict published-surface flags (`-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`, `-source:future`, `-Xfatal-warnings`; `build.mill:130–149`) — notable because two of those flags (`-Wvalue-discard`, `-Wnonunit-statement`) are explicitly the project's *leak-catchers*.

### Native stderr output (expected, not failures)

The suite prints OpenCV native warnings to stderr; **all are asserted-for negative-path tests, none indicate a crash**:

- `findDecoder imread_('…/definitely-not-here.png'): can't open/read file` — the `imread`-returns-empty path, exercised by `ImagesTest`/`PublicApiTest` (see `Images.read`, which converts this to `Left(DecodeFailed)`; `Images.scala:42–46`, `115–119`).
- `VIDEOIO(CV_IMAGES) … Assertion failed !filename_pattern.empty()` / `number < max_number` — `VideoWriter`/`VideoCapture` negative-path tests in `VideoTest`.

No `SIGSEGV`, no `Unrecognized option`, no `UnsatisfiedLinkError`, no double-free abort was observed in any run.

---

## Fork model — **one JVM per test module, suites run concurrently within it**

This determines whether native state (loaded libs, OpenCV thread-pool arenas, any residue) leaks between tests and contaminates measurements — a Phase 5/6 concern, so it is pinned down here.

- Mill forks **one JVM per test module** (`core.test`, `zio.test`, `examples.test` are separate processes). Fork args are set per module: `--enable-native-access=ALL-UNNAMED`, `--sun-misc-unsafe-memory-access=allow` (JDK ≥ 24 only, gated at `build.mill:79–81`), plus `-Djava.awt.headless=true` (`build.mill:169`).
- **Within** the core JVM, munit runs suites **concurrently on multiple threads** — evidenced by interleaved worker prefixes (`[305-00]`, `[305-09]`, `[305-11]`, …) in the log, where `305` is the one forked process and the suffix is a munit worker. So the existing suite **already** exercises concurrent native access from many threads sharing one loaded OpenCV and one set of `cv::fastMalloc` arenas.

Implications carried forward:

1. Per-test native leak attribution is **not** isolated by the current fork model — 45 suites share one heap and one native arena. A `LeakAssertions` harness (Phase 5) must therefore run its own workload in isolation (ideally a dedicated forked JVM, per the plan's Phase 4 "forked JVM so a segfault is a nonzero exit" note) rather than trusting a whole-suite RSS delta.
2. Concurrency is real today. `Managed` guards release with an `AtomicReference` compare-and-set (`Managed.scala:27`, `88–93`), which is the right primitive for the concurrent-suite reality; Phase 2/3 will test whether every *shared* wrapper is actually safe under it.

---

## Read-only cross-cutting facts established in Phase 0

These bound the later phases and are stated now with evidence so they are not re-litigated:

- **No `PointerScope` anywhere** in `core`, `vision`, `graphs`, `zio` (grep: 0 hits). The library does not use JavaCPP's thread-local scope at all — it replaces it with its own `Managed` (deterministic CAS release) + `NativeFinalizer.disarm` (double-free guard). *The Phase 2 checklist's PointerScope items are therefore mostly N/A and will be answered as "scope model is `Managed`, not `PointerScope`."*
- **No `Indexer` / `BytePointer` / `IntPointer` / `createIndexer` / raw `.ptr()`** anywhere (grep: 0 hits). Raw-buffer access is confined to the bulk `Mat.get/put(0,0,array)` bridge in `Interop.scala`, which is guarded by `isContinuous`/clone (`Interop.scala:36–38`). *Indexer-lifetime hazards are N/A.*
- **No global JVM-property mutation** (`maxBytes`, `maxPhysicalBytes`, `setNumThreads`, `System.setProperty`): 0 hits. The library correctly leaves those to the application. *This is an OK for the Phase 2 "library must not set global properties" item.*
- **No async boundary in library code** — no `Future`, `ExecutionContext`, `.par`, `new Thread`, `ForkJoin` in `*/src` (only test-comment matches). *The "allocate on one thread, close on another" async-leak class is N/A for library code; the only concurrency is munit's own suite-parallelism against shared, CAS-guarded wrappers.*

---

## Appendix — commands run

```bash
# environment
java -version
git rev-parse HEAD                       # fcf898df8318...
cat .mill-version                         # 1.1.7

# build + test (timed via shell $SECONDS; /usr/bin/time is not installed on this host)
./mill __.compile                         # exit 0
./mill core.test                          # exit 0, 454 tests
./mill examples.test                      # exit 0, 6 tests
./mill zio.test                           # exit 0, 10 tests

# test tallies (ANSI stripped first)
sed -E 's/\x1b\[[0-9;]*m//g' coretest.log \
  | grep -oE "finished: [0-9]+ failed, [0-9]+ ignored, [0-9]+ total" \
  | awk '{f+=$2; ig+=$4; t+=$6} END{printf "suites=%d total=%d failed=%d ignored=%d\n",NR,t,f,ig}'
  # -> suites=45 total=454 failed=0 ignored=0

# fork evidence: interleaved munit worker prefixes within one PID
grep -oE "^\[[0-9]+-[0-9]+\]" coretest.log | sort -u   # [305-00]..[305-15]

# cross-cutting scope/leak facts (all 0 hits in */src)
grep -rn --include='*.scala' "PointerScope\|retainReference\|releaseReference" core vision graphs zio
grep -rn --include='*.scala' "createIndexer\|Indexer\|BytePointer\|IntPointer\|FloatPointer" core vision graphs zio
grep -rn --include='*.scala' "maxBytes\|setNumThreads\|System.setProperty" core vision graphs zio
grep -rn --include='*.scala' "Future\|ExecutionContext\|new Thread\|ForkJoin\|parallel" core vision graphs zio
```
