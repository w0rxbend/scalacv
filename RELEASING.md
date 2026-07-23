# Releasing scalacv

Publishing goes to Maven Central under `com.worxbend`, via the Sonatype Central Portal.

## One-time namespace setup

`com.worxbend` must be a verified namespace before the first publish:

1. In the [Central Portal](https://central.sonatype.com), register the `com.worxbend` namespace.
2. Add the TXT record it issues to the `worxbend.com` DNS zone (the apex has none today).
3. Wait for verification to complete.

The groupId reverses a domain the owner controls, so this is the standard DNS proof. `bend.worx`
was the original proposal and is impossible — `.bend` is not a delegated TLD.

## One-time docs setup

The documentation site deploys to GitHub Pages from the `Docs` workflow. Before the first deploy,
enable Pages by hand once: in the repository's **Settings -> Pages**, set **Source** to
**GitHub Actions**. The workflow's token cannot create the Pages site itself, so this single
setting is an owner action. Once set, every push to `master` rebuilds and redeploys the site.

## Cutting a release

1. Make sure `master` is green in CI and the working tree is clean.
2. Update `CHANGELOG.md`: move `[Unreleased]` items under a new version heading with the date.
3. Tag: `git tag v0.1.0 && git push origin v0.1.0`. `publishVersion` reads the tag through
   `VcsVersion`, so the tag *is* the version — do not hand-edit a version anywhere.
4. The release workflow builds, signs and uploads `core` (`scalacv`) and `zio` (`scalacv-zio`)
   as a **USER_MANAGED** deployment — it is staged, not released.
5. Inspect the staged deployment in the Central Portal. It must show exactly two artifacts, each
   with sources, javadoc and signatures, and the POM must declare no `<classifier>` dependency.
6. Publish it, or drop it and fix what was wrong. Nothing reaches consumers until you publish.

## What must never happen

- `examples` and `examples-gui` must not publish. CI asserts exactly two publishable modules; if
  that assertion ever fails, do not release.
- The published POM must carry no per-platform classifier (it structurally cannot — but the POM
  test guards the dependency set regardless). Consumers add their own natives line; the README
  and docs explain why.
- MiMa is not armed until `0.2.0`. Do not add `mimaPreviousVersions` for the first release — an
  empty previous-version set fails.
