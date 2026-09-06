#!/usr/bin/env bash
# Build the book. Requires typst >= 0.15 on PATH (or in ~/.local/bin).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
typst="${TYPST:-$(command -v typst || echo "$HOME/.local/bin/typst")}"
"$typst" compile "$here/book.typ" "$here/scalacv-book.pdf" "$@"
echo "wrote $here/scalacv-book.pdf"
