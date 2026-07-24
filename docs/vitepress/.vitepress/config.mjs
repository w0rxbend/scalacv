import { defineConfig } from 'vitepress'

// This directory is the VitePress root. The .md pages beside it are written by
// `./mill docs.mdoc`, which type-checks every scala snippet in `docs/mdoc` before splicing it
// here — so a snippet that stops compiling breaks the docs build, by design.
export default defineConfig({
  title: 'scalacv',
  description: 'A Scala 3 wrapper for the OpenCV 4.13 Java API',
  base: '/scalacv/',
  lastUpdated: true,
  head: [['link', { rel: 'icon', href: '/scalacv/logo.svg' }]],
  themeConfig: {
    logo: '/logo.svg',
    nav: [
      { text: 'Getting Started', link: '/getting-started' },
      { text: 'Image API', link: '/image-api' },
      { text: 'Cookbook', link: '/cookbook' },
      // Scaladoc lives under public/api/{core,zio}, populated by the Docs workflow
      // (./mill core.docJar zio.docJar → unzip). These are static HTML, not VitePress
      // routes, so link to index.html directly and let the browser navigate out of the SPA.
      {
        text: 'API',
        items: [
          { text: 'scalacv (core)', link: '/api/core/index.html', target: '_blank' },
          { text: 'scalacv-zio', link: '/api/zio/index.html', target: '_blank' }
        ]
      }
    ],
    sidebar: [
      { text: 'Introduction', items: [
        { text: 'What is scalacv', link: '/' },
        { text: 'Getting Started', link: '/getting-started' }
      ]},
      { text: 'The high-level API', items: [
        { text: 'The Image API', link: '/image-api' },
        { text: '2D graphics & creative coding', link: '/graphics' },
        { text: 'Cookbook', link: '/cookbook' }
      ]},
      { text: 'The OpenCV surface', items: [
        { text: 'Reading & writing images', link: '/image-io' },
        { text: 'Image processing', link: '/image-processing' },
        { text: 'Geometric transforms & morphology', link: '/transforms' },
        { text: 'Colour, masking & compositing', link: '/color-masking' },
        { text: 'Drawing & annotation', link: '/drawing' },
        { text: 'Geometry & typed values', link: '/geometry' },
        { text: 'Contours & shape analysis', link: '/contours' },
        { text: 'Hough transforms', link: '/hough' }
      ]},
      { text: 'Detection & deep learning', items: [
        { text: 'Object detection', link: '/object-detection' },
        { text: 'Motion detection', link: '/motion-detection' },
        { text: 'Pose estimation', link: '/pose-estimation' },
        { text: 'Gesture & sign recognition', link: '/gestures' },
        { text: 'Deep learning (DNN)', link: '/dnn' }
      ]},
      { text: 'Applications', items: [
        { text: 'Video conferencing', link: '/conferencing' },
        { text: 'Screen analysis', link: '/screen-analysis' },
        { text: 'OCR', link: '/ocr' }
      ]},
      { text: 'Robotics & 3D vision', items: [
        { text: 'Visual navigation & SLAM', link: '/navigation' }
      ]},
      { text: 'Video & runtime', items: [
        { text: 'Video & the camera', link: '/video' },
        { text: 'The native cache', link: '/native-cache' }
      ]},
      { text: 'Concepts', items: [
        { text: 'Mat lifecycle & resource safety', link: '/mat-lifecycle' },
        { text: 'The error model', link: '/error-model' },
        { text: 'Working with the raw OpenCV API', link: '/low-level' }
      ]},
      { text: 'Integrations', items: [
        { text: 'ZIO', link: '/zio' }
      ]}
    ],
    socialLinks: [{ icon: 'github', link: 'https://github.com/w0rxbend/scalacv' }],
    footer: {
      message: 'Apache-2.0. Every snippet here is type-checked by mdoc.',
      copyright: '© 2026 w0rxbend'
    }
  }
})
