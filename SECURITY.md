# Security policy

scalacv wraps a native library. A mistake in the lifetime handling here does not surface as an
exception — it is a use-after-free or a double-free that segfaults the host JVM from native code,
with no Java stack trace. That is the class of bug this library exists to prevent, so a credible
report of one is taken seriously.

## Supported versions

The project is pre-1.0. Fixes land on `master` and go out in the next release; there are no
backports to earlier `0.x` tags. Report against the latest published version, or `master`.

## Reporting a vulnerability

Please report privately, not in a public issue:

- Use GitHub's **[private vulnerability reporting](https://github.com/w0rxbend/scalacv/security/advisories/new)**
  (repository **Security → Report a vulnerability**). It opens a private advisory only the maintainer
  can see.

Include enough to reproduce: the scalacv version, the JDK (`java -version`), the platform and
bytedeco classifier (e.g. `linux-x86_64`), and a minimal snippet. If the JVM crashed, attach the
`hs_err_pid*.log` — for a native crash it is usually the only evidence there is.

You can expect an acknowledgement within a few days. Once a fix is out, credit is offered in the
advisory unless you would rather stay anonymous.

## Scope

In scope: memory-safety defects reachable through the public API (a leak, a use-after-free, a
double-free), and any way the library mishandles untrusted input — a crafted image, model, or video
file — into a native crash or worse.

Out of scope: vulnerabilities in OpenCV, the bytedeco javacpp-presets, or the JVM itself. Report
those upstream; if scalacv can add a guard around one, a note here is still welcome.
