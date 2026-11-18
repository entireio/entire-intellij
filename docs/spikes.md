# M0 Spikes

Each spike answers one question the design depends on. A spike is done when it has a result on each operating system and IDE version the question names.

| Spike | Question | Status |
| --- | --- | --- |
| S1 | Do the inline shim, CSSOM theming, an 8 MB image, and IDE shortcuts work in JCEF on 2025.3.1 and 2026.3? | Go on 2026.3, macOS. Shortcuts and 2025.3.1 open. |
| S2 | Does a slot file restore after restart, retarget in place, open in two splits, and stay separate across two projects? | Go on 2026.3, macOS. Two projects and 2025.3.1 open. |
| S3 | What is `WatchService` latency, including worktrees? | Go on macOS with JetBrains Runtime. Linux and Windows open. |
| S4 | Does a `ProcessHandle.descendants()` snapshot stop the real `entire brain viz` tree? | Go on macOS. Linux, Windows, and IDE exit open. |
| S5 | What does the IDE show for a 178 MB transcript file? | Answered on 2026.3: its large-file editor opens it. |
| S6 | Do the optional JCEF dependency and a bundled `kotlinx-serialization-json` load, and does the verifier pass on four IDEs? | Optional dependency loads on 2026.3. A bundled copy, 2025.3.1, and the verifier open. |

## Running Them

S3 and S4 are single Java files:

```sh
java spikes/S3WatchLatency.java <empty-directory> 8
java spikes/S4ProcessTree.java [path-to-entire repository-root]
```

To run them on the runtime the IDE uses, replace `java` with `/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/java`.

S1, S2, S5, and the platform checks run inside a real IDE:

```sh
spikes/ide/run.sh <sandbox-directory>
```

The script compiles the plugin and a spike plugin with the Kotlin compiler the IDE bundles, starts the IDE twice on a made-up project, and prints a report. The sandbox has its own settings, caches, plugins, and logs. Gradle is not needed.

## What the IDE Changed in the Plan

These came from compiling against and running in IntelliJ IDEA 2026.3 (build 263.7307.200, JetBrains Runtime 25.0.4.1, macOS aarch64).

- **The plugin has to name `com.intellij.modules.vcs`.** `GitRepositoryManager` extends a class in `com.intellij.dvcs`, and in 2026.3 that package is in the bundled plugin Version Control (`com.intellij.platform.vcs`), which carries the alias. Without those jars the spike did not compile. `plugin.xml` now declares the dependency.
- **Compile against 2025.3.1, as planned, and not against a later IDE.** The 2026.3 platform is Java 25 bytecode. Kotlin refused to inline its functions into Java 21 bytecode: "cannot inline bytecode built with JVM target 25 into bytecode that is being built with JVM target 21". The plugin's own Java 21 classes load and run on Java 25.
- **A large message needs no splitting.** An 8 MB payload crossed in one `executeJavaScript` call in 215 ms. `rxChunk` stays in the shim, and the plugin can start without it.
- **A large transcript needs no fallback in IntelliJ IDEA.** See S5.
- **`kotlinx-serialization-json` can come from the IDE.** 2026.3 bundles version 1.11.0, and a plugin that carries no copy can call it. 2025.3.1 has it on its core class path too, so the modules use `compileOnly` and the plugin ships no copy of it.
- **JetBrains Runtime watches files natively on macOS.** See S3.
- **`BaseTreeModel` is `com.intellij.ui.tree.BaseTreeModel`.** The other classes the design names exist under the names it uses: `JBCefJSQuery`, `UiNotifyConnector`, `SimpleDiffRequestChain.fromProducers`, `GitExecutableManager.getPathToGit(Project)`, `TrustedProjects.isProjectTrusted(Project)`, and `EnvironmentUtil`.

## Platform Checks

IntelliJ IDEA 2026.3, both starts, no `ERROR` line in the IDE log:

| Check | Result |
| --- | --- |
| The plugin loads | Entire 0.1.0, enabled |
| Tool window | Registered as `Entire` on the left with its icon. Shown, it has one tab that reads "Loading…" |
| Embedded browser | Plugin Web Browser (JCEF), ID `com.intellij.modules.jcef`, Chromium 150, out-of-process mode |
| Optional descriptor | Loaded. The `config-file` of an optional `com.intellij.modules.jcef` dependency takes effect |
| Git | Git4Idea found the project's repository and its branch. The git path came from `GitExecutableManager` |
| Bundled Kotlin | Standard library 2.4.21, coroutines 1.11.0-intellij-1, serialization 1.11.0 |

## S1. The Panel Script in JCEF

The spike built the document the plan describes: `panel.css`, a one-line send function, `shim.js`, and `panel.js`, all inline under one nonce, with the policy `default-src 'none'; img-src data: blob:; style-src 'nonce-N'; script-src 'nonce-N'; base-uri 'none'; form-action 'none'`. It loaded the document with `loadHTML` in a tool window.

| Check | Result |
| --- | --- |
| The panel script starts on the shim | `{"type":"ready","protocol":1}` arrived 261 ms after `loadHTML`, for a document of 159,853 characters |
| Inline stylesheet under the policy | Applied: `#app` has `max-width: 920px` |
| IDE to page | An `error` message sent through `__entire.rx` was drawn |
| Page to IDE | A click on Retry arrived as `{"type":"retry"}` |
| Transcript | `init` and `turns` drew the prompt, and the assistant's Markdown became `<strong>` |
| Theme through CSSOM | `__entire.theme` changed the computed body color |
| The policy blocks the network | `fetch('https://example.com/')` failed with "Failed to fetch" |
| 8 MB in one call | Acknowledged after 215 ms |
| 8 MB in 17 parts of 512 KB | Acknowledged after 96 ms |
| Navigation | `onBeforeBrowse` was asked about the new address and cancelled it. The page stayed |

