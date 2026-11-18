#!/bin/sh
# Shows how the files copied from entire-vscode differ from a checkout of it.
# Prints nothing and exits 0 when they are the same.
#
# Usage: scripts/upstream-diff.sh [path-to-entire-vscode]
set -eu

upstream=${1:-../entire-vscode}
here=$(cd "$(dirname "$0")/.." && pwd)

if [ ! -f "$upstream/src/webview/main.ts" ]; then
  echo "No entire-vscode checkout at $upstream" >&2
  exit 2
fi

status=0
while read -r file; do
  if ! diff -u "$upstream/$file" "$here/webview/$file"; then
    status=1
  fi
done <<EOF
src/webview/app.ts
src/webview/css.d.ts
src/webview/dom.ts
src/webview/main.ts
src/webview/markdown.ts
src/webview/styles.css
src/webview/trail.ts
src/shared/protocol.ts
src/core/format.ts
src/core/schemas.ts
src/core/trails.ts
test/webview/app.test.ts
test/webview/dom.test.ts
test/webview/markdown.test.ts
test/webview/trail.test.ts
EOF
exit $status
