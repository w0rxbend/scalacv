# REVIEW.md — adversarial review of ROADMAP.md (Phase V)

Reviewed: `ROADMAP.md` (the merged plan), with `NOTES-audit.md` and `NOTES-upstream.md` as supporting objects. Seven hostile lenses (pins, api-design, missing-work, license, build, consistency, opencv-api) generated findings; every finding was then independently attacked by a verifier agent that re-ran the evidence on this machine and adjudicated the rank. Only findings that survived that second pass appear below; the ones that died are listed in **Dropped on verification** as an audit trail and must not be re-raised.

**Verdict: not safe to execute as written, but the damage is concentrated and cheap to repair.** Two blockers sit in Track A and both stop the plan reaching its own gates — the dependency set in A6 yields a classpath with zero OpenCV Java classes, and nothing anywhere in A–G tells a published consumer how to get natives. Everything else is a should-fix or a nit. No architectural decision (D3b, D4, D5, D9, D13) was successfully attacked; the design is sound and the pin table is, with three exceptions, genuinely verified. The recurring failure mode is **"verified" claims whose evidence does not support the conclusion drawn from it** — E-2, §3.2's recipe, §3.6's headline, and the JDK-25 pin row all fail that test.

Duplicate findings from different lenses have been merged; merged source ids are named so nothing is lost.

---

## Blockers

### B1 · A6's dependency set produces a classpath with zero `org.opencv.*` classes and no loadable natives
*(merges `classifier-dep-set-incomplete`, `openblas-classifier-omitted`)*
**Target:** §4 Track A6 · §2 pin table "Classifiers" and "openblas" rows · §6 experiment E-2

**What's wrong.** E-2 concluded "slim path works" from `show runClasspath` containing `opencv-4.13.0-1.5.13-linux-x86_64.jar`. That evidence is true and does not support the conclusion. A classifier dependency **replaces** the classifier-less artifact rather than adding to it. Measured, Mill 1.1.7, deps exactly as A6 writes them:

```
runClasspath = [scala3-library_3-3.3.8.jar, opencv-4.13.0-1.5.13-linux-x86_64.jar,
                scala-library-2.13.18.jar, openblas-0.3.31-1.5.13.jar, javacpp-1.5.13.jar]
```

No `opencv-4.13.0-1.5.13.jar`. That jar is where `org.opencv.*` lives (1420 `.class` entries, 354 under `org/opencv/`); the classifier jar has **1** class file and zero `org/opencv/` entries — it is natives-only. Second defect: `readelf -d libopencv_core.so.413` → `NEEDED libopenblas.so.0`, and that `.so` ships **only** in `openblas-0.3.31-1.5.13-linux-x86_64.jar`. The classifier-less openblas that resolves transitively is Java-classes-only, so §2's "Mandatory transitive" note is literally true and completely misleading. Runtime proof with a wiped javacpp cachedir, deps = {classifier-less opencv + classifier opencv}:

```
Loader.load(classOf[opencv_core])
  → java.lang.UnsatisfiedLinkError: no jniopenblas_nolapack in java.library.path
```

That is line 104 of §3.2 — the first line of the recipe B1 is built on.

**Why it matters.** Track A cannot reach its own gate: `./mill __.compile` fails on the first `import org.opencv.*`. Patch that and `OpenCv.load()` still dies. Blocks A6→A9, B1, and all of Track B. The "31 MB slim path" number that justified rejecting `opencv-platform` is also the size of a non-functional classpath.

**Fix.** A6 must specify **three** coordinates per platform (the fourth, `javacpp;classifier=$plat`, was tested and is *not* required — a 3-coordinate set loads fine with a wiped cache, and `javacpp-1.5.13.jar` contains no `linux-x86_64/libjnijavacpp.so`; include it only if a per-OS test justifies it):

```scala
mvn"org.bytedeco:opencv:4.13.0-1.5.13"                    // Java API — MANDATORY, classifier-less
mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=$plat"   // opencv natives + libopencv_java
mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=$plat" // libopenblas.so.0
```

Also amend:
- §2 "openblas" row: replace "Mandatory transitive" with "Mandatory — transitive only for the classifier-less jar; the per-platform native jar must be declared explicitly (see A6)."
- §2 "Classifiers" row sizes: the stated 31/25/34 MB are opencv-classifier-only. Real per-platform slim totals (measured, coursier cache): **linux-x86_64 49 MB · macosx-arm64 36 MB · windows-x86_64 80 MB** (openblas-windows-x86_64 alone is 47 MB — it is the *larger* half on Windows). Full 6-jar slim sets: 53.9 / 40.5 / 87.9 MB vs 408 MB for `opencv-platform`.
- §6 E-2's conclusion: rewrite to "`;classifier=` syntax works, but a classifier dep **replaces** the classifier-less artifact — every natives-bearing coordinate must be listed twice." As written it will be re-cited as proof later.
- Note that the D3b path additionally needs `System.load` of `libopencv_java.so`, which lives in the classifier jar — so the classifier jar is required for that too, not just for the preset.

---

### B2 · Nothing in A–G tells a published consumer how to get natives; the POM ships classifier-free
*(merges `no-consumer-natives-story`, `published-pom-drops-classifier`)*
**Target:** §4 Track A6, G5, Track G Gate · §2 "Classifiers" row · Track E4

**What's wrong.** §2 pins three classifiers for *the build*. No track item covers the *consumer*. There is no `runMvnDeps`/`compileMvnDeps` split, no docs page telling a user on `macosx-x86_64` or `linux-arm64` (both shipped by bytedeco) what to add, and no gate that inspects the published coordinate set. Meanwhile Mill 1.1.7 cannot express a classifier in a POM at all: `mill/javalib/publish/model.scala:6` is `case class Artifact(group, id, version)`, `Artifact.fromDep` drops `publication.classifier`, and the string `classifier` occurs zero times in `Pom.scala`. Reproduced twice independently — with A6's deps, `./mill core.pom` emits two byte-identical `<dependency>` blocks for `org.bytedeco:opencv:4.13.0-1.5.13`, neither carrying a classifier.

**Why it matters.** Per §5, M6 tags v0.1.0. That POM declares bare `org.bytedeco:opencv:4.13.0-1.5.13`, which ships zero `.so`/`.dylib`/`.dll`. Every consumer compiles green and dies at the first `OpenCv.load()`, and no page in Track E or F says what else to add. Track G's gate ("`publishLocal` dry-run succeeds") passes happily against exactly this POM — which is why the defect survived the plan's own gates. NOTES-upstream §3.6 already states the correct recommendation verbatim; ROADMAP A6 contradicts the research it was derived from. This is a transcription gap, but ROADMAP is the executable plan.

Two sub-claims are *not* load-bearing and should not be led with: the duplicate `<dependency>` block is cosmetic (a `mvn validate` warning, not a Central rejection), and a `scalacv-platform`-style aggregator *is* expressible in Mill — a classifier-less dep on `org.bytedeco:opencv-platform:4.13.0-1.5.13` passes through the renderer untouched. The real constraint there is 408 MB, not impossibility.

**Fix.**
1. **A6a** — platform classifiers live only on modules that are **not** `PublishModule`s (`examples`, `examples-gui`, test modules). Note the trap: `runMvnDeps` *does* propagate into a published POM as `Scope.Runtime` (`PublishModule.scala:154`), classifier-stripped — so `core.runMvnDeps` reintroduces the exact bug. State that explicitly.
2. State what `core`'s POM **should** depend on, not just what it shouldn't: `core.mvnDeps = Seq(mvn"org.bytedeco:opencv:4.13.0-1.5.13")`, classifier-less. Record "depend on `opencv-platform` so consumers work out of the box at 408 MB" as a considered-and-rejected option in §1.
3. **G-gate replacement** — the current "`publishLocal` dry-run succeeds" is name- and content-blind. Replace it with (a) a golden-file diff of the generated `out/core/pom.dest/*.pom` dependency set, (b) an assertion of no duplicate `groupId:artifactId`, and (c) **a consumer smoke test**: resolve the `publishLocal`'d artifact from a throwaway project on a clean coursier cache and call `OpenCv.load()`. That smoke test is the single highest-value item in this entire review. Do **not** assert "no `<classifier>` in the POM" — `Pom.scala` cannot emit one, so the assertion is a tautological no-op.
4. **E4a / F6** — the natives line must be in the README quick start and the first mdoc snippet, not only a docs subsection. Table the **5** classifiers a JVM consumer can reach (`linux-x86_64`, `linux-arm64`, `macosx-arm64`, `macosx-x86_64`, `windows-x86_64`), mention the three `-gpu` variants exist, then give `opencv-platform` as the it-just-works fallback with its price. Skip `android-*`/`ios-*` — noise. Lead with the classifier recipe, not `opencv-platform`.
5. Amend §2's "Classifiers" row from 3 to those 5 so the pin table stops implying only three platforms are supported.

---

## Should-fix

### S1 · §3.6's headline — "`Object.finalize()` is disabled on JDK 25" — is false, and it is slated for the top of the README
*(merges `finalize-not-disabled-jdk25`, `finalize-not-disabled`, `finalize-not-disabled-on-jdk25` ×2, `false-mat-leak-headline`)*
**Target:** §3.6 · NOTES-upstream.md:778 · B3 · E5 · F6

Reproduced independently by four verifiers on this machine's JDKs (Oracle GraalVM 25.0.3+9.1 and Temurin 25.0.3): 200k finalizable objects + `System.gc()` → `finalize_calls=200000` with default flags, `0` only under `--finalization=disabled`. `java --help-extra` states verbatim "Finalization is enabled by default." JEP 421 deprecated finalization *for removal* and added the opt-out; it did not flip the default, and no shipped JDK (including 26) has. So `CleanableMat.finalize()` **does** free Mat buffers on a stock JDK 25, and the derived absolute "every `Mat` leaks / is NEVER reclaimed automatically" is false. The `javap` half (bytedeco 4.13.0-1.5.13 ships the `java_classic` variant, `finalize()`, no `Cleaner` field) is correct and undisputed.

The engineering conclusion survives intact — B3 (`Mat.use`, `Using.Manager`, `Releasable`, no reliance on GC) is right regardless — so this is a prose defect, not a design defect. But F6 and E5 mandate that this exact sentence lead the README and the docs landing concept, where any reader falsifies it in five lines of Java.

