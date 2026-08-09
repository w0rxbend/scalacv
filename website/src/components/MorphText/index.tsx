/**
 * MorphText — a word that morphs into the next word in a list, one character at a time.
 *
 * The effect is a "settling decoder": every character position first cycles through a small glyph
 * set, then locks to its final letter, with later positions locking after earlier ones. It is the
 * text equivalent of the detection brackets in the pipeline canvas — something being resolved
 * rather than something appearing.
 *
 * Accessibility notes, because a decorative animation must not degrade the page for anyone:
 *   - The full list of words is rendered once into a visually hidden span, so a screen reader
 *     announces the real sentence and never the intermediate glyph soup.
 *   - The animated span is `aria-hidden`.
 *   - Under `prefers-reduced-motion: reduce` the component renders the first word and never
 *     schedules a timer at all.
 *   - The element reserves the width of the longest word, so the line does not reflow mid-cycle.
 */

import React, {useEffect, useRef, useState} from 'react';
import styles from './styles.module.css';

/** Glyphs a not-yet-settled character cycles through. Chosen to be uniformly narrow and to read as
 *  machine output rather than as a typo. */
const NOISE = '#$%&*+/<=>?@[]^_~01';

export interface MorphTextProps {
  words: string[];
  /** How long a fully settled word is held, in milliseconds. */
  holdMs?: number;
  /** How long the scramble between two words takes, in milliseconds. */
  morphMs?: number;
  className?: string;
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

export default function MorphText({
  words,
  holdMs = 2200,
  morphMs = 620,
  className,
}: MorphTextProps): React.ReactElement {
  const [text, setText] = useState(words[0] ?? '');
  const frame = useRef(0);
  const raf = useRef(0);

  useEffect(() => {
    if (words.length < 2 || prefersReducedMotion()) return undefined;

    let index = 0;
    let phaseStart = 0;
    let morphing = false;
    let cancelled = false;

    const step = (now: number) => {
      if (cancelled) return;
      raf.current = requestAnimationFrame(step);
      if (phaseStart === 0) phaseStart = now;
      const elapsed = now - phaseStart;

      if (!morphing) {
        if (elapsed >= holdMs) {
          morphing = true;
          phaseStart = now;
        }
        return;
      }

      const from = words[index];
      const to = words[(index + 1) % words.length];
      const p = Math.min(1, elapsed / morphMs);
      const len = Math.max(from.length, to.length);

      // Each character position gets its own slice of the morph. `spread` < 1 makes the slices
      // overlap, so the word resolves as a wave rather than one letter at a time.
      const spread = 0.55;
      let out = '';
      for (let i = 0; i < len; i++) {
        const start = (i / len) * spread;
        const local = (p - start) / (1 - spread);
        if (local >= 1) {
          out += to[i] ?? '';
        } else if (local <= 0) {
          out += from[i] ?? '';
        } else {
          // Advance the noise glyph every frame so the unsettled characters visibly churn.
          out += NOISE[(frame.current + i * 7) % NOISE.length];
        }
      }
      frame.current += 1;
      setText(out);

      if (p >= 1) {
        index = (index + 1) % words.length;
        setText(words[index]);
        morphing = false;
        phaseStart = now;
      }
    };

    raf.current = requestAnimationFrame(step);
    return () => {
      cancelled = true;
      cancelAnimationFrame(raf.current);
    };
  }, [words, holdMs, morphMs]);

  const longest = words.reduce((a, b) => (b.length > a.length ? b : a), '');

  return (
    <span className={className ? `${styles.wrap} ${className}` : styles.wrap}>
      {/* Reserves the line's width against the longest word, so nothing reflows as it cycles. */}
      <span className={styles.ghost} aria-hidden="true">
        {longest}
      </span>
      {/* The live copy is absolutely positioned but sized to its own content, so the blinking caret
          that follows it sits against the *current* word rather than against the reserved width of
          the longest one. */}
      <span className={styles.live} aria-hidden="true">
        {text}
        <i className={styles.caret} />
      </span>
      {/* What assistive technology actually announces. The animated copy above is aria-hidden and
          the width-reserving copy is `visibility: hidden`, so this span alone supplies the
          heading's accessible name — which means it has to read as a sentence, not as a list
          dump. "edges, contours … and depth" completes "Teach the JVM to see …" grammatically. */}
      <span className={styles.srOnly}>
        {words.length > 1 ? `${words.slice(0, -1).join(', ')} and ${words.at(-1)}` : words[0]}
      </span>
    </span>
  );
}
