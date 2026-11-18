#!/bin/sh
# Runs the spikes that need a real IDE (S1, S2, S5, and part of S6).
#
# It compiles the plugin and the spike plugin with the Kotlin compiler that the
# IDE bundles, puts both into a sandbox of their own, and starts the IDE on a
# small made-up project. The spike plugin writes what it finds to a report and
# closes the IDE. A second start checks that a panel tab was restored.
#
# The sandbox has its own settings, caches, plugins, and logs, so the IDE's
# real ones are left alone. Gradle is not needed.
#
# `show` starts the IDE with the plugin, opens the Entire tool window, and
# leaves the IDE running for you to look at. `inspect` does the same, writes
# down what the Checkpoints tab shows, and closes the IDE. Both take a project
# to open. Give them a copy of a repository, because the IDE writes an .idea
# folder into the project it opens.
#
# Usage: spikes/ide/run.sh <sandbox-directory> [build|run|show|inspect|all] [path-to-IDE.app] [project]
set -eu

sandbox=${1:?"usage: run.sh <sandbox-directory> [build|run|show|inspect|all] [path-to-IDE.app] [project]"}
step=${2:-all}
app=${3:-"/Applications/IntelliJ IDEA.app"}

repo=$(cd "$(dirname "$0")/../.." && pwd)
ide="$app/Contents"
kotlinc="$ide/plugins/Kotlin/kotlinc/bin/kotlinc"
out="$sandbox/out"
report="$sandbox/report.txt"
project=${4:-"$sandbox/project"}

JAVA_HOME="$ide/jbr/Contents/Home"
export JAVA_HOME

