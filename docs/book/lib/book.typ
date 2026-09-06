// ============================================================================
//  scalacv — the book.  Layout in the O'Reilly animal-book tradition.
//
//  Everything visual lives here so a chapter file is nothing but content.
//  Chapters call: chapter, sect, subsect, note, warning, tip, memory, sidebar,
//  example, figure-table, keyterm, api.
// ============================================================================

#let brand      = rgb("#bb2a22")   // link/accent red, AA on white (6.06:1)
#let brand-deep = rgb("#7a1c17")   // rules, chapter numerals
#let ink        = rgb("#141414")
#let muted      = rgb("#5a5a5a")
#let rule-grey  = rgb("#c9c9c9")
#let code-bg    = rgb("#f6f5f3")
#let side-bg    = rgb("#f2efe9")

#let body-font    = ("Libertinus Serif", "DejaVu Serif")
#let display-font = ("Lato", "DejaVu Sans")
#let mono-font    = ("DejaVu Sans Mono",)

// -- state used by running heads ---------------------------------------------
#let cur-chapter = state("cur-chapter", none)
#let cur-part    = state("cur-part", none)
#let part-counter = counter("part")
#let app-counter  = counter("appendix")
#let in-appendix  = state("in-appendix", false)
#let ex-counter  = counter("example")
#let tbl-counter = counter("booktable")

// -- inline helpers ----------------------------------------------------------
#let code(body) = raw(body)
#let api(body)  = raw(body)
#let keyterm(body) = emph(body)
#let path(body) = raw(body)

// ---------------------------------------------------------------------------
//  Admonitions.  O'Reilly puts a marginal icon beside a hanging block; we use
//  a coloured spine, which survives monochrome printing better than colour fill.
// ---------------------------------------------------------------------------
// "4" for chapter four, "B" for appendix B, "P" in the front matter (where the
// chapter counter is still zero) — the prefix on Example and Table numbers.
#let _unit-label() = context {
  if in-appendix.get() { app-counter.display("A") }
  else {
    let n = counter(heading).get()
    let c = if n.len() > 0 { n.at(0) } else { 0 }
    if c == 0 { "P" } else { str(c) }
  }
}

#let _admonition(label, accent, body) = block(
  width: 100%,
  inset: (left: 10pt, top: 7pt, bottom: 7pt, right: 8pt),
  stroke: (left: 2.5pt + accent),
  fill: accent.lighten(93%),
  breakable: true,
  above: 12pt, below: 12pt,
)[
  #text(font: display-font, size: 7.6pt, weight: "bold", fill: accent, tracking: 0.7pt)[#upper(label)]
  #v(-4pt)
  #set text(size: 9.6pt)
  // A narrow measure plus long inline code makes justification tear open; these
  // blocks are short enough that a ragged right edge reads better than rivers.
  #set par(justify: false)
  #body
]

#let note(body)    = _admonition("Note", rgb("#2a5d9f"), body)
#let tip(body)     = _admonition("Tip", rgb("#1f7a4d"), body)
#let warning(body) = _admonition("Warning", rgb("#a8620a"), body)
#let memory(body)  = _admonition("Native memory", brand, body)
#let caution(body) = _admonition("Caution", rgb("#a02020"), body)

// -- a full sidebar (O'Reilly's boxed digression) ----------------------------
#let sidebar(title, body) = block(
  width: 100%,
  fill: side-bg,
  stroke: 0.6pt + rule-grey,
  inset: 11pt,
  radius: 1pt,
  breakable: true,
  above: 14pt, below: 14pt,
)[
  #text(font: display-font, size: 10pt, weight: "bold", fill: brand-deep)[#title]
  #v(2pt)
  #line(length: 100%, stroke: 0.5pt + rule-grey)
  #v(2pt)
  #set text(size: 9.6pt)
  #set par(justify: false)
  #body
]

// -- numbered code example, O'Reilly "Example 3-2." --------------------------
#let example(caption, body) = {
  ex-counter.step()
  block(breakable: true, above: 13pt, below: 13pt, width: 100%)[
    #text(font: display-font, size: 8.4pt, weight: "bold", fill: brand-deep)[
      Example #_unit-label()-#context ex-counter.display().
    ]
    #h(4pt)
    #text(font: display-font, size: 8.4pt, fill: muted)[#caption]
    #v(3pt)
    #body
  ]
}

// -- numbered table ----------------------------------------------------------
#let figure-table(caption, body) = {
  tbl-counter.step()
  block(breakable: true, above: 13pt, below: 13pt, width: 100%)[
    #text(font: display-font, size: 8.4pt, weight: "bold", fill: brand-deep)[
      Table #_unit-label()-#context tbl-counter.display().
    ]
    #h(4pt)
    #text(font: display-font, size: 8.4pt, fill: muted)[#caption]
    #v(4pt)
    #body
  ]
}

