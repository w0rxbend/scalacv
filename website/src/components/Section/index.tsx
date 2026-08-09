/**
 * Section — the shared frame every landing block sits in.
 *
 * It exists so the landing page has exactly one definition of "what a section looks like": the
 * monospace eyebrow, the display heading, the optional standfirst, and the horizontal rhythm. A
 * page assembled from a dozen ad-hoc `<div className="…">` blocks drifts within a week; this does
 * not.
 */

import React from 'react';
import styles from './styles.module.css';

export interface SectionProps {
  /** Small uppercase readout above the heading. Keep it to two or three words. */
  eyebrow?: string;
  title?: string;
  /** One or two sentences under the heading. */
  lede?: React.ReactNode;
  /** `wide` drops the prose measure — for grids and tables that need the full column. */
  variant?: 'default' | 'wide';
  /** Adds a faint tinted backdrop, to break up a long run of identical sections. */
  tinted?: boolean;
  id?: string;
  children?: React.ReactNode;
}

export default function Section({
  eyebrow,
  title,
  lede,
  variant = 'default',
  tinted = false,
  id,
  children,
}: SectionProps): React.ReactElement {
  return (
    <section className={tinted ? `${styles.section} ${styles.tinted}` : styles.section} id={id}>
      <div className={variant === 'wide' ? `${styles.inner} ${styles.wide}` : styles.inner}>
        {(eyebrow || title || lede) && (
          <div className={styles.head}>
            {eyebrow && <p className={styles.eyebrow}>{eyebrow}</p>}
            {title && <h2 className={styles.title}>{title}</h2>}
            {lede && <p className={styles.lede}>{lede}</p>}
          </div>
        )}
        {children}
      </div>
    </section>
  );
}
