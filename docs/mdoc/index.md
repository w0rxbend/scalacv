---
layout: home
hero:
  name: scalacv
  text: OpenCV 4.13 for Scala 3
  tagline: A fluent, high-level image API over the complete OpenCV Java bindings — typed, headless, and honest about native memory.
  image:
    src: /logo.svg
    alt: scalacv
  actions:
    - theme: brand
      text: Getting Started
      link: /getting-started
    - theme: alt
      text: The Image API
      link: /image-api
    - theme: alt
      text: GitHub
      link: https://github.com/w0rxbend/scalacv
features:
  - icon: 🎯
    title: A high-level pipeline
    details: Image.read(…).gray.blur(2).canny(80, 160).write(…) — read, transform, detect, annotate, write as one chain. Every intermediate frees itself.
  - icon: 🧱
    title: The full surface, never walled off
    details: Drop to the typed org.opencv.* API and the mid-level Managed[Mat] ops any time. The high-level layer is the pleasant default, not a ceiling.
  - icon: 🧹
    title: Resource-safe by construction
    details: Move semantics free every intermediate; a spent handle throws in Scala, before the mistake becomes a native SIGSEGV.
  - icon: 🖥️
    title: Genuinely headless
    details: OpenCv.load() needs no GUI toolkit and no apt-get, on any runner — plus ArUco, QR, YuNet faces, ONNX, and an optional ZIO module.
---
