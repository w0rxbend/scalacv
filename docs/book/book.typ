// =============================================================================
//  scalacv — Computer Vision in Scala 3
//
//  Build:  typst compile docs/book/book.typ docs/book/scalacv-book.pdf
//  Watch:  typst watch  docs/book/book.typ
// =============================================================================

#import "lib/book.typ": *
#import "lib/front.typ": *

#show: book.with(
  title: "scalacv: Computer Vision in Scala 3",
  subtitle: "A typed, resource-safe OpenCV for the JVM — from a first pipeline to a production vision service",
  author: "The scalacv authors",
  version: "Covers scalacv 0.1.0 · OpenCV 4.13.0 · Scala 3.3 LTS",
  front: [
    #copyright-page("scalacv 0.1.0, OpenCV 4.13.0, Scala 3.3 LTS, JDK 17+")
    #pagebreak(to: "odd")
    #dedication[
      For everyone who has watched a JVM process die at 6 GB resident\
      while the heap graph sat flat at 40 MB.
    ]
    #pagebreak(to: "odd")
    #toc()
    #pagebreak(to: "odd")
    #include "chapters/preface.typ"
  ],
)

#include "chapters/all.typ"