// -- a plain, tidy table preset ----------------------------------------------
#let tbl(columns: auto, ..cells) = table(
  columns: columns,
  stroke: none,
  inset: (x: 7pt, y: 4.5pt),
  fill: (_, row) => if row == 0 { rgb("#eceae6") } else { none },
  ..cells,
)

// ---------------------------------------------------------------------------
//  Structure
// ---------------------------------------------------------------------------
#let part(title, blurb) = {
  pagebreak(weak: false, to: "odd")
  cur-part.update(title)
  part-counter.step()
  set page(header: none, footer: none)
  v(1fr)
  align(center)[
    #context text(font: display-font, size: 11pt, fill: brand, tracking: 3pt)[
      #upper[Part #part-counter.display("I")]
    ]
    #v(10pt)
    #line(length: 38%, stroke: 1.2pt + brand)
    #v(14pt)
    #text(font: display-font, size: 26pt, weight: "bold", fill: ink)[#title]
    #v(14pt)
    #line(length: 38%, stroke: 1.2pt + brand)
    #v(16pt)
    #block(width: 74%)[
      #set text(size: 10.5pt, fill: muted)
      #set par(justify: false)
      #align(center)[#blurb]
    ]
  ]
  v(1.4fr)
  pagebreak()
}

#let chapter(title, subtitle: none) = {
  pagebreak(weak: true, to: "odd")
  cur-chapter.update(title)
  ex-counter.update(0)
  tbl-counter.update(0)
  heading(level: 1, title)
  if subtitle != none {
    v(-4pt)
    block(width: 92%)[
      #set text(size: 11.5pt, fill: muted, style: "italic")
      #subtitle
    ]
    v(4pt)
  }
}

/// An appendix: same furniture as a chapter, lettered instead of numbered.
#let appendix(title, subtitle: none) = {
  pagebreak(weak: true, to: "odd")
  in-appendix.update(true)
  app-counter.step()
  cur-chapter.update(title)
  ex-counter.update(0)
  tbl-counter.update(0)
  heading(level: 1, title)
  if subtitle != none {
    v(-4pt)
    block(width: 92%)[
      #set text(size: 11.5pt, fill: muted, style: "italic")
      #subtitle
    ]
    v(4pt)
  }
}

#let sect(title)    = heading(level: 2, title)
#let subsect(title) = heading(level: 3, title)
#let minor(title)   = heading(level: 4, title)

