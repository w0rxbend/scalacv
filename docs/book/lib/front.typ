#import "book.typ": *

#let copyright-page(version) = [
  #v(1fr)
  #set text(size: 9pt, fill: muted)
  #set par(justify: false, leading: 0.6em)

  *scalacv: Computer Vision in Scala 3*

  Copyright © 2026 the scalacv authors. All rights reserved.

  scalacv is free software, published under the Apache License, Version 2.0. You may obtain a copy
  of the licence at #link("https://www.apache.org/licenses/LICENSE-2.0")[apache.org/licenses/LICENSE-2.0].
  This book is documentation for that library and is distributed on the same terms, without
  warranties or conditions of any kind, either express or implied.

  #v(6pt)
  Written for #version. Because the library is pre-1.0, a minor version bump may break
  compatibility; every code listing here was written against the version named above.

  #v(6pt)
  OpenCV is licensed under the Apache License 2.0 by OpenCV.org. The native binaries this library
  loads are the Bytedeco JavaCPP presets for OpenCV, also Apache-2.0. Neither project endorses this
  one. Scala is a trademark of the École Polytechnique Fédérale de Lausanne.

  #v(6pt)
  The cover mark is a six-bladed aperture --- the camera diaphragm that decides, mechanically and
  irreversibly, how much light reaches the sensor. It was chosen for the same reason this book keeps
  returning to lifetimes: the interesting part of an imaging system is not what it computes but what
  it lets go of.

  #v(6pt)
  Set in Libertinus Serif, with Lato for display and DejaVu Sans Mono for code. Typeset with Typst.

  #v(0.35fr)
]

#let dedication(body) = [
  #v(1fr)
  #align(center)[
    #block(width: 70%)[
      #set text(size: 12pt, style: "italic", fill: muted)
      #set par(justify: false)
      #body
    ]
  ]
  #v(2fr)
]

#let toc() = [
  #heading(level: 1, outlined: false)[Table of Contents]
  #v(6pt)
  #show outline.entry.where(level: 1): it => {
    v(9pt, weak: true)
    text(font: display-font, size: 10.5pt, weight: "bold")[#it]
  }
  #show outline.entry.where(level: 3): it => text(size: 9pt, fill: muted)[#it]
  #outline(title: none, depth: 3, indent: 1.1em)
]