What this settles: on 2026.3 the copied panel script runs unchanged, in out-of-process mode, with nothing loaded by URL.

What it leaves open: IDE shortcuts while the page has focus, which need a person at the keyboard. Also 2025.3.1, Linux, and Windows.

## S2. Panels as Restorable Editor Tabs

The spike registered a file system under `com.intellij.virtualFileSystem` and a `FileEditorProvider`. A panel file is a slot with the path `/<project location hash>/detail/preview`. What the slot shows is kept beside it.

| Check | Result |
| --- | --- |
| The slot resolves by URL | `entire-spike-panel:///5e3baa0e/detail/preview` |
| It opens as the panel editor | One editor, from the provider |
| It retargets in place | The tab title changed, and the provider had still created one editor |
| Each lookup returns the same file object | Yes |
| Two splits | Two editors for the one file |
| Restart | The tab was open again after the second start, and the target saved beside the slot was the second one |

During the second start the IDE asked the file system for the slot three times. Each time the project was already in `ProjectManager.openProjects`. The file system can therefore find the project by the hash in the path and read the target from that project's workspace state, as the plan proposes.

What it leaves open: two projects open at once, and 2025.3.1.

## S3. File Watch Latency

The program builds a repository with a linked worktree, makes each change with the real `git`, and times the event from the moment `git` exits. Both runs are on macOS aarch64, 8 rounds for each case.

| Change | Watched as | JetBrains Runtime 25.0.4.1 (median, max) | Temurin 25.0.4 (median, max) |
| --- | --- | --- | --- |
| Commit | `<gitDir>/logs/HEAD` | 328 ms, 496 ms | 1,826 ms, 1,996 ms |
| Branch switch | `<gitDir>/HEAD` | 336 ms, 487 ms | 1,832 ms, 1,985 ms |
| Commit in a worktree | `<gitDir>/worktrees/<name>/logs/HEAD` | 324 ms, 477 ms | 1,824 ms, 1,969 ms |
| Branch switch in a worktree | `<gitDir>/worktrees/<name>/HEAD` | 338 ms, 486 ms | 1,836 ms, 1,982 ms |
| Session state write | `<commonDir>/entire-sessions/*.json` | 363 ms, 722 ms | 1,858 ms, 1,999 ms |

Both runtimes reported all 40 changes. They differ in how: JetBrains Runtime uses `sun.nio.fs.MacOSXWatchService`, and Temurin uses `sun.nio.fs.PollingWatchService`, which asks every two seconds.

What this settles: in the IDE on macOS, a commit reaches the plugin in about a third of a second, before the 500 ms debounce. The three directories of `refresh.ts` are enough, linked worktrees included. The plugin's own tests run on Temurin in CI, so a test that waits for a watch event has to allow two seconds.

What it leaves open: Linux and Windows.

## S4. Stopping a Process Tree

The method: take a snapshot of the process and its descendants, ask each one to stop, wait up to 2 seconds, and kill what is left. JetBrains Runtime and Temurin gave the same result on macOS. These numbers are from JetBrains Runtime 25.0.4.1:

| Case | Processes | Time to stop | Killed after the wait | Left running |
| --- | --- | --- | --- | --- |
| Parent with two children | 3 | 25 ms | 0 | None |
| Grandchild | 3 | 25 ms | 0 | None |
| Tree that ignores the polite signal | 2 | 2,038 ms | 2 | None |
| Parent exited first, child not remembered | 1 | 0 ms | 0 | The child |
| Parent exited first, child remembered | 3 | 25 ms | 0 | None |
| `entire brain viz --no-open --port 0` | 2 | 25 ms | 0 | None |

The real server ran in a repository with a built brain. Its tree was the `entire` binary and its child `entire-brain`. Both stopped on the polite signal, and the port stopped accepting connections.

What this settles: on macOS the snapshot method stops the brain graph server without a process group.

What it adds to the design: a child whose parent has exited is no longer a descendant, so a snapshot taken at stop time misses it. `ProcessTree` has to remember the descendants it saw when the server reported its address, and signal those together with the ones it finds at stop time. A `ProcessHandle` compares by PID and start time, so a remembered handle does not match a later process that reuses the PID.

What it leaves open: Linux, Windows (where `destroy()` kills at once), and a stop that runs while the IDE exits.

## S5. A 178 MB Transcript

The spike wrote a read-only file of 186,647,340 bytes and opened it with `FileEditorManager.openFile`.

The IDE opened it in 69 ms with `LargeFileEditorImpl`, its large-file editor. That editor is not a text editor and holds no document, so the file does not go through memory. The IDE reported the file as not writable.

What this settles: in IntelliJ IDEA 2026.3 the plugin can open a raw transcript the way the VS Code extension does, with no notification to fall back on.

What it leaves open: an IDE that does not bundle the large-file editor.

## S6. Packaging

Done on 2026.3: the plugin loads with an optional dependency on `com.intellij.modules.jcef`, classes built for Java 21 run on the IDE's Java 25, and the IDE's own `kotlinx-serialization-json` answers a plugin that carries none.

Open: a plugin that carries its own copy of the library, the same checks on 2025.3.1, and the Plugin Verifier on four IDE versions. The verifier and 2025.3.1 need the Gradle build.
