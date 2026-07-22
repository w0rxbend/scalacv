# NOTES-upstream.md — upstream & ecosystem research (Phase R, Agent R2)

**Compiled 2026-07-22.** Synthesis of seven parallel upstream-scouting reports plus an adversarial
verification pass. **Where a scout claim was refuted, the correction is used and the original is
recorded in §8.** Every version number below traces to a command that was actually run or a URL that
was actually fetched; anything that could not be reproduced is marked **UNVERIFIED** in bold.

Registry rules learned the hard way and applied throughout:

- Never read Maven Central `maven-metadata.xml` `<latest>` / `<release>` as "newest stable" — for
  `scala3-compiler_3` it currently reads `3.9.0-RC3`, for `mill-dist` it reads
  `1.2.0-RC1-42-dc1f60`. Use the directory listing or the version list instead.
- Never use GitHub `releases/latest` for Scala 3 — it returns `3.3.8` (the LTS) because GitHub sorts
  by publish date, not semver.
- Never use `search.maven.org/solrsearch` for `org.bytedeco` — its index is stale at `4.10.0-1.5.11`.
- Unauthenticated `api.github.com` is rate-limited from this host; `git ls-remote --tags`,
  `releases.atom`, and Maven Central directory listings were used as substitutes.

---

## 0. Version pin table

**THE section.** Everything the build will need.

### Language / build tool / runtime

| Thing | Pinned version | How verified | Confidence |
|---|---|---|---|
| Scala 3 — **publish** target | **3.3.8** (LTS, released 2026-06-10) | Maven Central `scala3-compiler_3` dir listing `3.3.8/ 2026-06-10 14:07`; scala-lang.org/download "Current 3.3.x LTS release: **3.3.8** Released on June 10, 2026"; scala-lang.org says of LTS: *"Advised to be used for publishing libraries."* | CONFIRMED |
| Scala 3 — **dev / Next** | **3.8.4** (released 2026-06-05) | Maven Central non-RC/non-NIGHTLY max = 3.8.4; `curl https://www.scala-lang.org/download/` → "3.8.4 … Released on June 5, 2026"; `curl -sI .../scala3-compiler_3-3.8.4.jar` → HTTP/2 200 | CONFIRMED |
| Scala 3.9.0 | **DO NOT PIN** — only `3.9.0-RC1/RC2/RC3` exist (RC3 published 2026-07-11) | `curl -o /dev/null -w %{http_code} .../scala3-compiler_3/3.9.0/` → **404**; RC3 jar listed `2026-07-11 18:26` | CONFIRMED |
| Mill | **1.1.7** (published 2026-06-21) | `curl https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-launcher_3/` → `1.1.7/ 2026-06-21 13:04`; `git ls-remote --tags https://github.com/com-lihaoyi/mill` → …1.1.5 1.1.6 1.1.7; docs site header reads "Mill Documentation 1.1.7" | CONFIRMED |
| Mill 1.2.0 | **DO NOT PIN** — `1.2.0-RC1` (2026-06-17) + nightlies only; `curl -w %{http_code} .../mill-dist/1.2.0/` → **404** | Maven Central | CONFIRMED |
| Mill bootstrap script | `https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.1.7/mill-dist-1.1.7-mill.sh` | `curl -sI` → 200, 6647 bytes, contains `DEFAULT_MILL_VERSION="1.1.7"` | CONFIRMED |
| Build JDK (local) | **25.0.3-graal** (`Oracle GraalVM 25.0.3+9.1`), also `25.0.3-tem` present | `java -version`; compiled+ran Scala 3.8.4 and 3.3.8 on it, both printed `ok Red jdk=25.0.3` | CONFIRMED |
| Mill's own default JVM | `zulu:25` (auto-provisioned unless `//\| mill-jvm-version:` set). Mill needs ≥17 to run; user modules supported down to 11 | mill-build.org/mill/cli/build-header.html verbatim | CONFIRMED |
| Bytecode target | `-java-output-version 17` (3.8.4 accepts 17–25 only; 3.3.8 accepts 8–25) | probed each value against the live compiler; `8 is not a valid choice for -java-output-version` on 3.8.4 | CONFIRMED |

### OpenCV / native

| Thing | Pinned version | How verified | Confidence |
|---|---|---|---|
| `org.bytedeco:opencv` | **4.13.0-1.5.13** | `maven-metadata.xml` → `<latest>4.13.0-1.5.13</latest>`, lastUpdated 20260222024936; `cs fetch` resolves | CONFIRMED |
| `org.bytedeco:opencv-platform` | **4.13.0-1.5.13** (~408 MB — see §3 slimming) | `cs resolve` + `cs fetch` (36 files, 427,676,144 bytes) | CONFIRMED |
| `org.bytedeco:javacpp` | **1.5.13** (transitive) | `cs resolve org.bytedeco:opencv-platform:4.13.0-1.5.13` | CONFIRMED |
| `org.bytedeco:openblas` | **0.3.31-1.5.13** (transitive, mandatory) | `cs resolve`; **note 0.3.30-1.5.13 does not exist — that coordinate 404s** | CONFIRMED |
| Linux CI classifier | `linux-x86_64` | present in the 4.13.0-1.5.13 dir listing, 31,411,145 bytes | CONFIRMED |
| macOS CI classifier | `macosx-arm64` | present, 25,157,075 bytes | CONFIRMED |
| Windows CI classifier | `windows-x86_64` | present, 34,464,296 bytes | CONFIRMED |
| javacpp-presets release | 1.5.13, published 2026-02-22 | `gh api repos/bytedeco/javacpp-presets/releases` | CONFIRMED |
| Upstream OpenCV 4.13.0 | released 2025-12-31 (tag `2e1f8da6…`) | `releases.atom` `<updated>2025-12-31T09:44:36Z</updated><title>OpenCV 4.13.0</title>`; `git ls-remote --tags` | CONFIRMED |
| YuNet model | `face_detection_yunet_2023mar.onnx`, 232,589 B, sha256 `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`, **MIT** | downloaded from `media.githubusercontent.com/media/...` and hashed | CONFIRMED |
| SFace model (optional) | `face_recognition_sface_2021dec.onnx`, 38,696,353 B, sha256 `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79`, **Apache-2.0** | downloaded and hashed | CONFIRMED |

### Test / docs / quality

| Thing | Pinned version | How verified | Confidence |
|---|---|---|---|
| munit | **1.3.4** (`org.scalameta::munit`, `_3` published, 2026-07-09) | `maven-metadata.xml` last `<version>1.3.4</version>`, lastUpdated 20260709182258; jar HEAD 200 | CONFIRMED |
| ZIO | **2.1.26** (2026-05-06) | `zio_3` metadata lastUpdated 20260506111749 | CONFIRMED |
| zio-streams / zio-test / zio-test-sbt | **2.1.26** each | per-artifact `<release>2.1.26</release>` | CONFIRMED |
| ZIO test framework FQCN | `zio.test.sbt.ZTestFramework` | Mill 1.1.7 `TestModule.scala:666` `override def testFramework: T[String] = "zio.test.sbt.ZTestFramework"` | CONFIRMED |
| utest (if used instead) | 0.8.9 | `curl .../com/lihaoyi/utest_3/` newest = 0.8.9 | CONFIRMED |
| mdoc | **2.9.1** (`org.scalameta:mdoc_3`, 2026-07-17) | metadata lastUpdated 20260717034811; `mdoc_3-2.9.1.pom 2026-07-17 03:37` | CONFIRMED |
| mdoc co-dependency | **`org.scala-lang:scala3-compiler_3:<your scalaVersion>` MUST be added explicitly** | mdoc 2.9.1 alone resolves `scala3-compiler_3:3.3.8`; named-tuple test failed without the override, compiled with it | CONFIRMED |
| scalafmt | **3.11.4** (2026-07-17), `runner.dialect = scala3` | `scalafmt-core_3` metadata `<release>3.11.4</release>` lastUpdated 20260717092949 | CONFIRMED |
| scalafix | **0.14.7** (2026-06-12) | `scalafix-core_2.13` metadata lastUpdated 20260612170324 | CONFIRMED |
| mill-scalafix | **`com.goyeau::mill-scalafix::0.6.2`** (artifact `mill-scalafix_mill1_3`) | metadata `<release>0.6.2</release>` lastUpdated 20260718155807; pom pins `scalafix-interfaces 0.14.7`; `javap` shows `trait ScalafixModule` + `fix(Seq[String])` | CONFIRMED |
| MiMa core | **1.1.6** | `com/typesafe/mima-core_2.13` metadata `<release>1.1.6</release>` lastUpdated 20260615130138 | CONFIRMED |
| mill-mima | **`com.github.lolgab::mill-mima::0.2.2`** (artifact `mill-mima_mill1_3`, 2026-06-17) | metadata lastUpdated 20260617133516; `javap com.github.lolgab.mill.mima.Mima` shows `mimaPreviousVersions()`, `mimaReportBinaryIssues()` | CONFIRMED |
| VitePress | **1.6.4** (stable, 2025-08-05). `next` = 2.0.0-alpha.18 (2026-07-06) | `registry.npmjs.org/vitepress` dist-tags + time map | CONFIRMED |
| Node | **24** in CI (local `v24.18.0`, npm `11.16.0`) | `node --version`; VitePress deploy guide uses `node-version: 24`. VitePress has **no `engines` field** — the Node floor is docs-only | CONFIRMED |
| Scala Steward mill-plugin | 0.19.1 | `git ls-remote --tags https://github.com/scala-steward-org/mill-plugin` | CONFIRMED |

### GitHub Actions

