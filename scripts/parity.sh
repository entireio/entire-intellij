#!/bin/sh
# Checks that the Kotlin port reads a real repository the way entire-vscode does.
#
# It runs two programs on each repository and compares what they print, line
# by line: scripts/parity/reference.ts, which uses the core of an
# entire-vscode checkout, and core/src/smoke, which uses the Kotlin port. Both
# print the repository's paths, the CLI version, the Entire status, every
# commit that carries a checkpoint with its agent and its place in its
# session, and the sessions.
#
# Both programs run `entire checkpoint explain --json`, as the extension does.
# The CLI may fetch checkpoint data from the checkpoint remote while it lists.
#
# It needs Node, the npm dependencies of webview/ (for esbuild), and an
# IntelliJ IDEA, whose Kotlin compiler and libraries it uses in place of a
# Gradle build.
#
# Usage: scripts/parity.sh <work-directory> <path-to-entire-vscode> <repository>...
set -eu

work=${1:?"usage: parity.sh <work-directory> <path-to-entire-vscode> <repository>..."}
upstream=${2:?"usage: parity.sh <work-directory> <path-to-entire-vscode> <repository>..."}
shift 2
app=${ENTIRE_IDE:-"/Applications/IntelliJ IDEA.app"}
ide="$app/Contents"
here=$(cd "$(dirname "$0")/.." && pwd)

if [ ! -f "$upstream/src/core/checkpointIndex.ts" ]; then
  echo "No entire-vscode checkout at $upstream" >&2
  exit 2
fi

mkdir -p "$work"
"$here/webview/node_modules/esbuild/bin/esbuild" "$here/scripts/parity/reference.ts" --bundle --platform=node --format=esm \
  --log-level=warning "--alias:@upstream=$upstream/src/core" "--outfile=$work/reference.mjs"

libraries="$ide/lib/util-8.jar:$ide/lib/intellij.libraries.kotlinx.coroutines.core.jar:$ide/lib/intellij.libraries.kotlinx.serialization.core.jar:$ide/lib/intellij.libraries.kotlinx.serialization.json.jar"
rm -rf "$work/classes"
mkdir -p "$work/classes"
JAVA_HOME="$ide/jbr/Contents/Home" "$ide/plugins/Kotlin/kotlinc/bin/kotlinc" "$here/core/src/main/kotlin" "$here/core/src/smoke/kotlin" \
  -d "$work/classes" -jvm-target 21 -api-version 2.2 -language-version 2.2 -no-stdlib -no-reflect -nowarn -cp "$libraries"

# Both sides get the same clock, so "5m ago" reads the same in each.
now=$(($(date +%s) * 1000))
status=0
for repository in "$@"; do
  name=$(basename "$repository")
  node "$work/reference.mjs" "$repository" "$now" > "$work/$name.reference.txt" 2> "$work/$name.reference.err" || true
  "$ide/jbr/Contents/Home/bin/java" -cp "$work/classes:$libraries" io.entire.core.smoke.SmokeKt "$repository" "$now" \
    > "$work/$name.kotlin.txt" 2> "$work/$name.kotlin.err" || true
  lines=$(wc -l < "$work/$name.kotlin.txt" | tr -d ' ')
  if diff "$work/$name.reference.txt" "$work/$name.kotlin.txt" > "$work/$name.diff"; then
    echo "same      $name ($lines lines)"
  else
    echo "DIFFERENT $name (see $work/$name.diff)"
    status=1
  fi
done
exit $status
