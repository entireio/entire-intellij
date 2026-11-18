package io.entire.app.panel

import io.entire.app.actions.Actions
import io.entire.app.repo.RepoContext
import io.entire.app.repo.RepoManager
import io.entire.app.repo.TRAILS_NEED_ENTIRE_ORIGIN
import io.entire.app.repo.TRAILS_OFF
import io.entire.core.CallResult
import io.entire.core.FailureKind
import io.entire.core.cli.TrailChange
import io.entire.core.cli.TrailDetail
import io.entire.core.cli.TrailEvent
import io.entire.core.cli.TrailMergeability
import io.entire.core.cli.TrailSummary
import io.entire.core.cli.TrailTimeline
import io.entire.core.git.CommitRecord
import io.entire.core.git.isCommitSha
import io.entire.core.protocol.DetailMode
import io.entire.core.protocol.DetailTarget
import io.entire.core.protocol.TrailChangeView
import io.entire.core.protocol.TrailCheckView
import io.entire.core.protocol.TrailCommitView
import io.entire.core.protocol.TrailEventView
import io.entire.core.protocol.TrailGateView
import io.entire.core.protocol.TrailHostMessage
import io.entire.core.protocol.TrailMergeView
import io.entire.core.protocol.TrailTarget
import io.entire.core.protocol.TrailTimelineView
import io.entire.core.protocol.TrailView
import io.entire.core.protocol.TrailWebviewMessage
import io.entire.core.protocol.readTrailWebviewMessage
import io.entire.core.trails.RememberedTimeline
import io.entire.core.trails.TimelineOf
import io.entire.core.trails.changeRef
import io.entire.core.trails.checkDetail
import io.entire.core.trails.checkOutcome
import io.entire.core.trails.eventText
import io.entire.core.trails.gateLabel
import io.entire.core.trails.gateOutcome
import io.entire.core.trails.isCheckedOut
import io.entire.core.trails.isInRepo
import io.entire.core.trails.outcomeSummary
import io.entire.core.trails.primaryChange
import io.entire.core.trails.sameChange
import io.entire.core.trails.trailProject
import io.entire.core.trails.words
import io.entire.core.web.EntireRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Timeline entries per read. The CLI accepts 1 to 100. */
private const val TIMELINE_PAGE = 50

/** Where a trail panel's messages go: the page that draws it, and the tab around the page. */
interface TrailPanelSink {
    fun post(message: JsonObject)

    fun setTitle(title: String)

    /** The panel now shows this. The IDE saves it, so the tab can come back after a restart. */
    fun showing(target: TrailTarget)
}

/** A timeline entry with what this clone knows about its commit. */
private class LoadedEvent(
    val event: TrailEvent,
    /** The commit, when the entry is one and this clone has it. */
    val commit: CommitRecord?,
)

/** Everything the IDE keeps for the panel's current trail, once the trail has been read. */
private class LoadedTrail(
    val repo: RepoContext,
    val detail: TrailDetail,
    val view: TrailView,
    /** The change whose merge state and timeline are shown. */
    val change: TrailChange?,
) {
    var events: MutableList<LoadedEvent> = ArrayList()

    /** The first page of the timeline on screen, and how it was asked for. Its later pages are asked for the same way. */
    var timeline: RememberedTimeline? = null

    /** Where the next page of the timeline starts, while there is one. */
    var nextPageToken: String? = null
}

/** What a trail panel shows, for a test or a check that cannot look at the page. */
class TrailSnapshot(val view: TrailView?, val target: TrailTarget, val error: String?)

/** The top of the page: what a row of the Trails tab and the trail itself both say. */
private fun headerView(trail: TrailSummary, partial: Boolean): TrailView =
    TrailView(
        number = trail.number,
        title = trail.title,
        status = trail.status,
        phase = trail.phase,
        type = trail.type,
        priority = trail.priority?.takeIf { it != "none" },
        assignees = trail.assignees,
        createdAt = trail.createdAt,
        lastActivityAt = trail.lastActivityAt,
        closedAt = trail.closedAt,
        body = trail.body.orEmpty(),
        partial = partial,
    )

