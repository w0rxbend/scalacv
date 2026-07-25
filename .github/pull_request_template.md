<!-- Thanks for contributing. Keep this short; delete anything that does not apply. -->

**What this changes**

**Why**

## Before you push
<!-- CI runs the check forms of these and exits non-zero on drift, so it is cheaper to run them here. -->
- [ ] `./mill __.fix && ./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll` (scalafix + scalafmt)
- [ ] `./mill __.test` passes
- [ ] Docs snippets still type-check if I touched `docs/mdoc/` (`./mill docs.mdocCheck`)

## The non-negotiables (see CONTRIBUTING.md)
- [ ] Every native object I allocated is released on every path — wrapped in `Managed`, or freed in a `finally`.
- [ ] Any test I added can actually fail — it would break if the implementation returned a constant or did nothing.
- [ ] No raw `int` constants or unmanaged `Mat`s crossed the high-level (`Image`) tier.
- [ ] No `Co-Authored-By` / AI-attribution trailer on my commits (the repo owner is the sole author).