**Fix — rewrite §3.6's mechanism paragraph, leading with the argument that is unassailable:**
1. **GC invisibility (headline).** The collector sees ~100–200 bytes of Java `Mat` header per multi-megabyte pixel buffer. Heap pressure — the only thing that triggers a collection — is uncorrelated with native pressure, so a per-frame loop exhausts native RAM (or the cgroup limit) while the heap stays small and no collection ever runs. Measured: 3000 unreleased 512×512 CV_8UC4 Mats peak at **+2.93 GB RSS** vs flat with `release()`. Cite the asymmetry too — post-GC RSS came back only −310 MB against that +2.93 GB peak, i.e. the finalizer queue lagged badly.
2. **Mechanism-independence (second).** Finalization and `Cleaner` are both non-deterministic, both drain on a single background thread. Do not anchor the pitch on `java_classic`: bytedeco can flip `support_cleaners` on any release and the README would be wrong a second time. Better durable framing: *your Mat's reclamation strategy is an unversioned build-flag detail of your dependency.*
3. **Deprecation (third, phrased as intent not schedule).** Deprecated for removal (JEP 421), already switchable off with `--finalization=disabled` — a flag any downstream consumer's ops team can set without the library author's knowledge — and a future JDK may remove it. Do **not** write "will be removed, at which point this artifact leaks"; that asserts a future fact.
4. Delete the false sentence from **both** ROADMAP §3.6 and NOTES-upstream.md:778, or the error survives in the document the README is written from.
5. Reword B3's "**No GC finalizers**" to "no reliance on GC-driven reclamation (finalizer or `Cleaner`)" — keep the item's substance, it is correct.
6. Drop the "*the* reason for scalacv to exist" framing. Even the corrected argument is one of several (typed enums, `Either`-returning `imread`, loud `CascadeClassifier`), and staking the README's first paragraph on a GC-timing claim invites exactly the drive-by falsification this finding demonstrates.
7. Scope the pitch to the `org.opencv.*` API this project wraps — the javacpp `Pointer`/`PointerScope` path uses a different mechanism.

Reconcile while you are here: §3.6 asserts the `java_classic` (no-Cleaner) variant while NOTES-upstream §7 records that 4.13 removed `finalize()` from 236 classes. The reconciliation (only the `CleanableMat` family retains it; 244 classes in the shipped jar still declare `finalize()`) is nowhere written down.

---

### S2 · Mill 1.1.7's default JVM is `zulu:21`, not `zulu:25` — and G1's three-JDK matrix is unimplementable as written
*(merges `mill-default-jvm-21-not-25`, `mill-default-jvm-is-21-not-25`)*
**Target:** §2 "JDK (build)" row · D10 · A5 · G1 · NOTES-upstream §0

Mill 1.1.7's shipped constants say so: `mill/constants/BuildInfo.buildinfo.properties` → `defaultJvmVersion=zulu\:21`. Reproduced in clean scratch projects by two verifiers: header `//| mill-version: 1.1.7` only → `core.runMain` prints `JVM=21.0.10`; ambient `JAVA_HOME` = GraalVM 25.0.3 is **ignored**; adding `//| mill-jvm-version: zulu:25` → `25.0.2`. `show core.javaHome` → `null`, so modules inherit Mill's own provisioned JVM. NOTES-upstream marked this CONFIRMED from `mill-build.org/mill/cli/build-header.html` — the **unversioned** docs page; `.../mill/1.1.7/cli/build-header.html` is a 404, so it was never checked against the pinned version.

Nothing ships broken (`-java-output-version 17` fixes the bytecode target regardless), but the JDK-25 phenomena the plan reasons about are invisible locally, and B3's leak test would go green on 21 for reasons unrelated to whether the lifecycle API works.

Worse, and under-called by the finding: **G1's matrix is a no-op under every obvious mechanism.** Measured — `MILL_JVM_VERSION=zulu:25` with no header → still 21.0.10 (env ignored for module execution); `MILL_JVM_VERSION=zulu:21` with `//| mill-jvm-version: zulu:25` in the header → still 25.0.2 (header wins). So `actions/setup-java` cannot steer Mill, and a header pin freezes all three rungs to one JVM.

