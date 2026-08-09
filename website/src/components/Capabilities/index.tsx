/**
 * Capabilities — what is actually in the box, grouped the way the sidebar groups it.
 *
 * Every chip on every card links to a guide page that exists. That constraint is deliberate: a
 * capability grid whose links go nowhere is a marketing page, and this one has to survive
 * `onBrokenLinks: 'throw'` at build time, so it stays honest by construction.
 *
 * Icons are inline SVG drawn from the same vocabulary as the pipeline canvas — apertures, tracking
 * brackets, waveforms, grids. Inline rather than a sprite or an icon font: eight small paths cost
 * less than one extra request, and they inherit `currentColor` so theming is free.
 */

import React from 'react';
import Link from '@docusaurus/Link';
import styles from './styles.module.css';

type Icon = 'aperture' | 'contour' | 'bracket' | 'film' | 'skeleton' | 'axes' | 'layers' | 'shield';

interface Capability {
  icon: Icon;
  title: string;
  body: string;
  links: Array<{label: string; to: string}>;
  /** Accent used for the card's icon and hover rail. */
  tone: 'cyan' | 'red' | 'violet' | 'amber' | 'lime';
}

const CAPABILITIES: Capability[] = [
  {
    icon: 'aperture',
    tone: 'cyan',
    title: 'Core imaging',
    body:
      'Read, decode, convert, filter and write. Colour-space conversion, blurs and sharpening, ' +
      'morphology, thresholding, resizing, rotation, warping and cropping — all typed, all ' +
      'releasing their own intermediates.',
    links: [
      {label: 'Image API', to: '/image-api'},
      {label: 'Filters', to: '/filters'},
      {label: 'Transforms', to: '/transforms'},
      {label: 'Colour masking', to: '/color-masking'},
    ],
  },
  {
    icon: 'contour',
    tone: 'lime',
    title: 'Shapes & measurement',
    body:
      'Find the outlines in a binary image, measure their area and perimeter, fit boxes and ' +
      'ellipses, simplify polygons, and pull straight lines and circles out of an edge map.',
    links: [
      {label: 'Contours', to: '/contours'},
      {label: 'Hough', to: '/hough'},
      {label: 'Geometry', to: '/geometry'},
    ],
  },
  {
    icon: 'bracket',
    tone: 'red',
    title: 'Detection & deep learning',
    body:
      'Haar cascades and the YuNet DNN face detector, ONNX and Caffe models through OpenCV’s own ' +
      'inference engine, QR codes, ArUco markers, and face recognition by embedding distance.',
    links: [
      {label: 'Object detection', to: '/object-detection'},
      {label: 'Neural networks', to: '/dnn'},
      {label: 'Faces', to: '/face-recognition'},
      {label: 'Markers & AR', to: '/marker-ar'},
    ],
  },
  {
    icon: 'film',
    tone: 'amber',
    title: 'Video & camera',
    body:
      'A high-level Camera and Recorder over VideoCapture and VideoWriter, frame streams you can ' +
      'fold over, and background-subtraction motion detection for a fixed or MJPEG camera.',
    links: [
      {label: 'Video', to: '/video'},
      {label: 'Motion detection', to: '/motion-detection'},
      {label: 'Video tutorial', to: '/tutorial-video'},
    ],
  },
  {
    icon: 'skeleton',
    tone: 'violet',
    title: 'Human sensing',
    body:
      'Body and hand skeletons, head-pose angles from facial landmarks, and a gesture recogniser ' +
      'built on top of them — plus the background blur and virtual backgrounds a call needs.',
    links: [
      {label: 'Pose estimation', to: '/pose-estimation'},
      {label: 'Gestures', to: '/gestures'},
      {label: 'Conferencing', to: '/conferencing'},
    ],
  },
  {
    icon: 'axes',
    tone: 'cyan',
    title: 'Robotics & 3D vision',
    body:
      'Chessboard calibration and lens undistortion, stereo depth and obstacle maps, sparse and ' +
      'dense optical flow, ORB features, visual odometry, loop closure and an occupancy grid.',
    links: [
      {label: 'Calibration', to: '/calibration'},
      {label: 'Navigation', to: '/navigation'},
    ],
  },
  {
    icon: 'layers',
    tone: 'violet',
    title: '2D graphics & charts',
    body:
      'A composable Picture scene graph for overlays — dashed strokes, alpha compositing, text ' +
      'boxes, charts and animated GIFs — that renders down onto an Image without leaking a Mat.',
    links: [
      {label: 'Graphics', to: '/graphics'},
      {label: 'Drawing', to: '/drawing'},
    ],
  },
  {
    icon: 'shield',
    tone: 'lime',
    title: 'Built to ship',
    body:
      'A native-memory model you can reason about, a concurrency story that names what is and is ' +
      'not thread-safe, benchmark-backed performance guidance, and a production deployment guide.',
    links: [
      {label: 'Mat lifecycle', to: '/mat-lifecycle'},
      {label: 'Concurrency', to: '/concurrency'},
      {label: 'Performance', to: '/performance'},
      {label: 'Deploying', to: '/deploying-to-production'},
    ],
  },
];