/** The whole trail except its timeline, with the change at `position` selected. */
private fun trailView(detail: TrailDetail, hosted: EntireRepo, branch: String?, position: Int): TrailView {
    val trail = detail.summary
    return TrailView(
        number = trail.number,
        title = trail.title,
        status = trail.status,
        phase = trail.phase,
        type = trail.type,
        priority = trail.priority?.takeIf { it != "none" },
        project = detail.projectName,
        assignees = trail.assignees,
        createdAt = trail.createdAt,
        lastActivityAt = trail.lastActivityAt,
        closedAt = trail.closedAt,
        body = trail.body.orEmpty(),
        canOpenOnWeb = detail.url != null,
        changes = detail.changes.map { candidate ->
            TrailChangeView(
                repository = candidate.repository,
                number = candidate.number,
                title = candidate.title,
                status = candidate.status,
                branch = candidate.branch,
                base = candidate.base,
                checkedOut = isCheckedOut(candidate, hosted, branch),
                hasCodeChanges = candidate.hasCodeChanges,
                merge = candidate.mergeability?.let { mergeView(it) },
                canOpenOnWeb = candidate.url != null,
            )
        },
        selected = maxOf(0, position),
    )
}

private fun mergeView(merge: TrailMergeability): TrailMergeView {
    val outcomes = merge.checks.map { checkOutcome(it) }
    return TrailMergeView(
        mergeable = merge.mergeable,
        conflict = merge.conflictStatus?.takeIf { it != "clean" }?.let { words(it) },
        gates = merge.gates.map { gate ->
            TrailGateView(
                label = gateLabel(gate.key),
                outcome = gateOutcome(gate),
                blocking = gate.blocking,
                rationale = gate.rationale,
                findings = gate.findingCount,
            )
        },
        checks = merge.checks.mapIndexed { position, run ->
            TrailCheckView(name = run.name, outcome = outcomes[position], detail = checkDetail(run), app = run.appName, hasLink = run.detailsUrl != null)
        },
        checkSummary = outcomeSummary(outcomes),
        checksNote = merge.checksAvailability?.takeIf { it != "available" }
            ?.let { "Entire could not read the CI runs of this branch (${words(it)})." },
    )
}

/**
 * Whether a fresh first page of a timeline shows what an earlier one did.
 * The page tokens are left out: they are opaque, and two reads of one
 * timeline may not return the same one.
 */
private fun samePage(a: TrailTimeline, b: TrailTimeline): Boolean =
    a.events == b.events && a.commitCount == b.commitCount && a.incomplete == b.incomplete &&
        (a.nextPageToken == null) == (b.nextPageToken == null)

/** Hands the timeline one loaded trail shows to another, with every page of it read so far. */
private fun carryTimeline(from: LoadedTrail, to: LoadedTrail) {
    val shown = from.view.timeline
    if (from.timeline == null || shown == null) return
    to.timeline = from.timeline
    to.events = from.events
    to.nextPageToken = from.nextPageToken
    to.view.timelinePending = false
    to.view.timeline = shown
}

private fun timelineNotice(timeline: TrailTimeline): String? =
    if (timeline.incomplete) "Entire could not read the whole history of this branch, so some commits may be missing." else null

private fun eventView(loaded: LoadedEvent): TrailEventView {
    val event = loaded.event
    val commit = loaded.commit
    return TrailEventView(
        kind = event.kind,
        text = eventText(event),
        actor = event.actor,
        at = event.occurredAt,
        commit = event.commitSha?.let { sha ->
            TrailCommitView(
                shortSha = commit?.shortSha ?: sha.take(7),
                additions = event.additions,
                deletions = event.deletions,
                files = event.filesChanged,
                local = commit != null,
                checkpoints = commit?.checkpointIds?.size ?: 0,
                remoteCheckpoints = event.checkpointCount ?: 0,
                agents = event.agents,
            )
        },
    )
}

/** Puts the first page of a timeline in place of whatever timeline a loaded trail showed. */
private fun setTimeline(loaded: LoadedTrail, timeline: RememberedTimeline, events: List<LoadedEvent>) {
    loaded.timeline = timeline
    loaded.events = events.toMutableList()
    loaded.nextPageToken = timeline.page.nextPageToken
    loaded.view.timelinePending = false
    loaded.view.timeline = TrailTimelineView(
        events = events.mapTo(ArrayList()) { eventView(it) },
        hasMore = loaded.nextPageToken != null,
        notice = timelineNotice(timeline.page),
    )
}

private fun shortTitle(title: String): String = if (title.length > 40) title.take(39) + "…" else title

