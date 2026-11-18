import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Spike S3: how long after a commit, a branch switch, or a session-state write
 * does java.nio's WatchService report it?
 *
 * The plugin watches the three places the VS Code extension watches:
 * `<gitDir>/HEAD`, `<gitDir>/logs/HEAD`, and `<commonDir>/entire-sessions/*.json`.
 * This program builds a repository with a linked worktree in a directory of
 * its own, makes each kind of change with the real `git`, and times the event.
 *
 * Run: java spikes/S3WatchLatency.java <empty-directory> [rounds]
 */
public class S3WatchLatency {
    static final long LIMIT_MS = 15_000;

    static void git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        // The developer's own git settings, such as commit signing, stay out of this.
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_AUTHOR_NAME", "Spike");
        env.put("GIT_AUTHOR_EMAIL", "spike@example.com");
        env.put("GIT_COMMITTER_NAME", "Spike");
        env.put("GIT_COMMITTER_EMAIL", "spike@example.com");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
    }

    static final class Watcher implements AutoCloseable {
        final WatchService service = FileSystems.getDefault().newWatchService();
        final Map<WatchKey, Path> directories = new HashMap<>();

        Watcher() throws Exception {}

        void watch(Path directory) throws Exception {
            WatchKey key = directory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            directories.put(key, directory);
        }

        /** Throws away what has been reported so far. */
        void drain() {
            WatchKey key;
            while ((key = service.poll()) != null) {
                key.pollEvents();
                key.reset();
            }
        }

        /** Waits for an event on a file of this name in this directory. Returns the wait in ms, or -1. */
        long await(Path directory, String name, long sinceNanos) throws Exception {
            long deadline = System.nanoTime() + LIMIT_MS * 1_000_000;
            while (System.nanoTime() < deadline) {
                WatchKey key = service.poll(100, TimeUnit.MILLISECONDS);
                if (key == null) continue;
                boolean hit = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) hit = directory.equals(directories.get(key));
                    else if (directory.equals(directories.get(key)) && name.equals(event.context().toString())) hit = true;
                }
                key.reset();
                if (hit) return Math.max(0, (System.nanoTime() - sinceNanos) / 1_000_000);
            }
            return -1;
        }

        @Override
        public void close() throws Exception {
            service.close();
        }
    }

    interface Change {
        void make(int round) throws Exception;
    }

    static void measure(String name, Watcher watcher, Path directory, String file, int rounds, Change change) throws Exception {
        List<Long> waits = new ArrayList<>();
        int missed = 0;
        for (int round = 0; round < rounds; round++) {
            watcher.drain();
            change.make(round);
            // The clock starts when the change is on disk, which is when git has exited.
            long wait = watcher.await(directory, file, System.nanoTime());
            if (wait < 0) missed++;
            else waits.add(wait);
            // Spread the changes out, so that a service that polls is not always asked at the same moment.
            Thread.sleep(137L * (round % 5));
        }
        Collections.sort(waits);
        String stats = waits.isEmpty() ? "no events" : String.format("min %5d  median %5d  max %5d ms",
            waits.get(0), waits.get(waits.size() / 2), waits.get(waits.size() - 1));
        System.out.printf("%-46s %s%s%n", name, stats, missed > 0 ? "  (" + missed + " missed in " + LIMIT_MS + " ms)" : "");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java spikes/S3WatchLatency.java <empty-directory> [rounds]");
            System.exit(2);
        }
        Path base = Path.of(args[0]).toAbsolutePath();
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        Files.createDirectories(base);
        try (var existing = Files.list(base)) {
            if (existing.findAny().isPresent()) throw new IllegalStateException(base + " is not empty");
        }

        Path repo = Files.createDirectory(base.resolve("repo"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "commit", "-q", "--allow-empty", "-m", "first");
        Path worktree = base.resolve("worktree");
        git(repo, "worktree", "add", "-q", "-b", "side", worktree.toString());
        git(worktree, "commit", "-q", "--allow-empty", "-m", "first in the worktree");

        Path gitDir = repo.resolve(".git");
        Path worktreeGitDir = gitDir.resolve("worktrees").resolve("worktree");
        Path sessions = Files.createDirectory(gitDir.resolve("entire-sessions"));

        try (Watcher watcher = new Watcher()) {
            System.out.println("Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ") on "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            System.out.println("WatchService: " + watcher.service.getClass().getName());
            System.out.println(rounds + " rounds for each case. The wait is measured from the moment git exits.");
            System.out.println();

            watcher.watch(gitDir);
            watcher.watch(gitDir.resolve("logs"));
            watcher.watch(worktreeGitDir);
            watcher.watch(worktreeGitDir.resolve("logs"));
            watcher.watch(sessions);

            measure("commit, seen as <gitDir>/logs/HEAD", watcher, gitDir.resolve("logs"), "HEAD", rounds,
                round -> git(repo, "commit", "-q", "--allow-empty", "-m", "commit " + round));
            measure("branch switch, seen as <gitDir>/HEAD", watcher, gitDir, "HEAD", rounds,
                round -> git(repo, "checkout", "-q", "-b", "branch-" + round));
            measure("commit in a worktree, seen as its logs/HEAD", watcher, worktreeGitDir.resolve("logs"), "HEAD", rounds,
                round -> git(worktree, "commit", "-q", "--allow-empty", "-m", "worktree commit " + round));
            measure("branch switch in a worktree, seen as its HEAD", watcher, worktreeGitDir, "HEAD", rounds,
                round -> git(worktree, "checkout", "-q", "-b", "side-" + round));
            measure("session state write, entire-sessions/*.json", watcher, sessions, "session.json", rounds,
                round -> Files.writeString(sessions.resolve("session.json"), "{\"round\":" + round + "}"));
        }
    }
}