/** All icons share a 24×24 box, a 1.6 stroke and `currentColor`, so they sit on one optical weight. */
function CapIcon({name}: {name: Icon}): React.ReactElement {
  const common = {
    width: 24,
    height: 24,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.6,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    focusable: false,
  };
  switch (name) {
    case 'aperture':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="M12 3v7.5M20.8 8.2l-6.5 3.8M20.8 15.8 14.3 12M12 21v-7.5M3.2 15.8l6.5-3.8M3.2 8.2 9.7 12" />
        </svg>
      );
    case 'contour':
      return (
        <svg {...common}>
          <path d="M4 15c0-5 3-9 7-9s5 3 5 6-2 4-4 4-3-1.4-3-3 1-2.5 2-2.5" />
          <path d="M3 20h18" strokeDasharray="3 3" />
        </svg>
      );
    case 'bracket':
      return (
        <svg {...common}>
          <path d="M4 8V4h4M20 8V4h-4M4 16v4h4M20 16v4h-4" />
          <circle cx="12" cy="12" r="3" />
        </svg>
      );
    case 'film':
      return (
        <svg {...common}>
          <rect x="3" y="5" width="18" height="14" rx="2" />
          <path d="M3 9h18M3 15h18M8 5v14M16 5v14" />
        </svg>
      );
    case 'skeleton':
      return (
        <svg {...common}>
          <circle cx="12" cy="5" r="2" />
          <path d="M12 7v6M12 13l-3 6M12 13l3 6M7 10l5 1 5-1" />
        </svg>
      );
    case 'axes':
      return (
        <svg {...common}>
          <path d="M12 21V9M12 9 4 5M12 9l8-4" />
          <path d="M4 5v10l8 6 8-6V5" strokeDasharray="2.5 2.5" />
        </svg>
      );
    case 'layers':
      return (
        <svg {...common}>
          <path d="m12 3 8 4.5-8 4.5-8-4.5L12 3Z" />
          <path d="m4 12 8 4.5 8-4.5" />
          <path d="m4 16.5 8 4.5 8-4.5" />
        </svg>
      );
    case 'shield':
    default:
      return (
        <svg {...common}>
          <path d="M12 3 5 6v6c0 4.4 2.9 7.9 7 9 4.1-1.1 7-4.6 7-9V6l-7-3Z" />
          <path d="m9 12 2 2 4-4" />
        </svg>
      );
  }
}

export default function Capabilities(): React.ReactElement {
  return (
    <ul className={styles.grid}>
      {CAPABILITIES.map((c) => (
        <li key={c.title} className={`${styles.card} ${styles[c.tone]}`}>
          <span className={styles.icon}>
            <CapIcon name={c.icon} />
          </span>
          <h3 className={styles.title}>{c.title}</h3>
          <p className={styles.body}>{c.body}</p>
          <div className={styles.links}>
            {c.links.map((l) => (
              <Link key={l.to} className={styles.chip} to={l.to}>
                {l.label}
              </Link>
            ))}
          </div>
        </li>
      ))}
    </ul>
  );
}
