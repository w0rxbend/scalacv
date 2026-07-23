---
name: Feature request
about: A part of OpenCV you would like wrapped
labels: enhancement
---

**Which OpenCV API**
Class and method, ideally with a link to the 4.13 docs.

**What you are trying to do**

**Does it own native memory?**
If so, does it expose a public `release()`? Most OpenCV types do not — three out of 188 —
which decides how it has to be wrapped.
