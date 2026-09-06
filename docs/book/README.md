# scalacv: Computer Vision in Scala 3 — the book

A book-length treatment of the library, typeset with [Typst](https://typst.app) in the style of an
O'Reilly animal book. Seven parts, forty-two chapters and five appendices, from the native-memory
problem that motivates the design through to packaging natives into a container.

## Build

```bash
docs/book/build.sh          # -> docs/book/scalacv-book.pdf
```

The only dependency is Typst 0.15 or newer:

```bash
curl -sL https://github.com/typst/typst/releases/latest/download/typst-x86_64-unknown-linux-musl.tar.xz \
  | tar xJ -C /tmp && install /tmp/typst-x86_64-unknown-linux-musl/typst ~/.local/bin/
```

Fonts (Libertinus Serif, Lato, DejaVu Sans Mono) are resolved from the system; on a bare runner,
install `fonts-lato`, `fonts-dejavu` and `fonts-libertinus` or point Typst at a font directory with
`--font-path`.

While writing, `typst watch docs/book/book.typ` recompiles on save, and
`docs/book/check-chapter.sh chapters/ch09-filters.typ` compiles a single chapter in about a second.

## Layout

| Path | What it is |
|---|---|
| `book.typ` | entry point: cover, front matter, body |
| `lib/book.typ` | the design system — every visual decision, with its rationale |
| `lib/front.typ` | copyright page, dedication, table of contents |
| `chapters/all.typ` | part dividers and the include list, in reading order |
| `chapters/*.typ` | one file per chapter or appendix |
| `AUTHORING.md` | the chapter contract and the house voice |

## Relationship to `docs/mdoc/`

`docs/mdoc/` is the source of truth: those pages are type-checked by mdoc against the library on
every build, and they become the website. The book is written from them and from the source, and it
is *not* mdoc-checked — so when the two disagree, `docs/mdoc/` wins and the book is the thing that
needs fixing.