/**
 * Drives one trail panel: reads the trail and the timeline of one of its
 * changes through the CLI, and answers the page's requests.
 *
 * Each read takes seconds, so the page is drawn in stages. What is known at
 * once comes first: the trail as it was last read in this project, or else
 * what the Trails tab lists about it. The trail and the timeline are read
 * side by side, and each is drawn when it arrives.
 *
 * A commit in the timeline is matched to this clone by its SHA. When the
 * clone has it, the checkpoints it carries open in the detail panel.
 *
 * Everything here runs on the manager's own thread.
 */
class TrailPanelController(
    private val scope: CoroutineScope,
    private val manager: RepoManager,
    private val actions: Actions,
    private val sink: TrailPanelSink,
    private var target: TrailTarget,
    /** Opens a checkpoint from the timeline. It lands beside the trail, and not in its place. */
    private val openCheckpoint: (DetailTarget) -> Unit,
) {
    private var loaded: LoadedTrail? = null

    /** What the page shows before the trail has been read: the facts from the Trails tab's row. */
    private var partial: TrailView? = null
    private var loadJob: Job? = null
    private var loadSequence = 0
    private var ready = false
    private var error: TrailHostMessage.Error? = null

    /** A change the user picked in the panel, which overrides the default one. */
    private var pickedChange: Int? = null
    private var loadingMore = false
    private var disposed = false

    fun start() {
        scope.launch(manager.dispatcher) { load(reuse = false) }
    }

    /** Reads the panel's trail again. The IDE calls this when the setting that allows trails changes. */
    fun refresh() {
        scope.launch(manager.dispatcher) { load(reuse = false) }
    }

    /** Points the panel at another trail, reusing the tab. */
    fun show(next: TrailTarget) {
        scope.launch(manager.dispatcher) {
            target = next
            pickedChange = null
            load(reuse = false)
        }
    }

    /** A message from the page, as it arrived. Anything that is not one of the page's messages is dropped. */
    fun receive(raw: JsonElement?) {
        val message = readTrailWebviewMessage(raw) ?: return
        scope.launch(manager.dispatcher) { onMessage(message) }
    }

    /** The page was loaded again, so it has nothing and will ask for everything. */
    fun pageReset() {
        scope.launch(manager.dispatcher) { ready = false }
    }

    fun dispose() {
        scope.launch(manager.dispatcher) {
            disposed = true
            loadJob?.cancel()
        }
    }

    suspend fun snapshot(): TrailSnapshot = withContext(manager.dispatcher) {
        TrailSnapshot(loaded?.view ?: partial, target, error?.message)
    }

    private fun post(message: TrailHostMessage) {
        if (ready && !disposed) sink.post(message.toJson())
    }

    private fun sendView() {
        val view = loaded?.view ?: partial ?: return
        sink.showing(target)
        post(TrailHostMessage.Trail(view, target))
    }

    /** Makes a trail the one the panel shows, and draws it. */
    private fun display(next: LoadedTrail) {
        loaded = next
        partial = null
        // A page of the timeline that was on its way belonged to the view this one replaces.
        loadingMore = false
        sink.setTitle(shortTitle("#${next.detail.number} ${next.detail.title}"))
        sendView()
    }

    /** Ends the fresh read of a trail that was drawn from an earlier one. `notice` says so when the read failed. */
    private fun settle(current: LoadedTrail, notice: String?) {
        val wasRefreshing = current.view.refreshing
        current.view.refreshing = false
        current.view.notice = notice
        if (wasRefreshing || notice != null) post(TrailHostMessage.Refreshed(notice))
    }

    private fun showError(message: String, canRetry: Boolean) {
        loaded = null
        partial = null
        error = TrailHostMessage.Error(message, canRetry).also { post(it) }
    }

    /** The change to show: the one picked in the panel, or else the one that stands for the trail in this repository. */
    private fun position(changes: List<TrailChange>, hosted: EntireRepo, branch: String?): Int {
        val picked = pickedChange
        return if (picked != null && picked < changes.size) picked else primaryChange(changes, hosted, branch)
    }

    /** A trail ready to draw, with its timeline still to come. */
    private fun frame(repo: RepoContext, hosted: EntireRepo, detail: TrailDetail): LoadedTrail {
        val position = position(detail.changes, hosted, repo.branch)
        val change = detail.changes.getOrNull(position)
        val view = trailView(detail, hosted, repo.branch, position)
        if (change != null) view.timelinePending = true
        return LoadedTrail(repo, detail, view, change)
    }

    /**
     * Draws what is known about the trail at once, then reads the trail and
     * the timeline of the selected change, and draws each as it arrives.
     * `reuse` keeps the trail already read and reads only the timeline, for a
     * switch between the changes of one trail.
     */
    private fun load(reuse: Boolean) {
        val sequence = ++loadSequence
        // The reads of the load this one replaces are the panel's own, so they stop with it.
        loadJob?.cancel()
        val reused = if (reuse) loaded?.detail else null
        loaded = null
        partial = null
        error = null
        loadingMore = false
        loadJob = scope.launch(manager.dispatcher) { read(sequence, reused) }
    }

    private suspend fun read(sequence: Int, reused: TrailDetail?) {
        fun stale() = sequence != loadSequence || disposed

        if (manager.loading) {
            // A panel restored at startup waits for the repositories to be read.
            post(TrailHostMessage.Loading("Loading"))
            manager.whenLoaded()
            if (stale()) return
        }
        val repo = manager.repoFor(target.repoRoot)
        if (repo == null) {
            showError("The repository for this tab is not open in this project.", false)
            return
        }
        if (!manager.trailsEnabled) {
            // The setting is the user's word that nothing here reads Entire over the network.
            showError(TRAILS_OFF, true)
            return
        }
        val hosted = repo.entireRepo
        val cli = manager.cli
        if (hosted == null || cli == null) {
            showError(if (cli != null) TRAILS_NEED_ENTIRE_ORIGIN else "The Entire CLI is not available.", true)
            return
        }

        val number = target.trail
        val project = trailProject(hosted)
        val ref = manager.trails.trailRef(repo, number, target.id)

        // What is known before anything is read: the trail as it was last read
        // in this project, or else the row of the Trails tab.
        val listed = repo.trails?.trails?.firstOrNull { it.number == number }
        val known = reused ?: repo.trailMemory.get(number)?.detail

        // Each CLI call takes seconds, so the trail and its timeline are read
        // side by side. The timeline call has to say what it reads before the
        // trail has said which changes it has.
        //
        // It names the trail, by ID when there is one, and the CLI picks the
        // change: that is the quickest call, and for a trail with one change,
        // which most have, it reads that change. It names a change instead when
        // the changes are known: all of them from an earlier read, or more than
        // one from the row. A row's list can fall short of the trail's, and a
        // row for a trail in the complete phase names none.
        //
        // Either way the answer is used only if it turns out to be for the change
        // that the trail, once read, has the panel show.
        val candidates = known?.changes ?: listed?.changes?.takeIf { it.size > 1 } ?: emptyList()
        val guess = candidates.getOrNull(position(candidates, hosted, repo.branch))
        val noBranchYet = if (known != null) known.changes.isEmpty() else listed?.phase == "planning" && listed.changes.isEmpty()
        val earlyOf: TimelineOf? = if (guess != null) changeRef(guess) else if (noBranchYet) null else TimelineOf.Trail(ref)

        coroutineScope {
            val early: Deferred<CallResult<TrailTimeline>>? =
                earlyOf?.let { of -> async { cli.trailTimeline(repo.root, project, of, TIMELINE_PAGE) } }
            val reading: Deferred<CallResult<TrailDetail>>? =
                if (reused != null) null else async { manager.trails.showTrail(repo, ref, shared = false) }
            try {
                draw(sequence, repo, hosted, number, reused, known, listed, earlyOf, early, reading) { of ->
                    cli.trailTimeline(repo.root, project, of, TIMELINE_PAGE)
                }
            } finally {
                // Either answer can go unused: the timeline when it is of another change, both when the load ends early.
                early?.cancel()
                reading?.cancel()
            }
        }
    }

    private suspend fun draw(
        sequence: Int,
        repo: RepoContext,
        hosted: EntireRepo,
        number: Long,
        reused: TrailDetail?,
        known: TrailDetail?,
        listed: TrailSummary?,
        earlyOf: TimelineOf?,
        early: Deferred<CallResult<TrailTimeline>>?,
        reading: Deferred<CallResult<TrailDetail>>?,
        readTimeline: suspend (TimelineOf) -> CallResult<TrailTimeline>,
    ) {
        fun stale() = sequence != loadSequence || disposed

        var current: LoadedTrail? = null
        if (known != null) {
            current = frame(repo, hosted, known)
            val shownChange = current.change
            val kept = shownChange?.let { repo.trailMemory.timeline(number, it) }
            if (kept != null && shownChange != null) {
                val events = resolve(repo, shownChange, kept.page.events)
                if (stale()) return
                setTimeline(current, kept, events)
            }
            // Anything drawn from an earlier read is being checked against Entire.
            if (reused == null || kept != null) current.view.refreshing = true
            display(current)
        } else if (listed != null) {
            partial = headerView(listed, partial = true)
            sink.setTitle(shortTitle("#${listed.number} ${listed.title}"))
            sendView()
        } else {
            post(TrailHostMessage.Loading("Loading"))
        }

        var detail = reused
        if (reading != null) {
            val read = reading.await()
            if (stale()) return
            when (read) {
                is CallResult.Failed -> {
                    val message = read.failure.message
                    if (current == null || read.failure.kind == FailureKind.NOT_FOUND) {
                        showError(message, true)
                        return
                    }
                    // The trail as read earlier stays on screen, with the reason it could not be checked.
                    if (current.view.timelinePending) {
                        current.view.timelinePending = false
                        val empty = TrailTimelineView(ArrayList(), hasMore = false, notice = "$message Refresh to try again.")
                        current.view.timeline = empty
                        post(TrailHostMessage.Timeline(empty))
                    }
                    settle(current, "$message This is the trail as it was read earlier.")
                    return
                }
                is CallResult.Ok -> detail = read.value
            }
        }
        if (detail == null) return
        // The ID makes the next read of this trail quicker, after a restart too.
        if (detail.id != null && target.id != detail.id) target = target.copy(id = detail.id)

        if (current == null || current.detail != detail) {
            val next = frame(repo, hosted, detail)
            // A timeline already on screen stays until the fresh one arrives, when it is of the same change.
            val before = current
            if (before?.change != null && next.change != null && sameChange(before.change, next.change)) carryTimeline(before, next)
            if (before?.view?.refreshing == true) next.view.refreshing = true
            current = next
            display(next)
        }

        val shown: LoadedTrail = current
        val change = shown.change
        var notice: String? = null
        if (change != null) {
            // The timeline read alongside the trail counts when it is of this
            // change. One that named the trail is of this change only when the
            // trail has no other: the CLI's answer does not say which it read.
            //
            // The CLI picks a change by its branch. A closed change has none left,
            // so the CLI refuses to pick it, and takes a second or two to say so.
            // Such a change is asked for by name at once.
            val usable = when (earlyOf) {
                null -> false
                is TimelineOf.Trail -> detail.changes.size == 1 && !change.branch.isNullOrEmpty()
                is TimelineOf.Change -> sameChange(earlyOf, change)
            }
            var of: TimelineOf = if (usable && earlyOf != null) earlyOf else changeRef(change)
            var timeline = if (usable && early != null) early.await() else readTimeline(of)
            if (stale()) return
            // When the CLI will not pick a change after all, the change is asked for by name.
            if (timeline is CallResult.Failed && of is TimelineOf.Trail &&
                (timeline.failure.kind == FailureKind.EXIT || timeline.failure.kind == FailureKind.NOT_FOUND)
            ) {
                of = changeRef(change)
                timeline = readTimeline(of)
                if (stale()) return
            }
            when (timeline) {
                is CallResult.Ok -> {
                    val fresh = RememberedTimeline(of, timeline.value)
                    repo.trailMemory.keepTimeline(number, change, fresh)
                    val onScreen = shown.timeline
                    if (onScreen == null || !samePage(onScreen.page, fresh.page)) {
                        val events = resolve(repo, change, fresh.page.events)
                        if (stale()) return
                        setTimeline(shown, fresh, events)
                        loadingMore = false
                        shown.view.timeline?.let { post(TrailHostMessage.Timeline(it)) }
                    }
                }
                is CallResult.Failed -> {
                    val message = timeline.failure.message
                    if (shown.view.timeline != null) {
                        notice = "$message This is the timeline as it was read earlier."
                    } else {
                        // The trail itself loaded, so show it and say what is missing.
                        shown.view.timelinePending = false
                        val empty = TrailTimelineView(ArrayList(), hasMore = false, notice = "$message Refresh to try again.")
                        shown.view.timeline = empty
                        post(TrailHostMessage.Timeline(empty))
                    }
                }
            }
        }
        settle(shown, notice)
    }

    /** Looks up each commit of a page of the timeline in this clone. */
    private suspend fun resolve(repo: RepoContext, change: TrailChange, events: List<TrailEvent>): List<LoadedEvent> {
        // A change in another repository has its commits there, not here.
        val here = repo.entireRepo?.let { isInRepo(change, it) } == true
        return coroutineScope {
            events.map { event ->
                async {
                    val sha = event.commitSha
                    if (!here || sha == null || !isCommitSha(sha)) LoadedEvent(event, null) else LoadedEvent(event, manager.git.commit(repo.root, sha))
                }
            }.awaitAll()
        }
    }

    private suspend fun loadMoreTimeline() {
        val current = loaded ?: return
        val first = current.timeline ?: return
        val change = current.change ?: return
        val token = current.nextPageToken ?: return
        val cli = manager.cli ?: return
        val hosted = current.repo.entireRepo ?: return
        if (loadingMore || !manager.trailsEnabled) return
        loadingMore = true
        val sequence = loadSequence
        // A fresh read can put another timeline in place of this one while the page is on its way.
        fun stale() = sequence != loadSequence || disposed || loaded !== current || current.timeline !== first
        try {
            val page = cli.trailTimeline(current.repo.root, trailProject(hosted), first.of, TIMELINE_PAGE, token)
            if (stale()) return
            when (page) {
                is CallResult.Failed -> post(TrailHostMessage.TimelinePage(emptyList(), hasMore = true, notice = page.failure.message))
                is CallResult.Ok -> {
                    val events = resolve(current.repo, change, page.value.events)
                    if (stale()) return
                    current.events.addAll(events)
                    current.nextPageToken = page.value.nextPageToken
                    val views = events.map { eventView(it) }
                    val hasMore = current.nextPageToken != null
                    // Keep the IDE's copy of the view whole, so a page that reloads gets every entry.
                    current.view.timeline?.let {
                        it.events.addAll(views)
                        it.hasMore = hasMore
                    }
                    post(TrailHostMessage.TimelinePage(views, hasMore, timelineNotice(page.value)))
                }
            }
        } finally {
            if (loaded === current && current.timeline === first) loadingMore = false
        }
    }

    private suspend fun onMessage(message: TrailWebviewMessage) {
        when (message) {
            is TrailWebviewMessage.Ready -> {
                ready = true
                when {
                    loaded != null || partial != null -> sendView()
                    error != null -> error?.let { post(it) }
                    else -> post(TrailHostMessage.Loading("Loading"))
                }
                return
            }
            TrailWebviewMessage.Retry -> {
                load(reuse = false)
                return
            }
            is TrailWebviewMessage.OpenLink -> {
                actions.openExternal(message.href)
                return
            }
            else -> {}
        }

        val current = loaded ?: return
        val repo = current.repo
        val detail = current.detail
        val change = current.change

        when (message) {
            is TrailWebviewMessage.SelectChange ->
                if (message.position < detail.changes.size && message.position != current.view.selected) {
                    pickedChange = message.position
                    load(reuse = true)
                }
            TrailWebviewMessage.OpenOnWeb -> detail.url?.let { actions.openExternal(it) }
            TrailWebviewMessage.OpenChangeOnWeb -> change?.url?.let { actions.openExternal(it) }
            is TrailWebviewMessage.OpenCheck -> change?.mergeability?.checks?.getOrNull(message.run)?.detailsUrl?.let { actions.openExternal(it) }
            is TrailWebviewMessage.OpenCheckpoint -> {
                val commit = current.events.getOrNull(message.event)?.commit
                val checkpointId = commit?.checkpointIds?.getOrNull(message.checkpoint)
                if (commit != null && checkpointId != null) {
                    openCheckpoint(DetailTarget(repoRoot = repo.root, mode = DetailMode.CHECKPOINT, checkpointId = checkpointId, commitSha = commit.sha))
                }
            }
            is TrailWebviewMessage.ShowCommitDiff -> current.events.getOrNull(message.event)?.commit?.let { actions.showCommitDiff(repo, it.sha) }
            is TrailWebviewMessage.CopyCommitSha -> current.events.getOrNull(message.event)?.event?.commitSha?.let { actions.copy(it, "commit SHA") }
            TrailWebviewMessage.CopyBranch -> change?.branch?.let { actions.copy(it, "branch name") }
            TrailWebviewMessage.CopyLink -> detail.url?.let { actions.copy(it, "trail link") }
            TrailWebviewMessage.LoadMoreTimeline -> loadMoreTimeline()
            else -> {}
        }
    }
}
