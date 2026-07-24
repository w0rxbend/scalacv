# SEARCH-REPORT.md — one box over guides AND API

## Engine: Pagefind (local), via `docusaurus-plugin-pagefind`

Docusaurus's default search options (Algolia DocSearch, local-search plugins) index only Docusaurus's
own content graph. This site's API reference is **static Scaladoc HTML under `static/api/`** — outside
that graph — so those engines would silently cover only half the site.

**Pagefind** indexes the *final built HTML* in a post-build pass, so it picks up the guides **and**
the generated Scaladoc in one index. It needs no account, no crawl approval, and works on PR previews
(unlike Algolia DocSearch, which requires an application and a live public crawl and lags each
deploy). The UI is the `@docsearch/react` ⌘K modal the plugin ships, so the keyboard UX matches what
users expect. That combination — unified coverage + zero external dependency — is why Pagefind wins
here over Algolia.

One search box only: the plugin provides `@theme/SearchBar`, so there is a single box in the navbar.
Scaladoc's own in-page search still exists *inside* `/api/`, scoped to the API; the site-wide box is
the unified one.

## Coverage (measured)

Verified against the built index, not asserted:

| Source | HTML pages in `build/` | Indexed |
|---|---|---|
| Guides + site routes | 35 | ✓ |
| Scaladoc (`/api/core` + `/api/zio`) | 168 | ✓ |
| **Total** | **203** | **`page_count: 203`** |

The Pagefind `page_count` (203) equals guides (35) + API (168) exactly — the API reference is fully
in the index. `.navbar`, `.footer`, the right-hand TOC, and pagination are excluded from indexing so
nav chrome doesn't pollute results.

## Ten-query relevance test (measured)

Run against the built Pagefind index (`pagefind.search(q)`, top result shown). Every query returns
results, and the guide/API mix confirms unified search:

| Query | Hits | Top result | Kind |
|---|---:|---|---|
| `GaussianBlur` | 5 | `/image-processing` | guide |
| `load image` | 30 | `…/CvError$$LoadFailed.html` | API |
| `FaceRecognizer` | 7 | `…/FaceRecognizer$.html` | API |
| `not leak memory` | 7 | `/mat-lifecycle` | guide |
| `BufferedImage` | 4 | `/notebooks` | guide |
| `canny` | 13 | `/hough` | guide |
| `ArUco` | 14 | `…/ArucoDictionary.html` | API |
| `stereo depth` | 8 | `…/StereoDepth$.html` | API |
| `ZIO` | 4 | `/api/zio/` | API |
| `Mat lifecycle` | 6 | `/mat-lifecycle` | guide |

All ten surface a relevant page. Method/type names (`FaceRecognizer`, `StereoDepth`, `ArUco`) land on
their API pages; task phrases (`not leak memory`, `Mat lifecycle`) land on the right guide. Two are
serviceable but improvable — `GaussianBlur` tops `image-processing` rather than the `GaussianBlur` API
page, and `canny` tops `hough` (which uses Canny) over `image-processing`. Both are relevance-tuning,
not coverage gaps; noted as follow-up, not a blocker.

## Required UX

The `@docsearch/react` modal provides `⌘K`/`Ctrl-K` to open, `/` shortcut, arrow-key navigation,
highlighted matches with surrounding context, an empty state, and Escape-to-close with focus
restoration — the brief's required-UX list, out of the box. Verified the SearchBar renders in the
built navbar (`DocSearch` markup present in `build/index.html`).

## Follow-up

- Relevance tuning for `GaussianBlur` / `canny` (Pagefind ranking weights, or `data-pagefind-weight`
  on API signature headings).
- Confirm the ⌘K flow interactively on the deployed `/scalacv/` URL after this ships.
