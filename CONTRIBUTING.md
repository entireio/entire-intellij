# Contributing

## Development

The build needs Node 22.18 or later on the `PATH`. Gradle downloads JDK 21 when the machine has none.

The repository holds the Gradle wrapper, so the build needs no Gradle of its own:

```sh
./gradlew check                 # compile every module, run the JVM tests, type-check and test the panel pages
./gradlew :plugin:runIde        # start an IDE with the plugin loaded
./gradlew :plugin:verifyPlugin  # check the plugin against each supported IDE version
./gradlew :plugin:buildPlugin   # write the plugin zip to plugin/build/distributions
```

The panel pages build and test without Gradle:

```sh
cd webview
npm ci
npm run check    # type-check, then run the tests
npm run bundle   # write panel.js, panel.css, shim.js, and theme-variables.json to dist/
```

## Layout

| Path | Contents |
| --- | --- |
| `core` | Git and Entire CLI logic. It has no IntelliJ Platform dependency, so its tests run on a plain JVM. |
| `app` | Repository state, row building, refresh, and the panel controllers. It reaches the IDE through the interfaces in its `ports` package. |
| `plugin` | Tool window, trees, editor tabs, actions, and settings. This is the only module that may import the IntelliJ Platform. |
| `testkit` | Fixture repositories and the runner for the fake Entire CLI, for the tests of the other modules. |
| `webview` | The detail panel and the trail panel, which run in the IDE's embedded browser. |
| `spikes` | Single-file programs that answer design questions. [docs/spikes.md](docs/spikes.md) has their results. |

`core` and `app` do not declare the IntelliJ Platform as a dependency, so an import of an IDE class fails to compile there. Do not add that dependency to make an import work. Add an interface to `app`'s `ports` package and implement it in `plugin`.

## The Panel Pages

`webview/src/webview`, `webview/src/shared`, `webview/src/core`, and `webview/test/webview` are copies of files in [entire-vscode](https://github.com/entireio/entire-vscode). [PORTING.md](PORTING.md) lists them. Do not edit a copy for this plugin's sake: put what the IDE needs in `webview/src/bridge`. The panel script asks for its host with `acquireVsCodeApi()`, and `bridge.ts` answers it.

The plugin writes `panel.css`, `shim.js`, and `panel.js` into one HTML document as inline elements. The build fails when one of them contains a closing `script` or `style` tag, because an inline element ends at the first one.

`dist/theme-variables.json` lists the `--vscode-*` variables that `styles.css` reads. The plugin has to supply a value for each one.

## Checking the Port Against entire-vscode

`scripts/parity.sh` runs the Kotlin port and the core of an entire-vscode checkout on the same real repositories and compares what they read, line by line: the repository's paths, the CLI version, the Entire status, every commit that carries a checkpoint with its agent and its place in its session, and the sessions. For a repository with an `entire://` origin it also compares Entire's lists of sessions and trails, up to three trails with their merge gates, CI runs, and timelines, and for any repository the report on its brain.

```sh
scripts/parity.sh <work-directory> ../entire-vscode ~/code/a-repository ~/code/another
```

It prints `same` or `DIFFERENT` for each repository. Run it after a change to `core`. It uses the Kotlin compiler and the libraries of an installed IntelliJ IDEA, so it needs no Gradle build. Set `ENTIRE_IDE` when the IDE is not at `/Applications/IntelliJ IDEA.app`.

The check covers what the repositories happen to contain. It does not replace the unit tests.

## Trying the Plugin in an IDE

Until the Gradle build runs, `spikes/ide/run.sh` is the way to see the plugin in an IDE. It compiles the plugin with the IDE's own Kotlin compiler and starts the IDE in a sandbox that has its own settings:

```sh
spikes/ide/run.sh <sandbox-directory> show "/Applications/IntelliJ IDEA.app" <copy-of-a-repository>
```

Give it a copy of a repository, such as a fresh clone, because the IDE writes an `.idea` folder into the project it opens. `inspect` in place of `show` writes down what the Checkpoints tab displays and closes the IDE. With `ENTIRE_SPIKE_ONLY` set to `settings`, `trails`, `brain`, `scroll`, `refresh`, `search`, `setup`, or `setup-run` in the environment, `inspect` checks that part and skips the rest. `setup` reads the Setup tab and runs no step. `setup-run` clicks Enable, Graph, and Brain (with backfill and the watcher off), so give it a fresh copy of a repository where Entire is not enabled, and set `ENTIRE_PLUGIN_DIR` to an empty folder so that the plugins install there. Set `ENTIRE_CONFIG_DIR` to an empty folder to see the plugin as a user who is not logged in. The refresh check makes an empty commit in the copy of the repository and takes it back. The brain check starts the real `entire brain viz` and stops it, and takes the address in place of the browser, so no browser opens.

## The Tool Window Icon

The IDE draws the button of an open tool window in white. It repaints the icon's light variant and replaces the colors of its own icon palette, `#6C707E` among them. It replaces them on the elements inside the `<svg>` element and not on that element itself. So each path in `plugin/src/main/resources/icons` carries its own `fill`, in `#6C707E` for the light variant and `#CED0D6` for the dark one. An icon with `fill` on the `<svg>` element, or in another color, stays dark gray on the selected button. The `inspect` step checks this as `inspect.icon.selected`.

## Spikes

A spike that needs no IDE is one Java file that runs without a build:

```sh
java spikes/S4ProcessTree.java
```

The spikes that need an IDE are a small plugin in `spikes/ide`. One script compiles it with the Kotlin compiler the IDE bundles, starts the IDE in a sandbox of its own, and prints a report:

```sh
spikes/ide/run.sh <sandbox-directory> all "/Applications/IntelliJ IDEA.app"
```

The script needs neither Gradle nor a Gradle wrapper, and it leaves the IDE's real settings alone. The spike plugin does nothing unless the IDE starts with `-Dentire.spike.out`, which the script sets.

Record the result in [docs/spikes.md](docs/spikes.md), with the operating system, the IDE version, and the Java runtime it ran on.
