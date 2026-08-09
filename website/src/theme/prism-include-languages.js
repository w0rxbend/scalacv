/**
 * Swizzled from `@docusaurus/theme-classic/src/theme/prism-include-languages.ts`.
 *
 * Two reasons this file exists rather than the stock one:
 *
 *  1. It loads the grammars named in `themeConfig.prism.additionalLanguages`, exactly as upstream.
 *  2. It then patches the Scala grammar, which predates Scala 3.
 *
 * Prism's bundled `prism-scala` already knows most of the Scala 3 vocabulary — `given`, `using`,
 * `extension`, `enum`, `opaque`, `inline`, `transparent`, `derives`, `infix`, `open` are all in its
 * keyword alternation. Three are missing, and all three appear throughout this site's snippets:
 *
 *   - `then`   — `if cond then a else b`, the brace-free conditional this codebase uses everywhere.
 *   - `end`    — the optional end marker (`end Image`, `end match`).
 *   - `export` — export clauses, which the library uses to re-expose types from a nested module.
 *
 * Prism's Java-inherited operator rule also does not know Scala's type-level arrows and bounds
 * (`?=>`, `=>>`, `<:`, `>:`), so a signature like `[A <: Releasable]` rendered its bound as plain
 * text. That is fixed here too.
 *
 * The patch is additive: it rebuilds the `keyword` pattern from the upstream one rather than
 * restating the whole list, so a future Prism release that adds keywords of its own keeps them.
 */

import siteConfig from '@generated/docusaurus.config';

/** Scala 3 keywords Prism's `scala` grammar does not list. */
const SCALA3_KEYWORDS = ['then', 'end', 'export'];

/**
 * Type-level operators Prism inherits nothing for from Java.
 *
 * Ordered longest-first: a regex alternation is first-match-wins, so `=>>` has to be tried before
 * `=>` or a type lambda would highlight as a plain function arrow followed by a stray `>`.
 */
const SCALA3_OPERATORS = /\?=>|=>>|<:|>:/;

function patchScalaForScala3(Prism) {
  const scala = Prism.languages.scala;
  // Defensive: if a Prism upgrade renames or restructures the grammar, do nothing rather than
  // throw. A missing keyword is a cosmetic regression; a throw here blanks every code block.
  if (!scala || !(scala.keyword instanceof RegExp)) {
    return;
  }

  // Splice the extra keywords into the existing `\b(?:a|b|c)\b` alternation.
  const source = scala.keyword.source;
  const patched = source.replace(/\\b\(\?:/, `\\b(?:${SCALA3_KEYWORDS.join('|')}|`);
  if (patched !== source) {
    scala.keyword = new RegExp(patched, scala.keyword.flags);
  }

  // Type-level operators go BEFORE `keyword`, because `keyword` already matches a bare `=>` and
  // would otherwise consume the first two characters of `=>>`.
  Prism.languages.insertBefore('scala', 'keyword', {
    'scala3-operator': {
      pattern: SCALA3_OPERATORS,
      alias: 'operator',
    },
  });
}

export default function prismIncludeLanguages(PrismObject) {
  const {
    themeConfig: {prism},
  } = siteConfig;
  const {additionalLanguages} = prism;

  // Prism components work on the Prism instance on `window`, while prism-react-renderer uses its
  // own instance. Mount ours temporarily, let the component files enhance it, then unmount so the
  // global namespace is left as we found it. (Upstream comment, kept because it explains the dance.)
  const PrismBefore = globalThis.Prism;
  globalThis.Prism = PrismObject;

  additionalLanguages.forEach((lang) => {
    if (lang === 'php') {
      // eslint-disable-next-line global-require
      require('prismjs/components/prism-markup-templating.js');
    }
    // eslint-disable-next-line global-require, import/no-dynamic-require
    require(`prismjs/components/prism-${lang}`);
  });

  patchScalaForScala3(PrismObject);

  delete globalThis.Prism;
  if (typeof PrismBefore !== 'undefined') {
    globalThis.Prism = PrismObject;
  }
}
