// The body of the book, in reading order. Part dividers live here so the
// chapter files stay pure content.

#import "../lib/book.typ": *

// ============================================================================
#part("Foundations")[
  What OpenCV's Java bindings are, what they get wrong on the JVM, and the five
  ideas --- typed constants, owned handles, move semantics, scoped lifetimes and
  errors-as-values --- that the rest of the library is built out of.
]

#include "ch01-why.typ"
#include "ch02-setup.typ"
#include "ch03-first-pipeline.typ"
#include "ch04-image.typ"
#include "ch05-lifetimes.typ"
#include "ch06-errors.typ"

// ============================================================================
#part("Working with Pixels")[
  Classical image processing, in the order you actually apply it: get the pixels
  in, describe them in typed terms, filter them, move them, reduce them to a
  mask, measure what the mask contains, and draw the answer back on top.
]

#include "ch07-image-io.typ"
#include "ch08-enums.typ"
#include "ch09-filters.typ"
#include "ch10-transforms.typ"
#include "ch11-segmentation.typ"
#include "ch12-contours.typ"
#include "ch13-hough.typ"
#include "ch14-drawing.typ"
#include "ch15-photo.typ"

// ============================================================================
#part("The Graphics Layer")[
  `scalacv-graphs`: an immutable scene graph that turns an overlay from a
  sequence of mutating calls into a value you can build, transform, test and
  composite --- plus the charts and animations that fall out of it for free.
]

#include "ch16-picture.typ"
#include "ch17-charts.typ"
#include "ch18-animation.typ"

// ============================================================================
#part("Video and Cameras")[
  Frames arrive whether or not you are ready for them. Reading and writing
  video, driving a live camera, detecting motion cheaply, and deciding what
  happens when the pipeline falls behind the source.
]

#include "ch19-video.typ"
#include "ch20-camera.typ"
#include "ch21-motion.typ"
#include "ch22-streaming.typ"

// ============================================================================
#part("Vision Applications")[
  `scalacv-vision`: faces, deep networks, markers, poses, tracks, calibration
  and the visual-navigation front end. Every one of them owns native memory that
  the Java bindings will not free for you.
]

#include "ch23-models.typ"
#include "ch24-faces.typ"
#include "ch25-recognition.typ"
#include "ch26-dnn.typ"
#include "ch27-detection.typ"
#include "ch28-markers.typ"
#include "ch29-pose.typ"
#include "ch30-tracking.typ"
#include "ch31-calibration.typ"
#include "ch32-navigation.typ"
#include "ch33-ocr.typ"
#include "ch34-screen-conferencing.typ"

// ============================================================================
#part("Into Production")[
  The part that decides whether the service survives its first week: where the
  time and the memory go, what may be shared between threads, what to measure,
  what to do when you cannot keep up, and how to package natives without a
  four-hundred-megabyte surprise.
]

#include "ch35-performance.typ"
#include "ch36-concurrency.typ"
#include "ch37-zio.typ"
#include "ch38-observability.typ"
#include "ch39-degradation.typ"
#include "ch40-testing.typ"
#include "ch41-deploying.typ"
#include "ch42-troubleshooting.typ"

// ============================================================================
#part("Appendices")[
  The escape hatch to raw OpenCV, and the lookup tables --- operations, typed
  constants, sample data, and the vocabulary.
]

#include "appA-low-level.typ"
#include "appB-operations.typ"
#include "appC-enums.typ"
#include "appD-notebooks.typ"
#include "appE-glossary.typ"
