/**
 * Paths — four routes into the documentation, chosen by what the reader already knows.
 *
 * This replaces the bulleted "Find your path" list the landing page used to carry. The content is
 * the same set of links; the change is that a reader can now find their own row at a glance instead
 * of reading four paragraphs to discover which one is theirs.
 */

import React from 'react';
import Link from '@docusaurus/Link';
import styles from './styles.module.css';

interface Path {
  /** Two-digit route number, purely as a visual anchor. */
  index: string;
  audience: string;
  question: string;
  steps: Array<{label: string; to: string; note: string}>;
}

const PATHS: Path[] = [
  {
    index: '01',
    audience: 'New to computer vision',
    question: 'You know some Scala. You have never written an image pipeline.',
    steps: [
      {label: 'Image basics', to: '/basics', note: 'What a pixel, a channel and a colour space really are — five minutes.'},
      {label: 'Tutorial: count objects', to: '/tutorial', note: 'Build a working thing, one step at a time.'},
      {label: 'Glossary', to: '/glossary', note: 'Every term on this site, in plain language.'},
    ],
  },
  {
    index: '02',
    audience: 'You already know OpenCV',
    question: 'You have written this in Python or Java and want the Scala idiom.',
    steps: [
      {label: 'Coming from OpenCV', to: '/opencv-java', note: 'The idiom map, and the three deliberate differences.'},
      {label: 'Getting started', to: '/getting-started', note: 'Dependency, natives classifier, first pipeline.'},
      {label: 'Architecture', to: '/architecture', note: 'The two tiers, and why the raw Mat is never hidden.'},
    ],
  },
  {
    index: '03',
    audience: 'Building something now',
    question: 'You have a task and want the shortest correct route to it.',
    steps: [
      {label: 'Choosing an approach', to: '/choosing', note: 'Which detector, which tier, which module.'},
      {label: 'Cookbook', to: '/cookbook', note: 'Recipes to copy and adapt.'},
      {label: 'Operations reference', to: '/operations-reference', note: 'Every operation, its parameters and its units.'},
    ],
  },
  {
    index: '04',
    audience: 'Shipping to production',
    question: 'It works on your laptop. Now it has to survive a week in a container.',
    steps: [
      {label: 'Mat lifecycle', to: '/mat-lifecycle', note: 'The memory model that makes this trustworthy.'},
      {label: 'Concurrency', to: '/concurrency', note: 'What is thread-safe, what is emphatically not.'},
      {label: 'Deploying', to: '/deploying-to-production', note: 'Images, natives, health checks, degradation.'},
    ],
  },
];

export default function Paths(): React.ReactElement {
  return (
    <div className={styles.grid}>
      {PATHS.map((p) => (
        <section key={p.index} className={styles.path}>
          <header className={styles.head}>
            <span className={styles.index} aria-hidden="true">
              {p.index}
            </span>
            <div>
              <h3 className={styles.audience}>{p.audience}</h3>
              <p className={styles.question}>{p.question}</p>
            </div>
          </header>
          <ol className={styles.steps}>
            {p.steps.map((s) => (
              <li key={s.to} className={styles.step}>
                <Link className={styles.stepLink} to={s.to}>
                  {s.label}
                </Link>
                <span className={styles.note}>{s.note}</span>
              </li>
            ))}
          </ol>
        </section>
      ))}
    </div>
  );
}
