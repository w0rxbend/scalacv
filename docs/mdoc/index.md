---
layout: home
hero:
  name: scalacv
  text: OpenCV 4.13, in idiomatic Scala 3
  tagline: Typed, headless, and honest about native memory.
  image:
    src: /logo.svg
    alt: scalacv
  actions:
    - theme: brand
      text: Getting Started
      link: /getting-started
    - theme: alt
      text: Why it exists
      link: /mat-lifecycle
    - theme: alt
      text: GitHub
      link: https://github.com/w0rxbend/scalacv
features:
  - icon: 🧭
    title: Typed, not stringly
    details: ColorConversion.BgrToGray, not the integer 6. Enums, geometry value types, and errors as a CvError ADT.
  - icon: 🧹
    title: Resource-safe by construction
    details: Managed[A] releases exactly once and throws on use-after-release — in Scala, before the mistake becomes a native SIGSEGV.
  - icon: 🖥️
    title: Genuinely headless
    details: OpenCv.load() needs no GUI toolkit and no apt-get, on any runner.
  - icon: ⚡
    title: Modern OpenCV
    details: ArUco, QR, YuNet face detection and ONNX inference — plus an optional ZIO module.
---
