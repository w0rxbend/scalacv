# The native cache

The first `OpenCv.load()` in a fresh environment extracts the platform's native libraries out of the
bytedeco jars and into a cache directory, once. On Linux this is about **196 MB** under
`~/.javacpp`, and every later run reuses it.

If that location does not suit you — a read-only home, a container layer you want to keep thin, a
shared cache across builds — point javacpp elsewhere with a system property:

```
-Dorg.bytedeco.javacpp.cachedir=/some/writable/path
```

The extraction is idempotent and content-addressed, so pre-warming that directory in a base image
makes the first `load()` in each container instant.

Cascade classifier data is handled the same way: `Cascades.resolve` extracts the requested XML from
the same payload on demand, and it needs no native library loaded to do it.
