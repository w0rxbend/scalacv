# Authoring the scalacv book

The book is a Typst project. `book.typ` is the entry point; it pulls the design system from
`lib/book.typ`, the front matter from `lib/front.typ`, and the body from `chapters/all.typ`, which
`#include`s one file per chapter.

```
docs/book/
  book.typ            entry point — cover, front matter, body
  lib/book.typ        the design system: every visual decision lives here
  lib/front.typ       copyright page, dedication, table of contents
  chapters/all.typ    part dividers + the include list, in reading order
  chapters/*.typ      one file per chapter
  build.sh            typst compile -> scalacv-book.pdf
  check-chapter.sh    compile ONE chapter standalone (fast syntax check)
```

## Building

```bash
docs/book/build.sh                          # -> docs/book/scalacv-book.pdf
docs/book/check-chapter.sh chapters/ch07-image-io.typ
```

Typst 0.15 or newer is required. There is no other dependency: fonts are Libertinus Serif, Lato and
DejaVu Sans Mono, all resolved from the system.

## The chapter contract

Every chapter file begins with the import and one `#chapter` call, and uses nothing but the helpers
below. A chapter never sets its own fonts, colours, page geometry, or `#heading` levels directly —
if a chapter needs a new visual element, it goes into `lib/book.typ` first, with a rationale.

```typst
#import "../lib/book.typ": *

#chapter("Lifetimes", subtitle: [Why a 40-byte object can cost you six gigabytes.])

Body prose.

#sect("A section")           // level 2
#subsect("A subsection")     // level 3
#minor("A run-in heading")   // level 4
```

An appendix uses `#appendix(...)` instead, with the same signature; it is lettered rather than
numbered, and its Examples and Tables pick up the letter (`Example B-1.`).

### Blocks

| Helper | Use it for |
|---|---|
| `#note[…]` | an aside the reader can skip without harm |
| `#tip[…]` | a shortcut, an idiom, a better default |
| `#warning[…]` | something that will bite: wrong results, a stuck build |
| `#caution[…]` | something that costs data or money |
| `#memory[…]` | native-memory lifetime hazards — this book's signature callout |
| `#sidebar("Title")[…]` | a boxed digression of a paragraph or three |
| `#example("Caption.")[…]` | a numbered code listing (`Example 4-2.`) |
| `#figure-table("Caption.")[…]` | a numbered table (`Table 4-1.`) |
| `#tbl(columns: (1fr, 2fr), …)` | the table preset used inside `figure-table` |

Example and table numbers restart at each chapter and pick up the chapter number automatically.

### Code

Fenced blocks with a language tag. Use `scala` for library code, `bash` for shell, `text` for
output, `scala` for build definitions too (Mill's `build.mill` is Scala).

````typst
#example("Every intermediate frees itself.")[
```scala
Image.reading("photo.jpg") { img =>
  img.gray.blur(2).canny(80, 160).write("edges.png")
}
```
]
````

Inline code is single backticks: `` `Managed[Mat]` ``. Prefer it for every type, method, flag and
path name.

### Typst gotchas that bite

- `#` starts a code expression **in markup**. A bare `#` in prose must be escaped: `\#`.
- `@` starts a reference. Escape it in prose: `\@Override`, or wrap it in backticks.
- `_underscores_` are emphasis. Identifiers containing underscores go in backticks.
- `*` is strong emphasis; multiplication in prose should be `×` or backticked.
- A `<` followed by a letter is fine in Typst (unlike MDX), but `<label>` at the end of a block
  makes a label — write `` `Seq[A]` `` style generics in backticks anyway.
- Em dashes: type `---`. En dash: `--`.
- A line starting with `+` or `-` becomes a list item. Indent continuation lines.
- Inside `#example(...)[ ... ]` the fenced block must start at column 0.

## Voice

The library's own documentation sets the register, and the book keeps it: direct, concrete, and
willing to state a cost. Prefer a measured number to an adjective. Say what a thing does before
saying why it is good. Never call something "simply" or "just" — if it were simple the reader
would not be reading. Second person for instructions, first person plural never.

Every claim about the library must be true of the code in this repository. When a chapter needs a
number, take it from `docs/mdoc/benchmark-results.md`, the `CHANGELOG.md`, or the source — do not
invent one.