// ---------------------------------------------------------------------------
//  The document shell
// ---------------------------------------------------------------------------
#let book(title: "", subtitle: "", author: "", version: "", front: [], body) = {
  set document(title: title, author: author)

  set page(
    width: 7in, height: 9.25in,           // O'Reilly trim
    margin: (inside: 0.85in, outside: 0.7in, top: 0.75in, bottom: 0.75in),
    binding: left,
  )

  set text(font: body-font, size: 10.5pt, fill: ink, lang: "en", hyphenate: true)
  set par(justify: true, leading: 0.62em, spacing: 0.95em, first-line-indent: 0pt)

  // Headings ---------------------------------------------------------------
  set heading(numbering: none)
  show heading: set text(font: display-font, fill: ink)

  show heading.where(level: 1): it => {
    block(above: 0pt, below: 20pt)[
      #context {
        let eyebrow = if in-appendix.get() {
          [#upper[Appendix #app-counter.display("A")]]
        } else {
          let n = counter(heading).get().at(0)
          if n > 0 { [#upper[Chapter #n]] } else { none }
        }
        if eyebrow != none {
          text(size: 9.5pt, font: display-font, weight: "bold", fill: brand, tracking: 2.6pt)[#eyebrow]
          v(5pt)
          line(length: 100%, stroke: 2pt + brand)
          v(10pt)
        }
      }
      #text(size: 24pt, weight: "bold")[#it.body]
    ]
  }
  show heading.where(level: 2): it => block(above: 20pt, below: 8pt)[
    #text(size: 14.5pt, weight: "bold")[#it.body]
  ]
  show heading.where(level: 3): it => block(above: 15pt, below: 6pt)[
    #text(size: 11.6pt, weight: "bold", fill: brand-deep)[#it.body]
  ]
  show heading.where(level: 4): it => block(above: 12pt, below: 4pt)[
    #text(size: 10.5pt, weight: "bold", style: "italic")[#it.body]
  ]

  // Code -------------------------------------------------------------------
  show raw.where(block: false): it => box(
    fill: code-bg,
    inset: (x: 2.5pt, y: 0pt),
    outset: (y: 3pt),
    radius: 1.5pt,
    text(font: mono-font, size: 0.87em, fill: rgb("#3c2f2f"))[#it],
  )
  // 7.7pt DejaVu Sans Mono fits ~82 columns in the text block, which clears about
  // 87% of the listings whole; the rest wrap, and the hanging indent makes a
  // wrapped line read as a continuation rather than a new statement at column 0.
  show raw.where(block: true): it => block(
    width: 100%,
    fill: code-bg,
    stroke: (left: 2pt + rule-grey),
    inset: (x: 7pt, y: 8pt),
    radius: 1pt,
    breakable: true,
    above: 11pt, below: 11pt,
    {
      set par(justify: false, leading: 0.52em, hanging-indent: 2em)
      text(font: mono-font, size: 7.7pt)[#it]
    },
  )
  set raw(tab-size: 2)

  // Links, lists, quotes ---------------------------------------------------
  show link: it => text(fill: brand)[#it]
  set list(indent: 8pt, spacing: 0.72em, marker: text(fill: brand)[•])
  set enum(indent: 8pt, spacing: 0.72em)
  set terms(indent: 8pt, hanging-indent: 12pt, separator: [ --- ])
  show quote.where(block: true): it => block(
    inset: (left: 12pt), stroke: (left: 2pt + rule-grey),
  )[#text(style: "italic", fill: muted)[#it.body]]

  set table(stroke: none)
  show table: set text(size: 9.3pt)

  // ---- Cover -------------------------------------------------------------
  set page(header: none, footer: none, margin: 0pt)
  page[
    #place(top + left, rect(width: 100%, height: 3.05in, fill: brand))
    #place(top + left, dx: 0pt, dy: 3.05in, rect(width: 100%, height: 5pt, fill: brand-deep))
    #place(top + left, dx: 0.75in, dy: 0.62in)[
      #text(font: display-font, size: 10pt, fill: white.transparentize(15%), tracking: 4pt)[
        #upper[Scala 3 · OpenCV 4.13]
      ]
      #v(14pt)
      #text(font: display-font, size: 40pt, weight: "bold", fill: white)[scalacv]
      #v(2pt)
      #block(width: 4.6in)[
        #set par(justify: false, leading: 0.5em)
        #text(font: display-font, size: 13pt, fill: white.transparentize(10%))[#subtitle]
      ]
    ]

    // The animal plate: an aperture, drawn rather than engraved.
    #place(center, dy: 4.65in)[
      #let blade(i) = {
        let a = i * 60deg
        place(center + horizon, rotate(a,
          polygon(fill: brand.transparentize(30%),
            (0pt, -34pt), (30pt, -12pt), (18pt, 30pt))))
      }
      #box(width: 2.6in, height: 2.6in)[
        #place(center + horizon, circle(radius: 1.15in, stroke: 3.5pt + brand))
        #place(center + horizon, box(width: 100pt, height: 100pt)[
          #blade(0) #blade(1) #blade(2) #blade(3) #blade(4) #blade(5)
        ])
      ]
    ]

    #place(bottom + left, dx: 0.75in, dy: -0.75in)[
      #line(length: 3.2in, stroke: 1pt + brand)
      #v(8pt)
      #text(font: display-font, size: 13pt, weight: "bold", fill: ink)[#author]
      #v(3pt)
      #text(font: display-font, size: 9.5pt, fill: muted)[#version]
    ]
  ]

  // ---- Front matter: roman numerals, no running heads, unnumbered heads ---
  set page(
    margin: (inside: 0.85in, outside: 0.7in, top: 0.75in, bottom: 0.75in),
    numbering: "i",
    footer: context align(center)[
      #text(font: display-font, size: 8.5pt, fill: muted)[#counter(page).display("i")]
    ],
    header: none,
  )
  counter(page).update(1)

  front

  // ---- Numbered body: arabic pages, running heads, chapter numerals -------
  pagebreak(weak: false, to: "odd")
  set heading(numbering: (..n) => if n.pos().len() == 1 { str(n.pos().at(0)) } else { none })
  counter(heading).update(0)
  counter(page).update(1)
  set page(
    numbering: "1",
    header: context {
      let pg = here().page()
      if query(heading.where(level: 1)).any(h => h.location().page() == pg) { return }
      let p = counter(page).get().at(0)
      set text(font: display-font, size: 8pt, fill: muted)
      let part-name = cur-part.get()
      let chap-name = cur-chapter.get()
      if calc.even(p) {
        grid(columns: (1fr, 1fr),
          align(left)[#upper[scalacv]],
          align(right)[#if part-name != none { part-name }])
      } else {
        grid(columns: (1fr, 1fr),
          align(left)[#if part-name != none { part-name }],
          align(right)[#if chap-name != none { emph(chap-name) }])
      }
      v(2pt)
      line(length: 100%, stroke: 0.4pt + rule-grey)
    },
    footer: context align(center)[
      #text(font: display-font, size: 8.5pt, fill: muted)[#counter(page).display("1")]
    ],
  )

  body
}
