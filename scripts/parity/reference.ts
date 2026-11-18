// The same facts as core/src/smoke/kotlin/io/entire/core/smoke/Smoke.kt, read by the core of entire-vscode.
// scripts/parity.sh bundles this file with @upstream set to src/core of an entire-vscode checkout.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import os from 'node:os';
import { CheckpointStore } from '@upstream/checkpointStore';
import { REMOTE_SESSIONS_PAGE } from '@upstream/remoteSessions';
import { readSessionRows, totalTokens, type TrailChange, type TrailSummary } from '@upstream/schemas';
import { brainDetails, hasBrain, isPluginMissing, parseVizUrl } from '@upstream/brain';
import {
  changeRef, checkDetail, checkOutcome, eventText, gateLabel, gateOutcome, isCheckedOut, isInRepo, noTrailsText, outcomeSummary, primaryChange,
  readTrailFilter, trailFilterKey, trailFilterLabel, trailFilters, trailProject, trailRepo,
} from '@upstream/trails';
import { parseCliText } from '@upstream/transcript/cliText';
import { toWireTurn, toolInputText } from '@upstream/transcript/model';
import { parseCompactTranscript } from '@upstream/transcript/normalize';
import { parseEntireRemote } from '@upstream/webLinks';
import { CheckpointIndex, LIST_LIMIT, PAGE_SIZE, loadPage } from '@upstream/checkpointIndex';
import { isExecutableFile, isSupportedVersion, locateCli } from '@upstream/cliLocator';
import { EntireCli } from '@upstream/entireCli';
import { exec } from '@upstream/exec';
import { formatPosition, relativeTime } from '@upstream/format';
import { Git } from '@upstream/git';
import { Lane } from '@upstream/scheduler';

/** How many checkpoints are read in full: their detail, their stored transcript, and their author. */
const DETAILS = 12;
const sha = (text: string): string => createHash('sha256').update(text, 'utf8').digest('hex').slice(0, 16);

const [folder, nowArg] = process.argv.slice(2);
const now = nowArg ? Number(nowArg) : Date.now();
const out = (line: string): void => void process.stdout.write(`${line}\n`);

