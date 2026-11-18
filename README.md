# Entire for JetBrains IDEs

Browse the [Entire](https://entire.com) checkpoints behind your commits, the agent sessions behind each checkpoint, and the trails of a repository on entire.com, in the IDE where you read the code.

This plugin is the JetBrains counterpart of [Entire for VS Code](https://github.com/entireio/entire-vscode). It is in development and has no release yet. [PORTING.md](PORTING.md) tracks what is ported.

## Requirements

- A JetBrains IDE, version 2025.3.1 through 2026.3, with the bundled Git plugin enabled.
- A project in a Git repository. The Entire tool window shows only in such a project, and appears once a folder of the project becomes one.
- [Entire CLI](https://docs.entire.com/installation) 0.12.0 or later, and a local repository with Entire enabled. The Setup tab sets up both.
- For session names from Entire, for trails, and for Entire's search, an `origin` remote that uses the `entire://` scheme and a saved login. Without them, the Sessions tab names each session by its opening prompt, and there is no Trails tab.
- For the Brain tab, the entire-brain plugin and a brain for the repository. The Setup tab sets up both.

## Installation

The plugin has no release on JetBrains Marketplace yet, so you build it from this repository. The build needs Node 22.18 or later on the `PATH`, and Gradle downloads JDK 21 when the machine has none.

```sh
./gradlew :plugin:buildPlugin
```

In the IDE, open Settings > Plugins, click the gear button, choose Install Plugin from Disk…, and select the zip that the build wrote to `plugin/build/distributions`. Then open a project in a Git repository, and open View > Tool Windows > Entire. When the Entire CLI is missing or Entire is not enabled in the repository, the Setup tab comes first and sets them up.

To try the plugin without installing it, `./gradlew :plugin:runIde` starts a separate IDE with the plugin loaded. [CONTRIBUTING.md](CONTRIBUTING.md) covers the rest of the build.

## What Works Today

**Checkpoints tab.** Lists each commit on the current branch that carries an `Entire-Checkpoint` trailer, with the agent, the checkpoint's place in its session, and the commit's age. A commit expands only when there is something to tell apart: several checkpoints, as in a squash, or a checkpoint that holds several sessions. The list shows 50 commits at a time, and the Load more checkpoints row reads the next 50. When there is nothing to list, the tab says why, offers the step that fixes it, and links to the page of Entire's documentation that explains it: the Entire CLI is missing or too old, or Entire is not enabled in the repository.

**Sessions tab.** Lists the sessions running now, then the rest by recency, each with its checkpoints under it. A row gives the session's agent, its count of checkpoints, and when it was last active, and the tab's name counts the sessions that are running. When the `origin` remote uses the `entire://` scheme, the tab reads the repository's sessions from Entire through the Entire CLI's saved login, as the Sessions page of entire.com lists them: every session on every branch, under the name Entire gave it, with Entire's count of its checkpoints. Expanding a session lists the checkpoints your clone knows at once, and adds the ones Entire lists for it, such as those on other branches. In any other repository, a session appears when Entire has local state for it or when a checkpoint references it, and the tab names it by the prompt that opened it. The tab shows 50 sessions at a time, and it reads nothing until it is first on screen.

**Detail panel.** Click a commit, a checkpoint, or a session to open it in an editor tab. The panel shows the commit, the files the session touched, and the transcript. Tool calls start collapsed, and expanding one shows its input and result. A checkpoint opens at its own part of the conversation, and Show Earlier Messages loads what came before. A session opens on its whole conversation. When the Entire CLI finds no session data for a checkpoint, the panel shows a note in place of the transcript. The panel says which checkpoint of the session it shows, and Previous and Next step through the session's other checkpoints. Show Commit Diff opens the commit's files in the IDE's diff viewer, and Open Raw Transcript has the Entire CLI write the transcript to a read-only temporary file and opens it. One click reuses a single tab, and the IDE brings an open tab back after a restart. The selected row moves with the tab: when Previous or Next shows another checkpoint, the tab the panel was opened from selects that checkpoint's row, and loads more of its list when the row is beyond its end.

The IDE's embedded browser draws the panel. In an IDE without it, the tab says so.

**Trails tab.** Lists the trails that touch the repository, most recent activity first, with each trail's phase, its branch here, and its age. The tab exists when the `origin` remote uses the `entire://` scheme. It reads trails from Entire through the Entire CLI's saved login, and not before it is first on screen. The Filter button shows every trail, the open ones, the closed ones, or the open ones in one phase. The tab shows 50 trails at a time.

**Trail panel.** Click a trail to open it in an editor tab. The panel shows the trail's description and its branches, which Entire calls changes. For a change it shows whether the branch can merge, each merge gate with the reason for its result, and the CI runs on the head commit. Below that is the change's timeline of commits, reviews, and resolved findings. A commit that your clone has opens its checkpoint in the detail panel, beside the trail. The panel draws what the list already knows at once, then the trail, then the timeline, because each read from Entire takes seconds.

**Brain tab.** Shows what the entire-brain plugin reports about the repository's brain: the size of the code graph, the memories, the indexed conversations and docs, how fresh the brain is, and whether its updater runs. View Brain starts `entire brain viz` and opens its graph page in your browser. The CLI serves the page on `127.0.0.1`, and the plugin stops the server when you click Stop or close the project. When the plugin is not installed, or the repository has no brain, the tab says so, and its Set Up the Brain button opens the Brain step of the Setup tab. When a repository on Entire has a brain that is not connected to its hosted brain, Connect to the Hosted Brain… opens that step.

**Search.** Each list tab has a search field. Typing filters the rows already loaded, by the text each row shows and by the full IDs and SHA that the row shortens, and keeps a commit or session row when a row under it matches. In the Checkpoints and Sessions tabs, Enter also runs Entire's own search (`entire search`), which finds commits, checkpoints, and sessions by meaning and by keyword on every branch. Its results appear under On Entire at the end of each tab: commits and checkpoints in Checkpoints, and sessions in Sessions. Opening a result shows it in the detail panel, or its page on entire.com when your clone does not have it. Entire's search needs an `origin` remote on Entire and a login, and it runs only when you press Enter.

**Setup.** The Setup tab finds what is missing for Entire in the repository and sets it up one step at a time: the Entire CLI, the login, Entire in the repository with hooks for your agents, the graph, the brain, and the connection to the hosted brain for a repository on Entire. Each step shows what it changes and its exact command before you click it, and its output streams into a console in the tab. Its options choose the agents whose sessions Entire records, whether Entire may collect anonymous usage analytics, how you log in, and whether the brain updates in the background. Distilling past sessions into memories spends agent tokens, so it stays off until you check it. The tab comes first when the CLI is missing or Entire is not enabled. Otherwise, Entire Setup in the title bar's ⋮ menu opens it, and Close Setup hides it again.

**Row menu.** Right-click a row for Open in New Tab, Show Commit Diff, Open on entire.com, Open Raw Transcript, and the copy actions for a checkpoint ID, a commit SHA, a session ID, or a trail's link. A row offers only what it has something for. Hovering over a row shows its full title and its IDs. When a repository has an `entire://` origin, the globe button in the tool window's title bar opens the repository's page on entire.com.

**Staying current.** The Checkpoints and Sessions tabs follow the repository by themselves while the Entire tool window is on screen: a commit, a branch switch, or a change of Entire's session state shows up within a second or two. These refreshes read Git and `entire status --json` only. Refresh Entire reads the full session list, Entire's lists of sessions and trails, and the brain's report again.

## Settings

The plugin's settings are under Settings > Tools > Entire. The gear button in the tool window's title bar opens the page. The IDE keeps these settings out of settings sync, because the first two describe your machine.

| Setting | What It Sets |
| --- | --- |
| Path to Entire CLI | The Entire CLI binary to run. Leave it empty to find `entire` on your `PATH` and in common install locations. A leading `~` stands for your home folder. |
| Entire web app URL | The base of links to the Entire web app. Leave it empty to use `ENTIRE_WEB_BASE_URL` if it is set, and `https://entire.com` otherwise. |
| Load more rows on scroll | Whether a list loads its next 50 rows when you scroll to its end. When off, click its Load more row to load them. |
| Show trails | Whether the plugin reads trails from Entire. When off, the Trails tab is hidden, an open trail says so, and the plugin runs no `entire trail` command. |

A new path takes effect when you apply it: each open project looks for the CLI again. When a path is set, the plugin looks nowhere else. When no file that can be run is there, the page warns you, and the tabs say the CLI is missing until you correct the path or empty it. The page does not store a web app URL that is not an `http://` or `https://` address.

## What It Runs

The plugin reads through Git and the Entire CLI, and it sends no network requests of its own. The Setup tab's steps are the exception to reading: each one writes, and each one runs only when you click it. They install the CLI or a plugin, log in, enable Entire, build the graph or the brain, or connect the brain to Entire.

## What It Does Not Do Yet

- Search inside a detail or trail panel.
- Show a checkpoint's header and files in an IDE without the embedded browser. The tab says the browser is missing.
- Keep a log of its own. Show log opens the IDE's log, which holds the plugin's lines among the IDE's.
- Work in remote development, where the IDE's backend runs on another machine.

## Feedback

[CONTRIBUTING.md](CONTRIBUTING.md) covers building and testing the plugin. Report a security vulnerability the way [SECURITY.md](SECURITY.md) says, and not in an issue.

## License

[MIT](LICENSE).