platform_classpath() {
  # The platform, and the bundled plugins the two plugins depend on: the
  # embedded browser, version control, and Git.
  ls "$ide"/lib/*.jar "$ide"/plugins/jcef-plugin/lib/modules/*.jar \
    "$ide"/plugins/platform-vcs-plugin/lib/*.jar "$ide"/plugins/platform-vcs-plugin/lib/modules/*.jar \
    "$ide"/plugins/vcs-git/lib/*.jar "$ide"/plugins/vcs-git/lib/modules/*.jar | tr '\n' ':'
}

compile() {
  # compile <module-name> <jvm-target> <output-directory> <classpath> <source-directory>...
  # Each module has a name of its own. Kotlin lists a module's top-level
  # functions in a file named after it, and modules that share a name and an
  # output directory overwrite each other's list.
  module=$1
  jvm=$2
  target=$3
  classpath=$4
  shift 4
  mkdir -p "$target"
  "$kotlinc" "$@" -d "$target" -module-name "$module" -jvm-target "$jvm" -no-stdlib -no-reflect -nowarn -cp "$classpath"
}

build() {
  if [ ! -f "$repo/webview/dist/panel.js" ]; then
    (cd "$repo/webview" && npm run bundle)
  fi
  rm -rf "$out" "$sandbox/p"
  mkdir -p "$out" "$sandbox/p/entire-intellij/lib" "$sandbox/p/entire-spikes/lib"
  cp=$(platform_classpath)

  # The plugin is built for Java 21, the runtime of the oldest IDE it supports.
  echo "Compiling the plugin against $(basename "$app")"
  compile entire-core 21 "$out/entire" "$cp" "$repo/core/src/main/kotlin"
  compile entire-app 21 "$out/entire" "$out/entire:$cp" "$repo/app/src/main/kotlin"
  compile entire-plugin 21 "$out/entire" "$out/entire:$cp" "$repo/plugin/src/main/kotlin"
  cp -R "$repo/plugin/src/main/resources/." "$out/entire/"
  mkdir -p "$out/entire/webview"
  cp "$repo"/webview/dist/* "$out/entire/webview/"
  # Gradle writes the version and the build range into plugin.xml. This does the same by hand.
  sed 's|<name>Entire</name>|<name>Entire</name><version>0.1.0</version><idea-version since-build="253.29346" until-build="263.*"/>|' \
    "$repo/plugin/src/main/resources/META-INF/plugin.xml" > "$out/entire/META-INF/plugin.xml"
  (cd "$out/entire" && zip -qr "$sandbox/p/entire-intellij/lib/entire-intellij.jar" .)

  # The spike runs only in this IDE and uses its inline functions, so it is
  # built for this IDE's runtime. Kotlin refuses to inline Java 25 bytecode
  # from the platform into Java 21 bytecode.
  echo "Compiling the spike plugin"
  runtime=$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
  compile entire-spikes "$runtime" "$out/spikes" "$out/entire:$cp" "$repo/spikes/ide/src"
  cp -R "$repo/spikes/ide/resources/." "$out/spikes/"
  mkdir -p "$out/spikes/webview"
  cp "$repo"/webview/dist/* "$out/spikes/webview/"
  (cd "$out/spikes" && zip -qr "$sandbox/p/entire-spikes/lib/entire-spikes.jar" .)
  echo "Built both plugins into $sandbox/p"
}

make_project() {
  # Only the made-up project is made here. A project given on the command line is opened as it is.
  [ "$project" = "$sandbox/project" ] || return 0
  [ -d "$project/.git" ] && return 0
  mkdir -p "$project"
  printf '# Spike Project\n\nA made-up project for the Entire spikes.\n' > "$project/README.md"
  (
    cd "$project"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 git init -q -b main
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 git add README.md
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GIT_AUTHOR_NAME=Spike GIT_AUTHOR_EMAIL=spike@example.com \
      GIT_COMMITTER_NAME=Spike GIT_COMMITTER_EMAIL=spike@example.com git commit -q -m "Start the spike project"
  )
}

write_options() {
  # write_options <file> <extra-option>...
  file=$1
  shift
  cat > "$file" <<EOF
-Xmx2g
-Didea.config.path=$sandbox/c
-Didea.system.path=$sandbox/s
-Didea.plugins.path=$sandbox/p
-Didea.log.path=$sandbox/l
-Didea.trust.all.projects=true
-Didea.initially.ask.config=never
-Djb.consents.confirmation.enabled=false
-Djb.privacy.policy.text=<!--999.999-->
-Dide.show.tips.on.startup.default.value=false
-Dnosplash=true
EOF
  for option in "$@"; do
    echo "$option" >> "$file"
  done
}

stop_running() {
  # A second start with the same settings folder would only hand the project to the IDE already running.
  # The IDE writes its process ID into its system folder. Its options are not on its command line.
  [ -f "$sandbox/s/.pid" ] || return 0
  running=$(cat "$sandbox/s/.pid" 2>/dev/null || true)
  case "$running" in
    '' | *[!0-9]*) return 0 ;;
  esac
  kill -0 "$running" 2>/dev/null || return 0
  ps -o command= -p "$running" | grep -q "/MacOS/idea" || return 0
  echo "Closing the IDE that was left running in this sandbox."
  kill "$running" 2>/dev/null || true
  waited=0
  while kill -0 "$running" 2>/dev/null && [ "$waited" -lt 40 ]; do
    sleep 1
    waited=$((waited + 1))
  done
}

show() {
  # Starts the IDE with the plugin, opens the Entire tool window, and leaves the IDE running.
  mkdir -p "$sandbox/c" "$sandbox/s" "$sandbox/l"
  make_project
  stop_running
  picture="$sandbox/entire.png"
  seen="$sandbox/show.txt"
  rm -f "$picture" "$seen"
  options="$sandbox/idea-show.vmoptions"
  write_options "$options" "-Dentire.spike.inspect=$seen" "-Dentire.spike.screenshot=$picture" ${ENTIRE_SPIKE_ONLY:+"-Dentire.spike.only=$ENTIRE_SPIKE_ONLY"}
  # `open` hands the IDE to the system, so it keeps running after this script ends.
  open -n -a "$app" --env "IDEA_VM_OPTIONS=$options" --args "$project"
  waited=0
  while [ ! -s "$picture" ] && [ "$waited" -lt 240 ]; do
    sleep 2
    waited=$((waited + 2))
  done
  cat "$seen" 2>/dev/null || true
  if [ -s "$picture" ]; then
    echo "The IDE is running with the plugin. It painted its window into $picture"
  else
    echo "The IDE did not write $picture within 240 s. See $sandbox/l/idea.log"
    return 1
  fi
}

inspect() {
  # Starts the IDE on a project, writes down what the Checkpoints tab shows, and closes the IDE.
  mkdir -p "$sandbox/c" "$sandbox/s" "$sandbox/l"
  make_project
  stop_running
  seen="$sandbox/inspect.txt"
  rm -f "$seen"
  options="$sandbox/idea-inspect.vmoptions"
  # ENTIRE_SPIKE_ONLY=settings runs the checks of the settings page and skips the rest.
  write_options "$options" "-Dentire.spike.inspect=$seen" "-Dentire.spike.exit=true" ${ENTIRE_SPIKE_ONLY:+"-Dentire.spike.only=$ENTIRE_SPIKE_ONLY"}
  IDEA_VM_OPTIONS="$options" "$ide/MacOS/idea" "$project" > "$sandbox/ide-inspect.out" 2>&1 &
  pid=$!
  waited=0
  while [ "$waited" -lt 240 ]; do
    if grep -q "^INFO done: inspect" "$seen" 2>/dev/null; then break; fi
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 2
    waited=$((waited + 2))
  done
  closing=0
  while kill -0 "$pid" 2>/dev/null && [ "$closing" -lt 40 ]; do
    sleep 1
    closing=$((closing + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
  fi
  echo "Project: $project"
  cat "$seen" 2>/dev/null || echo "The IDE wrote no report. See $sandbox/l/idea.log"
}

start_ide() {
  # start_ide <phase> <seconds-to-wait>
  phase=$1
  limit=$2
  options="$sandbox/idea-$phase.vmoptions"
  write_options "$options" "-Dentire.spike.out=$report" "-Dentire.spike.phase=$phase"
  echo "Starting the IDE, phase $phase"
  IDEA_VM_OPTIONS="$options" "$ide/MacOS/idea" "$project" > "$sandbox/ide-$phase.out" 2>&1 &
  pid=$!
  waited=0
  while [ "$waited" -lt "$limit" ]; do
    if grep -q "^INFO done: phase $phase" "$report" 2>/dev/null; then break; fi
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 2
    waited=$((waited + 2))
  done
  # The spike closes the IDE itself. Give it time to save the project, then insist.
  closing=0
  while kill -0 "$pid" 2>/dev/null && [ "$closing" -lt 40 ]; do
    sleep 1
    closing=$((closing + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "The IDE was still running after phase $phase, so it was stopped."
    kill "$pid" 2>/dev/null || true
    sleep 3
    kill -9 "$pid" 2>/dev/null || true
  fi
  if ! grep -q "^INFO done: phase $phase" "$report" 2>/dev/null; then
    echo "Phase $phase did not finish. See $sandbox/l/idea.log and $sandbox/ide-$phase.out"
    return 1
  fi
}

run() {
  mkdir -p "$sandbox/c" "$sandbox/s" "$sandbox/l"
  rm -f "$report"
  make_project
  start_ide 1 300
  start_ide 2 180
  echo
  echo "Report:"
  cat "$report"
}

case "$step" in
  build) build ;;
  run) run ;;
  show) build && show ;;
  inspect) inspect ;;
  all) build && run ;;
  *) echo "unknown step: $step" >&2; exit 2 ;;
esac