async function main(): Promise<number> {
  const git = new Git(exec, new Lane(4));
  const paths = await git.repoPaths(folder ?? '.');
  if (!paths.ok) { out(`notGit ${paths.failure.message}`); return 1; }
  const { root, gitDir, commonDir } = paths.value;
  out(`root=${root}`); out(`gitDir=${gitDir}`); out(`commonDir=${commonDir}`);
  out(`branch=${(await git.branch(root)) ?? ''}`);
  const located = await locateCli(undefined, { env: process.env, platform: process.platform, homedir: os.homedir(), isExecutable: isExecutableFile });
  if (!located) { out('cliMissing'); return 1; }
  const cli = new EntireCli(located.path, exec, new Lane(4));
  const version = await cli.version(root);
  if (!version.ok) { out(`cliMissing ${version.failure.message}`); return 1; }
  out(`cli=${version.value.raw} supported=${isSupportedVersion(version.value)}`);
  const status = await cli.status(root);
  if (status.ok) out(`status enabled=${status.value.enabled} agents=${status.value.agents.join(',')} active=${status.value.activeSessions.length}`);
  else out(`status failed=${status.failure.kind} ${status.failure.message}`);
  const tip = await git.head(root);
  if (!tip) { out('head=none'); return 0; }
  const index = new CheckpointIndex(tip);
  const read = await loadPage(index, async (skip, count) => { const w = await git.logWindow(root, tip, skip, count); return w.ok ? w.value : undefined; }, PAGE_SIZE);
  out(`index read=${read} scanned=${index.scanned} exhausted=${index.exhausted} linked=${index.commits.length}`);
  const rows = await cli.listCheckpoints(root, LIST_LIMIT);
  if (rows.ok) index.setRows(rows.value); else out(`rows failed=${rows.failure.kind} ${rows.failure.message}`);
  out(`rows=${index.allRows().length} unlinked=${index.unlinkedRows().length} sessions=${index.sessions().length}`);
  for (const commit of index.commits) {
    const sole = commit.checkpointIds.length === 1 ? commit.checkpointIds[0] : undefined;
    const row = sole ? index.row(sole) : undefined;
    const sessions = row ? (row.sessionCount ?? row.sessionIds.length) : 0;
    const position = sole && sessions === 1 && row?.sessionId ? index.position(row.sessionId, sole) : undefined;
    out(['commit', commit.sha, commit.shortSha, String(commit.authorTime), commit.author, commit.subject, commit.checkpointIds.join(','), row?.agent ?? '', String(sessions), position ? formatPosition(position) : '', relativeTime(commit.authorTime * 1000, now)].join('\t'));
  }
  const store = new CheckpointStore(git);
  let textViewRead = false;
  for (const commit of index.commits.filter((c) => c.checkpointIds.length === 1).slice(0, DETAILS)) {
    const id = commit.checkpointIds[0] ?? '';
    const detail = await cli.explainCheckpoint(root, id);
    if (!detail.ok) out(`detail\t${id}\tfailed`);
    else {
      const value = detail.value;
      out(['detail', id, value.sessions.length, value.filesTouched.length, value.partial, value.promptCount ?? '', value.branch ?? ''].join('\t'));
      for (const session of value.sessions) {
        out(['dsession', session.index, session.sessionId ?? '', session.agent ?? '', session.model ?? '', session.kind ?? '', session.createdAt ?? '',
          session.filesTouched.length, session.tokenUsage ? totalTokens(session.tokenUsage) : '', sha(session.summary?.intent ?? ''), sha(session.summary?.outcome ?? '')].join('\t'));
      }
    }
    const tree = await store.locate(root, id);
    out(['store', id, tree?.treeish ?? 'none', tree?.prefix ?? ''].join('\t'));
    if (tree) {
      for (const slot of await store.sessions(root, tree)) {
        out(['slot', slot.index, slot.compactTranscriptPath ?? '', slot.metadataPath ?? ''].join('\t'));
        const meta = await store.sessionMeta(root, tree, slot);
        out(['meta', slot.index, meta?.sessionId ?? '', meta?.compactTranscriptStart ?? ''].join('\t'));
        const text = await store.compactTranscript(root, tree, slot);
        if (text !== undefined) {
          const parsed = parseCompactTranscript(text, meta?.compactTranscriptStart);
          const wire = parsed.turns.map((turn, position) => JSON.stringify(toWireTurn(turn, position))).join('\n');
          let bodies = '';
          for (const turn of parsed.turns) {
            for (const block of turn.blocks) {
              if (block.kind === 'tool') bodies += `${toolInputText(block.input)}\u0000${block.output ?? ''}\u0001`;
            }
          }
          out(['transcript', slot.index, parsed.turns.length, parsed.startTurn, sha(wire), sha(bodies)].join('\t'));
        }
        if (meta?.sessionId) {
          const opening = await store.openingPrompt(root, tree, meta.sessionId);
          out(['opening', slot.index, opening?.fromSessionStart ?? '', sha(opening?.text ?? '')].join('\t'));
        }
      }
    }
    const author = await cli.checkpointAuthor(root, id);
    out(author.ok ? ['author', id, author.value ?? ''].join('\t') : `author\t${id}\tfailed`);
    if (!textViewRead) {
      textViewRead = true;
      const full = await cli.fullText(root, id);
      if (full.ok) {
        const parsed = parseCliText(full.value);
        const wire = parsed.turns.map((turn, position) => JSON.stringify(toWireTurn(turn, position))).join('\n');
        out(['textview', id, parsed.turns.length, sha(wire)].join('\t'));
      } else out(`textview\t${id}\tfailed`);
    }
  }

  for (const session of index.sessions().slice(0, 200)) {
    out(['session', session.sessionId, session.agent ?? '', String(session.checkpoints.length), session.lastDate ?? '', String(session.mayHaveEarlier), session.checkpoints[0]?.checkpointId ?? '', relativeTime(session.lastDate, now)].join('\t'));
  }

  // A file of made-up rows, read the way `entire session list --json` is. The real command prunes old session state, so it is not run here.
  const rowsFile = process.env.PARITY_SESSION_ROWS;
  if (rowsFile) {
    const sessionRows = readSessionRows(JSON.parse(readFileSync(rowsFile, 'utf8')));
    out(`sessionrows ${sessionRows?.length ?? 'unreadable'}`);
    for (const row of sessionRows ?? []) {
      out(['sessionrow', row.sessionId, row.agent ?? '', row.model ?? '', row.status ?? '', row.kind ?? '', row.startedAt ?? '', row.endedAt ?? '',
        row.lastActive ?? '', row.turns ?? '', row.pendingTurns ?? '', row.lastCheckpointId ?? '',
        row.tokens ? `${row.tokens.total}/${row.tokens.input}/${row.tokens.cacheRead}/${row.tokens.cacheWrite}/${row.tokens.output}` : '', sha(row.lastPrompt ?? '')].join('\t'));
    }
  }

  // What Entire lists for the repository, read with the saved login. Only a repository that Entire hosts has such a list.
  if (parseEntireRemote(await git.originUrl(root))) {
    const page = await cli.remoteSessions(root, REMOTE_SESSIONS_PAGE);
    if (!page.ok) out(`remote failed=${page.failure.kind.toLowerCase()} ${page.failure.message}`);
    else {
      out(`remote sessions=${page.value.sessions.length} more=${page.value.nextCursor !== undefined}`);
      for (const session of page.value.sessions) {
        out(['remotesession', session.sessionId, sha(session.name ?? ''), session.agent ?? '', session.model ?? '', session.checkpointCount ?? '',
          session.startedAt ?? '', session.lastActivityAt ?? '', sha(session.prompt ?? '')].join('\t'));
      }
      const first = page.value.sessions.find((session) => (session.checkpointCount ?? 0) >= 1 && (session.checkpointCount ?? 0) <= 40);
      if (first) {
        const listed = await cli.remoteSessionCheckpoints(root, first.sessionId);
        if (!listed.ok) out(`remotecheckpoints failed=${listed.failure.kind.toLowerCase()}`);
        else for (const checkpoint of listed.value) {
          out(['remotecheckpoint', checkpoint.checkpointId, checkpoint.commitSha ?? '', sha(checkpoint.message ?? ''), checkpoint.date ?? ''].join('\t'));
        }
      }
    }
  }
  // Trails are read from Entire with the saved login, for a repository that Entire hosts.
  const hosted = parseEntireRemote(await git.originUrl(root));
  if (hosted) {
    const branch = await git.branch(root);
    const scope = { project: trailProject(hosted), repo: trailRepo(hosted) };
    const changeLine = (change: TrailChange): string =>
      [change.repository, change.number, change.branch ?? '', change.base ?? '', change.status ?? '', change.hasCodeChanges, change.updatedAt ?? '', sha(change.title ?? ''), change.url ?? ''].join('/');
    const seen: (string | undefined)[] = [];
    let listed: TrailSummary[] = [];
    for (const status of ['open', 'closed', 'all'] as const) {
      const page = await cli.listTrails(root, scope, status, PAGE_SIZE);
      if (!page.ok) { out(`trails ${status} failed=${page.failure.kind.toLowerCase()} ${page.failure.message}`); continue; }
      out(`trails ${status} count=${page.value.trails.length} more=${page.value.nextPageToken !== undefined} total=${page.value.total ?? ''}`);
      for (const trail of page.value.trails) {
        seen.push(trail.phase);
        out(['trail', status, trail.number, trail.id ?? '', sha(trail.title), sha(trail.body ?? ''), trail.status ?? '', trail.phase ?? '', trail.type ?? '', trail.priority ?? '',
          trail.assignees.join(','), trail.createdAt ?? '', trail.lastActivityAt ?? '', trail.closedAt ?? '', primaryChange(trail.changes, hosted, branch), trail.changes.map(changeLine).join(' ')].join('\t'));
      }
      if (status === 'all') listed = page.value.trails;
    }
    for (const filter of trailFilters(seen)) {
      const back = readTrailFilter(trailFilterKey(filter));
      out(['filter', trailFilterKey(filter), trailFilterLabel(filter), noTrailsText(filter), back ? trailFilterKey(back) : 'unreadable'].join('\t'));
    }
    for (const key of ['open:', 'closed:building', 'open:Building', 'open:a:b', 'nope', '']) out(`filterkey\t${key}\t${readTrailFilter(key) ? 'read' : 'refused'}`);
    // Three trails are read in full: the most recent, one with several changes, and one that is closed.
    const chosen = [listed[0], listed.find((trail) => trail.changes.length > 1), listed.find((trail) => trail.status === 'closed')]
      .filter((trail, at, all): trail is TrailSummary => trail !== undefined && all.indexOf(trail) === at);
    for (const trail of chosen) {
      const detail = await cli.showTrail(root, scope.project, trail.id ? { number: trail.number, id: trail.id } : { number: trail.number });
      if (!detail.ok) { out(`traildetail ${trail.number} failed=${detail.failure.kind.toLowerCase()}`); continue; }
      const read = detail.value;
      out(['traildetail', read.number, read.id ?? '', sha(read.title), read.url ?? '', read.projectName ?? '', read.changes.length].join('\t'));
      for (const change of read.changes) {
        out(['change', changeLine(change), isInRepo(change, hosted), isCheckedOut(change, hosted, branch)].join('\t'));
        const merge = change.mergeability;
        if (!merge) continue;
        out(['merge', merge.mergeable, merge.headSha ?? '', merge.conflictStatus ?? '', merge.checksAvailability ?? '', outcomeSummary(merge.checks.map(checkOutcome))].join('\t'));
        for (const gate of merge.gates) out(['gate', gate.key, gateLabel(gate.key), gateOutcome(gate), gate.blocking, gate.status ?? '', sha(gate.rationale ?? ''), gate.findingCount ?? ''].join('\t'));
        for (const run of merge.checks) out(['check', sha(run.name), checkOutcome(run), checkDetail(run), run.appName ?? '', run.detailsUrl !== undefined].join('\t'));
      }
      const change = read.changes[primaryChange(read.changes, hosted, branch)];
      if (!change) continue;
      const timeline = await cli.trailTimeline(root, scope.project, changeRef(change), 50);
      if (!timeline.ok) { out(`timeline ${read.number} failed=${timeline.failure.kind.toLowerCase()}`); continue; }
      out(['timeline', read.number, timeline.value.events.length, timeline.value.nextPageToken !== undefined, timeline.value.commitCount ?? '', timeline.value.incomplete].join('\t'));
      for (const event of timeline.value.events) {
        out(['event', event.kind, sha(eventText(event)), event.actor ?? '', event.occurredAt ?? '', event.commitSha ?? '', event.additions ?? '', event.deletions ?? '',
          event.filesChanged ?? '', event.checkpointCount ?? '', event.agents.join(','), event.findingCount ?? ''].join('\t'));
      }
    }
  }

  // What the entire-brain plugin reports about the repository's brain. It reads local state only.
  const brain = await cli.brainStatus(root);
  if (!brain.ok) out(`brain failed=${brain.failure.kind.toLowerCase()}`);
  else {
    const report = brain.value;
    out(['brain', report.manifestState, hasBrain(report), report.generatedAt ?? '', report.files ?? '', report.symbols ?? '', report.relations ?? '', report.memories ?? '',
      report.exchanges ?? '', report.docsRecords ?? '', report.docsFiles ?? '', report.freshness ?? '', report.updater ?? '', report.hosted ?? '', report.hostedSynced ?? '',
      sha(report.staleParts.join('\n')), sha(report.warnings.join('\n'))].join('\t'));
    for (const detail of brainDetails(report, relativeTime(report.generatedAt, now))) {
      out(['braindetail', detail.key, detail.label, detail.value, sha(detail.tooltip ?? ''), detail.attention === true].join('\t'));
    }
  }
  for (const line of ['entire brain viz - http://127.0.0.1:49316', 'http://127.0.0.1:0', 'http://127.0.0.1:123456', 'http://localhost:8080', 'x http://127.0.0.1:65535 y', 'http://127.0.0.1:00080']) {
    out(`viz\t${parseVizUrl(line) ?? ''}`);
  }
  for (const text of ['Error: unknown command "brain" for "entire"', 'The entire-brain plugin is not installed.', 'run entire plugin install brain', 'unknown command "brainstorm"', 'something else']) {
    out(`pluginmissing\t${isPluginMissing(text)}`);
  }
  return 0;
}
process.exitCode = await main();
