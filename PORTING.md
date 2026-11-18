# Porting From entire-vscode

This plugin is a port of [entire-vscode](https://github.com/entireio/entire-vscode) at commit `8440828218f2ae3de22fafcd64d983de025341e0`. This file says which upstream file each part comes from, how far each port has come, and where the port departs from upstream.

## Copied Files

`webview/` holds 15 files copied from upstream without a change. Keep them that way, so that a later change upstream can be copied over them. What the IDE needs on top of them lives in `webview/src/bridge`.

| Upstream | Here |
| --- | --- |
| `src/webview/*` (7 files) | `webview/src/webview/` |
| `src/shared/protocol.ts` | `webview/src/shared/` |
| `src/core/format.ts`, `schemas.ts`, `trails.ts` | `webview/src/core/` |
| `test/webview/app.test.ts`, `dom.test.ts`, `markdown.test.ts`, `trail.test.ts` | `webview/test/webview/` |

`scripts/upstream-diff.sh [path-to-entire-vscode]` prints how the copies differ from a checkout, and prints nothing when they are the same.

`test/webview/brainPage.test.ts` is not copied. It tests the Brain view's HTML page, which this plugin replaces with a Swing form.

## Where the Port Departs

- `Result` is `CallResult` here, because Kotlin has a `Result` of its own.
- The failure kind `aborted` has no counterpart, and `ExecOptions` has no `signal`. A cancelled call throws `CancellationException`, as coroutines do, and the process runner stops the process when its caller is cancelled.
- A run in a `Lane` that has a key belongs to the lane. A caller that is cancelled stops waiting for it, and the run goes on for the other callers. Upstream, a shared run carries the abort signal of its first caller.
- The process runner cannot start a process in a group of its own, so it stops a process tree by finding it. [docs/spikes.md](docs/spikes.md) has the measurements under S4.
- `EntireCli` refuses arguments that are not one of the read commands, before a process starts.
- A count is a whole number or absent: `CheckpointRow.sessionCount`, and `turns` and `pendingTurns` of a session row. Upstream keeps a count such as 2.5, which the CLI does not print.
- A tool call's input is kept as parsed JSON. `json/JsJson.kt` writes it out the way `JSON.stringify` does: the same key order, and numbers written as JavaScript writes them.
- `openExternal` requires `http://` or `https://` followed by a host. Upstream also accepts forms that a browser would repair, such as `https:example.com`.
- A session's checkpoints that only Entire lists are read when the session's row is opened. Until then a session with none in the clone shows one row that reads "Loading…". The sessions of a checkpoint that holds several show the same row until the CLI has listed them. When either read fails, the row gives the reason, and closing the row and opening it again repeats the read.
- A row that is open when a refresh drops what it held asks for it again. Upstream gets this from VS Code, which asks an open row for its children each time the tree redraws.
- A tab waits up to five seconds for a row that a detail tab asked it to select, because the rows that hold it can arrive after the request. Upstream finds the row before it asks, so it does not wait.
- Set Entire CLI path… opens the settings page, where upstream opens a file picker and writes the setting. The page warns about a path with no file that can be run, and refuses a web address that is not an http(s) URL. Upstream stores either value as typed.
- `TrailDetail` holds a `TrailSummary` and the two fields only `entire trail show` reports, where upstream extends the summary's type.
- A trail number, a change number, and each count the CLI prints about a trail or a brain is a whole number or absent.
- The four views are tabs on a row of their own under the tool window's title bar. A tool window at the side of the IDE is narrow, and tabs in its title bar share that width with its name and buttons, so one of the four went into a menu. Refresh, Open Repository on entire.com, and the settings button are in the title bar, and serve every tab.
- The Trails tab shows its filter on a toolbar button that opens the list of filters. Upstream names it beside the view's title and asks in a quick pick. The tab has no progress bar while a page of trails is read.
- A trail panel that finds trails turned off names the setting as it reads on the settings page.
- A trail or a timeline that a panel reads is the panel's own read: it stops when the panel moves on. Upstream gives the same reads an abort signal.
- Show Log opens the IDE's log, which holds the plugin's lines among the IDE's. Upstream has a log of its own.
- Stopping a brain graph server sends the request to every process under it at once, and a daemon thread kills what is left after two seconds. The request has gone out before a closing project returns.
- The hover text of a row is built with the row, and not when the row is hovered: a row here is plain data and holds nothing to build it from later. It is plain text with a bold title.
- A list loads on scroll when its Load More row is among the rows in view, which the tab works out from its scroll position. Upstream learns it from the editor asking how to decorate the row.
- A folder that does not exist yet, such as `entire-sessions` before the first session, is watched for from its parent, and its appearance counts as a change.
- The IDE's own notice that a repository changed, and the IDE coming to the front, both ask for the same refresh the watchers ask for. Upstream has the second and not the first.
- Search has no upstream counterpart. A search field filters the loaded rows of each tab, and Enter runs `entire search --json` for the Checkpoints and Sessions tabs (`search/SearchDirectory.kt`, `rows/SearchRows.kt`, `rows/RowFilter.kt`). Under a filter, lists do not load on scroll.
- The Setup tab has no upstream counterpart. Upstream's empty states copy a command. Here they open a step of the Setup tab, which runs the command on a click (`setup/Setup.kt`, `core/setup/SetupCommands.kt`, `toolwindow/SetupPanel.kt`). Its commands write, and go through `isSetupCommand`, an allowlist of their own. `EntireCli` gains four reads: `search`, `auth status --json`, `graph version --json`, and `agent list`.
- Messages say "this plugin" where upstream says "this extension".
- The text follows the [IntelliJ UI guidelines](https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html) where they differ from upstream's: sentence case for row labels and links, an ellipsis on a link that opens a dialog or a popup, straight single quotes, and no second person. An empty state offers at most two actions and links to a page of Entire's documentation, where upstream also offers Retry. An error notification offers Show log, and a page that failed to load offers Retry.
- The Entire tool window has a button only in a project whose folder is in a Git repository, or that maps one as a VCS root. Upstream's views show in every workspace, and say when a folder is not a repository.
- `src/core/brainPage.ts` is not ported.

## Kotlin Modules

"Checked" means `scripts/parity.sh` printed the same lines for this port and for upstream on five real repositories. That check covers what a repository happens to contain. It does not replace the unit tests, which are ported for `errors.ts` only.

| Upstream | Here | Status |
| --- | --- | --- |
| `src/core/errors.ts` | `:core`, `Errors.kt` | Ported with its tests |
| `src/core/exec.ts` | `:core`, `exec/Exec.kt` | Ported. Checked |
| `src/core/scheduler.ts` | `:core`, `sched/Lane.kt` | Ported. Checked |
| `src/core/trailers.ts` | `:core`, `git/Trailers.kt` | Ported. Checked |
| `src/core/git.ts` | `:core`, `git/Git.kt` | Ported. `repoPaths`, `head`, `branch`, and `logWindow` checked |
| `src/core/cliLocator.ts` | `:core`, `cli/CliLocator.kt` | Ported. The search of `PATH` and the version checked |
| `src/core/schemas.ts` | `:core`, `cli/Schemas.kt` | `readStatus`, `readCheckpointRows`, `readCheckpointDetail`, and `readSessionRows` ported and checked, the last on made-up rows. The trail readers are in `cli/TrailSchemas.kt`, checked against Entire's trails of one repository |
| `src/core/entireCli.ts` | `:core`, `cli/EntireCli.kt` | `version`, `status`, `listCheckpoints`, `explainCheckpoint`, `fullText`, `checkpointAuthor`, `remoteSessions`, and `remoteSessionCheckpoints` ported and checked. `rawTranscript`, `sessionList`, and `sessionRawTranscript` ported. `listTrails`, `showTrail`, `trailTimeline`, and `brainStatus` ported and checked. `startBrainViz` ported, and run from the IDE |
| `src/core/remoteSessions.ts` | `:core`, `cli/RemoteSessions.kt` | Ported. Checked against Entire's lists for two repositories |
| `src/core/checkpointIndex.ts` | `:core`, `checkpoint/CheckpointIndex.kt` | Ported. Checked |
| `src/core/format.ts` | `:core`, `format/Format.kt` | Ported. `relativeTime` and `formatPosition` checked |
| `scripts/smoke.ts` | `:core`, `src/smoke` | A smaller program with the same purpose |
| `src/core/checkpointStore.ts` | `:core`, `checkpoint/CheckpointStore.kt` | Ported. `locate`, `sessions`, `sessionMeta`, `compactTranscript`, and `openingPrompt` checked on both storage layouts. `asset` not checked |
| `src/core/transcript/normalize.ts`, `model.ts`, `prompts.ts`, `cliText.ts` | `:core`, package `transcript` | Ported. Checked on real transcripts: turn counts, the start turn, every wire turn, and every tool body |
| `src/core/webLinks.ts` | `:core`, `web/WebLinks.kt` | Ported. Not checked |
| `src/shared/protocol.ts` | `:core`, package `protocol` | The detail panel's messages are in `Detail.kt`, and the trail panel's in `Trail.kt` |
| `src/core/trails.ts`, `trailMemory.ts` | `:core`, package `trails` | Ported. The filter, the labels, the outcomes, and the choice of a trail's change checked against Entire's trails of one repository |
| `src/core/brain.ts` | `:core`, `brain/Brain.kt` | Ported. Checked against the report on one repository's brain |
| `src/core/serverProcess.ts` | `:core`, `exec/ServerProcess.kt` | Ported, on a snapshot of the process tree in place of a process group. Run from the IDE with the real `entire brain viz` |
| `src/ext/repoManager.ts` | `:app`, `repo/RepoManager.kt`, `repo/SessionDirectory.kt` | `reload`, `showMore`, `loadStatus`, `loadGit`, `scanForCommits`, `enrich`, and `detail` with its cache ported. The sessions ported: the merged list, Entire's list and its paging, a session's checkpoints, and names from opening prompts. The trails are in `repo/TrailDirectory.kt`: the list, its filter and paging, one trail, and a trail's web page. `refreshGit`, `refreshStatus`, and the wait before enrichment ported. The view's progress bar not started |
| `src/ext/views/nodes.ts`, `checkpointsTree.ts` | `:app`, `rows/CheckpointRows.kt` | Commit rows, checkpoint rows under a commit with several, the rows of the sessions of a checkpoint that holds several, Other checkpoints, and Load more ported. Row labels use sentence case, as the IntelliJ UI guidelines ask for tree items, where upstream uses title case The hover text is in `rows/Hover.kt`, and what a row's menu acts on is in `RowMenu` |
| `src/ext/actions.ts`, `transcriptFiles.ts` | `:app`, package `actions` | Ported. `RowActions.kt` holds what the row commands of `commands.ts` do |
| `src/ext/panel/panelController.ts` | `:app`, `panel/DetailPanelController.kt` | Ported, for a panel on a checkpoint and on a session |
| `src/ext/panel/panelManager.ts` | `:plugin`, `editor/PanelFiles.kt` | The tab that each click reuses, tabs of their own, and restoring a tab |
| `src/ext/panel/html.ts` | `:plugin`, `jcef/JcefPanelView.kt` | The document, its policy, and the theme |
| `src/ext/brainService.ts` | `:app`, `brain/BrainService.kt` | Ported, with the wording of `reportBrain` from `commands.ts` |
| `src/ext/panel/trailPanelController.ts` | `:app`, `panel/TrailPanelController.kt` | Ported |
| `src/ext/refresh.ts` | `:app`, `refresh/RefreshController.kt`, and `:core`, `io/DirectoryWatcher.kt` | Ported, on the JDK's file watching. Checked in the IDE with a commit made outside it |
| `src/ext/views/selectionSync.ts`, `find` in both trees | `:app`, `rows/SelectionFollower.kt`, `RepoManager.rowPath`, `RepoManager.sessionRowPath` | Ported for both tabs, with the row of a session inside a checkpoint |
| `src/ext/views/sessionsTree.ts`, the session rows of `nodes.ts` | `:app`, `rows/SessionRows.kt` | Ported. A session's checkpoints on other branches are asked for when its row is opened |
| `src/ext/views/trailsTree.ts`, the trail rows of `nodes.ts` | `:app`, `rows/TrailRows.kt` | Ported, with the texts of the Trails view's empty states from `package.json` |
| `src/ext/views/scrollLoader.ts` | `:plugin`, `toolwindow/RowsPanel.kt` | Ported as a look at which rows are in view. Whether a list waits for a click is on its Load More row |
| `src/extension.ts`, the views and their empty states in `package.json` | `:plugin`, `EntireService.kt`, `toolwindow/` | The Checkpoints and Sessions tabs, their empty states, and Refresh. The count of running sessions is in the Sessions tab's name. The Trails tab exists while a repository has an `entire://` origin and trails are on, and the Brain tab once Entire is enabled in a repository. The four settings are on the settings page in `settings/` |
| `src/ext/gitContent.ts` | `:plugin`, `PluginUi.kt` | The commit diff reads each side from git when its file is shown. Tool bodies open as read-only text |
| `src/ext/commands.ts` | `:plugin` | Refresh, Filter Trails, View Brain, Stop Brain Graph, Open Repository on entire.com, Show Log, and Set Entire CLI Path as a link to the settings page. The row commands are a menu on each row. A row has no button of its own for Open on entire.com |
| `src/ext/views/brainView.ts` | `:plugin`, `toolwindow/BrainPanel.kt` | A Swing form with the same sections, lines, and buttons |
| `src/ext/panel/panelManager.ts`, the trail panels | `:plugin`, `editor/PanelFiles.kt` | Trail tabs have slots of their own, so a checkpoint opened from a timeline lands beside its trail |
| `test/helpers/fixtureRepo.ts`, the scenarios in `.vscode-test.mjs`, `test/fake-entire/entire` | `:testkit` | Not started |