| Action | Pin | Latest release | How verified | Confidence |
|---|---|---|---|---|
| `actions/checkout` | `@v7` | v7.0.1 | `git ls-remote --tags` → `v7` and `v7.0.1` both `3d3c42e5aac5ba805825da76410c181273ba90b1` | CONFIRMED (tag); **release date 2026-07-20 UNVERIFIED** |
| `actions/setup-java` | **`@v5`** — **there is no `v6` tag**, despite the main-branch README showing `@v6` | v5.6.0 (2026-07-16) | `gh api .../releases/latest` → v5.6.0; `git ls-remote --tags` has v1..v5 only; `v5` == `v5.6.0` == `03ad4de0992f5dab5e18fcb136590ce7c4a0ac95` | CONFIRMED |
| `actions/cache` | `@v6` | v6.1.0 | `git ls-remote --tags` → `v6` == `v6.1.0` == `55cc8345863c7cc4c66a329aec7e433d2d1c52a9` | CONFIRMED (tag); **date UNVERIFIED** |
| `coursier/cache-action` | `@v8` | v8.1.1 (2026-05-12) | `git ls-remote --tags`; `/releases/latest` redirects to `tag/v8.1.1`; page `datetime="2026-05-12T21:28:04Z"`. **Its README still shows `@v7` — stale** | CONFIRMED |
| `coursier/setup-action` | `@v3` (only if `cs`/`scala-cli` needed on PATH) | v3.0.0 | `git ls-remote --tags` → `v3`==`v3.0`==`v3.0.0`==`fd1707a76b027efdfb66ca79318b4d29b72e5a02`, commit date 2026-03-18 | CONFIRMED |
| `actions/configure-pages` | `@v6` | v6.0.0 (2026-03-25) — "upgrade to node 24" | `gh api .../releases` | CONFIRMED |
| `actions/upload-pages-artifact` | `@v5` | v5.0.0 (2026-04-10) — bumps to `upload-artifact` v7, adds `include-hidden-files` | `gh api .../releases` | CONFIRMED |
| `actions/deploy-pages` | `@v5` | v5.0.0 (2026-03-25) — Node 24 | `gh api .../releases`. **Beware the odd `v3.0.2-node.24` tag published later with a copy-pasted body — do not pin it** | CONFIRMED |
| `actions/setup-node` | `@v6` (per VitePress guide) | — | VitePress deploy.md line 165 | CONFIRMED (as doc content); **currency of v6 not independently tag-checked** |
| `scala-steward-org/scala-steward-action` | `@v2` (README's own recommendation) | v2.92.0 (2026-06-26) | `gh api .../releases/latest`; README at tag v2.92.0 uses `@v2` in all 10 examples | CONFIRMED |

### Things that are NOT pinnable

| Thing | Status |
|---|---|
| groupId `bend.worx` | **IMPOSSIBLE.** Reverses to domain `worx.bend`; `.bend` is not an IANA-delegated TLD (checked `data.iana.org/TLD/tlds-alpha-by-domain.txt` v2026062302 — `grep -ix -e bend -e worx` → 0 hits). Sonatype Central requires a DNS TXT record on the exact reversed domain. **Use `io.github.w0rxbend`** (auto-verified for GitHub-signup users) or a real owned domain. |
| Mill classifier-dependency syntax | **UNVERIFIED** — see §9.1 |
| OpenCV 4.14.0 / 5.0.0 | Exist upstream (4.14.0 released 2026-07-19; 5.0.0 2026-06-26) but **bytedeco has no binding**. `4.13.0-1.5.13` remains the only reachable pin. Do not describe 4.13.0 as "the latest OpenCV" in docs. |

---

## 1. Scala 3

### 1.1 The two lines

| Line | Newest | Released | Min JDK | Notes |
|---|---|---|---|---|
| **LTS (3.3.x)** | **3.3.8** | 2026-06-10 | **8** | scala-lang.org: *"Advised to be used for publishing libraries."* |
| **Next (3.8.x)** | **3.8.4** | 2026-06-05 | **17** | *"The default to be used by most users, containing the latest features…"* |
| Next-next | 3.9.0-RC3 | 2026-07-11 | — | **Not released.** docs.scala-lang.org: *"The next Scala 3 LTS release will be Scala 3.9."* |

### 1.2 The trade-off — measured, not recited

TASTy is **backward** compatible but **not forward** compatible. Both directions were executed:

**3.8.4-built library consumed by a 3.3.8 compiler → HARD FAILURE:**

```
class file out384/mylib/Wrapper.class is broken, reading aborted with class
dotty.tools.tasty.UnpickleException: TASTy signature has wrong version.
 expected: {majorVersion: 28, minorVersion: 3}
 found   : {majorVersion: 28, minorVersion: 8}

This TASTy file was produced by a more recent, forwards incompatible release.
To read this TASTy file, please upgrade your tooling.
The TASTy file was produced by Scala 3.8.4.
```

**3.3.8-built library consumed by a 3.8.4 compiler → WORKS.** Test exercised `enum`, `case class`
and an `inline def` across the boundary; the linked program ran and printed `w1`.

**There is no escape hatch on 3.8.4.** Both candidate flags are rejected:

```
$ cs launch org.scala-lang:scala3-compiler_3:3.8.4 -M dotty.tools.dotc.Main -- -Yscala-release 3.3 ...
bad option '-Yscala-release' was ignored
$ ... -- -scala-output-version 3.3 ...
bad option '-scala-output-version' was ignored
```

Bisected: 3.7.4 and 3.3.6 **accept** both flags; 3.8.0, 3.8.3, 3.8.4 all reject them. The capability
was removed in the 3.8.x series. So if you want newer-than-LTS syntax *and* LTS reach, the only
route is building the published artifact with **3.7.4** (and even then, that 3.7.4→3.3 TASTy path is
**UNVERIFIED**).

### 1.3 Recommendation

**Publish with 3.3.8 LTS. Develop/test on JDK 25.** A 3.8.4 artifact is unreadable by every
downstream project still on the line that scala-lang.org itself tells library authors to use. Set
`-java-output-version 17` so the bytecode floor is sane.

Two costs to accept, both verified:

1. **`sun.misc.Unsafe` warnings on JDK 25.** `scala3-library_3-3.3.8.jar` → `scala.runtime.LazyVals$`
   calls `sun.misc.Unsafe::objectFieldOffset`. On JDK 25 any lazy-val code path prints four WARNING
   lines. 3.8.4 does not (on normal lazy-val paths). Verified by `javap -p -c 'scala.runtime.LazyVals$'`
   plus a live run.
2. **3.9.0 is at RC3 and is the designated next LTS.** Re-baseline onto 3.9.x LTS once
   `maven-metadata.xml` lists a final `3.9.0`. **Re-check this pin before the first release.**

### 1.4 JDK support (verified empirically)

- Both 3.8.4 and 3.3.8 compile and run under `Oracle GraalVM 25.0.3+9.1`, zero diagnostics.
- `-java-output-version` accepted choices: **3.8.4 → 17…25** (26 rejected, 16 and 11 rejected);
  **3.3.8 → 8…25**.
- Compiler class-file major version: 3.8.4 `Main.class` = `003d` = 61 = **Java 17**; 3.7.4 = `0034` =
  52 = Java 8. The 3.8 compiler literally cannot load on JDK 8/11.
- Gotcha for scripted invocations: `cs launch org.scala-lang:scala3-compiler_3:$V -M dotty.tools.dotc.Main`
  **fails** with `Could not find package scala from compiler core libraries` unless
  `-classpath $(cs fetch --classpath org.scala-lang:scala3-library_3:$V)` is passed.

### 1.5 Standard-library artifact change in 3.8.x — build-config trap

On **3.8.x** the real standard library ships as `org.scala-lang:scala-library:3.8.4` (4645 files);
`org.scala-lang:scala3-library_3:3.8.4` is an **empty manifest-only shim jar (1 file)** that merely
depends on it. `scala/runtime/LazyVals*.class` lives in `scala-library-3.8.4.jar`. On **3.3.8** the
layout is the old one (`scala3-library_3` + `scala-library:2.13.x`).

Any build code that assumes classes live in `scala3-library_3`, or that parses
`org.scala-lang:scala-library`'s version as "Scala 2", is wrong on 3.8.x.

---

## 2. Mill

### 2.1 Version & bootstrap

Pin **1.1.7**. Install:

```
curl -L https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.1.7/mill-dist-1.1.7-mill.sh -o mill
chmod +x mill
./mill version
```

Version-resolution order, read verbatim out of the downloaded script (lines 37-49):

```sh
if [ -z "${MILL_VERSION}" ] ; then
  if [ -f ".mill-version" ] ; then
    MILL_VERSION="$(tr '\r' '\n' < .mill-version | head -n 1 2> /dev/null)"
  elif [ -f ".config/mill-version" ] ; then
    MILL_VERSION="$(tr '\r' '\n' < .config/mill-version | head -n 1 2> /dev/null)"
  elif [ -f "build.mill.yaml" ] ; then
    MILL_VERSION="$(grep -E "mill-version:" "build.mill.yaml" | sed -E "$TRIM_VALUE_SED")"
  elif [ -n "${MILL_BUILD_SCRIPT}" ] ; then
    MILL_VERSION="$(grep -E "//\|.*mill-version" "${MILL_BUILD_SCRIPT}" | sed -E "$TRIM_VALUE_SED")"
  fi
fi

if [ -z "${MILL_VERSION}" ] ; then MILL_VERSION="${DEFAULT_MILL_VERSION}"; fi
```

`DEFAULT_MILL_VERSION="1.1.7"` is baked in at line 5. `MILL_BUILD_SCRIPT` is whichever of
`build.mill`, `build.mill.scala`, `build.sc` exists. The chain is if/elif — **do not split version
config across `.mill-version` and `build.mill.yaml`**, the first wins and the rest are ignored.

Native launcher is a Graal image: supported on Windows/Mac/Linux x64 and Mac/Linux ARM, **not
Windows-ARM** ("due to limitations in the upstream Graal Native Image tooling, see oracle/graal#9215").
Opt into the JVM launcher with a `-jvm` suffix (`1.1.7-jvm`), which requires a global JVM ≥17.

### 2.2 build.mill idioms — REAL code

Dependency syntax is **`mvn"…"` / `def mvnDeps`**, not `ivy"…"` / `ivyDeps`. Proven by decompiling
the shipped jar:

```
$ javap -cp mill-libs-javalib_3-1.1.7.jar mill.javalib.JavaModule | grep -iE "ivyDeps|mvnDeps"
  public default mill.api.Task$Simple<Seq<mill.javalib.Dep>> mvnDeps();
  ... mandatoryMvnDeps(); allMvnDeps(); compileMvnDeps(); runMvnDeps();
```

Zero `ivyDeps` matches. (Nuance: the `ivy"…"` *string interpolator* still exists as a deprecated
alias — `javap 'mill.javalib.package$DepSyntax'` shows both `Dep mvn(Seq)` and `Dep ivy(Seq)` — but
the `ivyDeps` **task** genuinely does not exist.)

**Multi-module, verbatim from `example/scalalib/basic/7-multi-module/build.mill` @ tag 1.1.7:**

```scala
package build
import mill.*, scalalib.*

trait MyModule extends ScalaModule {
  def scalaVersion = "3.8.2"
  object test extends ScalaTests {
    def mvnDeps = Seq(mvn"com.lihaoyi::utest:0.8.9")
    def testFramework = "utest.runner.Framework"
  }
}

object foo extends MyModule {
  def moduleDeps = Seq(bar)
  def mvnDeps = Seq(mvn"com.lihaoyi::mainargs:0.7.8")
}

object bar extends MyModule {
  def mvnDeps = Seq(mvn"com.lihaoyi::scalatags:0.13.1")
}
```

**Publishing, verbatim from `example/scalalib/publishing/3-publishing/build.mill` @ tag 1.1.7:**

```scala
package build
import mill.*, scalalib.*, publish.*

object foo extends ScalaModule, PublishModule {
  def scalaVersion = "3.8.2"
  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    description = "Hello",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/example",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "example"),
    developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
  )
}
```

Note the Scala 3 style throughout: `package build` header, `import mill.*`, comma-separated mixins
(`extends ScalaModule, PublishModule`). `with` also works.

**YAML build header (`//|`)** — documented keys: `mill-version`, `mill-opts`, `mill-jvm-version`,
`mill-jvm-opts`, `mill-jvm-index-version`, plus `mvnDeps:` and `repositories:` for the meta-build
(build-file plugin deps). Docs example:

```
//| mill-version: 1.1.7
//| mill-opts: ["--jobs=0.5C"]
//| mill-jvm-version: temurin:17.0.6
```

⚠️ **Naming inconsistency in the docs:** the build-header page documents the repository key as
`mill-repositories`, while the plugin-import page shows a bare `repositories:`. Use whichever the
docs for your pinned Mill version show; do not assume both aliases work.

**Multi-file layout**, verbatim from `mill-build.org/mill/large/multi-file-builds.html`:

```scala
// foo/package.mill
package build.foo
import mill.*, scalalib.*

object `package` extends build.MyModule {
  def moduleDeps = Seq(build.bar.qux.mymodule)
  def mvnDeps = Seq(mvn"com.lihaoyi::mainargs:0.7.8")
}
```

`package.mill` files are only discovered in direct subfolders of the root `build.mill` or of another
folder containing a `package.mill` — so an empty intermediate `bar/package.mill` (containing only
`package build.bar`) is required for nesting.

**Warnings-as-errors**, verbatim from `mill/scalalib/module-config.html` line 1119:

```scala
  def scalacOptions: T[Seq[String]] = Seq("-deprecation", "-Werror")
```

The same page also shows the YAML spelling `scalacOptions: [-deprecation, -Xfatal-warnings]`. For
Scala 3 prefer `-Werror`. Define as `super.scalacOptions() ++ Seq(...)` rather than overwriting —
the docs' own `consoleScalacOptions` example assumes inherited options exist.

### 2.3 Test modules

Current idiom (verbatim from `mill/scalalib/testing.html`):

```scala
object test extends ScalaTests, TestModule.Utest {
  def utestVersion = "0.8.9"
}
```

`TestModule` lives in **`mill.javalib`**, not `mill.scalalib` (the docs URL path still says
`scalalib`; the code moved). Source (`libs/javalib/src/mill/javalib/TestModule.scala` @ 1.1.7):

```scala
  trait Munit extends TestModule {
    /** The MUnit version to use, or the empty string, if you want to provide the MUnit-dependency yourself. */
    def munitVersion: T[String] = Task { "" }
    override def testFramework: T[String] = "munit.Framework"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(munitVersion())
          .filter(!_.isBlank())
          .map(v => mvn"org.scalameta::munit::${v.trim()}")
    }
  }
```

```scala
  trait ZioTest extends TestModule {
    def zioTestVersion: T[String] = Task { "" }
    override def testFramework: T[String] = "zio.test.sbt.ZTestFramework"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task { ... mvn"dev.zio::zio-test:$v", mvn"dev.zio::zio-test-sbt:$v" }
```

⚠️ `munitVersion` / `zioTestVersion` **default to `""`, which adds no dependency at all.** You must
override them or supply the dep yourself.

**Full mixin list** (corrected — see §8.1). From `git main` @ `8d7ab8e`, `object TestModule`:
`TestNg`, `Junit4`, `Junit5`, **`Junit6`**, `ScalaTest`, `Specs2`, `Utest`, `Munit`, `Weaver`,
`ZioTest`, **`ScalaCheck`**, **`Spock`**. (`Spock extends Junit5`, `Junit6 extends Junit5`.) The
docs page lists only 9 — it lags the source. **UNVERIFIED** which of Junit6/ScalaCheck/Spock are in
the 1.1.7 *release* vs main-only; ScalaTest/Munit/Utest are in both.

Other verified facts: **"Mill has no `test`-scoped dependencies!"** — test modules are ordinary
modules nested in the parent, and can `moduleDeps` on each other's test modules.
`def testParallelism = true` is the default.

### 2.4 Publishing traits

- `mill.javalib.PublishModule` — abstract members: `def pomSettings: T[PomSettings]` (line 53) and
  `def publishVersion: T[String]` (line 58). **Both required.**
- `PomSettings` case class fields, decompiled from `mill-libs-javalib_3-1.1.7-sources.jar`:

  ```scala
  case class PomSettings(
      description: String,
      organization: String,
      url: String,
      licenses: Seq[License],
      versionControl: VersionControl,
      developers: Seq[Developer],
      @deprecated("Value will be ignored. Use PublishModule.pomPackagingType instead", "Mill 0.11.8")
      packaging: String = PackagingType.Jar
  ) derives RW
  ```

  All six named fields are required; only the deprecated `packaging` has a default.
- `mill.javalib.SonatypeCentralPublishModule` exists **both** as a trait (line 15) and as an
  `ExternalModule` object (line 94) with `def defaultTask(): String = "publishAll"` (line 106) — which
  is why `./mill mill.javalib.SonatypeCentralPublishModule/` works bare.
- Git-tag versioning: `mill.util.VcsVersionModule` is **core** (artifact `mill-libs-util_3`), not a
  third-party plugin:

  ```
  $ javap -cp mill-libs-util_3-1.1.7.jar mill.util.VcsVersionModule
  public interface mill.util.VcsVersionModule extends mill.api.Module {
    public default mill.api.Task$Simple<java.lang.String> publishVersion();
  }
  ```
  Do **not** use the old `de.tobiasroeser.mill.vcs.version.VcsVersion` coordinate.

### 2.5 Proposed skeleton — **SKETCH** (composed from verified idioms; this exact file was not run)

```scala
//| mill-version: 1.1.7
//| mill-jvm-version: temurin:25

package build
import mill.*, scalalib.*, publish.*

trait ScalacvModule extends ScalaModule, PublishModule {
  def scalaVersion   = "3.3.8"                       // LTS — see §1.3
  def publishVersion = "0.1.0"
  def scalacOptions: T[Seq[String]] = super.scalacOptions() ++ Seq("-deprecation", "-Werror")
  def pomSettings = PomSettings(
    description    = "Scala 3 bindings for OpenCV 4.13.0",
    organization   = "io.github.w0rxbend",           // NOT bend.worx — see §6.1
    url            = "https://github.com/w0rxbend/scalacv",
    licenses       = Seq(License.MIT),
    versionControl = VersionControl.github("w0rxbend", "scalacv"),
    developers     = Seq(Developer("w0rxbend", "…", "https://github.com/w0rxbend"))
  )
}

object core extends ScalacvModule {
  def mvnDeps = Seq(mvn"org.bytedeco:opencv:4.13.0-1.5.13")   // classifier-less; see §3.5
  object test extends ScalaTests, TestModule.Munit {
    def munitVersion = "1.3.4"
  }
}

object zio extends ScalacvModule {
  def moduleDeps = Seq(core)
  def mvnDeps    = Seq(mvn"dev.zio::zio:2.1.26")
  object test extends ScalaTests, TestModule.ZioTest {
    def zioTestVersion = "2.1.26"
  }
}

object demos extends ScalaModule {   // JavaFX / HighGui / camera — NOT published, NOT in CI
  def scalaVersion = "3.3.8"
  def moduleDeps   = Seq(core)
}
```

---

## 3. OpenCV binding

### 3.1 Coursier resolution — real output

```
$ /home/worxbend/.local/bin/cs resolve org.bytedeco:opencv-platform:4.13.0-1.5.13
org.bytedeco:javacpp:1.5.13:default
org.bytedeco:javacpp-platform:1.5.13:default
org.bytedeco:openblas:0.3.31-1.5.13:default
org.bytedeco:openblas-platform:0.3.31-1.5.13:default
org.bytedeco:opencv:4.13.0-1.5.13:default
org.bytedeco:opencv-platform:4.13.0-1.5.13:default
```

```
$ /home/worxbend/.local/bin/cs resolve org.bytedeco:opencv:4.13.0-1.5.13
org.bytedeco:javacpp:1.5.13:default
org.bytedeco:openblas:0.3.31-1.5.13:default
org.bytedeco:opencv:4.13.0-1.5.13:default
```

The bare `opencv` coordinate gives Java classes and **zero natives** — it compiles but fails at
runtime with `UnsatisfiedLinkError`.

`org.bytedeco:opencv` version history jumps `4.10.0-1.5.11 → 4.11.0-1.5.12 → 4.13.0-1.5.13`.
**There is no bytedeco 4.12 release** — any plan phrased "target 4.12 then upgrade" is not executable.

### 3.2 THE structural fact: the jar ships **two** Java APIs

`opencv-4.13.0-1.5.13.jar` is 1,908,057 bytes and contains:

```
$ unzip -l opencv-4.13.0-1.5.13.jar | grep -c 'org/opencv/'          → 354   (322 .class files)
$ unzip -l opencv-4.13.0-1.5.13.jar | grep -c 'org/bytedeco/opencv/' → 1146
$ unzip -l opencv-4.13.0-1.5.13-linux-x86_64.jar | grep -c 'org/opencv/' → 0
```

1. **`org.opencv.*`** — the official OpenCV JNI Java API. Same package/class names the 2015 code
   already uses (`org.opencv.core.Mat`, `org.opencv.imgproc.Imgproc`, `java.util.List<MatOfPoint>`,
   `MatOfRect`, …). 31 packages: `android, aruco, bgsegm, bioinspired, calib3d, core, dnn,
   dnn_superres, face, features2d, highgui, imgcodecs, img_hash, imgproc, ml, objdetect, osgi,
   phase_unwrapping, photo, plot, saliency, structured_light, text, tracking, utils, video, videoio,
   wechat_qrcode, xfeatures2d, ximgproc, xphoto`.
2. **`org.bytedeco.opencv.*`** — the JavaCPP 1:1-to-C++ presets API
   (`org.bytedeco.opencv.opencv_core.Mat`, `org.bytedeco.opencv.global.opencv_imgproc.cvtColor`,
   `MatVector`/`RectVector`/`StringVector` instead of `java.util.List`, `UMat`/`GpuMat` overloads).

**These are two different, non-interoperable APIs in one artifact. `scalacv` must pick one
deliberately, and the choice changes every signature below.** The bytedeco preset API is the
"idiomatic bytedeco" target named in the project brief; the `org.opencv.*` API is what makes a
near-1:1 port of the 2015 code possible.

Also present: `org.bytedeco.opencv.opencv_java`, whose entire body is the loader shim:

```
$ javap -p -c org/bytedeco/opencv/opencv_java.class
public class org.bytedeco.opencv.opencv_java {
  public org.bytedeco.opencv.opencv_java();
    Code: 0: aload_0  1: invokespecial #1  4: return
  static {};
    Code: 0: invokestatic #2  // Method org/bytedeco/javacpp/Loader.load:()Ljava/lang/String;
          3: pop  4: return
}
```

### 3.3 Verified class inventory (all PRESENT in `opencv-4.13.0-1.5.13.jar`)

| Class | Bytes | Notes |
|---|---|---|
| `org/opencv/core/Core.class` | 44,550 | |
| `org/opencv/core/Mat.class` | 24,867 | `extends org.opencv.core.CleanableMat` |
| `org/opencv/core/CleanableMat.class` | — | new in 4.13; see §4.2 |
| `org/opencv/imgproc/Imgproc.class` | — | |
| `org/opencv/imgcodecs/Imgcodecs.class` | — | |
| `org/opencv/videoio/VideoCapture.class` | 5,152 | |
| `org/opencv/highgui/HighGui.class` | 6,306 | Swing-based; see §7.3 |
| `org/opencv/objdetect/CascadeClassifier.class` | — | |
| `org/opencv/objdetect/FaceDetectorYN.class` | 6,545 | |
| `org/opencv/objdetect/FaceRecognizerSF.class` | 3,637 | |
| `org/opencv/objdetect/QRCodeDetector.class` | 2,658 | |
| `org/opencv/objdetect/GraphicalCodeDetector.class` | — | QRCodeDetector's superclass |
| `org/opencv/objdetect/ArucoDetector.class` | 9,232 | bytedeco twin is 21,236 |
| `org/opencv/objdetect/CharucoDetector.class` | 6,664 | |
| `org/opencv/objdetect/BarcodeDetector.class` | 3,936 | |
| `org/opencv/dnn/Dnn.class` | 20,631 | |
| `org/opencv/dnn/Net.class` | 14,847 | bytedeco twin is 15,346 |
| `org/bytedeco/opencv/opencv_java.class` | 1,781 | loader shim only |

### 3.4 Captured `javap` signatures

**`org.opencv.objdetect.FaceDetectorYN`** — no public constructor, **12 static `create` overloads**
in two families:

```
protected org.opencv.objdetect.FaceDetectorYN(long);
public static FaceDetectorYN __fromPtr__(long);
public long getNativeObjAddr();
public void setInputSize(org.opencv.core.Size);  public Size getInputSize();
public void setScoreThreshold(float);            public float getScoreThreshold();
public void setNMSThreshold(float);              public float getNMSThreshold();
public void setTopK(int);                        public int getTopK();
public int detect(org.opencv.core.Mat, org.opencv.core.Mat);
public static FaceDetectorYN create(String, String, Size, float, float, int, int, int);   // + 5 shorter
public static FaceDetectorYN create(String, MatOfByte, MatOfByte, Size, float, float, int, int, int); // + 5 shorter
```

Full arity is `create(model, config, inputSize, scoreThreshold, nmsThreshold, topK, backendId, targetId)`.
The `MatOfByte` family lets you feed ONNX bytes from a classpath resource with no temp file.

The **bytedeco twin** `org.bytedeco.opencv.opencv_objdetect.FaceDetectorYN` is a *different class*:
public constructor `FaceDetectorYN(org.bytedeco.javacpp.Pointer)`, extends `Pointer`, **16** static
native `create` overloads over `BytePointer`/`String`/`ByteBuffer`/`byte[]`, `detect` overloads for
`Mat`/`UMat`/`GpuMat`, and **no `MatOfByte` at all** (that type is `org.opencv.core` only).

**`org.opencv.objdetect.ArucoDetector`:**

```
public class org.opencv.objdetect.ArucoDetector extends org.opencv.core.Algorithm {
  public ArucoDetector();
  public ArucoDetector(Dictionary);
  public ArucoDetector(Dictionary, DetectorParameters);
  public ArucoDetector(Dictionary, DetectorParameters, RefineParameters);
  public void detectMarkers(...)                x2
  public void detectMarkersWithConfidence(Mat, List<Mat>, Mat, Mat, List<Mat>);
  public void detectMarkersWithConfidence(Mat, List<Mat>, Mat, Mat);
  public void refineDetectedMarkers(...)        x4
  public void detectMarkersMultiDict(...)       x3
  get/setDictionary, get/setDetectorParameters, get/setRefineParameters
}
```

The bytedeco twin exposes **12** `detectMarkersWithConfidence` overloads over
`Mat`/`UMat`/`GpuMat` × `MatVector`/`UMatVector`/`GpuMatVector`.

**`org.opencv.objdetect.GraphicalCodeDetector`** (QRCodeDetector's base — no model file needed):

```
public boolean detect(Mat, Mat);
public String  decode(Mat, Mat, Mat);  decode(Mat, Mat);
public String  detectAndDecode(Mat, Mat, Mat);  (Mat, Mat);  (Mat);
public boolean detectMulti(Mat, Mat);
public boolean decodeMulti(Mat, Mat, List<String>, List<Mat>);  (Mat, Mat, List<String>);
public boolean detectAndDecodeMulti(Mat, List<String>, Mat, List<Mat>);  (…, Mat);  (…);
public byte[]  detectAndDecodeBytes(Mat, Mat, Mat);  (Mat, Mat);  (Mat);
public byte[]  decodeBytes(...);   public boolean decodeBytesMulti(...);
public boolean detectAndDecodeBytesMulti(Mat, List<byte[]>, Mat, List<Mat>);  (…);  (…);
```

`QRCodeDetector` adds a no-arg constructor, `setEpsX`/`setEpsY`/`setUseAlignmentMarkers`,
`decodeCurved`, `detectAndDecodeCurved`, `getEncoding`.

⚠️ **The bytedeco `GraphicalCodeDetector` has NO `*Bytes` methods at all** — no
`detectAndDecodeBytes`, `decodeBytes`, `decodeBytesMulti`, `detectAndDecodeBytesMulti`. If you wrap
the bytedeco API, those capabilities are simply unavailable.

**`org.opencv.core.Core.VERSION` is not a compile-time constant:**

```
$ javap -constants -cp opencv-4.13.0-1.5.13.jar org.opencv.core.Core
  public static final java.lang.String VERSION;             // no = "..." initializer
  public static final java.lang.String NATIVE_LIBRARY_NAME;
$ javap -c -p ... static {}:
       0: invokestatic  #241  // Method getVersion:()Ljava/lang/String;
       3: putstatic     #242  // Field VERSION:...
       6: invokestatic  #243  // Method getNativeLibraryName:()...
```

Reading `Core.VERSION` triggers `<clinit>` → a **native** call, so natives must be loaded first.
`NATIVE_LIBRARY_NAME` is the exception: `getNativeLibraryName()` is **pure Java** (`ldc "opencv_java4130"`,
no `native` modifier), so it is safe to read cold. Runtime values verified: `VERSION=4.13.0`,
`NATIVE_LIBRARY_NAME=opencv_java4130`, soname suffix `.413`.

New in 4.13, verified at bytecode level with `javap -constants`:
`Core.ALGO_HINT_DEFAULT = 0`, `ALGO_HINT_ACCURATE = 1`, `ALGO_HINT_APPROX = 2` (plain `static final
int`, not an enum).

### 3.5 Platform classifiers and download size

Exact classifier set for `4.13.0-1.5.13` (from the repo1 directory listing):

| Classifier | Bytes |
|---|---|
| *(none — Java API only)* | 1,908,057 |
| `android-arm64` | 24,237,052 |
| `android-x86_64` | 26,837,443 |
| `ios-arm64` | 27,583,556 |
| `ios-x86_64` | 28,699,936 |
| **`linux-x86_64`** | **31,411,145** |
| `linux-x86_64-gpu` | 129,584,545 |
| `linux-arm64` | 28,180,987 |
| `linux-arm64-gpu` | 125,507,702 |
| **`macosx-arm64`** | **25,157,075** |
| `macosx-x86_64` | 27,589,769 |
| **`windows-x86_64`** | **34,464,296** |
| `windows-x86_64-gpu` | 136,466,553 |
| `javadoc` / `sources` | 13,150,410 / 2,345,584 |

**Absent in 4.13.0:** `linux-x86`, `linux-armhf`, `linux-ppc64le`, `windows-x86`, `android-arm`,
`android-x86` (all `<!-- -->`-commented in the `opencv-platform` POM), and `windows-arm64` (which
appears nowhere in that POM at all).

`opencv-platform` aggregates exactly: android-arm64, android-x86_64, ios-arm64, ios-x86_64,
linux-x86_64, linux-arm64, macosx-arm64, macosx-x86_64, windows-x86_64. It also pulls
`openblas-platform` (same 9) and `javacpp-platform` (11 — adds linux-ppc64le, linux-riscv64,
windows-arm64), which is why the total is so large.

### 3.6 Slimming — measured, not estimated

```
=== FULL opencv-platform ===
36 files   427,676,144 bytes   (407.9 MiB)

=== SLIM (explicit classifiers) ===
 6 files    53,932,894 bytes   ( 51.4 MiB)     ≈ 7.9x reduction
```

The exact slim command that produced that:

```sh
cs fetch \
  org.bytedeco:opencv:4.13.0-1.5.13 \
  'org.bytedeco:opencv:4.13.0-1.5.13,classifier=linux-x86_64' \
  org.bytedeco:javacpp:1.5.13 \
  'org.bytedeco:javacpp:1.5.13,classifier=linux-x86_64' \
  org.bytedeco:openblas:0.3.31-1.5.13 \
  'org.bytedeco:openblas:0.3.31-1.5.13,classifier=linux-x86_64'
```

**`-Djavacpp.platform=linux-x86_64` DOES NOT WORK outside Maven.** A/B tested:

```
$ cs fetch --classpath org.bytedeco:opencv-platform:4.13.0-1.5.13 | tr ':' '\n' | grep -c jar
36
$ JAVA_OPTS=-Djavacpp.platform=linux-x86_64 cs fetch --classpath ... | grep -c jar
36
```

Root cause read from the POM itself: `opencv-platform-4.13.0-1.5.13.pom` has **zero `<profile>`
elements** and lists every platform as an unconditional dependency with
`<classifier>${javacpp.platform.linux-x86_64}</classifier>`; those properties are resolved by
javacpp-presets' own Maven plugin, not by any mechanism Coursier has. The javacpp-presets wiki says
so explicitly: *"This works only with Maven. It does not work with Gradle, sbt, or any other build
system."* `cs fetch --java-opt -D…` also fails.

**Publishing recommendation:** make the published POM depend on the **classifier-less**
`org.bytedeco:opencv:4.13.0-1.5.13` only, and document that consumers add either `opencv-platform`
or their own classifier. Use classifiers only in the CI/test classpath. Otherwise every downstream
user eats a 400 MB download.

### 3.7 Native extraction cost

JavaCPP extracts to `~/.javacpp/cache`. Measured cold-vs-warm on this box: `0.543s` vs `0.373–0.399s`
(baseline `java -version` = 0.035s), i.e. ~0.15–0.2s. Cache size ~114 MB for
javacpp+opencv+openblas linux-x86_64 (98 MB of it is openblas), ~131 MB for the whole dir.

**Do not cache `~/.javacpp` in CI** — 114 MB for a 0.2s saving. Cache `~/.cache/coursier` instead;
the real cost is the 30 MB + 19 MB download.

---

## 4. OpenCV 4.13 features & 3.0 → 4.x drift

### 4.1 Headline features (Java-reachable)

| Feature | New in | Java-exposed? | Evidence |
|---|---|---|---|
| **Java `Cleaner`-based lifecycle; `finalize()` removed from 236 classes** | 4.13.0 | Yes (it *is* a Java-binding change) | javadoc `member-search-index.js` set-diff 4.12→4.13: 237 removals, 236 of them `finalize()` on 236 distinct classes; the one non-finalize removal is `org.opencv.core.Mat#nativeObj`. Generator source adds `USE_CLEANERS = config['support_cleaners']`. |
| **`ArucoDetector.detectMarkersWithConfidence`** | 4.13.0 | Yes — verified in the shipped bytedeco jar | 4.12 javadoc has only `detectMarkers`/`detectMarkersMultiDict`; 4.13 adds `WithConfidence` (2 overloads). Changelog: *"Added pixel-based confidence in ArUco marker detection [#23190]"* |
| `Imgproc.minEnclosingConvexPolygon(Mat, Mat, int)` | 4.13.0 | Yes | member-index diff; also `javap` → `public static native double minEnclosingConvexPolygon(Mat, Mat, int)` on `opencv_imgproc` |
| `Imgproc.phaseCorrelateIterative` (3 overloads) | 4.13.0 | Yes | member-index diff + `javap` |
| `CLAHE.setBitShift(int)` / `getBitShift()` | 4.13.0 | Yes | member-index diff + `javap` |
| `Calib3d.estimateTranslation2D` (7 overloads) | 4.13.0 | Yes | member-index diff + `javap` |
| `Video.findTransformECCWithMask` (4 overloads), `DISOpticalFlow.setCoarsestScale(int)` | 4.13.0 | Yes | member-index diff + `javap` |
| Imgcodecs metadata: `IMAGE_METADATA_CICP`, `IMWRITE_PNG_ZLIBBUFFER_SIZE`, `IMWRITE_BMP_COMPRESSION*`, `IMWRITE_TIFF_RESOLUTION_UNIT_*` | 4.13.0 | Yes | javadoc index-all grep counts: 0 in 4.12, non-zero in 4.13 |
| `imreadWithMetadata` / `imwriteWithMetadata` / `imdecodeWithMetadata` / `imdecodeanimation` / `IMAGE_METADATA_EXIF` | **4.12.0** | Yes | grep counts 0 in 4.11 → 4/6/6/6/2 in 4.12 |
| `Animation` class, `imreadanimation`, `imwriteanimation` | **4.11.0** (NOT 4.12 — see §8.7) | Yes | `docs.opencv.org/4.11.0/javadoc/org/opencv/imgcodecs/Animation.html` → HTTP 200; 4.12 only *added public constructors* `Animation()`, `(int)`, `(int, Scalar)` |
| `Imgproc.thresholdWithMask`, `THRESH_DRYRUN`, `getClosestEllipsePoints`, `MORPH_DIAMOND` | 4.12.0 | Yes | header diff 4.11→4.12 (0→3/1/4/1 occurrences) + javadoc counts |
| `HoughLinesWithAccumulator` | **4.11.0** (NOT 4.12 — see §8.8) | Yes | 25 occurrences in the 4.11.0 javadoc; it is the generated Java name for `cv::HoughLines(..., bool use_edgeval)`, already declared in 4.11 |
| `findTRUContours` | **4.14.0, NOT 4.13** (see §8) | — | `contents/modules/imgproc/src/contours_truco.cpp?ref=4.13.0` → **404**; `?ref=4.14.0` → sha `94f1f332…`. **Not reachable at the 4.13.0 pin.** |
| CVBenchmark | 4.13.0 | **No** — separate repo `github.com/opencv/cvbenchmark` | changelog |

**Behavioural changes that will move test baselines:**

- `cv::minAreaRect` now forces the angle into `[-90, 0)`. Changelog verbatim: *"Corrected
  `cv::minAreaRect` to follow documentation (force angle to range [-90, 0)) [#28051]. Versions of
  OpenCV with different angle range are 4.5.1-4.12.0."* PR #28051 merged into `4.x` 2025-11-25.
- *"Fixed standard `cv::HoughLines` output shift for rho. [#27992]"* (changelog line 434)
- *"Fixed LINE_4/LINE_8 swap in `cv::drawContours` [#28088]"* (line 435)
- *"Fixed bug in approxPolyDP: calculate distance to a segment, not to a line [#28119]"*

### 4.2 The `CleanableMat` trap — READ THIS

Upstream 4.13 source `modules/java/generator/src/java9/CleanableMat.java`:

```java
package org.opencv.core;
import java.lang.ref.Cleaner;
public abstract class CleanableMat {
    public static Cleaner cleaner = Cleaner.create();
    protected CleanableMat(long obj) {
        if (obj == 0) throw new UnsupportedOperationException("Native object address is NULL");
        nativeObj = obj;
        long nativeObjCopy = nativeObj;
        cleaner.register(this, () -> n_delete(nativeObjCopy));
    }
    private static native void n_delete(long nativeObj);
    public final long nativeObj;
}
```

**But there are two implementations, selected at build time** (`support_cleaners` CMake flag):
`src/java9/CleanableMat.java` (Cleaner-based) and `src/java_classic/CleanableMat.java` (no `cleaner`
field, overrides `finalize()`).

**The bytedeco 4.13.0-1.5.13 build shipped the `java_classic` variant:**

```
$ javap -p -c -cp opencv-4.13.0-1.5.13.jar org.opencv.core.CleanableMat
public abstract class org.opencv.core.CleanableMat {
  public final long nativeObj;
  protected org.opencv.core.CleanableMat(long);
  protected void finalize() throws java.lang.Throwable;
    Code: getfield nativeObj -> invokestatic n_delete:(J)V -> super.finalize()
  private static native void n_delete(long);
}
```

No `Cleaner` field exists (checked with `-p`). **On JDK 25, `Object.finalize()` is disabled, so
`org.opencv.core.Mat` native memory is NEVER reclaimed automatically with this artifact.** Any Scala
wrapper must own lifetime explicitly (`scala.util.Using`, a bracket/`Resource`, or `Mat.release()`),
never rely on GC. This is a first-class "why this library exists" argument for the README.

(The bytedeco `org.bytedeco.opencv.opencv_core.Mat` path uses javacpp `PointerScope`/deallocators
instead, a different but equally explicit mechanism.)

### 4.3 Drift table for APIs this repo actually uses

Baseline: `javap` on the in-repo `lib/opencv-300.jar` (dated 2015-05-10, 83 classes). Target:
`javap` on `opencv-4.13.0-1.5.13.jar` + the 4.13.0 javadoc.

Reference links: [`Core`](https://docs.opencv.org/4.13.0/javadoc/org/opencv/core/Core.html) ·
[`Imgproc`](https://docs.opencv.org/4.13.0/javadoc/org/opencv/imgproc/Imgproc.html) ·
[`Imgcodecs`](https://docs.opencv.org/4.13.0/javadoc/org/opencv/imgcodecs/Imgcodecs.html) ·
[`VideoCapture`](https://docs.opencv.org/4.13.0/javadoc/org/opencv/videoio/VideoCapture.html) ·
[`CascadeClassifier`](https://docs.opencv.org/4.13.0/javadoc/org/opencv/objdetect/CascadeClassifier.html) ·
[`MatOfRect`](https://docs.opencv.org/4.13.0/javadoc/org/opencv/core/MatOfRect.html)

#### 4.3a Against the `org.opencv.*` API (near-1:1 port route)

An exhaustive audit of all **30** distinct static symbols the repo references
(`grep -rhoE '(Core|Imgproc|Imgcodecs|Videoio|CvType)\.[A-Za-z_0-9]+' src/ | sort -u | wc -l` → 30;
only source file is `src/main/scala-2.11/it/callisto/scalacv/OpenCV.scala`) found **29 OK, exactly 1
missing**.

| 3.0 symbol | Status in 4.13.0 | Detail |
|---|---|---|
| `Core.FONT_HERSHEY_PLAIN` | 🔴 **MOVED → `Imgproc.FONT_HERSHEY_PLAIN`** | `javap … org.opencv.core.Core \| grep -i FONT` → no output on 4.13; `Imgproc` has it. Same value (1). The HersheyFonts enum moved core→imgproc in OpenCV 4. **The only compile-breaking symbol in the repo.** Used at `OpenCV.scala:263`. |
| `Core.LINE_8` | 🔴 **alias dropped from `Core`; use `Imgproc.LINE_8`** | 3.0 had `LINE_4/8/AA` on **both** `Core` and `Imgproc`; 4.13 has them only on `Imgproc`. Repo passes a bare `8`, so it compiles either way. Note the 4.13 `drawContours` LINE_4/LINE_8 swap fix changes rendering. |
| `Core.NATIVE_LIBRARY_NAME` | unchanged (value differs) | 3.0 `ldc "opencv_java300"` → 4.13 `ldc "opencv_java4130"`. Pure Java, safe to read cold. **But with bytedeco, use `Loader.load(...)`, not `System.loadLibrary(Core.NATIVE_LIBRARY_NAME)`.** |
| `Core.convertScaleAbs`, `Core.addWeighted` | unchanged | |
| `org.opencv.core.Mat` | **changed supertype** | now `extends org.opencv.core.CleanableMat`; `Mat#finalize()` removed from the API surface. See §4.2. |
| `MatOfRect` | **unchanged** | exactly 10 members: `MatOfRect()`, `protected (long)`, `fromNativeAddr(long)`, `(Mat)`, `(Rect...)`, `alloc`, `fromArray`, `toArray`, `fromList`, `toList`. ⚠️ the `(long)` ctor is `protected`. |
| `MatOfByte`, `MatOfPoint`, `Point`, `Rect`, `Scalar`, `Size` | unchanged | |
| `CvType.CV_16S` | unchanged | |
| `Imgproc.cvtColor(Mat,Mat,int,int)` | **unchanged on `org.opencv.*`** | but see §4.3b — the bytedeco API is a different story |
| `Imgproc.GaussianBlur(...,6 args)` | **unchanged on `org.opencv.*`** | see §4.3b |
| `Imgproc.blur(Mat,Mat,Size,Point,int)` | unchanged | |
| `Imgproc.Canny(Mat,Mat,double,double,int,boolean)` | unchanged | 4.x adds a 5-arg form and gradient-input `Canny(dx,dy,…)` overloads |
| `Imgproc.Sobel` (9-arg) | unchanged | |
| `Imgproc.Laplacian` (7-arg) | unchanged | |
| `Imgproc.threshold` (5-arg, returns `double`) | unchanged | new sibling `thresholdWithMask` (4.12) |
| `Imgproc.THRESH_*` | unchanged | new `THRESH_DRYRUN` (4.12) |
| `Imgproc.equalizeHist(Mat,Mat)` | unchanged | |
| `Imgproc.HoughLines(Mat,Mat,double,double,int)` | **unchanged** | C++ decl has `use_edgeval = false` as the 10th param — **already present in 4.11.0**, all extra params defaulted. Repo's 5-arg call at `OpenCV.scala:110` is source-compatible. ⚠️ rho output shifted in 4.13. |
| `Imgproc.HoughLinesP` (7-arg) | unchanged | |
| `Imgproc.findContours(Mat, List<MatOfPoint>, Mat, int, int[, Point])` | **unchanged** | exactly the 5- and 6-arg forms, `java.util.List<MatOfPoint>` intact. New sibling `findContoursLinkRuns`. Scala-side break is `scala.collection.JavaConversions` (gone in 2.13/3), not API drift. |
| `Imgproc.RETR_TREE`, `CHAIN_APPROX_SIMPLE`, `COLOR_BGR2GRAY` | unchanged | |
| `Imgproc.drawContours` (9-arg) | unchanged | |
| `Imgproc.line`, `rectangle`, `putText` | unchanged | `rectangle` gained `Rect`-based overloads |
| `Imgcodecs.imread(String)`, `imread(String,int)`, `imwrite(String,Mat)`, `imencode(String,Mat,MatOfByte)` | **unchanged** | verified verbatim by `javap`. 4.13 adds `imread(String, Mat[, int])`, `*WithMetadata`, `*animation`, class `Animation` — all additive. |
| `org.opencv.videoio.VideoCapture` | **unchanged + extended** | keeps `()`, `(String)`, `(int)`, `isOpened`, `grab`, `read(Mat)`, `retrieve(Mat[,int])`; adds `(String,int)`, `(int,int)`, `(…,MatOfInt)`, `(IStreamReader,int,MatOfInt)`, `setExceptionMode`, `getExceptionMode`, `getBackendName`, `release` |
| `org.opencv.objdetect.CascadeClassifier` | **unchanged** | `()`, `(String)`, `load(String)`, `empty()`, `detectMultiScale`×6, `detectMultiScale2`×6, `detectMultiScale3`×7, `isOldFormatCascade`, `getOriginalWindowSize`, `getFeatureType`, `static convert(String,String)`. One delta: `getOldCascade()` is **absent** from the `org.opencv` class in 4.13 (it exists only on the bytedeco twin). |
| `CascadeClassifier.detectMultiScale(Mat, MatOfRect)` | unchanged | |

**Caveats on the audit:** it is symbol-presence only — `javap` name matching cannot rule out changed
parameter/return types in overload sets; only an actual compile settles that. Non-static classes
(`Mat`, `MatOfPoint`, `MatOfRect`, `Scalar`, `Size`, `Point`, `Rect`, `CascadeClassifier`,
`VideoCapture`) were spot-checked but not exhaustively enumerated.

#### 4.3b Against the `org.bytedeco.opencv.*` preset API — **DIFFERENT ANSWERS**

JavaCPP generates only the **full-arity** and **all-defaults-omitted** Java overloads for a C++
function with default args. Adding a parameter therefore **deletes** intermediate arities.

| Function | bytedeco 4.10.0-1.5.11 | bytedeco 4.11.0-1.5.12 & 4.13.0-1.5.13 | Consequence |
|---|---|---|---|
| `cvtColor` | `(Mat,Mat,int,int)` + `(Mat,Mat,int)` | **`(Mat,Mat,int,int,int)` + `(Mat,Mat,int)` only** | any `cvtColor(src,dst,code,dstCn)` call **fails to compile**; must become 5-arg with `opencv_core.ALGO_HINT_DEFAULT` |
| `GaussianBlur` | `(Mat,Mat,Size,double,double,int)` + `(Mat,Mat,Size,double)` | **`(Mat,Mat,Size,double,double,int,int)` + `(Mat,Mat,Size,double)` only** | the repo's 6-arg call at `OpenCV.scala:77` **fails to compile**; must become 7-arg or 4-arg |

The `hint` parameter landed in **OpenCV 4.11**, not 4.13. **Grep for every other 4-arg→5-arg /
6-arg→7-arg delta before assuming only these two are affected.**

Other structural differences on the bytedeco surface: `findContours` takes `MatVector`, not
`List<MatOfPoint>`; `CascadeClassifier.detectMultiScale` takes `RectVector`, not `MatOfRect`;
`GraphicalCodeDetector` has no `*Bytes` methods; constants live on
`org.bytedeco.opencv.global.opencv_imgproc` (e.g. `FONT_HERSHEY_PLAIN`, plus legacy
`CV_FONT_HERSHEY_PLAIN`).

### 4.4 Modern detectors & model-file requirements

None of `FaceDetectorYN`, `FaceRecognizerSF`, `QRCodeDetector`, `GraphicalCodeDetector`,
`ArucoDetector` existed in 3.0 — the entire 3.0 objdetect module was
`BaseCascadeClassifier, CascadeClassifier, HOGDescriptor, Objdetect` (verified against the in-repo
`lib/opencv-300.jar`; `grep -Ei "aruco|qrcode|facedetector|graphicalcode"` over the whole jar → 0 hits).

| Target | Model needed? | Source | License | CI impact |
|---|---|---|---|---|
| `QRCodeDetector`, `QRCodeEncoder`, `BarcodeDetector` | **No** | — | — | none — pure unit test |
| `ArucoDetector`, `CharucoDetector` | **No** — dictionaries are code-generated | `Objdetect.getPredefinedDictionary(int)`, `Objdetect.generateImageMarker(...)`, `Objdetect.extendDictionary(...)`; constants `DICT_4X4_50…DICT_ARUCO_MIP_36h12`. `javap` confirms no String/path-taking ctor or `load`. | — | none — self-contained, deterministic fixtures |
| `CascadeClassifier` | tiny XMLs, **already bundled** in the classifier jar | `org/bytedeco/opencv/linux-x86_64/share/opencv4/haarcascades/` (17 XMLs), `lbpcascades/` (5), `quality/brisque_*.yml`, plus `valgrind.supp`, `valgrind_3rdparty.supp` | BSD-3 (OpenCV) — **UNVERIFIED**, headers not read | none. ⚠️ `CascadeClassifier` takes a filesystem path, so resources must be extracted; **UNVERIFIED** whether javacpp auto-extracts `share/`. |
| `FaceDetectorYN` | **Yes** — 232,589 B ONNX | see below | **MIT**, © 2020 Shiqi Yu | LFS-aware download + checksum; gate as integration |
| `FaceRecognizerSF` | **Yes** — 38,696,353 B ONNX | opencv_zoo `models/face_recognition_sface/face_recognition_sface_2021dec.onnx` | **Apache-2.0** | 37 MB — never vendor; download-on-demand only |

**🔴 The models are Git-LFS. `raw.githubusercontent.com` returns HTTP 200 with a 131-byte pointer,
not the model:**

```
$ curl -sL .../raw.githubusercontent.com/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
version https://git-lfs.github.com/spec/v1
oid sha256:8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
size 232589
```

The working URL is the LFS media host:

```
https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
→ http=200 bytes=232589
→ sha256 8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
→ first bytes 0806 1207 7079 746f 7263 68 ("pytorch") = real ONNX protobuf
```

Any download script must **validate size/sha256, not just HTTP status**, and `main` is a moving
branch — pin by hash.

**Which YuNet file:** use **`face_detection_yunet_2023mar.onnx`** (fixed input shape) for OpenCV 4.x.
`face_detection_yunet_2026may.onnx` (229,738 B) also exists and is the repo's *default*, but its
README says it is a dynamic-shape re-export *"compatible with OpenCV 5.x ONNX Runtime engine
(`OPENCV_FORCE_DNN_ENGINE=4`)"*, whereas *"`face_detection_yunet_2023mar.onnx` has fixed input shape.
OpenCV 4.x DNN infers on the exact shape of input image."*

**Licensing:** opencv_zoo's **root** LICENSE is Apache-2.0, but per-model licenses are heterogeneous
and override it — `face_detection_yunet` is MIT, `face_recognition_sface` is Apache-2.0,
`text_detection_ppocr` is Apache-2.0 (PaddlePaddle). **Check the LICENSE file of each specific model
directory; do not generalize.** MIT requires reproducing the copyright notice if you redistribute.

Docs: [tutorial_dnn_face](https://docs.opencv.org/4.13.0/d0/dd4/tutorial_dnn_face.html) (HTTP 200),
[FaceDetectorYN javadoc](https://docs.opencv.org/4.13.0/javadoc/org/opencv/objdetect/FaceDetectorYN.html),
which names the model source verbatim: *"DNN-based face detector model download link:
https://github.com/opencv/opencv_zoo/tree/master/models/face_detection_yunet"*.

---

## 5. Docs toolchain

### 5.1 mdoc

**There is NO official or maintained Mill 1.x mdoc plugin.**

- `mill-contrib` has no mdoc: `gh api repos/com-lihaoyi/mill/contents/contrib` → `artifactory,
  buildinfo, codeartifact, docker, flyway, gitlab, jmh, owaspdependencycheck, package.mill, playlib,
  proguard, sbom, scalapblib, scoverage, sonatypecentral, testng, twirllib, versionfile`.
- `atooni/mill-mdoc`, the plugin Mill's own third-party docs still list at line 666 of
  `thirdparty-plugins.adoc`, is **archived**, last commit **2022-02-01**, published only for Mill
  0.9/0.10 + Scala 2.13 (`de.wayofquality.blended.mill.mdoc_mill0.10_2.13`, max 0.0.4). Nothing for
  0.11, 0.12, or 1.x.
- `Quafadas/millSite` (`io.github.quafadas:millSite_mill1_3.8:0.0.57`) is the only live Mill 1.x
  option with an `MdocModule`, but it pins mdoc **2.8.2** / Scala **3.8.0**, drags Laika +
  cats-effect + scala-cli-config onto the build classpath, references a SNAPSHOT
  (`protosearch 0.0-7f79720-SNAPSHOT`), and has 2 stars. **Not recommended.**

**Hand-roll it.** This is exactly what Chisel does — `chipsalliance/chisel` `docs/package.mill`
lines 104-128 use `Jvm.callProcess(mainClass = "mdoc.Main", classPath = classpath, mainArgs = mdocArgs)`
with `--no-link-hygiene`, and `mvnDeps = Task { Seq(v.mdoc, v.scalatest) }` — mdoc as a plain library
dep, no plugin.

**Verified-working Mill 1.1.7 task** (this exact build ran successfully, `./mill show docs.mdoc` →
SUCCESS in 4s):

```scala
object docs extends ScalaModule with CoursierModule {
  def scalaVersion = "3.8.4"
  def mdocClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"org.scalameta::mdoc:2.9.1", mvn"org.scala-lang::scala3-compiler:3.8.4")
    )
  }
  def mdocSources = Task.Source(BuildCtx.workspaceRoot / "docs" / "src")
  def mdoc: T[PathRef] = Task {
    val out = Task.dest
    mill.util.Jvm.callProcess(
      mainClass = "mdoc.Main",
      classPath = mdocClasspath().map(_.path).toVector,
      mainArgs  = Seq("--in", mdocSources().path.toString, "--out", out.toString),
      cwd = out
    )
    PathRef(out)
  }
}
```

**Three load-bearing gotchas, all reproduced:**

1. **`scala3-compiler` must be listed explicitly and kept in lockstep with `scalaVersion`.**
   `cs resolve org.scalameta:mdoc_3:2.9.1` pulls `scala3-compiler_3:3.3.8`. Inside a
   `ScalaModule{scalaVersion="3.8.4"}` Mill then mixes compiler 3.3.8 with library 3.8.4 and mdoc
   dies at runtime: `exception caught when loading module class scala: package scala contains object
   and package with same name: caps`.
2. **`.withDottyCompat(...)` on the mdoc dep is actively wrong** — it resolves `mdoc_2.13-2.9.1.jar`
   and fails with `NoSuchMethodError: scala.math.Ordering$$anon$1.<init>`.
3. A bare `object docs extends Module` cannot see `defaultResolver()` — it is declared only on
   `mill.javalib.CoursierModule`. `JavaModule`/`ScalaModule` already inherit it, so the extra mixin
   is needed only for a plain `Module`.

**Fallback / CI shape (also verified end-to-end):**

```sh
CP=$(./mill --ticker false show core.runClasspath | jq -r '.[]' \
      | sed 's/^[a-z]*ref:v[0-9]*:[0-9a-f]*://' | paste -sd:)

cs launch org.scalameta:mdoc_3:2.9.1 org.scala-lang:scala3-compiler_3:3.8.4 -- \
  --in docs/mdoc --out docs/site/src --classpath "$CP" --no-link-hygiene --site.VERSION 4.13.0
```

⚠️ The `sed` regex must handle the **full** PathRef grammar, not just two cases. From Mill 1.1.7
`core/api/java11/src/mill/api/PathRef.scala`, `toString` is
`{qref|ref}:{v0|v1|vn}:{08x-sig}:{path}` — six prefix combinations (`vn` = `Revalidate.Always`), and
the hash is exactly 8 lowercase hex chars from `%08x` of an `Int`.

**mdoc behaviours verified for the VitePress wiring:**

- Non-markdown files, **including dot-directories like `.vitepress`**, are copied verbatim from
  `--in` to `--out`.
- YAML frontmatter, `::: tip` containers, `<script setup>` blocks and `{{ }}` Vue interpolation pass
  through **byte-identical**; `--site.VERSION 4.13.0` substitutes `@VERSION@` **even inside
  frontmatter**. ⚠️ Substitution uses mdoc's `@NAME@` delimiters, not `{{ }}` — so any literal
  `@word@` in prose (annotations, email addresses) becomes an undefined site variable.
- `--check` (alias of `--test`): exit **0** when up to date, exit **1** with a unified diff when
  stale. ⚠️ The error text says **`--test failed!`**, not "--check failed" — don't grep for the wrong
  string. `--check` performs a full compile; it is not a cheap hash comparison.
- A `.md` file that fails to compile is **not written to `--out`** while the non-markdown assets are
  still copied — always check the exit code.
- Under JDK 25 mdoc emits `sun.misc.Unsafe` terminal-deprecation warnings on stderr (harmless, noisy).

`mdoc_3-2.9.1.jar` `META-INF/MANIFEST.MF` → `Main-Class: mdoc.Main` (`mdoc.SbtMain` also exists for
in-process use). It needs the full transitive classpath — `java -jar` will not work.

### 5.2 VitePress

- **Pin `vitepress@1.6.4`** (stable, 2025-08-05). `next` = `2.0.0-alpha.18` (2026-07-06, ~9 months
  in alpha).
- **`vitepress.dev` now serves the 2.0-alpha docs** ("Node.js version 22 or higher",
  `npm add -D vitepress@next`). The v1 docs live only on the `v1` git branch ("version 18 or higher").
  Read docs from the `v1` branch if pinning 1.6.4.
- VitePress declares **no `engines` field** in any version — the Node floor is documentation-only and
  will fail at runtime, not install time. Use Node 24 in CI (satisfies both lines).
- **Config filename**: with no `"type": "module"` in `package.json` and TypeScript selected, the init
  wizard produces **`.vitepress/config.mts`**. Logic from `src/node/init/init.ts`:

  ```ts
  const useMjs = userPkg.type !== 'module'
  ...
    if (useMjs && file === '.vitepress/config.js') targetPath = targetPath.replace(/\.js$/, '.mjs')
    if (useTs) targetPath = targetPath.replace(/\.(m?)js$/, '.$1ts')
  ```
- **Base path.** Git remote is `git@github.com:w0rxbend/scalacv.git`, so Pages URL is
  `https://w0rxbend.github.io/scalacv/`. `site-config.md`, verbatim: *"If you plan to deploy your
  site to `https://foo.github.io/bar/`, then you should set base to `'/bar/'`. It should always start
  and end with a slash. Relative bases are not supported."* → **`base: '/scalacv/'`**. It is
  auto-prepended to every option URL starting with `/`, so specify it once.
- **`srcDir`** (default `.`) is resolved relative to the **project root**, i.e. the directory
  containing `.vitepress` (the dir passed to the CLI). That is the clean seam for mdoc output.

Config (**SKETCH** — assembled from verified option semantics, not a quoted file):

```ts
import { defineConfig } from 'vitepress'

export default defineConfig({
  base: '/scalacv/',
  srcDir: './src',                       // GITIGNORED — mdoc --out lands here
  title: 'scalacv',
  description: 'Scala 3 bindings for OpenCV 4.13.0'
})
```

### 5.3 GitHub Pages workflow

`actions/starter-workflows/pages` contains `astro, gatsby, hugo, jekyll-gh-pages, jekyll, mdbook,
nextjs, nuxtjs, static` — **no VitePress template** (verified three ways, including
`search/code?q=vitepress+repo:actions/starter-workflows` → `total_count: 0`). So VitePress's own
guide is canonical.

**Verbatim from `https://raw.githubusercontent.com/vuejs/vitepress/main/docs/en/guide/deploy.md`:**

```yaml
# Sample workflow for building and deploying a VitePress site to GitHub Pages
#
name: Deploy VitePress site to Pages

on:
  # Runs on pushes targeting the `main` branch. Change this to `master` if you're
  # using the `master` branch as the default branch.
  push:
    branches: [main]

  # Allows you to run this workflow manually from the Actions tab
  workflow_dispatch:

# Sets permissions of the GITHUB_TOKEN to allow deployment to GitHub Pages
permissions:
  contents: read
  pages: write
  id-token: write

# Allow only one concurrent deployment, skipping runs queued between the run in-progress and latest queued.
# However, do NOT cancel in-progress runs as we want to allow these production deployments to complete.
concurrency:
  group: pages
  cancel-in-progress: false

jobs:
  # Build job
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v5
        with:
          fetch-depth: 0 # Not needed if lastUpdated is not enabled
      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: 24
          cache: npm # or pnpm / yarn
      - name: Cache VitePress
        uses: actions/cache@v4
        with:
          path: docs/.vitepress/cache
          key: ${{ runner.os }}-vitepress-${{ hashFiles('docs/**', 'package-lock.json', 'pnpm-lock.yaml', 'yarn.lock', 'bun.lockb') }}
          restore-keys: |
            ${{ runner.os }}-vitepress-
      - name: Setup Pages
        uses: actions/configure-pages@v4
      - name: Install dependencies
        run: npm ci # or pnpm install / yarn install / bun install
      - name: Build with VitePress
        run: npm run docs:build # or pnpm docs:build / yarn docs:build / bun run docs:build
      - name: Upload artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: docs/.vitepress/dist

  # Deployment job
  deploy:
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    needs: build
    runs-on: ubuntu-latest
    name: Deploy
    steps:
      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v4
```

**Two required deviations from that verbatim file:**

1. **`branches: [main]` → `branches: [master]`.** `gh api repos/w0rxbend/scalacv --jq .default_branch`
   → `"master"`; `origin/HEAD → origin/master`. The doc's own comment says exactly this.
2. **Bump the three Pages actions.** The guide's `configure-pages@v4` / `upload-pages-artifact@v3` /
   `deploy-pages@v4` are 2, 2 and 1 majors behind. Current: **`@v6` / `@v5` / `@v5`**, all Node-24
   runtime bumps per their release notes. ⚠️ `upload-pages-artifact` **v4.0.0 was breaking**: *"hidden
   files (specifically dotfiles) will not be included in the artifact"*; v5.0.0 restores control via a
   new `include-hidden-files` input. A VitePress `dist/` has no dotfiles, and Pages-Actions deploys
   don't run Jekyll (so no `.nojekyll` needed) — but set `include-hidden-files: true` if that ever
   changes.

Also required (from `actions/deploy-pages` README): the deploy job needs at minimum
`pages: write` + `id-token: write` and must target the `github-pages` environment. And in repo
settings: *"under 'Pages' menu item, select 'GitHub Actions' in 'Build and deployment > Source'."*

**PR-only docs gate** (uses the verified `--check` exit codes) — **SKETCH**:

```yaml
- name: Verify docs are up to date
  run: |
    CP=$(./mill --ticker false show core.runClasspath | jq -r '.[]' \
          | sed -E 's/^q?ref:v[0-9n]+:[0-9a-f]{8}://' | paste -sd:)
    cs launch org.scalameta:mdoc_3:2.9.1 org.scala-lang:scala3-compiler_3:3.3.8 -- \
      --in docs/mdoc --out docs/site/src --classpath "$CP" --no-link-hygiene --check
```

`--no-link-hygiene` is effectively mandatory: VitePress best practice is extensionless links, which
mdoc's link hygiene flags as dead. Chisel does the same and says why.

---

## 6. Publishing & quality

### 6.1 Sonatype Central via Mill

**Namespace first.** `bend.worx` is unusable (§0). Sonatype Central, verbatim: *"Before Sonatype can
grant you permissions to publish under your requested namespace, we must validate that you own the
web domain reflected by your namespace"* and *"The automated system checks the exact domain for the
namespace for the TXT record. For the `com.example` namespace, the registration process checks
`example.com`."* GitHub-signup users automatically get a verified `io.github.<username>` namespace
with **no DNS verification**. → **`io.github.w0rxbend`**.

**Module path is `mill.javalib`, not `mill.scalalib`** (the legacy path exists but is the older
OSSRH-staging publisher). Source at tag 1.1.7,
`libs/javalib/src/mill/javalib/SonatypeCentralPublishModule.scala`:

```
  1: package mill.javalib
 15: trait SonatypeCentralPublishModule extends PublishModule, MavenWorkerSupport,
 16:       PublishCredentialsModule {
 94: object SonatypeCentralPublishModule extends ExternalModule, DefaultTaskModule, MavenWorkerSupport,
 95:       PgpWorkerSupport, PublishCredentialsModule, MavenPublish {
105:   // Set the default command to "publishAll"
106:   def defaultTask(): String = "publishAll"
```

You do **not** need to mix the trait in — extend plain `PublishModule` and drive the external module:

```
./mill mill.javalib.SonatypeCentralPublishModule/
./mill mill.javalib.SonatypeCentralPublishModule/publishAll
./mill mill.javalib.SonatypeCentralPublishModule/initGpgKeys
```

**Env vars — verified in source, not docs prose.**
`libs/javalib/src/mill/javalib/publish/SonatypeHelpers.scala:18-20`:

```scala
  val CREDENTIALS_ENV_VARIABLE_PREFIX = "MILL_SONATYPE"
  val USERNAME_ENV_VARIABLE_NAME = s"${CREDENTIALS_ENV_VARIABLE_PREFIX}_USERNAME"
  val PASSWORD_ENV_VARIABLE_NAME = s"${CREDENTIALS_ENV_VARIABLE_PREFIX}_PASSWORD"
```

`libs/javalib/src/mill/javalib/internal/PublishModule.scala:121-122`:

```scala
  val EnvVarPgpPassphrase = "MILL_PGP_PASSPHRASE"
  val EnvVarPgpSecretBase64 = "MILL_PGP_SECRET_BASE64"
```

So exactly four: `MILL_SONATYPE_USERNAME`, `MILL_SONATYPE_PASSWORD`, `MILL_PGP_SECRET_BASE64`,
`MILL_PGP_PASSPHRASE`. Notes: the username/password are the **User Token** halves from the portal,
not the login. `MILL_PGP_SECRET_BASE64` is **mandatory** when signing —
`resolveSigningConfig` throws *"'MILL_PGP_SECRET_BASE64' must be set when signing because the gpg CLI
is no longer used."* — while `MILL_PGP_PASSPHRASE` is optional. The docs page also mentions
`MILL_MAVEN_USERNAME` / `MILL_MAVEN_PASSWORD` for generic non-Sonatype repos.

**Publish workflow, verbatim from the Mill docs:**

```yaml
name: Publish Artifacts
on:
  push:
    tags:
      - '**'
  workflow_dispatch:
jobs:
  publish-artifacts:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - run: ./mill mill.javalib.SonatypeCentralPublishModule/
        env:
          MILL_PGP_PASSPHRASE: ${{ secrets.MILL_PGP_PASSPHRASE }}
          MILL_PGP_SECRET_BASE64: ${{ secrets.MILL_PGP_SECRET_BASE64 }}
          MILL_SONATYPE_PASSWORD: ${{ secrets.MILL_SONATYPE_PASSWORD }}
          MILL_SONATYPE_USERNAME: ${{ secrets.MILL_SONATYPE_USERNAME }}
```

(Bump `actions/checkout@v3` → `@v7`.)

Recommended extras: pass `--bundleName groupName-artifactId-versionNumber` so cross-version artifacts
upload as one verifiable bundle; use `--shouldRelease false` for a first dry run; mix in
`mill.util.VcsVersionModule` for git-tag versioning (pairs with the tag trigger above).

### 6.2 scalafmt

**Built into Mill — nothing to install, nothing to mix in.** `mill-libs-scalalib_3-1.1.7.jar`
contains `mill/scalalib/scalafmt/ScalafmtModule.class`, `ScalafmtWorkerModule.class`,
`default.scalafmt.conf`; `libs/scalalib/src/mill/scalalib/scalafmt/package.scala` is
`object \`package\` extends ExternalModule.Alias(ScalafmtModule)`, which is why the short invocation
works.

```
$ javap -cp mill-libs-scalalib_3-1.1.7.jar 'mill.scalalib.scalafmt.ScalafmtModule$'
  public java.lang.String defaultTask();                                          // "scalafmt"
  public mill.api.Task$Command<BoxedUnit> reformatAll(mill.util.Tasks<Seq<PathRef>>);
  public mill.api.Task$Command<BoxedUnit> checkFormatAll(mill.util.Tasks<Seq<PathRef>>);
  public mill.api.Task$Command<BoxedUnit> scalafmt(Seq<String>);
```

Format: `./mill mill.scalalib.scalafmt/reformatAll` · CI gate:
`./mill mill.scalalib.scalafmt/checkFormatAll` · per-module: `reformat()` / `checkFormat()`.

The worker uses **scalafmt-dynamic** (Mill's own build pins
`mvn"org.scalameta::scalafmt-dynamic:3.11.1".withDottyCompat(...)`, and `ScalafmtWorkerImpl.scala`
uses `Scalafmt.create(...)` + `CoursierDependencyDownloader`), so **the actual formatter version comes
from `version =` in `.scalafmt.conf`** — you are not pinned to whatever Mill bundles. Mill's task
fails outright if that line is missing: *"Found scalafmtConfig file does not specify the scalafmt
version to use."*

`.scalafmt.conf`:

```
version = 3.11.4
runner.dialect = scala3
maxColumn = 100
```

Both `version` and `runner.dialect` are **required since scalafmt 3.1.0** (docs verbatim). Valid
dialects: `scala211, scala212, scala212source3, scala213, scala213source3, scala3` (moving alias for
"most recent release"), `Scala3Future`, pinned `Scala30`…`Scala36`, `sbt0137`, `sbt1`. Because
`build.mill` is Scala 3 syntax, a `scala3` dialect covers it too.

### 6.3 scalafix

Not built into Mill. Use **`com.goyeau::mill-scalafix::0.6.2`** (artifact `mill-scalafix_mill1_3` —
Mill 1.x + Scala 3; the 0.6.x line publishes **only** that suffix). Its POM pins
`ch.epfl.scala:scalafix-interfaces:0.14.7`. Bytecode confirms the API:

```
$ javap -cp mill-scalafix_mill1_3-0.6.2.jar com.goyeau.mill.scalafix.ScalafixModule
public interface com.goyeau.mill.scalafix.ScalafixModule extends mill.scalalib.ScalaModule {
  public default mill.api.Task$Command<BoxedUnit> fix(scala.collection.immutable.Seq<java.lang.String>);
}
```

Source at tag v0.6.2 line 65 shows Scala 3 support is via Mill's own semanticdb:

```scala
classpath = (compileClasspath() ++ localClasspath() ++ Seq(semanticDbData())).iterator.toSeq.map(_.path),
```

and line 145 confirms `--check`: *"Run `fix` without `--check` or `--diff` to fix the error"*.
README usage (bump the coordinate to 0.6.2 — the README still says 0.6.0):

```scala
//| mvnDeps:
//|   - com.goyeau::mill-scalafix::0.6.2
import com.goyeau.mill.scalafix.ScalafixModule
import mill.scalalib._

object project extends ScalaModule with ScalafixModule:
  def scalaVersion = "3.7.3"
```

**OrganizeImports is built in since Scalafix 0.11.0 — no extra dependency.** Proven by jar
inspection rather than docs: `scalafix-rules_2.13:0.10.4` has **zero** `organize` entries;
`0.11.0` has `scalafix/internal/rule/OrganizeImports*.class` and lists
`scalafix.internal.rule.OrganizeImports` in `META-INF/services/scalafix.v1.Rule`. The legacy
`com.github.liancheng::organize-imports` tops out at 0.6.0 and has **no `_3` artifact** — do not use it.

⚠️ **Scala 3 rules artifacts are published under FULL Scala versions** (`scalafix-rules_3.3.4` …
`scalafix-rules_3.8.4`), not `_3`. There is no `scalafix-rules_3`. Whether Mill's integration
resolves the right one automatically is **UNVERIFIED**.

`OrganizeImports.targetDialect`: `Auto | Scala2 | Scala3 | StandardLayout`, default
**`StandardLayout`** (*"For files containing `scala-3` in their path, use `*` as wildcard and `as` for
renames … For others, use `_` and `=>`"*). Rationale given: *"`Auto` is not a safe default for
projects cross-compiling with Scala 3.x and Scala 2.x without `-Xsource:3`."* For a Scala-3-only
library, set it explicitly. Two documented Scala 3 limitations: deprecated package objects may yield
incorrect imports, and `groupSeparately=ByNameImplicits` has no effect.

`.scalafix.conf`:

```
rules = [
  RemoveUnused,
  OrganizeImports
]
OrganizeImports.targetDialect = Scala3
```

`RemoveUnused` needs `-Wunused:all` in `scalacOptions`.

### 6.4 MiMa

**Mill 1.1.7 has no built-in MiMa** — verified by scanning `mill-libs-scalalib_3`,
`mill-libs-javalib_3` and `mill-libs_3` jars (0 mima entries vs 13 scalafmt entries in scalalib), and
by `curl https://repo1.maven.org/maven2/com/lihaoyi/ | grep -i mima` → empty.

Use **`com.github.lolgab::mill-mima::0.2.2`** (artifact `mill-mima_mill1_3`, published 2026-06-17,
pins mima-core 1.1.6).

```
$ javap -cp mill-mima_mill1_3-0.2.2.jar com.github.lolgab.mill.mima.Mima
public interface com.github.lolgab.mill.mima.Mima extends mill.javalib.JavaModule
  public default Task$Simple<Seq<String>>      mimaPreviousVersions();
  public default Task$Simple<Seq<mill.javalib.Dep>> mimaPreviousArtifacts();
  public default Task$Simple<CheckDirection>   mimaCheckDirection();
  public default Task$Simple<String>           mimaVersion();
  public default Task$Command<BoxedUnit>       mimaReportBinaryIssues();
```

Element types are `scala.collection.immutable.Seq` + `mill.javalib.Dep` — **no `Agg`, no `ivy`**.
The README's `Agg(ivy"…")` snippet is Mill-0.x-era and will not compile. The correct forms come from
the project's own integration tests at tag 0.2.2:

```scala
// itest/resources/previous-versions/build.mill
override def mimaPreviousVersions = Task(Seq("0.0.1"))
// asserts:  curr.mimaPreviousArtifacts() == Seq(mvn"org:prev_2.13:0.0.1")
```

```scala
// itest/resources/mima-version/build.mill
override def mimaPreviousArtifacts = Task(Seq(mvn"org::prev:0.0.1"))
override def mimaCheckDirection    = CheckDirection.Backward
override def mimaVersion           = "1.1.0"
```

(Bare values work too via Mill's implicit value→Task conversion; `Task(...)` is not mandatory.)
Build-header import is the Mill 1.x YAML form:

```
//| mvnDeps:
//|   - com.github.lolgab::mill-mima::0.2.2
```

Task: `mill __.mimaReportBinaryIssues`. Direction also settable via
`MIMA_CHECK_DIRECTION=forward|backward|both`. Keep `mimaPreviousVersions = Seq()` until the first
release exists.

### 6.5 Scala Steward vs Dependabot

**Corrected** (see §8.9): Dependabot **has** supported the sbt ecosystem since **2026-05-26**
(version updates only, not security updates). Scala Steward's own README now lists it under
"Alternatives". **Dependabot still does NOT support Mill.**

Scala Steward README: *"It works with Maven, Mill, sbt, and Scala CLI."*
`scala-steward-org/mill-plugin` README.adoc line 30: *"This plugin supports all Mill major versions
from `0.6.x` to `1.x`, including minor releases."* Latest plugin tag **0.19.1**.
Action: **`scala-steward-org/scala-steward-action@v2`** (v2.92.0, 2026-06-26; README uses `@v2` in all
10 examples). It has a `mill-version` input (default `1.0.6` at v2.92.0), documented as affecting
only the global `mill` executable.

**Two hard constraints found in Scala Steward's source — act on these:**

1. **`build.mill.yaml` is NOT detected.** `MillAlg.scala`:

   ```scala
   private def findBuildFile(buildRootDir: File): F[Option[File]] =
     List("build.mill", "build.mill.scala", "build.sc")
       .map(buildRootDir / _)
       .findM(fileAlg.isRegularFile)
   ```

   No occurrence of "yaml" anywhere in the file. **→ scalacv must keep a real `build.mill`, not a
   YAML-only build.**
2. **Mill *plugin* deps in the `//| mvnDeps:` header are NOT auto-updated.** `parseMillPluginDeps` is
   a `cats-parse` parser hard-coded to ``import $ivy.`org::artifact::version` `` lines, and
   `millPluginArtifact` returns `""` for anything that isn't `0.9.12` or `0.10+`/`0.11+`
   (`case _ => ""`, with the comment *"Once it's for sure verified that v1 will also follow this
   pattern we can include that"*). **→ mill-scalafix / mill-mima bumps must be watched manually.**
   Ordinary module `mvnDeps` (javacpp-presets, zio, munit) *are* extracted, via
   `mill --import <plugin> show extractDeps`.

Mill version detection **does** work, and reads the build header first:

```scala
private val millVersionRegex = """\s*\/\/\|\s*mill-version:\s*['"]?(.+?)['"]?\s*""".r
```
with `.mill-version` / `.config/mill-version` as fallbacks.

**Recommendation:** Scala Steward action on a weekly cron for JVM deps + a `.scala-steward.conf`;
additionally enable Dependabot with `package-ecosystem: github-actions` only, to keep workflow action
pins fresh (that ecosystem is supported and is orthogonal).

---

## 7. CI

### 7.1 Recommended matrix

```yaml
strategy:
  fail-fast: false
  matrix:
    include:
      - { os: ubuntu-latest,  jdk: '17', classifier: linux-x86_64  }
      - { os: ubuntu-latest,  jdk: '21', classifier: linux-x86_64  }
      - { os: ubuntu-latest,  jdk: '25', classifier: linux-x86_64  }
      - { os: macos-latest,   jdk: '21', classifier: macosx-arm64  }
      - { os: windows-latest, jdk: '21', classifier: windows-x86_64 }
```

Justification, each point backed:

- **Fan JDKs out on Linux only.** GitHub billing docs give per-minute rates: Linux 2-core x64
  **$0.006**, Linux 2-core arm64 **$0.005**, Windows 2-core **$0.010**, macOS 3/4-core **$0.062** —
  macOS is ~10× Linux. (Moot if the repo stays public: *"The use of standard GitHub-hosted runners is
  free: In public repositories."*) JDK risk is JDK-specific, not OS-specific; mac/win exist purely as
  **native-loading smoke tests**, which is the one thing Linux cannot tell you.
- **17 / 21 / 25.** All three are **preinstalled** on `ubuntu-latest` (`8.0.492+9`, `11.0.31+11`,
  `17.0.19+10 (default)`, `21.0.11+10`, `25.0.3+9`), so `setup-java` is a cache hit. 25 is the leg
  that surfaces the JEP 472 native-access warning. Dropping 11 and 8: Mill needs ≥17 to run and
  Scala 3.8 needs ≥17 to compile.
- **Runner labels, from `actions/runner-images/README.md`:** `ubuntu-latest` → **Ubuntu 24.04 x64**;
  `macos-latest` → **macOS 26 arm64**; `windows-latest` → **Windows Server 2025** (also labelled
  `windows-2025-vs2026`). `macos-14` is **deprecated**; `ubuntu-26.04` is **preview**. x64 macOS is
  now `macos-26-intel` / `macos-latest-large` — nightly only, at $0.062/min.
- **`distribution: 'temurin'`** — available for every version on all three OSes, no licence footgun.
- Optional nightly leg: `ubuntu-24.04-arm` + `linux-arm64` ($0.005/min, cheaper than macOS).

### 7.2 Caching

**Use `coursier/cache-action@v8`, not `setup-java`'s `cache:`.** setup-java's `cache` input accepts
only `"maven"`, `"gradle"` or `"sbt"` (action.yml v5.6.0 verbatim); **Mill is not a valid value**. Its
`sbt` mode *does* cover the coursier cache:

```ts
  { id: 'sbt',
    path: [ ~/.ivy2/cache, ~/.sbt, getCoursierCachePath(),
            '!'+~/.sbt/*.lock, '!'+~/**/ivydata-*.properties ],
    pattern: ['**/*.sbt','**/project/build.properties','**/project/**.scala','**/project/**.sbt'] }
```

…but the key is hashed over `*.sbt` files that will not exist in a Mill repo, so the cache would
never invalidate on a dependency change.

`coursier/cache-action` README, verbatim: *"### Coursier cache — Always cached. ### `~/.sbt` and
`~/.ivy2/cache` — Cached when sbt files are found… ### `~/.cache/mill` — Cached when mill files are
found (any of `.mill-version`, `./mill`). ### `~/.ammonite` …"*

⚠️ Mill detection triggers **only** on `.mill-version` or a checked-in `./mill` launcher at the repo
root. A repo with only `build.mill` gets no `~/.cache/mill` caching — **commit a `.mill-version`**.
⚠️ Its README example still shows `@v7`; pin `@v8`.

Do **not** cache `~/.javacpp` (§3.7).

### 7.3 Headless traps and mitigations

**Trap 1 — JavaFX is in no modern JDK.** `java --list-modules | grep -ic javafx` → **0** on both
`25.0.3-graal` and `25.0.3-tem`. **Seven** repo sources import `javafx`:
`BrightnessContrastDemo, CamFaceDetect, CannySlider, HoughSlider, JavaFxUtils, OpenCV, ThresholdDemo`
(`OpenCV.scala:25` is just `import javafx.scene.image.Image`). Adding OpenJFX means adding *another*
platform-classified dependency. **Mitigation: keep JavaFX out of the CI-built modules entirely** —
separate `demos` module, not in the default aggregate.

**Trap 2 — Swing/AWT `HeadlessException`.** Reproduced:

```
$ env -u DISPLAY -u WAYLAND_DISPLAY java -Djava.awt.headless=true -cp . H
headless=true / DISPLAY=null / JFrame threw: java.awt.HeadlessException
BufferedImage OK 4 px0=ffff0000 / Graphics2D OK
```

Note the flag is **not** the cause — with `DISPLAY` unset and no flag, `isHeadless()` is still true
and the same exception is thrown (with the "No X11 DISPLAY variable was set" message). Off-screen
raster work (`BufferedImage`, `Graphics2D`, `setRGB`/`getRGB`) is fully usable headless, so
Mat↔image conversion tests need no gating.

**Trap 3 — `org.opencv.highgui.HighGui` is pure Swing.** `javap` shows
`createJFrame(String,int)`, `toBufferedImage(Mat)`, and constant-pool refs to `javax/swing/JFrame`,
`JLabel`, `ImageIcon`, `java/awt/image/*`. It is not a native window — so `HighGui.imshow` throws
`HeadlessException`, not a GTK error.

**Trap 4 — THE BIG ONE: `Loader.load(opencv_java.class)` fails on Linux without GTK 2.**

```
Exception in thread "main" java.lang.UnsatisfiedLinkError: no jniopencv_highgui in java.library.path: ...
	at org.bytedeco.opencv.global.opencv_highgui.<clinit>(opencv_highgui.java:23)
	at org.bytedeco.javacpp.Loader.load(Loader.java:1289)
Caused by: java.lang.UnsatisfiedLinkError:
  ~/.javacpp/cache/opencv-4.13.0-1.5.13-linux-x86_64.jar/org/bytedeco/opencv/linux-x86_64/libjniopencv_highgui.so:
  libgtk-x11-2.0.so.0: cannot open shared object file: No such file or directory
```

The shipped Linux OpenCV was built `GUI: GTK2 / GTK+: YES (ver 2.24.33)` (read out of
`libopencv_core.so.413`'s embedded build-info string). `readelf -d libopencv_highgui.so.413` NEEDS
`libgtk-x11-2.0.so.0`, `libgdk-x11-2.0.so.0`, `libcairo.so.2`, `libgdk_pixbuf-2.0.so.0`,
`libgobject-2.0.so.0`, `libglib-2.0.so.0` — **none bundled in the jar**, and `grep -i gtk` over the
entire apt-package table of `Ubuntu2404-Readme.md` → **0 hits**.

Per-module load results (each in a fresh JVM — in one JVM the follow-ons appear as
`NoClassDefFoundError` from class-init caching, masking the real error):

| Global | Result |
|---|---|
| `opencv_core`, `opencv_imgproc`, `opencv_imgcodecs`, `opencv_dnn`, `opencv_videoio` | **OK** |
| `opencv_objdetect`, `opencv_calib3d`, `opencv_features2d`, `opencv_video`, `opencv_highgui` | **FAIL** → `no jniopencv_highgui` |

**`objdetect` is exactly the module scalacv needs most.** Note `libopencv_java.so` *itself* does not
link GTK (`readelf -d` lists 28 `libopencv_*.so.413` + libstdc++/libm/libgcc_s/libc; only unresolved
external is `libopenblas.so.0`) — the failure comes purely from javacpp's eager preset-graph
initialisation.

Mitigations, in order:

1. **Use the `org.bytedeco.opencv.*` API and never touch `opencv_highgui`.** Verified headless with
   no `DISPLAY`: `Mat OK: 4x4 ch=3` / `imgproc OK ch=1` / `videoio OK isOpened=false`.
2. `-Dorg.bytedeco.javacpp.loadlibraries=false` makes `Loader.load` a no-op (returns null before any
   platform check — `Loader.java:1255`, `:1659`). **Useful for native-free compile/codegen/CI paths
   only:** it does NOT protect `global.*` classes whose `<clinit>` constant-folds via native calls —
   `Class.forName("org.bytedeco.opencv.global.opencv_core")` still throws
   `UnsatisfiedLinkError: 'int ... CV_MAKETYPE(int, int)'`. It is documented as a Builder flag.
3. If you must use `org.opencv.*`: `apt-get install -y libgtk2.0-0t64` — verified the noble package
   is **`libgtk2.0-0t64` 2.24.33-4ubuntu1.1**, section `oldlibs`; **plain `libgtk2.0-0` does not
   exist in noble** (packages.ubuntu.com returns "Package not available in this suite"). Don't
   hard-pin the version — release vs updates pockets differ.
4. `xvfb-run -a` — **`xvfb 2:21.1.12-1ubuntu1.6` IS preinstalled** on ubuntu-latest. Only needed to
   exercise window code; it does not substitute for the missing GTK2 libs.
5. Bypassing javacpp with `System.load(<extracted>/libopencv_java.so)` **works** (verified:
   `VERSION=4.13.0`, `ArucoDetector` constructed, `QRCodeDetector ok`, `cvtColor` ran) — **but only
   with `LD_LIBRARY_PATH` covering both the extracted opencv and openblas native dirs.** A bare
   `System.load` fails with `libopencv_xphoto.so.413: cannot open shared object file`. Not a
   single-file load.

**macOS / Windows are fine on the loading front:**
- macosx-arm64 `libopencv_highgui.413.dylib` Mach-O `LC_LOAD_DYLIB` list (parsed from the real load
  commands, not `strings`): `@rpath/libopencv_{videoio,imgcodecs,imgproc,core}.413.dylib`,
  `/System/Library/Frameworks/{Cocoa,AppKit,CoreFoundation,CoreGraphics,Foundation}`,
  `/usr/lib/{libc++.1,libSystem.B,libobjc.A}.dylib`. All OS-supplied. ⚠️ the JNI shim
  `libjniopencv_highgui.dylib` additionally needs `@rpath/libopenblas.0.dylib`, which is **not** in
  the opencv macosx-arm64 jar — the openblas artifact must be on the classpath.
- windows-x86_64 `opencv_highgui4130.dll` PE import table (`objdump -x | grep 'DLL Name'`, not
  `strings`): `USER32, GDI32, COMDLG32, ADVAPI32, KERNEL32, MSVCP140, VCRUNTIME140, api-ms-win-crt-*`.
  All present on `windows-latest`.
- **UNVERIFIED**: whether `imshow` actually *renders* on a hosted macOS/Windows runner session.
  Assume not; gate it.

**Trap 5 — `VideoCapture` degrades, does not throw.** With no camera:

```
[ WARN:0@0.056] global cap_v4l.cpp:914 open VIDEOIO(V4L2:/dev/video0): can't open camera by index
[ERROR:0@0.056] global obsensor_uvc_stream_channel.cpp:163 getStreamChannelGroup Camera index out of range
VideoCapture(0).isOpened = false
```

Both lines go to **stderr**; no exception. ⚠️ a log scraper keying on the literal `ERROR` will false-
positive. ⚠️ a bigger failure mode in CI: with an incomplete classpath, `VideoCapture.<clinit>` dies
with `ExceptionInInitializerError / ClassNotFoundException: org.bytedeco.openblas.presets.openblas` —
i.e. a missing-dependency error, not a camera error.

The only real device-opening call site is `CamFaceDetect.scala:20`
(`val videoCapture: VideoCapture = new VideoCapture(0)`). `OpenCV.scala:304` is the **abstract**
`def videoCapture: VideoCapture` inside `trait OpenCVVideo` (line 302) — that trait, plus
`takeImage`'s unbounded `while (videoCapture.read(image) == false) {}` busy-loop, is the seam to stub
for headless CI.

**Trap 6 — JEP 472 restricted native access on JDK 24+.** First `Loader.load` prints:

```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by org.bytedeco.javacpp.Loader in an unnamed module
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

`--enable-native-access=ALL-UNNAMED` silences it completely (verified: output was exactly `loaded ok`,
zero warnings). ⚠️ `--illegal-native-access=deny` did **not** hard-fail on either local JDK 25 — it
printed `loaded ok`. So `deny` is **not** a usable canary; add the enable flag proactively.

**Also:** the required minimum runtime classpath is javacpp + opencv + **openblas**, each with and
without the platform classifier. Omitting the openblas native jar produces
`UnsatisfiedLinkError: no jniopenblas_nolapack in java.library.path`, which masks the GTK error.

### 7.4 Sketch of the ubuntu leg — **SKETCH** (composed from verified parts)

```yaml
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with: { distribution: 'temurin', java-version: '${{ matrix.jdk }}' }
      - uses: coursier/cache-action@v8
      - run: ./mill -i __.test
        env:
          FORCE_COLOR: '1'
          JAVACPP_PLATFORM: ${{ matrix.classifier }}
```

`FORCE_COLOR: '1'` is Mill's own explicit CI recommendation (installation-ide.html: *"One thing that
you should set up manually is to enable a FORCE_COLOR"*). Add
`-Djava.awt.headless=true --enable-native-access=ALL-UNNAMED` to Mill's `forkArgs`/`testForkArgs`.

**Do not add a GUI job.** No `xvfb-run`, no GTK2 install — keep `opencv_highgui`, `HighGui`, JavaFX
and camera code in a non-default `demos` module. A highgui smoke test would need *both*
`apt-get install -y libgtk2.0-0t64` **and** `xvfb-run -a`, and would be the flakiest job in the repo.

---

## 8. Rejected / refuted claims (audit trail)

**11 scout claims were refuted by the verification pass.** Each is recorded with its correction.

### 8.1 REJECTED — "Mill's built-in test framework mixins are Junit4, Junit5, TestNg, Munit, ScalaTest, Specs2, Utest, Weaver, ZioTest" *(scout:mill)*
**Incomplete/stale.** Mill `main` @ `8d7ab8e` (2026-07-22), `libs/javalib/src/mill/javalib/TestModule.scala`,
defines **12**: `TestNg, Junit4, Junit5, `**`Junit6`**`, ScalaTest, Specs2, Utest, Munit, Weaver,
ZioTest, `**`ScalaCheck`**`, `**`Spock`** (Spock extends Junit5; Junit6 extends Junit5). The 9 named
were correctly spelled; three were omitted. The docs page lists only 9 and lags the source. Package
is **`mill.javalib`**, not `mill.scalalib`.

### 8.2 REJECTED — "Coursier cache grew from 2.7G to 3.1G across the fetch" *(scout:bytedeco)*
**Non-reproducible and worthless.** Independent measurement gives 3.3G (3,412,394,367 bytes), matching
neither claimed value. It is an environment-state observation about mutable local state, not a fact
about scalacv. **Dropped entirely.** The defensible restatement is the forward-looking constraint
already in §3.6: ~408 MB for `opencv-platform`, ~51 MB slim.

### 8.3 REJECTED — "javacpp 1.5.13's Loader provides no system property to make a missing native non-fatal" *(scout:bytedeco)*
**False.** `Loader.java` lines 1061-1062 read
`System.getProperty("org.bytedeco.javacpp.loadlibraries", "true")` (alias `loadLibraries`); line 1255
`if (!isLoadLibraries() || cls == null) { return null; }`; line 1659 same short-circuit in
`loadLibrary`. Empirically, `-Dorg.bytedeco.javacpp.loadlibraries=false` +
`Class.forName("org.bytedeco.opencv.opencv_core.Mat")` prints `clinit OK`. **Partial rescue of the
original worry:** the flag does NOT protect `global.*` classes whose `<clinit>` calls natives to
constant-fold — `global.opencv_core` still throws `UnsatisfiedLinkError: CV_MAKETYPE`. Usable for
native-free build/test paths only. (Applied in §7.3 mitigation 2.)

### 8.4 REJECTED — "Imgproc.cvtColor gained a 5-arg `hint` overload in 4.x while the 3- and 4-arg forms are unchanged" *(scout:opencv-changelog)*
**Wrong for the target binding, and wrong on timing.** On `org.bytedeco.opencv.global.opencv_imgproc`
the `Mat` overloads are **exactly two**: `(Mat,Mat,int,int,int)` and `(Mat,Mat,int)`. **There is no
4-arg `(src,dst,code,dstCn)` form** — javacpp emits only full-arity and all-defaults-omitted, so
adding `hint` *deleted* it. The change landed in **4.11.0** (`4.10.0-1.5.11` still has
`(Mat,Mat,int,int)`), not 4.13. Also, the claim cited `Imgproc.cvtColor`, an `org.opencv.*` class,
not the bytedeco target. (Applied in §4.3b.)

### 8.5 REJECTED — "Imgproc.GaussianBlur gained a 7-arg overload ending in `int hint` in 4.13 while the repo's 6-arg call is unchanged" *(scout:opencv-changelog)*
**Both halves wrong.** The `hint` param landed in **4.11**, not 4.13 (`4.10.0-1.5.11` has the 6-arg
form; `4.11.0-1.5.12` already has 7-arg). And the repo's 6-arg call at `OpenCV.scala:77` is **not**
source-compatible with the bytedeco API, which emits only 7-arg and 4-arg. Every intermediate-arity
call site must be re-checked by `javap` against the bytedeco jar, not against opencv.org javadoc.
**UNVERIFIED**: that the 7th int is specifically named `hint`/`AlgorithmHint` — javap shows only `int`.

### 8.6 REJECTED — "OpenCV 4.13.0 removed finalize() from 232 wrapper classes" *(scout:opencv-changelog)*
**Count wrong: it is 236.** Independent set-diff of `member-search-index.js` (4.12.0 vs 4.13.0):
7206 → 7100 entries, 237 removed, 131 added. 236 of the removals are `finalize()` on 236 distinct
classes; the single non-finalize removal is `org.opencv.core.Mat#nativeObj`. Substance confirmed;
`CleanableMat` is new in 4.13's `type-search-index.js`.

### 8.7 REJECTED — "OpenCV 4.12 added the Java imgcodecs metadata AND animation API (incl. imreadanimation, Animation class)" *(scout:opencv-changelog)*
**Wrong on attribution of the animation half.** `docs.opencv.org/4.11.0/javadoc/org/opencv/imgcodecs/Animation.html`
returns **HTTP 200** and 4.11's index-all already contains `imreadanimation` (6) and `imwriteanimation`.
What 4.12 added to `Animation` is **public constructors** (`()`, `(int)`, `(int, Scalar)`; 4.11 had
only `Animation(long)`). 4.12 genuinely added `imreadWithMetadata`, `imwriteWithMetadata`,
`imdecodeWithMetadata`, `imdecodeanimation`, `IMAGE_METADATA_EXIF`. 4.13 genuinely added
`IMAGE_METADATA_CICP`, `IMWRITE_PNG_ZLIBBUFFER_SIZE`, `IMWRITE_BMP_COMPRESSION*`,
`IMWRITE_TIFF_RESOLUTION_UNIT_*`. (Corrected in §4.1.)

### 8.8 REJECTED — "OpenCV 4.12 added HoughLinesWithAccumulator (with thresholdWithMask etc.)" *(scout:opencv-changelog)*
**4 of 5 items correct; `HoughLinesWithAccumulator` is wrong.** It already exists in the **4.11.0**
Java API (25 occurrences in the 4.11.0 javadoc) and is not a C++ symbol at all — it is the generated
Java overload name for `cv::HoughLines(..., bool use_edgeval)`, and `use_edgeval` is already declared
in 4.11.0's `imgproc.hpp`. `thresholdWithMask`, `THRESH_DRYRUN`, `getClosestEllipsePoints`,
`MORPH_DIAMOND` (+ `CV_SHAPE_DIAMOND`) genuinely are 4.12 additions (0 occurrences in 4.11 headers
and javadoc, present in 4.12).

### 8.9 REJECTED — "Dependabot does not support Scala, sbt, or Mill" *(scout:publish-quality)*
**Stale.** Dependabot **has** supported the sbt ecosystem since **2026-05-26**
(github.blog changelog: *"Dependabot now supports sbt. Add sbt as a package ecosystem in your
dependabot.yml file"* — version updates only, not security updates). Scala Steward's own README now
lists it under "Alternatives to Scala Steward". **Mill is still not a Dependabot ecosystem**, so the
practical conclusion for scalacv is unchanged. Note: the docs.github.com supported-ecosystems page
fetches back alphabetically truncated at "pub" — its apparent omission of sbt is a fetch artifact,
**not** evidence; do not cite that page for a negative.

### 8.10 REJECTED — "actions/setup-java distributions include liberica-nik" *(scout:ci-headless)*
**`liberica-nik` is not a supported keyword.** The cache half of the claim was correct. Actual
`enum JavaDistribution` at v5.6.0 (`src/distributions/distribution-factory.ts`), **17 values**:
`adopt, adopt-hotspot, adopt-openj9, temurin, zulu, liberica, jdkfile, microsoft, semeru, corretto,
oracle, dragonwell, sapmachine, graalvm, graalvm-community, jetbrains, kona`. `grep -in "nik"` over
the README → zero matches. (Probably confused with `graalvm/setup-graalvm`.)

### 8.11 REJECTED — "The legacy repo uses JavaFX in 6 sources and `VideoCapture(0)` in CamFaceDetect.scala:20 and OpenCV.scala:304" *(scout:ci-headless)*
**Two errors.** JavaFX appears in **7** files: `BrightnessContrastDemo, CamFaceDetect, CannySlider,
HoughSlider, JavaFxUtils, OpenCV, ThresholdDemo`. And `OpenCV.scala:304` is **not** a camera-device
construction — it is the abstract member `def videoCapture: VideoCapture` inside `trait OpenCVVideo`
(line 302). The only `new VideoCapture(0)` is `CamFaceDetect.scala:20`. (Applied in §7.3 trap 5.)

### 8.12 Non-refutation corrections worth recording

- `Core.NATIVE_LIBRARY_NAME` evidence originally cited `bd-opencv-…jar` / `CoreBD.class` /
  `Core30.class` — those filenames were mangled; the real paths are `opencv-4.13.0-1.5.13.jar` and
  `org/opencv/core/Core.class`. The bytecode quoted was verbatim correct.
- `findTRUContours` was misattributed to 4.13 by a web summarizer. It is **4.14.0** only:
  `contents/modules/imgproc/src/contours_truco.cpp?ref=4.13.0` → 404, `?ref=4.14.0` → sha
  `94f1f332f65c4f26440f0c10236ef637adfac9b0`. **Not reachable at the 4.13.0 pin.**
- The `//| mvnDeps:` docs example coordinate
  `com.goyeau::mill-scalafix::0.5.1-14-4d3f5ea-SNAPSHOT` could not be reproduced — treat as
  UNVERIFIED; resolve mill-scalafix from Maven Central (0.6.2).
- mdoc's `--check` failure message is literally `--test failed!`.
- `actions/deploy-pages` publishes a stray `v3.0.2-node.24` tag dated *after* v5.0.0 whose body is a
  copy-paste of configure-pages v6.0.0's changelog. Do not sort tags lexically or by date.
- The scalafmt/scalafix/mima docs pages on mill-build.org are **unversioned**; they reflect whatever
  the site currently documents (1.1.7 at the time of checking).

---

## 9. Unresolved / UNVERIFIED

1. **Mill classifier-dependency syntax.** The exact Mill 1.1.7 spelling for a classifier dep
   (`mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64"` vs some other form) was **never
   tested** — Mill was not installed on the box during that scout's run. This is load-bearing for the
   slimming strategy in §3.6. **Settle first.**
2. **Which OpenCV Java API scalacv wraps** — `org.opencv.*` (near-1:1 port of the 2015 code, needs
   the GTK2-triggering loader path or a `System.load` workaround) vs `org.bytedeco.opencv.*` (loads
   cleanly headless, but different types, no `MatOfRect`/`MatOfByte`/`List<MatOfPoint>`, no
   `GraphicalCodeDetector.*Bytes` methods, and deleted intermediate arities per §4.3b). **This is the
   single biggest open design decision** and every signature in §3–4 depends on it.
3. **Whether installing `libgtk2.0-0t64` actually makes `Loader.load(opencv_java.class)` succeed.**
   Not tested — the system was not modified.
4. **Whether javacpp auto-extracts `share/opencv4/haarcascades/*.xml`** from the classifier jar to a
   real filesystem path. `CascadeClassifier` takes a path, so this matters.
5. **Licence headers of the three cascade XMLs already vendored** in `src/main/resources/`
   (`lbpcascade_frontalface.xml`, `haarcascade_lefteye_2splits.xml`,
   `haarcascade_righteye_2splits.xml`). Assumed BSD-3 (OpenCV); headers never read.
6. **Whether other bytedeco functions besides `cvtColor`/`GaussianBlur` lost intermediate arities in
   4.11.** Grep the whole `global.*` surface for 4-arg→5-arg / 6-arg→7-arg deltas before assuming two.
7. **Signature-level (not name-level) compatibility** of the 30 audited `org.opencv.*` symbols. The
   audit was symbol-presence only; only a real compile settles overload/return-type changes. Same for
   the non-static classes, which were never enumerated.
8. **`imshow` rendering on hosted macOS/Windows runners.** Loading is analysed statically only; no
   run on those OSes was possible.
9. **Whether `mill-scalafix` resolves the right `scalafix-rules_<full-scala-version>` artifact
   automatically** for Scala 3 (there is no `scalafix-rules_3`).
10. **Whether `mill.util.VcsVersionModule` works with no git tags present.** `VcsVersion$State`
    suggests a fallback; not executed. Also the exact Scala-syntax mixin form was not seen in docs
    (only the YAML `extends:` list).
11. **Whether Mill 1.x still accepts the `ivy:` scheme** in Scala Steward's `--import` coordinate
    (`s"ivy:$org::$artifact::$version"`). Test the Steward action on a branch before relying on it.
12. **Whether `PublishModule` has abstract members beyond `pomSettings` and `publishVersion`.** Only
    those two were located in source.
13. **`actions/checkout` v7.0.1 and `actions/cache` v6.1.0 publish dates.** Tags confirmed by
    `git ls-remote`; dates could not be re-fetched (GitHub API rate limit). Not load-bearing.
14. **Whether a newer Scala 3 LTS line has been *announced* but not released.** No release-feed source
    stated it either way. Related: **3.9.0-RC3 dates from 2026-07-11, so a 3.9.0 final is plausibly
    imminent — re-check the Scala pin before the first release.**
15. **`Quafadas/millSite` v0.0.58** exists as a git tag but has no Maven Central artifact under
    `millSite_mill1_3.8` (Central lastUpdated 2026-01-27 vs repo push 2026-06-21). Reason unknown;
    do not pin 0.0.58. Moot if millSite is not used.
16. **`actions/setup-node@v6` currency.** Taken from the VitePress guide; not independently
    tag-checked. (`setup-java@v6` was checked and does NOT exist — see §0.)
17. **The composed CI and docs workflow YAML in §5.3 / §7.4** was never executed end-to-end. Each
    constituent piece is verified; the assembly is not.
18. **`mimaPreviousArtifacts` behaviour on the very first release** (no previous versions). Keep
    `Seq()` until a release exists.