**Fix (two parts, both required).**
1. Add `//| mill-jvm-version: zulu:25` to the `build.mill` header in A5 (NOTES-upstream §2.5's sketch had it; ROADMAP dropped it). Change the §2 row to `JDK (build) | 25 | **must** be pinned via //| mill-jvm-version:; Mill 1.1.7 defaults to zulu 21`. Fix NOTES-upstream §0 line 36 likewise.
2. Add a Track G item for per-rung JVM selection that does not rely on the header or `setup-java`. Verified working: `def jvmId` on the modules, driven by an env var — `def jvmId = Task { sys.env.getOrElse("SCALACV_JDK", "temurin:25") }`, with the matrix setting `SCALACV_JDK`. Add a G1 assertion that each leg prints its expected `java.version`, otherwise this silent fallback recurs undetected.
3. Add an explicit assertion to B3 that it runs on 25 (fail fast if `Runtime.version().feature() < 25`).

---

### S3 · §3.2's "verified end-to-end" recipe does not compile and, taken literally, loads nothing
*(merges `section-3-2-recipe-does-not-run`, `recipe-as-printed-not-runnable`, and the recipe half of `s32-recipe-halfworks` / `loader-recipe-not-executable`)*
**Target:** §3.2 code block and its captured output · B1

Four independent reproductions agree. `Loader.cacheResources(Class, String)` returns `File[]` of length **1** whose sole element is the extracted platform **directory**, not 44 libraries. `Loader.loadGlobal` takes a `String`, not a `File`, so `files.foreach(Loader.loadGlobal)` does not typecheck in Scala. Run with the type patched, it throws `UnsatisfiedLinkError: …/linux-x86_64: cannot read file data: Is a directory`, `libopencv_java.so` is never located, and `System.load` is never reached. The block's own comment describes a highgui filter and an "iterative retry to resolve link order" that the three printed lines do not contain; `<libopencv_java.so>` is an unresolved placeholder; the block is fenced ` ```java ` and written in Scala. The quoted "module libs found = 44" is not reproducible either — verifiers got 39, 40, 42 and 43 depending on the filter, which is itself proof the printed code is not the executed code.

The **concept is sound and load-bearing** — independently confirmed: `Loader.load(classOf[opencv_java])` really does die headless with `no jniopencv_highgui` / `libgtk-x11-2.0.so.0`, and a bare `System.load(libopencv_java.so)` without the `loadGlobal` pre-pass dies with `libopencv_xphoto.so.413: cannot open shared object file`. With directory expansion + filter + retry, it works headless: 0 remaining, `objdetect`/`aruco`/`QRCodeDetector`/`cvtColor` all OK.

**Fix — replace the block with the code that actually ran, fenced ` ```scala `:** `cacheResources(...)[0].listFiles()`, filter `libopencv_*` (match `libopencv_*.so*` — the real files are versioned sonames like `libopencv_core.so.413`; the bare `.so` names are symlinks), **substring**-exclude `highgui` (both `libopencv_highgui.so` and `.so.413` exist), self-exclude `libopencv_java`, exclude `libjni*` (those are the javacpp preset bindings for a *different* API surface and are what drag GTK in), retry until the failure set stops shrinking (never a fixed pass count), then `System.load` the file whose basename is `libopencv_java`.

Three things the fix must also carry:
- **It is Linux-only as printed.** macosx-arm64 ships `libopencv_java.dylib` / `libopencv_*.413.dylib`; windows-x86_64 ships `opencv_java.dll` / `opencv_*4130.dll` with no `lib` prefix. The `libopencv_*.so*` filter matches zero files on both. Derive prefix/extension from `Loader.getPlatform()`. On Windows also beware three lookalike entries — `opencv_java.dll` (3,179,520 B, the real one), `jniopencv_java.dll` (49,664 B, the shim) and `lib/opencv_java.lib` (an MSVC import library, not loadable). Correct rule: basename stripped of `lib` prefix and extension equals `opencv_java`, excluding paths under `lib/`.
- **Openblas co-resolution differs per OS** (see B1): Linux needs both extracted dirs reachable; macOS's `libjniopencv_highgui.dylib` needs `@rpath/libopenblas.0.dylib`, which is **not in the macosx-arm64 opencv jar**. Any per-OS assertion must prove openblas co-resolution, not just filenames.
- **Classloader scoping.** JNI native-method resolution is classloader-scoped: `System.load` must run from a class in the same loader as `org.opencv.*`. The same program run via `java Rx.java` source mode fails with `UnsatisfiedLinkError` on symbols `nm` proves are present; compiled onto the app loader it succeeds. Harmless for a normal jar, lethal under isolated test-runner or OSGi loaders. One line in B1.
- B1 should also assert idempotency (a second `OpenCv.load()` must not re-`System.load`) and record the resolved library path in any failure message.

---

### S4 · Track A's gate passes with zero natives loaded — `Core.VERSION` is a compile-time constant
*(merges `a9-gate-proves-nothing` and the gate half of `s32-recipe-halfworks` / `loader-recipe-not-executable`)*
**Target:** A9 and the Track A Gate · NOTES-upstream §3.4

`javap -p -c org.opencv.core.Core` shows `Core.<clinit>` calls five private helpers and **every one is pure Java**: `getVersion` = `ldc "4.13.0"; areturn`, `getNativeLibraryName` = `ldc "opencv_java4130"`, `getVersionMajorJ` = `iconst_4`, `getVersionStatusJ` = `ldc ""`. `VERSION` is a `public static final String` that javac inlines into the caller's constant pool. Verified: `env -i HOME=/nonexistent java -cp opencv-4.13.0-1.5.13.jar:. V` — java-only jar, no javacpp, no natives, unreachable cache — prints `VERSION=4.13.0`. The gate cannot fail for any reason short of a classpath error. Two verifiers also reproduced the specific false-green: a half-working loader prints `4.13.0` and then dies on `CascadeClassifier_0()`.

NOTES-upstream:618 ("Reading `Core.VERSION` triggers `<clinit>` → a **native** call") is flatly false and :619's framing of `NATIVE_LIBRARY_NAME` as "the exception" is backwards — it is the rule. ROADMAP §3.2's own output block cites `Core.VERSION = 4.13.0` as evidence; only the adjacent `objdetect = true` / `aruco = true` / `module libs found` lines carry weight.

**Fix.** Note the ordering problem first: A9 precedes B1, so there is no correct loader to exercise at Track A time. Either **(a)** demote A9 to "Smoke main compiles and resolves the classifier-selected jars; native health is B1's gate" and strike `prints 4.13.0` from the Track A gate entirely, or **(b)** pull `OpenCv.load()` forward from B1 into Track A and make A9 depend on it. Under either option, do not leave a version-string print in the gate — it reads as a check and is not one.

When a real probe is added, keep it small: `Core.getVersionString()` (a genuine `_0` native) asserted `startsWith("4.13.0")` **and** equal to `Core.VERSION` — that one line catches both "no natives" and "natives are a different OpenCV build than the Java classes". Then `new Mat(3,3,CvType.CV_8UC1)` and `new CascadeClassifier()` (the highgui/GTK cascade that takes objdetect down), optionally `Objdetect.getPredefinedDictionary(0)`. Skip the nine-call battery; `VideoCapture`/`imencode`/`Dnn.Net` belong in the CI matrix, not a build-resurrection gate. Run it under `env -u DISPLAY` (or `-Djava.awt.headless=true`), which A9 currently does not state — otherwise the gate passes on a dev box with GTK and regresses headless.

Caution on B1's own failing test: `nm -D --defined-only libopencv_java.so | grep CascadeClassifier_CascadeClassifier` shows only `_10` and `_11` — the no-arg `CascadeClassifier_0` is **not exported** in 4.13.0-1.5.13, and a verifier misdiagnosed a correct load as a failure because of it. Use the path-taking constructor, `HOGDescriptor`, or `QRCodeDetector`.

Correct NOTES-upstream:618–621 to say the whole of `Core.<clinit>` is constant-folded, and annotate ROADMAP:114 so that line is never re-cited as native-load evidence.

---

### S5 · B3's leak gate measures glibc arena behaviour, not leaks — and three other gate clauses cannot fail
*(merges `rss-leak-gate-invalid`, `unfalsifiable-gates`)*
**Target:** B3 gate clause · Track B Gate · Track E Gate · Track F Gate

RSS is the wrong instrument twice over, both reproduced independently:
- **False negative.** `cv::Mat::create` → `fastMalloc`, which does not zero or touch pages. A leaking test that never writes pixels moves RSS by ~0. Measured in C against glibc: 2000 × 5.9 MB `malloc` with one byte written → RSS 1 MB → 9 MB; the same loop touching one byte per page → 11,259 MB. B3 does not say the buffers are written, and `Mat.use` ergonomics plus B14's synthetic fixtures make an untouched-buffer test the likely thing someone writes.
- **False positive.** glibc's `M_MMAP_THRESHOLD` is dynamic: freeing a 1 MB mmap'd block raises the threshold to that size (capped 32 MB), after which frees return to the arena, not the OS. A *correct* program shows monotonically rising RSS. Measured: +2.76 GB retained after six full GCs with the buffers provably freed.
- Amplified across G1's matrix: macOS libmalloc behaves differently and has no `MALLOC_MMAP_THRESHOLD_` knob, so the same assertion means something different per runner.

**Fix — replace the gate clause with:**

> **Leak test (primary, all platforms):** 10k `Mat.use` round-trips; assert an internal live-handle counter in `Releasable`/`Mat.use` returns to 0, and `dataAddr() == 0 && empty()` post-release.
> **Leak test (secondary, linux-glibc only, forked JVM, non-gating):** 64 MB touched Mats; assert peak-RSS delta *with* release is < 10% of the delta *without* release.

Details that matter:
- The proposed `mat.nativeObj != 0` assertion is a **tautology** — `release()` calls `n_release`, which frees the data buffer and leaves the `cv::Mat` header alive, so `nativeObj != 0` holds whether or not release was ever called. `Mat.dataAddr()` is public and returns the buffer pointer; that is the assertion that discriminates. Keep `nativeObj != 0` only as a separate use-after-free-is-inspectable test.
- Size the secondary test's Mats **above glibc's 32 MB `DEFAULT_MMAP_THRESHOLD_MAX`** (40–64 MB) so the dynamic threshold cannot swallow them and every free is a real `munmap` — cleaner than the `MALLOC_MMAP_THRESHOLD_=131072` env hack, which is glibc-only and silently no-ops on macOS and musl. Force-touch the buffers.
- Do **not** run the arm under `--finalization=disabled` unconditionally: that flag does not exist on JDK 17, which is G1's floor, and `java --finalization=disabled` aborts at startup there.
- Add: `release()` idempotency, and use-after-release detected in the wrapper (a released flag on the opaque type) rather than handing a freed address to JNI.

**The other three unfalsifiable gate clauses** (real, smaller):
- Track B's gate says "API surface dump reviewed by you" but **no B item produces an API dump**. Add a task emitting the public signature dump to a checked-in golden file and make the gate `git diff --exit-code api.txt`.
- Track E's gate says "all snippets mdoc-compile-checked" but no task runs `mdoc --check` (see S14). Point the gate at that CI job.
- Track F's "renders correctly in both GitHub themes" is human judgement — demote it to a note; F's other two clauses are already checkable.

---

### S6 · The `Releasable` doctrine is undeliverable as stated for B10–B13: 242 of 244 finalizable `org.opencv.*` classes have no `release()`
*(merges `no-release-for-detectors`, `no-release-on-242-classes`)*
**Target:** B3 (`Releasable` given, "No GC finalizers") vs B10/B11/B12/B13 · D4 ("resource-safe") · §3.6

Verified by `javap` on the resolved jar: `CascadeClassifier`, `FaceDetectorYN`, `QRCodeDetector`, `GraphicalCodeDetector`, `ArucoDetector`, `Dictionary`, `Algorithm`, `dnn.Net` all expose `protected void finalize()` and **no** public `release()`/`close()`/`dispose()`. Only `Mat` (and subclasses), `VideoCapture` and `VideoWriter` have `release()`. A jar-wide scan: 244 classes declare `finalize()`, 242 have no public release. B3 never states which regime B10–B13's wrappers are in, and D4 promises "resource-safe" across the board.

Second, smaller defect: `Mat.release()` → `n_release` = `cv::Mat::release()` frees the pixel buffer only. The `cv::Mat` header itself is deleted exclusively by `CleanableMat.finalize()` → `private static native n_delete`. Verified: after `release()`, `nativeObj` is unchanged and `create()` on the same object succeeds. So "release" is not "reclaim".

**The two verifiers disagree on the remedy and the owner must adjudicate.** Both ran on this machine, JDK 25:
- One demonstrated deterministic teardown *works*: every such class has its own `private static native void delete(long)` plus a public `getNativeObjAddr()`; `setAccessible(true).invoke(null, addr)` succeeded for `CascadeClassifier` and `Net`; zeroing the instance field afterwards succeeded ("zeroed final field OK" ×3, then `System.gc()` with no crash); and `delete(0L)` is a safe no-op, so the surviving finalizer degenerates harmlessly.
- The other argues the opposite is unsafe: finalization *is* enabled by default (S1), OpenCV's generated `finalize()` calls `delete(nativeObj)` unconditionally, and if the pointer cannot be reliably zeroed (`protected final long` on `CascadeClassifier`/`Net`/`GraphicalCodeDetector`) the result is a double free — a SIGSEGV at an arbitrary later GC in the *consumer's* process, which no green test can disprove.

**Recommended resolution — conservative default, opt-in fast path:**
1. B3: split the doctrine into two regimes explicitly, and add one line to each of B10–B13 naming its regime.
2. Direct `Releasable` for `Mat`/`MatOf*`/`VideoCapture` (`_.release()`).
3. For the handle classes, default to **scalacv-owned `Cleaner` registration holding only the `long` address**, plus a wrapper `close()` that drops the strong reference and marks the handle unusable so use-after-close is a Scala-level error, not a native crash. Document that reclamation is reachability-based.
4. Offer reflective `delete()` only behind an explicit opt-in, resolved eagerly at class-init via `MethodHandles.privateLookupIn` (never discovered at `close()` time), and only after settling the double-free question empirically — including whether the pointer field can be zeroed on *every* target class, not just `CleanableMat`.
5. **Module-path constraint, missed by both:** the opencv jar ships `META-INF/versions/9/module-info.class` declaring `module org.bytedeco.opencv` with exports but **zero `opens`**. On the classpath the reflection works (verified); on the module path `setAccessible` throws `InaccessibleObjectException` without `--add-opens`. That is the real design constraint and must drive the fallback.
6. Add to §7: pin a test asserting `delete(long)` still exists with that exact signature on every wrapped class. It is private API of generated bindings and can change across javacpp-presets releases — that is the genuine fragility.
7. Drop the unqualified "No GC finalizers" absolutism; scalacv cannot make it true for types whose only OpenCV-provided teardown is a finalizer.
8. Also covered here: `detectMultiScale`, `detectMarkers`, `Net.forward` and `blobFromImage` allocate carrier `MatOfRect`/`List<Mat>` intermediates that B5's copy-out never releases. Add that to B5 or B10.

---

### S7 · B6 never states who owns the Mat an extension method returns
**Target:** B6 vs B3

`grep -niE 'ownership|escape|alias|owns|caller' ROADMAP.md` → nothing. B6 is one line naming 11 allocating ops with no signature and no ownership word; B3 lists `Mat.use`/`Using.Manager`/`Releasable` and stops. The uncovered case is the **unnamed intermediate** in a chain: in `a.gaussianBlur(k).canny(50)` the blurred Mat is never bound to a name, so no `use` block and no `Using.Manager` registration can reach it. (The stronger "structural incompatibility between B3 and B6" framing is wrong — a returned Mat is caller-owned and handled the moment it is named. One hole, not a contradiction. Note also that NOTES-audit §4.1's `toDst`/`PointerScope` mandate is superseded: D3b's `org.opencv.core.Mat` is not a javacpp `Pointer`, so any registry must be scalacv-owned.)

Cost, measured: 3000 unreleased 512×512 CV_8UC4 → +2.93 GB peak RSS. Real, though it is unbounded-until-GC growth rather than an unrecoverable leak (see S1).

**Fix — write the contract into B6 before any code is written:**
1. **The rule:** every allocating op returns a fresh, **caller-owned** Mat; the receiver is never released and never aliased. This also settles the mutates-in-place-vs-returns-new distinction B6 currently ignores — in-place ops (`addVec4iLines`-style, and the drawing ops of S18) must be a visibly different shape: `drawInto`/`!`-suffixed, `Unit`-returning.
2. **An explicit scoped chaining combinator**, not an implicit one: `Mat.pipeline { p => p(img.gaussianBlur(k)).canny(50) }` or `img.through(_.gaussianBlur(k), _.canny(50))`, releasing every value except the final result.
3. Keep `(using Using.Manager)` only as an escape hatch and say why it is not the default: a `Manager` releases at *block* exit, so an implicit manager captured by a 30 fps frame loop converts a fast leak into a slower unbounded one, invisibly at the call site — and it poisons all 11 signatures with a context parameter and complicates Track C's `acquireRelease` bridging.
4. Test: a scalacv-side allocation registry asserting a 3-op chain leaves ≤1 live Mat. Not RSS (see S5).

---

### S8 · D4 calls the core "total"; `CvException` is thrown from ordinary in-memory ops and no track covers translating it
*(merges `total-is-false`, `cvexception-breaks-totality`)*
**Target:** D4 · B2 · B6–B13 signatures

`org.opencv.core.CvException extends java.lang.RuntimeException`, carries only a message String, and has no error-code accessor. Reproduced against loaded 4.13.0 natives: `cvtColor(1ch, BGR2GRAY)`, `equalizeHist(3ch)`, `threshold(3ch, OTSU)`, `GaussianBlur` with even ksize, `resize` to 0×0, `HoughLines` on CV_32F, `findContours` on CV_8UC3, `submat` out of range, `imencode`/`imwrite` with a bad extension all throw. Meanwhile `imread` on a missing file returns an empty Mat and does *not* throw, and `imwrite("/proc/nope/x.png")` returns `false` silently. So B2 draws its `Either` boundary at the one call that doesn't throw, and `CvException` is never named anywhere in ROADMAP.md. D4's literal "synchronous, **total**, resource-safe" is false as a specification and will be quoted verbatim in D8 docs.

Two over-readings to discard: this is not a *silent* failure class (an uncaught `CvException` is loud, with file/line), and the MiMa escalation is backwards — MiMa arms from 0.2.0, so 0.1.0→0.2.0 is exactly the free window for this.

**Fix — add an explicit error-policy item ahead of B6, with a three-way split, not the two-way one originally proposed:**
- **Data-dependent silent failures → `Either[CvError, A]`**: `imread`, `imwrite` (returns `false`), `imencode`/`encode`, `CascadeClassifier.load`, model loading in B11/B13, `VideoCapture.open`/`read`. NOTES-audit §4.2's mandate is broader than B2 ("decode, encode, classifier load, frame grab") — B2 already under-delivers against it. Fold `imwrite` into B15's scope; it currently covers encode only.
- **Preconditions statically preventable → prevent them.** B4's enums and B5's value types already kill the raw-int class; extend with cheap explicit guards (odd ksize, positive dst size, channel/depth check before `cvtColor`/`equalizeHist`/`threshold`) throwing `IllegalArgumentException` with a Scala-side message. Also close the trap that `Canny` silently accepts 3-channel input.
- **Residual `CvException` → propagate, documented per op** (`@throws` scaladoc), at most wrapped in a `ScalacvException` keeping the original as cause. Provide one `Cv.attempt[A](f: => A): Either[CvError, A]` opt-in escape hatch.

Explicitly **do not** model `CvError.Native(code, fn, msg)` by parsing the message: `CvException` has no code field and the message is `cv::Exception::what()`, not an API contract — two different shapes already observed (`(-15:Bad number of channels) in function '…CvtHelper<VScn,…>'` vs `(-215:Assertion failed) !_src.empty() in function 'GaussianBlur'`). Model it as `CvError.Native(message, cause)` with `code: Option[Int]` best-effort, and never branch library logic on the parse. Under MiMa from 0.2.0, baking a fragile string parse into a public ADT is the wrong permanent commitment.

Edit D4's cell: replace "total" with "direct-style" (or "total at the boundary ops; native `CvException` from precondition violations propagates unchecked and is documented per op") and point at the new item.

Also add to B2 the interop line that is missing: `imread` returns an `Either` whose `Right` is an **unowned** Mat; state the documented idiom (`imread(p).map(Mat.use(_)(f))`) and add a test that no leak occurs when an exception is thrown inside a `map` over a `Right` Mat.

---

### S9 · 3 of B4's 9 "enums" are OR-able bitmask flag sets; `threshold` also drops its computed `double`
**Target:** B4 · B6

`javap -constants` on the resolved jar: `THRESH_BINARY=0 … THRESH_TOZERO_INV=4`, `THRESH_MASK=7`, `THRESH_OTSU=8`, `THRESH_TRIANGLE=16`, `THRESH_DRYRUN=128`. The presence of `THRESH_MASK=7` is dispositive — OpenCV masks the low 3 bits off the base mode. `IMREAD_ANYDEPTH=2`, `ANYCOLOR=4`, `LOAD_GDAL=8`, `IGNORE_ORIENTATION=128`, `COLOR_RGB=256`. `BORDER_ISOLATED=16` over 0..5. A flat `enum X(cvValue: Int)` cannot express `THRESH_BINARY | THRESH_OTSU`, so Otsu and Triangle — the most common reason anyone calls `threshold` — are unreachable and users fall back to raw ints. Separately `Imgproc.threshold` returns `double` (the computed threshold) and B6 doesn't say it survives — the exact defect NOTES-audit §2 flagged in the legacy code.

**Fix — split B4, but not into a generic `opaque type Flags[T] = Int` with a free `|`, which is unsound for two of the three:**
- **B4a — six true enumerations** unchanged: `ColorConversion`, `InterpolationFlag`, `LineType`, `HersheyFont`, `ContourRetrieval`, `ContourApproximation`.
- **B4b — `ImreadFlag`:** `IMREAD_UNCHANGED = -1` (all bits set) makes `|` degenerate (`UNCHANGED | ANYDEPTH == -1`), and `IMREAD_REDUCED_COLOR_2 = 17` is itself `16|1` — the reduced-* constants are pre-composed base modes. Model as a closed `enum ImreadMode` (Unchanged, Grayscale, ColorBgr, ColorRgb, ReducedGray2/4/8, ReducedColor2/4/8) plus an explicit modifier set (AnyDepth, AnyColor, LoadGdal, IgnoreOrientation), combined by `ImreadFlag(mode, modifiers*)`.
- **B4b — `ThresholdType`:** Otsu and Triangle are mutually exclusive auto-algorithms; OR-ing both is undefined. Use `ThresholdType(mode, auto: Option[AutoThreshold], dryRun: Boolean)` or `ThresholdMode.Binary.otsu`. Never expose `THRESH_MASK` — it is an internal masking constant.
- **`BorderType`:** plain enum in 0.1.0. `BORDER_ISOLATED` is only meaningful for ROI-aware filter/`borderInterpolate` calls, none of which are in B6's surface. Document as out of scope rather than building a flag algebra.
- **B6:** `threshold` returns a named `ThresholdResult(dst: Mat, computedThreshold: Double)` — not a tuple, so the Mat stays a single owned handle. Always populate it; for non-auto modes OpenCV returns the input `thresh` unchanged.
- Test: `THRESH_BINARY|THRESH_OTSU` round-trips to `cvValue == 8`, and the returned double is non-trivial on a bimodal B14 fixture.

---

### S10 · B9's frame `LazyList` memoizes, and `read()` cannot be timed out from Scala
**Target:** B9 · inherited by C2

`scala.collection.immutable.LazyList` caches every computed element — that is its defining difference from `Iterator`. Both possible implementations are broken: fresh-Mat-per-cell retains every frame while the head is reachable (1080p CV_8UC3 = 6.2 MB/frame), and reused-Mat means `frames.take(3).toList` yields three copies of the same frame. If a consumer releases each frame, re-traversal hands back released Mats, and the next op throws `CvException` (verified) — a late, confusing failure rather than a crash. So the item chartered to kill the highest-frequency leak in the library (`takeImage`) reintroduces it.

`javap org.opencv.videoio.VideoCapture` → `read(Mat)`, `grab()`, `retrieve(...)`, `setExceptionMode(boolean)`, `release()`. **No overload takes a duration**, and `read()` blocks in native code, uninterruptible by `Thread.interrupt`. "with timeout" is not implementable as written.

**Fix.** Replace `LazyList` with a scoped, non-memoizing shape created only inside `VideoCapture.use`, with the frame Mat allocated once and released by the scope: `def foreachFrame[A](cap)(f: Mat => A): Unit` or `def frames[A](f: Mat => A): Iterator[A]` (copy-out before yielding). A plain `Iterator[Mat]` with a "valid until `next()`" contract still leaks unless the wrapper owns and releases that Mat at exhaustion — state that or B9 fails its own charter.

On timeout: `CAP_PROP_OPEN_TIMEOUT_MSEC` and `CAP_PROP_READ_TIMEOUT_MSEC` **do** exist in this jar (verified on `Videoio`), but are honoured only by a subset of backends (FFMPEG/GStreamer/network paths); V4L2 and most local-webcam backends ignore the `set()` and return false. Honest rewrite: drop the bare word "timeout"; specify `setExceptionMode(true)` + best-effort `CAP_PROP_*_TIMEOUT_MSEC` with a documented backend caveat; and for the unplugged-camera case NOTES-audit:109 actually cares about, bound the read loop by iteration/wall-clock around `grab()`/`read()` returning false. Do **not** adopt a watchdog thread calling `release()` on a capture another thread is blocked inside — that is unsafe.

---

### S11 · No track sets `artifactName` — the library would publish as `com.worxbend:core_3` and `:zio_3`
*(merges `artifact-names-core3`, `published-artifactid-is-core_3`)*
**Target:** A5 · G5 · Track A and G gates · §3.3 · NOTES-upstream §2.5

Mill derives artifactId from the module object name. Reproduced under Mill 1.1.7: `object core extends ScalaModule, PublishModule` with organization `com.worxbend` → `./mill show core.artifactMetadata` = `{"group":"com.worxbend","id":"core_3","version":"0.1.0"}`, and the inter-module dep propagates `core_3` into zio's POM. `grep -n artifactName` across all four plan documents → **zero hits**. Track G's gate (`publishLocal` dry-run) is name-blind.

Consequence is real but bounded: Central Portal deployments are `USER_MANAGED` by default and the component list is shown before the human clicks Publish, so this is catchable; worst case one burned pre-1.0 version. `zio_3` does not "collide" with `dev.zio:zio_3` — coordinates are scoped by groupId. The cost is unsearchability plus a permanent dead coordinate.

**Fix.**
1. **Resolve the contradiction first:** §3.3 promises "the artifact is `com.worxbend::scalacv`" (singular) while A5 defines two publishable modules and D4 says "Optional `scalacv-zio`". Decide explicitly whether core publishes as `scalacv` or `scalacv-core`, and make §3.3, F6 and A5 agree — blindly applying a prefix rule ships `scalacv-core` while the README advertises `com.worxbend::scalacv`, which is the same bug one layer up.
2. Prefer **explicit per-module literals** (`def artifactName = "scalacv-core"`). The shared-trait form `def artifactName = "scalacv-" + super.artifactName()` works for top-level objects but inherits Mill's segment-joined default: nest one module and it silently yields `scalacv-outer-zio_3` (verified). `override` is not needed.
3. State in A5 that `examples`/`examples-gui` are plain `ScalaModule` and never publish (see S13), otherwise the prefix rule mints `scalacv-examples*` coordinates.
4. Assert in **both** gates: Track A (`./mill show __.artifactMetadata` matches an expected set — A5 is where the file is written) and Track G (grep `<artifactId>` in the produced POMs — G is what actually stands between the repo and Central).

---

### S12 · G1 has no Windows leg, and §7 item 6 gates on a job that does not exist
**Target:** G1 · §7 item 6 · B1

§2 pins `windows-x86_64` and NOTES-upstream §7.1 explicitly recommends `{ os: windows-latest, jdk: '21', classifier: windows-x86_64 }`; G1 drops it. §7 item 6 ("`imshow` on hosted macOS/Windows runners → gates G1") therefore gates on nothing. Library basenames differ per platform (verified byte counts): `libopencv_java.so` 2,784,856 · `libopencv_java.dylib` 2,163,304 · `opencv_java.dll` 3,179,520.

Two claims from the original finding are **refuted** and should not be repeated: `Loader.loadGlobal(String)` is a portable javacpp API backed by `jnijavacpp` (shipped as `jnijavacpp.dll` in the windows classifier jar), not a POSIX-only construct; and §3.2's `System.load(<libopencv_java.so>)` is placeholder notation, not a hardcoded literal. NOTES-upstream:1573–1586 already carries per-platform binary link analysis (Mach-O `LC_LOAD_DYLIB` and the PE import table) concluding "macOS / Windows are fine on the loading front". Residual risk is a path/name bug in one Scala method, not a broken artifact.

**Fix.** Add the `windows-latest` leg to G1 and amend §7 item 6 so it does not gate on an absent job. Do **not** promote macOS from smoke to full `core.test` — the Linux legs cover algorithmic behaviour; mac and windows exist purely as native-loading smoke tests. Make the cheap version a required check on both: `OpenCv.load()` + `Core.getVersionString()` + `objdetect`/`aruco` reachability, plus an explicit assertion of **openblas co-resolution** per OS (see S3), which is the actual per-OS hazard rather than the filename.

---

### S13 · `publishAll` would push `examples` and `examples-gui` to Central
**Target:** A5 · G5 · Track G gate

`mill.javalib.SonatypeCentralPublishModule/publishAll` defaults to `Tasks.resolveMainDefault("__:PublishModule.publishArtifacts")` (`SonatypeCentralPublishModule.scala:110`) — every `PublishModule` in the tree. No checkbox says only `core` and `zio` extend it. NOTES-upstream §2.5 *does* encode the split (`object demos extends ScalaModule // NOT published`), but ROADMAP A5/G5 do not restate it, and the shared-trait shape makes it easy for all four to inherit `PublishModule`.

Low probability (it requires a deliberate mistake, and the `publishLocal` dry-run would surface extras) but the consequence is permanent, undeletable JavaFX/webcam demo artifacts plus the OpenJFX dependency mess in the published graph.

**Fix.** One line in A5: "only `core` and `zio` extend `PublishModule`; `examples`/`examples-gui` are plain `ScalaModule`." The durable guard is a gate assertion, not a CLI flag: `./mill resolve "__:PublishModule.publishArtifacts"` must list exactly `core.publishArtifacts` and `zio.publishArtifacts`. (Note `./mill resolve __.publishArtifacts` is the wrong selector for the command G5 actually uses, and if you do pin `--publishArtifacts`, Mill's multi-select is brace expansion — `"{core,zio}.publishArtifacts"` — not a bare comma list.)

---

### S14 · mdoc `--check` never runs on a PR; snippet drift only breaks the docs deploy, on master
**Target:** G2 · Track E gate · E9

G2 enumerates PR checks as "compile / test / scalafmt-check / scalafix-check" — no mdoc. E9 is a push-triggered Pages deploy. `grep -n mdoc ROADMAP.md` → 4 hits, none in Track G. NOTES-upstream §5.3 already contains a "PR-only docs gate" block that the merge lost — precisely the "necessary work missing from the tracks" failure mode. Blast radius is docs-only and loud, hence SHOULD not BLOCKER.

**Fix.** Do **not** copy the NOTES §5.3 `cs launch` YAML into a required check: it is labelled **SKETCH** (unlike §5.2's task, which was actually run), and its `sed -E 's/^q?ref:…//'` classpath scrape is fragile against Mill's `show` JSON. Instead make it a Mill task — `docs.mdocCheck` = the verified §5.2 task plus `--check` — and have G2 call `./mill docs.mdocCheck` on the ubuntu/JDK-21 leg only. That keeps one definition shared with the deploy build so the gate and the deploy cannot drift, is reproducible locally, and avoids a second hardcoded mdoc version that Scala Steward won't see. The compiler co-dep must be `s"org.scala-lang::scala3-compiler:${scalaVersion()}"`, never a literal (see N3). Reword the Track E gate to point at that job.

---

### S15 · First `OpenCv.load()` writes ~196 MB into `~/.javacpp`; no track owns the cost, the docs, or an opt-out
**Target:** §3.2 / B1 · Track E · NOTES-upstream §3.7

Measured on this box after running the §3.2 recipe: `du -sh ~/.javacpp` = **196M** — opencv 98M + openblas 98M + javacpp 172K. Inside the opencv cache dir: 916 files, **776** of them `*.h`/`*.hpp`; `include/` 16M, `python/` 7.9M, `share/` 11M, ~29M of unused `libjniopencv_*.so` shims, against ~40M actually needed. `Loader.cacheResources` extracts JAR directories recursively (javacpp `Loader.java:483-498` javadoc). Nothing in ROADMAP mentions the cache location, its size, or `-Dorg.bytedeco.javacpp.cachedir=`.

NOTES-upstream §3.7's "~114 MB / ~131 MB, do not cache `~/.javacpp` in CI" was measured against the on-demand `Loader.load` path, **not** the bulk-directory recipe §3.2 adopted. The real figure is 196 MB — that guidance argues from a number 42% low.

**Fix — the docs half is the load-bearing half; do it first.**
- Getting Started subsection: cache location, **~196 MB** combined footprint, one-time ~0.25 s cost, `-Dorg.bytedeco.javacpp.cachedir=<path>` for containers / read-only home / Lambda. Document the system property rather than inventing a scalacv-specific cache API.
- Correct NOTES-upstream §3.7's figures and restate the CI-caching guidance against 196 MB.
- **Do not** file "enumerate the directory and load only `libopencv_*`" as a footprint fix — by the time you can enumerate, all 98M is already on disk. That is a load-correctness measure (S3), not a size measure.
- Per-resource `cacheResources` on individual `.so` paths *would* avoid extracting headers, but costs the platform-agnostic directory listing (~44 filenames per classifier, changing across releases) and would skip `share/opencv4/haarcascades`, which B10 needs. Treat it as a benchmarked optimisation with a maintenance tax, not a default.
- Free win: `share/` **is** extracted (11M present), which closes §7 open question 1 and unblocks B10.

---

### S16 · D11 is a BLOCKER scheduled at M5, and a Càllisto grant cannot clear the isight-java-derived files
*(merges `license-gate-sequenced-last`, `license-blocker-scheduled-last`)*
**Target:** §3.4 · D11 · F1 · Track F gate · §5

Two real defects, both smaller than originally argued:
1. **Sequencing.** §5 runs M2 Track A → M3 Track B → M5 E+F+G, and F1 ("send the relicense request, or formally adopt the clean-room path") sits in Track F at M5. An unbounded-latency external request to an 11-year-cold gmail is scheduled last. NOTES-audit:342's "decide the window and who sends it" was dropped entirely — ROADMAP has no deadline, no owner, no window. Track F's Gate also omits F1, so Track F can close with D11 unresolved.
2. **Completeness.** §3.4 *does* state that isight-java and chimpler are also unlicensed (line 138) — but it never draws the inference that path 1 (a Càllisto grant) therefore leaves `CamFaceDetect`/`JavaFxUtils` uncured, and F1's "or" lets a reader tick the box on path 1 alone. NOTES-audit §9.2 states the second clean-room requirement; ROADMAP lost the conclusion, not the facts.

Two arguments that do **not** hold and must not be repeated: §3.4's path 2 says "no copied bodies, no copied structure-of-expression" — the non-literal-copying standard, **not** a Chinese wall, so an implementer who has read the original satisfies it; and clean-room isolation was already unrecoverable at M0, since NOTES-audit is a line-level archaeology of the 2015 sources that ROADMAP itself quotes. Also, Track B has almost no 2015 ancestor to copy (B2, B3, B11, B12, B13, B14, B15 are all new; D4 kills the Future API and cake pattern; D3b changes every call site), so "redo Track B" is not a real exit.

**Fix.**
- **F1a → M1:** send the email on ack day, name the sender, record a response window (21 days) in §1 next to D2/D9. It is a *review* deadline, not a gate.
- **Adopt path 2 unconditionally as the working assumption on the same day.** Track B proceeds immediately. A grant, if it ever arrives, is recorded verbatim in NOTICE as a bonus and relaxes nothing already written. **Do not** add "Track B may not start until D11 is resolved" — that hands a veto over the project to an unresponsive stranger's inbox and converts this SHOULD into the blocker it claims to fix.
- **F1b stays in Track F** (transcribe the grant text or the declaration into `NOTICE`) and is added to the Track F Gate.
- Write the provenance rule concretely, not abstractly: "no file under `src/` may be open while writing `core/`; port by re-deriving from the 4.13 javadoc; THIRD-PARTY.md records lineage as attribution, not as a license claim." Because a strict Chinese wall is no longer available, **NOTICE must never use the phrase "clean room"** — say "independently reimplemented against the OpenCV 4.13 Java API; no code copied", which is defensible and true.
- Amend §3.4 to state that a Càllisto grant does not reach `CamFaceDetect`/`JavaFxUtils`, and that D6 requires an independent rewrite (or dropping the demo) on either path. Do not ask rladstaetter for a second grant — that is a second unbounded wait for ~80 lines of JavaFX boilerplate.
- **Missing from every document:** no Track A item deletes `src/main/scala-2.11/`. A1 removes sbt scaffolding, A2 `lib/`, A3 CI dotfiles; the unlicensed 2015 sources are never scheduled for removal. Add that as a Track A (or post-Track-B) item.

---

### S17 · The reference `build.mill` skeleton declares `License.MIT` while D11 mandates Apache-2.0
**Target:** NOTES-upstream.md:422 · A5 · Track F gate

NOTES-upstream §2.5 is the block an implementer will paste at A5, and its `pomSettings` sets `licenses = Seq(License.MIT)` while D11/F2 mandate Apache-2.0. Both symbols exist (`Licence.scala:767` MIT, `:475` `` `Apache-2.0` ``, `:1025` alias `Common.Apache2`), so `License.MIT` compiles and **no gate catches it** — Track F checks only that `PomSettings.licenses` is *populated*. Central artifacts are immutable, so a mis-declared 0.1.0 POM is permanent. The same block also carries `organization = "io.github.w0rxbend"`, contradicting decided D9 `com.worxbend` — that one would be caught (obvious wrong coordinate); the license would not.

**Fix.** Mark §2.5 explicitly non-normative and move the authoritative `pomSettings` (organization, `` License.`Apache-2.0` ``, developers) into **ROADMAP A5 as a checklist item** — A5 currently does not mention `pomSettings` at all. Fill in the `Developer("w0rxbend", "…", …)` ellipsis in the same edit; it is a compile-valid placeholder that would ship into the POM. Strengthen the Track F gate to assert the POM's SPDX id equals the root LICENSE's — verified via `./mill core.pom` + grep of `out/core/pom.dest/*.pom`, or `./mill show core.pomSettings`. (The originally proposed `~/.ivy2/local/.../core.pom` path is an sbt/Ivy-layout guess and will not match Mill 1.1.7's output.)

---

### S18 · Drawing vanished from the plan: no track draws anything, yet B4 mandates two enums only drawing consumes
*(merges `drawing-module-vanished`, `drawing-module-missing`)*
**Target:** D5 · Track B · B4

`grep -i` across ROADMAP.md finds **no** drawing primitive — the single `draw` hit is "withdrawn" in the Lena paragraph. NOTES-audit §4.5 mandated a `scalacv-draw` module (`line`/`rectangle`/`putText`/`drawContours`); D5 collapsed to `core / zio / examples / examples-gui` and the capability disappeared with no note. B4 mandates `LineType` and `HersheyFont`, which nothing in B5–B15 or Track D can consume — two orphaned enums are dispositive that this is an oversight, not a decision. B7's `Seq[PolarLine]`/`Seq[Segment]` and B8's `Seq[Contour]` are unrenderable by the library that produced them, and the legacy code has 8 drawing call sites being dropped silently.

Two inflations to discard: Track D's gate is "`CannyEdges` + `QrDecode` produce asserted file output" — D2 FaceDetectHaar is not in the gate, so nothing stalls; and `Core.FONT_HERSHEY_PLAIN` moving to `Imgproc` in 4.x already has an owner in B4's `HersheyFont` (verified: `Core` has 0 HERSHEY constants, `Imgproc` has 8).

**Fix — do not restore a separate module.** OpenCV drawing is imgproc: headless, no GUI dependency, and it does not violate B15's "zero GUI types in core" (that boundary is JavaFX/HighGUI). A fourth Maven coordinate for ~6 static calls buys a MiMa surface and a cross-module version constraint for nothing. Add in place:

> **B16 · Drawing ops (headless, imgproc-only):** `line`, `rectangle`, `circle`, `putText`, `polylines`, `drawContours` over B5 geometry types and B4's `LineType`/`HersheyFont`. In-place, `Unit`-returning per S7's naming rule.

Note in D5/B15 that drawing stays in `core` because it introduces no GUI dependency, so the omission is closed on the record. Two sub-prescriptions to reject: a separate `Color` value type is redundant (B5 already has `Scalar`; add `Scalar.rgb` constructors), and forcing copy-returning draw variants costs a full-image allocation per frame in exactly the D6 path where it hurts most — offer `annotated`/`copyWith` as opt-in. `drawContours`'s 6 overloads all take `List<MatOfPoint>`, so B16 must convert back from B8's `Seq[Contour]`.

---

### S19 · The pinned classifier set omits `linux-arm64` and `macosx-x86_64`, and A6 selects "per-OS" with no arch dimension
**Target:** §2 "Classifiers" row · A6 · G1

Verified by HEAD against repo1: `linux-arm64` → 200, `macosx-x86_64` → 200, `windows-arm64` → 404. ROADMAP pins three classifiers and says "platform selected per-OS"; the strings `os.arch`, `aarch64`, `arm64` (outside `macos-arm64`) appear nowhere. A literal implementation is a 3-way OS switch that maps an Intel Mac to `macosx-arm64` and an ARM Linux box to `linux-x86_64` — a jar that resolves cleanly then fails at native-load time. For GitHub-hosted CI as scoped the three pins are correct; the exposure is the local-developer path.

**Fix.**
1. **Do not hand-roll an `(os.name, os.arch)` switch** — the JDK reports `aarch64`/`amd64` while bytedeco classifiers are `arm64`/`x86_64`, so the naive match misses on every ARM box. javacpp is already on the classpath: `org.bytedeco.javacpp.Loader.getPlatform()` returns exactly these strings and is what bytedeco itself uses. Add a `-Dscalacv.platform=` override for cross-builds and for CI to pin explicitly rather than sniff.
2. Warn-and-fall-back to `windows-x86_64` on Windows-on-ARM (it runs x86_64 under emulation); hard-error only where no fallback exists.
3. Extend the §2 row to the 5 reachable classifiers (bookkeeping — items 1 and 3 are the substance).
4. The consumer-facing half of this is B2: whichever classifier the *release machine* picks must never reach the published POM.

---

### S20 · B11 misses `FaceDetectorYN`'s two hard runtime contracts
**Target:** B11 · NOTES-upstream's FaceDetectorYN section

Verified against a real detector (YuNet 2023mar, sha256 matching §2, natives loaded):
- The shortest `create` in each family is `create(String, String, Size)` / `create(String, MatOfByte, MatOfByte, Size)` — 12 overloads, **no 1- or 2-arg form**. Model path *and* input `Size` are both mandatory.
- `detect(frame, faces)` throws `CvException` from `face_detect.cpp:133` whenever `frame.size() != getInputSize()` — the normal case for a video stream.
- No-face result is a **0×0 CV_32FC1** empty Mat, not 0×15. One-face result is 1×15 CV_32FC1.
- The `int` return is **1 in both cases** — it is a status flag, not a detection count.

B11 is a bare one-liner and NOTES-upstream records only the arities. The project has committed to `CvError` ADT errors and B10 already carries this kind of annotation; B11 deserves the same.

**Fix — rewrite B11 to:**

> **B11 ·** `FaceDetectorYN` wrapper + YuNet provisioning (232 KB, MIT). `detect()` throws native `CvException` when `frame.size() != getInputSize()`; no-face result is a 0×0 CV_32FC1 Mat (not 0×15); the row count is `faces.rows()`, the `int` return is a status flag; decode the fixed 15 columns (x, y, w, h, 5×landmark xy, score) — assert `cols()==15` rather than reading it; load the ONNX via the `create(String, MatOfByte, MatOfByte, Size)` family (pass an empty `MatOfByte` for config), no temp file.

On the size mismatch, prefer a **fixed-size detector plus a typed error** as the default and auto-resize as opt-in. Unconditional `setInputSize(frame.size())` per frame works (verified) but mutates detector state, triggers dnn input-shape re-setup every frame, and makes a shared detector racy under concurrent `detect`. If auto-resize is used, guard it (`if (frame.size() != getInputSize())`) and document thread-safety. Either way, wrap the call so neither a size mismatch nor a corrupt/missing ONNX at `create` leaks a raw `CvException`.

---

## Nits

- **N1 · `actions/setup-node` is missing from §2's Actions row**, and NOTES carries `@v6`, which is stale — `v7.0.0` shipped 2026-07-14 (ESM migration). Add `setup-node@v7` (tag-checked, not sourced from VitePress docs), close NOTES-upstream open item #16, and note that §5.3/§7.4's workflow snippet still carries `checkout@v5` / `cache@v4` against §2's `@v7` / `@v6` — that inconsistency is the more likely thing to be copied into E9. Node 24 stays.
- **N2 · §2's "31 MB / 25 MB / 34 MB vs 408 MB" is apples-to-oranges** (one jar vs a 36-jar resolution) and implies ~13× when the measured ratio is 7.9×. Superseded by B1's per-platform table; also fix the identical stale ratio at §7/E-2, and pick one unit convention (decimal MB throughout — NOTES mixes MB and MiB for the same bytes).
- **N3 · §2's "mdoc **must** add explicit `scala3-compiler_3:3.3.8` co-dep" is a no-op under D2** — mdoc_3-2.9.1's POM already declares that exact version. But do not delete the co-dep (that makes the build correct-by-coincidence and breaks obscurely on the next D2 bump with `package scala contains object and package with same name: caps`). Restate it as a lockstep invariant written as `s"org.scala-lang::scala3-compiler:${scalaVersion()}"`, never a literal — and fix NOTES-upstream:945, which hardcodes `3.8.4` with the same drift bug in the other direction.
- **N4 · No `CvType`/depth abstraction in B4 or B5**, yet B14's fixtures cannot construct a `Mat(rows, cols, type)` without one and B15/B3 make Mat a managed public type. Add one B5 bullet: `enum Depth(val cvValue: Int)` (CV_8U=0 … CV_16F=7) + `MatType(depth, channels)` whose `cvValue` **delegates to `CvType.makeType`** (do not reimplement `depth + ((cn-1) << 3)`). Motivate it by Mat construction, not by B6 `ddepth` — the legacy `sobel`/`laplacian` signatures do not expose `ddepth` (`CV_16S` is an internal constant) and exposing it would create the footgun.
- **N5 · Sub-Mat/ROI ownership is unmentioned** (`grep submat|roi|clone|alias` → 0 hits) although D2/D6's `findEyes` is built on `new Mat(image, rect)`. ROI is *not* a use-after-free hazard — OpenCV refcounts the data block and releasing the parent while a submat lives is safe (verified) — but two rules belong in B3 and E5: submats **write through** to the parent, and leaving a `use` block releases the handle without freeing the buffer while any alias survives. Expose `isSubmatrix()`; pin "double-release is safe" with a test rather than asserting it.
- **N6 · No CHANGELOG, no RELEASING checklist.** Add one G8 checkbox: `CHANGELOG.md` (Keep a Changelog, meaningful from 0.2.0) + `RELEASING.md` whose core step is: deploy with `publishingType = USER_MANAGED`, inspect the VALIDATED deployment's file list and POM in the Portal, then publish or `DELETE`. **Reject snapshot publishing as the validation story** — Central performs *no* validation on SNAPSHOT deployments (no signature, sources/javadoc or POM-field checks), so it misses exactly the error class it would be added to catch. Reject a `0.1.0-RC1` milestone; it burns a coordinate to test packaging.
- **N7 · `versionScheme` is never set**, so the POM ships no `info.versionScheme` (Mill's default is `None`) and sbt/coursier get no compatibility hint for D13's early-semver policy. Add `def versionScheme = Some(VersionScheme.EarlySemVer)` (needs `import mill.javalib.publish.VersionScheme`) on the publishable trait only, and fold `<info.versionScheme>early-semver</info.versionScheme>` into the Track G POM assertion. Not `SemVerSpec`.
- **N8 · B11 never states vendor-vs-download for the YuNet ONNX** *(merges two findings)*. NOTES-upstream:871 already chose download-on-demand + checksum, gated as integration — ROADMAP just lost it. Amend B11 to: "download `face_detection_yunet_2023mar.onnx` from the git-lfs media endpoint into a cached dir, pinned by commit SHA (not `main`), verify sha256 `8f2383e4…52fa4`, tag `Network` so it auto-skips offline; not vendored, not in the published jar." Extend F4's schema to `asset | upstream URL | ref | SHA-256 | fetch date | SPDX id | notice required in artifact? (Y/N)` — opencv_zoo's root license is Apache-2.0 but per-model licenses override heterogeneously. Leave B13 alone (`Net.fromOnnx` ships no model). Fix F3's Intel/Shiqi-Yu attribution split while there — Shiqi Yu is YuNet/lbpcascade, Intel is the haarcascades.
- **N9 · Scaladoc is built for Central but never published anywhere readable.** Add E10 (dependent on E9): emit `core.scalaDocGenerated` / `zio.scalaDocGenerated` (the *jar* task `docJar` would need an unzip step) into `docs/public/api/{core,zio}/` — separate subdirs, or one multi-root scaladoc invocation, since both modules write an `index.html` at their output root. Copy into `public/` before `vitepress build`, not into `dist/` after. README gets a javadoc.io badge as the pre-site stopgap.
- **N10 · G6 relies on Scala Steward alone**, which by NOTES-upstream §6.5's own source reading cannot update GitHub Actions pins (`parseMillPluginDeps` is hardcoded to `import $ivy.` lines; `millPluginArtifact` returns `""` for Mill 1.x). Append to G6: "· Dependabot (`package-ecosystem: github-actions` **only** — never sbt/maven, it would collide with Steward and Mill is unsupported)". Note in CONTRIBUTING and `.scala-steward.conf` that `mill-scalafix`/`mill-mima` bumps are manual. List `.whitesource` for deletion here too (already an A3 item; it is a sunset Mend app).
- **N11 · A Càllisto grant cannot clear `CamFaceDetect`/`JavaFxUtils`** (isight-java lineage). Covered by S16's §3.4 amendment; the minimal wording is F1 as a conjunction — "obtain the mcallisto grant AND clean-room `CamFaceDetect`/`JavaFxUtils` regardless". Do not open a second grant request.
- **N12 · `LICENSE`/`NOTICE` are repo-root only and never packaged into the jars.** Ecosystem norm is against this (cats, ZIO, os-lib and bytedeco's own opencv jar all ship without `META-INF/LICENSE`), and Maven-ecosystem scanners read the POM `<licenses>` block, which the plan already populates — so this is hygiene, not exposure. If added: an *additive* generated-resources task (`resources = super.resources() ++ Seq(licenseResources())`), never an override of `resources` (that clobbers module resource dirs and hits test modules). `THIRD-PARTY.md` is not required in the jar.
- **N13 · F3 loads courtesy credits into `NOTICE`**, which Apache-2.0 §4(d) makes a propagating obligation. Minimal action: append one conditional to F3 — "under the clean-room path, NOTICE carries only the Intel/Shiqi Yu cascade notice (if the XMLs remain vendored) plus the copyright line; isight-java and chimpler credits live in README §Credits and THIRD-PARTY.md." Do not trim F3 before F1 decides the path, and note the internal tension: if the relicense grant text belongs in NOTICE (it does, conventionally), so does the lineage sentence that gives it a referent.
- **N14 · §7 item 11 (Wayback) is settled — close it.** The snapshot is reachable: `web.archive.org/web/20180611035138/https://github.com/mcallisto/scalacv`, HTTP 200, 54 KB. Its `tree-commit` is `c9bf38951e0338fbfe9d77d018a082e1f186178d` — a commit in *this* repo (2015-05-19, `mario.callisto@gmail.com`); `git rev-list --count` = 36 matches the archived "36 commits", and the root tree is identical. Zero occurrences of "licen", no `octicon-law`. CDX returns only 2 rows, so there is no later snapshot — record that so nobody re-runs it. Amend the hedges in **NOTES-audit §7**, not §3.4 (which contains no hedge to soften), and caveat precisely: upstream had no license as of 2018-06-11 and never advanced past `c9bf3895`; this says nothing about 2018→deletion, nor about the isight-java/chimpler ancestry. Does not change D11's status.
- **N15 · The OpenCV BSD-3 → Apache-2.0 relicensing point is 4.5.0** (4.4.0's LICENSE is the Intel/BSD "License Agreement"; 4.5.0's is Apache-2.0). Closes NOTES-audit §9 item 14. In THIRD-PARTY.md record ordinary provenance pinned to a **commit SHA, not the moving `4.x` branch**. Do not write that the embedded Intel notice "overrides" the repo license — both apply, and the file-level terms add obligations. Cheaper alternative worth deciding first: consume the cascades from the bytedeco jar and vendor none (they are byte-identical — md5s match — so vendoring adds an Intel-notice obligation for zero content).
- **N16 · §7 item 9 is wrong: empty `mimaPreviousVersions` fails the build, it does not no-op.** Confirmed from the artifact — `mill-mima_mill1_3:0.2.2`'s `mimaPreviousArtifacts` body contains `isEmpty → Result$Failure("No previous artifacts configured…")`. Reword §7.9 / NOTES-upstream §6.4 / §8.18 to "do not mix in `Mima` at all until 0.2.0" and move it off the unverified list. Do **not** adopt a `if: startsWith(github.ref, 'refs/tags/v0.2')` CI guard — it silently stops running MiMa at v0.3.0.
- **N17 · JEP 472 restricted-native-access warnings are in NOTES but in no ROADMAP checkbox** (`grep native-access|forkArgs ROADMAP.md` → 0). NOTES-upstream §7.3 Trap 6 already verifies `--enable-native-access=ALL-UNNAMED` silences them and prescribes it in `forkArgs`/`testForkArgs`. Propagate: one bullet in §3.1's cost list, `--enable-native-access=ALL-UNNAMED -Djava.awt.headless=true` in Track A's `forkArgs` (verified safe unconditionally — JDK 17 accepts the flag), one paragraph in E4. Two corrections: under D3c the warning is attributed to **scalacv's own class**, not javacpp, so docs must say so or users grep for the wrong thing; and JPMS consumers need `--enable-native-access=<their.module>`, not `ALL-UNNAMED` — a library cannot enable this for its callers.
- **N18 · §7 triage.** Items 1 and 2 are settled — `share/opencv4/haarcascades/` **is** extracted (17 XMLs at a real path, via the `cacheResources` the recipe already calls), and all three vendored 3.0-era XMLs load in a 4.13 `CascadeClassifier` (`empty=false`), while a bogus path yields `empty=true` with a stderr ERROR and no throw (which also confirms B10's premise). Strike both. Restore NOTES-upstream §9.11 (Mill 1.x vs Steward's `ivy:` scheme) under G6. Annotate dropped items with `moot (D3b)` / `moot (D4)` / `settled` so the ledger is auditable. Add a track item deciding vendor-vs-jar cascades — F3 currently answers a question no item asked.
- **N19 · B7's dtype assumption.** `HoughLines` → Nx1 CV_32FC2 (rho, theta) as the plan assumes, but `HoughLinesP` → Nx1 **CV_32SC4** signed ints, and B7 describes both as CV_32FC2. `Imgproc.HoughLinesWithAccumulator` (6 overloads, Nx1 CV_32FC3 with votes) exists and is unmentioned in Track B. Simplest correct decoder is `Mat.get(i,0): double[]`, which never throws on dtype and handles all three uniformly — typed bulk arrays only if a benchmark justifies them. **Bigger B7 hazard the finding missed:** OpenCV 4.13 "Fixed standard `cv::HoughLines` output shift for rho" (#27992) is a *value* change in the exact API B7 wraps — add a regression test. Then strike §7 item 3.

---

## Verified sound — do not re-litigate

Attacked hard, held up:

- **The whole pin table except three cells.** Re-verified independently against live registries on 2026-07-22 (repo1 directory listings, `maven-metadata.xml`, `git ls-remote --tags` — not NOTES' evidence column): Scala 3.3.8 is still the newest LTS and 3.9.0 is still RC-only; Mill 1.1.7 is the newest final; `opencv 4.13.0-1.5.13` / `openblas 0.3.31-1.5.13` / `javacpp 1.5.13` all confirmed, including the negative claims (`opencv/4.13.0/` → 404, `openblas/0.3.30-1.5.13/` → 404, no 4.12 release); munit 1.3.4, ZIO 2.1.26, mdoc 2.9.1, scalafmt 3.11.4, scalafix 0.14.7, mill-scalafix 0.6.2, mill-mima 0.2.2, MiMa 1.1.6, VitePress 1.6.4 all current. **`actions/setup-java@v6` genuinely does not exist** (v1–v5 only, max v5.6.0) — that flagship negative claim is correct, as are all eight other action pins. YuNet re-downloaded: 232,589 B, sha256 byte-exact, license genuinely MIT. Cross-consistency nobody had checked: munit and scalafmt-core are built against scala3-library 3.3.8, ZIO against 3.3.7 — all ≤ 3.3.8, so none trips §3.1's TASTy wall. The three bad cells are §2's JDK row (S2), Classifiers row (B1/N2) and mdoc row (N3).
- **§3.1's TASTy argument for pinning 3.3.8.** The best-reasoned section in the document. `-java-output-version 17` was executed, not read: emitted class-file major is 61, and it genuinely restricts the API surface (`List.reversed()` rejected). The stated cost reproduces verbatim — 4 `sun.misc.Unsafe`/`LazyVals$` warning lines.
- **D3b, D4, D5, D9, D13.** No lens broke any of them. D3b's `org.opencv.*` choice is a memory-safety win the plan does not even claim: `Mat.release()` is idempotent and refcounted, triple release survives, and releasing a parent while an ROI is live is safe. D9's `com.worxbend` reasoning is airtight (`grep -ix bend|worx` over IANA v2026062302 → 0 hits). D13's MiMa timing is correct.
- **§3.2's premise** (not its code — see S3): `Loader.load(classOf[opencv_java])` really does die headless with `no jniopencv_highgui` / `libgtk-x11-2.0.so.0`, `readelf -d libopencv_java.so` shows 28 OpenCV modules and no highgui, and the bare `System.load` shortcut really does fail — so the `loadGlobal` pre-pass is load-bearing work, not ceremony.
- **The entire licensing analysis.** Upstream 404 confirmed; no LICENSE on any ref including the three PR heads and the renovate branch; javacpp-presets' dual Apache-2.0/GPL+CE election is quoted correctly and is compatible; OpenCV 4.x Apache-2.0 confirmed by fetch; the Intel cascade notice is **self-carrying inside the XML**, which satisfies the binary-redistribution clause by shipping the file; `lib/opencv-300.jar` has no LICENSE entry and a 3-line MANIFEST, understating the plan's own case; §3.5's Lena analysis is thorough and its resolution eliminates the surface entirely.
- **Track B's API assumptions against the real jar.** All 13 imgproc ops in B6 exist with the assumed arities; `FONT_HERSHEY_PLAIN=1`/`LINE_8=8` on `Imgproc` (not `Core`) as §4.3a says; B2's `imread`-returns-empty and B10's silent-empty-`CascadeClassifier` footguns both reproduce; B12's full aruco round trip works (`generateImageMarker(7)` → `detectMarkers` → id 7) and `QRCodeDetector.detectAndDecode` returns `""` not null on failure; B13's `Dnn.readNetFromONNX`/`blobFromImage`/`Net.forward` all present. B5's `Point`/`Rect`/`Size`/`Scalar` are pure-Java POJOs with no finalizer, so copy-out is trivially safe.
- **Two §7 items settled affirmatively during review:** mill-scalafix 0.6.2 + `OrganizeImports` works on Scala 3.3.8 under Mill 1.1.7 with **no** explicit rule dependency (§7.7 → closed), and `VcsVersionModule` with no tags yields `0.0.0-1-<sha>-DIRTY…` rather than crashing (§7.8 → closed, but watch the `DIRTY` suffix leaking into a release build). Also: `mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll` parses the pinned config, and `License.` `` `Apache-2.0` `` exists, so D11 is expressible.
- **Sources and javadoc jars are automatic** — `PublishModule.publishArtifactsDefaultPayload(sources = true, docs = true)`. The one Central requirement the plan gets for free.
- **Milestone ordering.** M3 (Track B) before M5 (Track E) correctly respects mdoc's dependency on a stable core API; Track D's dependence on B11–B13 is correctly ordered.

---

## Dropped on verification

Do not resurrect these; each was raised, attacked, and did not survive.

- **B1 "idempotent, thread-safe" holds only per-classloader** — Mill runs tests in subprocesses, not shared-classloader in-JVM, so the headline scenario does not exist; the residual is the generic JNI contract, and the JEP 472 half is already in NOTES (kept as N17).
- **B8's `Seq[Contour]` leaks N native Mats** — premises true, conclusion doesn't follow: CLAUDE.md forbids unmanaged Mats in the public API, B7 one line above is the structural twin ("replacing the untyped Mat"), and B5 mandates copy-out. A wording gap at most.
- **NOTES-audit §4.1/§2 lifecycle mandates target the rejected bytedeco API** — true, but E5 is told to lead with ROADMAP §3.6, which is written correctly against `org.opencv.*`. NOTES-audit is a superseded research input; patching two of a dozen stale sentences would make it more misleading.
- **GPG signing has no key-generation task** — `./mill mill.javalib.SonatypeCentralPublishModule/initGpgKeys` does exactly that, including keyserver upload, and NOTES-upstream §6.1 already records it.
- **`Hardware`/`Gui` tag mechanism unspecified** — G4 *is* the checkbox for it, at the same granularity as every other line in the document; nothing tagged exists yet, so no false green is possible.
- **GraalVM native-image configs / JPMS module-info undocumented** — `META-INF/native-image/**` is consumed at image-build time by classpath scan and cannot be "bypassed" by a runtime loader; the shipped `resource-config.json` in fact whitelists exactly what the recipe extracts.
- **The §3.4 fallback is not a clean room and saying so in NOTICE is bad faith** — F3's NOTICE spec contains no clean-room declaration; the repo and the 2015 sources are already public, so NOTES-audit discloses nothing new.
- **Mandating history preservation while concluding all-rights-reserved** — NOTES-audit:286 already states and reasons the decision ("notice-only breach; history rewrite is not warranted"); the finding quotes that sentence as evidence it is missing.
- **D11/F2/F3 never name the copyright owner** — that is the mechanical content of executing F2/F3, not a roadmap decision; owner identity is fixed by D9/§3.3.
- **Mill strips `;classifier=` from the POM, so A6 is a trap** — mechanism true (see B2), but the stripping *produces* the POM NOTES-upstream §3.6 prescribes. A POM carrying `<classifier>linux-x86_64</classifier>` would be the actual defect.
- **`mill.javalib.scalafmt` does not exist** — nothing in either document ever says it does; NOTES-upstream §6.2 records the correct `mill.scalalib.scalafmt.ScalafmtModule` with javap evidence.
- **D3b/D4 marked ✅ with no reasoning while smaller decisions got 🔴** — 🔴 means "deviates from what the owner already decided"; PLAN.md §1 and §3 D4 already contain both calls verbatim, and §3.2/§3.6 *are* D3b's cost accounting.
- **B3's leak test is below the noise floor and cannot fail** — refuted by measurement: at n=10,000 with 480×640 CV_8UC3 Mats the release/no-release RSS separation is ~41 MB, correctly signed and run-stable. (The instrument is still wrong for other reasons — see S5.)
- **`cacheResources` cannot be called per-file** — refuted on a cold cache: a per-file resource path returns the file itself (`isFile=true`, 930,127 B), extracted on demand.

---

## Recommended ROADMAP amendments

Ordered so that nothing blocks on something later in the list.

**Before Track A starts**

1. **§4 A6** — rewrite the dependency set to three per-platform coordinates (classifier-less opencv + opencv classifier + openblas classifier). *(B1)*
2. **§2 openblas row** — replace "Mandatory transitive" with the explicit-declaration note. *(B1)*
3. **§2 Classifiers row** — 5 classifiers, per-platform slim totals 49/36/80 MB (6-jar sets 53.9/40.5/87.9 MB) vs 408 MB. *(B1, N2, S19)*
4. **§6 E-2** — rewrite the conclusion: a classifier dep *replaces* the classifier-less artifact. *(B1)*
5. **§2 JDK row + A5** — `//| mill-jvm-version: zulu:25` in the build header; row reads "must be pinned; Mill 1.1.7 defaults to zulu 21". Fix NOTES-upstream §0:36. *(S2)*
6. **§3.2** — replace the code block with the code that actually ran (dir expansion, `libopencv_*` filter, substring highgui exclusion, `libjni*` exclusion, retry-until-stable, terminal `System.load`), fenced ` ```scala `, with per-OS name derivation from `Loader.getPlatform()` and the openblas/classloader caveats. Drop the unreproducible "44". *(S3)*
7. **§3.6** — rewrite the mechanism paragraph: GC-invisibility first, mechanism-independence second, JEP 421 third; delete "finalize() is disabled on JDK 25" here **and** at NOTES-upstream:778; drop the "*the* reason to exist" framing; reword B3's "No GC finalizers". *(S1)*
8. **A9 + Track A gate** — either demote A9 to a compile/resolve check or pull `OpenCv.load()` forward; strike `prints 4.13.0` as evidence either way; add `env -u DISPLAY`. Correct NOTES-upstream:618–621 and annotate ROADMAP:114. *(S4)*
9. **A5** — add the authoritative `pomSettings` checklist (organization `com.worxbend`, `` License.`Apache-2.0` ``, filled-in `Developer`), `artifactName` per module, `versionScheme`, "only `core`/`zio` extend `PublishModule`", and `forkArgs` with `--enable-native-access=ALL-UNNAMED -Djava.awt.headless=true`. Mark NOTES-upstream §2.5 non-normative and fix its `License.MIT` / `io.github.w0rxbend`. *(S11, S13, S17, N7, N17)*
10. **§3.3 vs A5** — decide `scalacv` vs `scalacv-core` once, authoritatively; align F6. *(S11)*
11. **§1 + F1** — split F1: F1a (send the relicense email, named sender, 21-day window) moves to M1 and joins the ack list; F1b (transcribe into NOTICE) stays in Track F and joins the Track F gate. Adopt path 2 as the working assumption immediately; add no M3 gate. Amend §3.4 to state a Càllisto grant does not reach `CamFaceDetect`/`JavaFxUtils`. Add a Track A item deleting `src/main/scala-2.11/` once Track B lands. *(S16, N11)*

**Before Track B code lands**

12. **B6** — write the ownership contract (caller-owned returns, receiver never released/aliased, in-place ops visibly different), add an explicit scoped chaining combinator, relegate `(using Using.Manager)` to an escape hatch. *(S7)*
13. **New item ahead of B6** — the error policy: `Either` for data-dependent failures, guards for preconditions, documented propagation for residual `CvException`, one `Cv.attempt` hatch. Edit D4's "total". *(S8)*
14. **B3** — split the `Releasable` doctrine into two regimes (direct vs handle classes), name the regime in each of B10–B13, default to Cleaner + `close()` semantics with reflective `delete` as a gated opt-in, and record the module-path `--add-opens` constraint. Add the `MatOfRect`/`List<Mat>` intermediates. Add a §7 item pinning `delete(long)`'s existence. *(S6)*
15. **B3 gate** — replace "assert stable RSS" with the two-tier test (live-handle counter + `dataAddr()==0` primary; forked 64 MB relative-delta secondary, linux-glibc only, non-gating). Add release-idempotency and use-after-release detection. Add a JDK-25 assertion. *(S5, S2)*
16. **B4 → B4a/B4b** — six true enums; `ImreadFlag` and `ThresholdType` as mode+modifier constructors; `BorderType` plain with `ISOLATED` deferred; `THRESH_MASK` never exposed. **B6** — `threshold` returns `ThresholdResult`. *(S9)*
17. **B5** — add `Depth`/`MatType` (delegating to `CvType.makeType`) and the submat/ROI aliasing bullet. *(N4, N5)*
18. **B7** — state both dtypes, decode via `Mat.get(i,0): double[]`, add the 4.13 rho-shift regression test; strike §7 item 3. *(N19)*
19. **B9** — replace `LazyList` with a scoped non-memoizing iterator owning one frame Mat; replace "with timeout" with `setExceptionMode(true)` + best-effort `CAP_PROP_*_TIMEOUT_MSEC` + a bounded read loop, with the backend caveat. Propagate to C2. *(S10)*
20. **B11** — the full contract line (mandatory Size, size-mismatch throw, 0×0 no-face Mat, status-flag return, 15-column decode, `MatOfByte` create, download-not-vendor provisioning). *(S20, N8)*
21. **New B16** — headless drawing ops in `core`; note in D5/B15 why no `draw` module. *(S18)*
22. **New Track B item** — emit a golden public-API signature dump; Track B gate becomes `git diff --exit-code`. *(S5)*

**Track E / F / G**

23. **E4 + F6** — the natives section: 5-classifier table, `opencv-platform` fallback with its price, in the README quick start and the first mdoc snippet. *(B2)*
24. **E4/E5** — the `~/.javacpp` cache section (~196 MB, `-Dorg.bytedeco.javacpp.cachedir=`, one-time cost) and the JEP 472 flag paragraph. Correct NOTES-upstream §3.7's 114/131 MB figures. *(S15, N17)*
25. **New E10** — publish Scaladoc into `docs/public/api/{core,zio}/`, wired into E9. *(N9)*
26. **G2** — add `./mill docs.mdocCheck` (a Mill task, not inline `cs launch`) on the ubuntu/JDK-21 leg; repoint the Track E gate. *(S14)*
27. **G1** — add the `windows-latest` leg; add per-rung JVM selection via `def jvmId` + an env var, with a per-leg `java.version` assertion; amend §7 item 6. *(S12, S2)*
28. **Track G gate** — replace "`publishLocal` dry-run succeeds" with: golden-file POM dependency diff, no duplicate `groupId:artifactId`, `./mill resolve "__:PublishModule.publishArtifacts"` lists exactly two modules, `artifactMetadata` matches expectations, `info.versionScheme` present, **and a consumer smoke test** resolving the `publishLocal`'d artifact on a clean cache and calling `OpenCv.load()`. *(B2, S11, S13, N7)*
29. **G6** — append "· Dependabot (github-actions only)"; note manual `mill-scalafix`/`mill-mima` bumps in CONTRIBUTING. *(N10)*
30. **New G8** — `CHANGELOG.md` + `RELEASING.md` (USER_MANAGED deploy → inspect VALIDATED → publish or DELETE). *(N6)*
31. **Track F gate** — add F1b; add "published POM's SPDX id equals the root LICENSE"; demote the theme clause to a note. *(S5, S17, S16)*
32. **F3/F4** — the NOTICE-scope conditional; F4's schema gains SPDX id + notice-required columns; fix the Intel/Shiqi-Yu attribution split. Decide vendor-vs-jar cascades in a real track item. *(N8, N13, N15, N18)*

**Bookkeeping**

33. **§2 Actions row** — add `setup-node@v7`; fix NOTES-upstream's stale `checkout@v5`/`cache@v4` snippet. *(N1)*
34. **§2 mdoc row + E1** — restate the compiler co-dep as a `${scalaVersion()}` lockstep invariant; fix NOTES-upstream:945. *(N3)*
35. **§7** — strike items 1, 2, 3, 11; correct item 9 ("empty `mimaPreviousVersions` fails — do not mix in `Mima` until 0.2.0"); mark items 7 and 8 closed; restore the Steward `ivy:`-scheme question under G6; annotate dropped items `moot (D3b)` / `moot (D4)` / `settled`. *(N14, N16, N18, N19, S15)*
36. **NOTES-audit §7 / §9.14** — close the Wayback item with the caveat, and record OpenCV's relicensing point as 4.5.0. *(N14, N15)*
