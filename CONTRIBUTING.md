# Contributing to scalacv

Thanks for looking. This is a small library with a narrow remit, so the bar is less
"is this a good idea" and more "does it hold up under a native debugger".

## Getting set up

You need a JDK — anything from 17 up. Nothing else: `./mill` fetches its own launcher,
and Mill provisions the build JDK itself.

```sh
./mill __.compile
./mill __.test
./mill examples.runMain scalacv.smoke      # proves the natives load
```

## Before you push

```sh
./mill __.fix                                          # apply scalafix
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll
./mill __.test
```

CI runs the check forms of those (`__.fix --check`, `checkFormatAll`) and they exit non-zero
on drift, so running them locally is cheaper than a red build.

## The rules that are not negotiable

**Release every native object, on every path.** This library exists because OpenCV's Java
bindings do not reclaim native memory in any useful timeframe. A leak in scalacv is not a
performance issue, it is the defect the project exists to prevent. If you allocate a `Mat`,
`MatOfPoint`, `MatOfRect`, `MatOfByte` or any detector, it is released in a `finally` or it
is wrapped in `Managed`.

**Tests must be able to fail.** An assertion that would still pass if the implementation
returned a constant, or did nothing, is a false assurance and worse than no test. We have
shipped a few of these and had to go back and fix them. Ask of every assertion: what
regression does this catch?

**Never claim an OpenCV symbol you have not checked.** `javap` against the actual jar. The
Java API is not the C++ API — `GaussianBlur`, `Canny`, `Sobel` and `Laplacian` are
capitalised, `imread` returns an empty `Mat` instead of throwing, and `HoughLinesP` gives
back `CV_32SC4` integers where you would expect floats.

**No image assets.** Test fixtures are generated programmatically. This is deliberate: it
keeps image licensing out of the project entirely, which is why `Lena.png` is gone.

**Nothing GUI in `core`.** No `highgui`, no JavaFX, no AWT. `examples-gui` is the one place
a toolkit may appear, and it is never built in CI or published.

## Commits

Conventional commits (`feat:`, `fix:`, `build:`, `docs:`, `test:`, `chore:`). If your change
completes a `ROADMAP.md` checkbox, flip it **in the same commit** — the roadmap in git
history should always describe the tree it sits in.

Write commit messages that explain *why*. "fix: handle empty Mat" tells the next person
nothing; the reason it was broken, and what it cost, is the useful part.

## Reporting a bug

Include your platform and the classifier you depend on, your JDK, and — if it is a crash —
the `hs_err_pid*.log`. Native crashes have no Java stack trace, so that file is usually the
only evidence there is.
