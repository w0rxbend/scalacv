#!/usr/bin/env bash
# Compile ONE chapter standalone, to catch Typst syntax errors without waiting
# for the whole book. Usage: ./check-chapter.sh chapters/ch04-image.typ
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
typst="${TYPST:-$(command -v typst || echo "$HOME/.local/bin/typst")}"
target="$1"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cat > "$here/.check.typ" <<TYP
#import "lib/book.typ": *
#show: book.with(title: "check", subtitle: "check", author: "check", version: "check", front: [])
#part("Check")[Check.]
#include "$target"
TYP
"$typst" compile "$here/.check.typ" "$tmp/out.pdf" --root "$here"
rm -f "$here/.check.typ"
echo "OK: $target compiles"
