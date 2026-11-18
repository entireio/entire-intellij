import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spike S4: can the JVM stop a process and everything under it, without a
 * process group?
 *
 * Node starts `entire brain viz` in a process group of its own and signals the
 * group. The JVM cannot create a process group, so the plugin has to find the
 * tree itself. This program tries the method the plan proposes on trees of
 * `sh` and `sleep`, and on the real `entire brain viz` when given a repository
 * that has a brain.
 *
 * Run: java spikes/S4ProcessTree.java [path-to-entire repository-root]
 */
public class S4ProcessTree {
    static final long GRACE_MS = 2000;

    record Outcome(String name, int tracked, long stopMs, int forced, List<Long> survivors) {}

    /** Everything under a process, and the process itself. Take it while the parent is alive. */
    static Set<ProcessHandle> snapshot(Process process) {
        Set<ProcessHandle> tree = new LinkedHashSet<>();
        tree.add(process.toHandle());
        process.toHandle().descendants().forEach(tree::add);
        return tree;
    }

    /**
     * Asks every process to stop, waits for the grace period, and kills what is
     * left. `remembered` holds processes seen earlier: a child whose parent has
     * exited is no longer a descendant, so it can only be stopped if it was
     * seen while the parent was alive.
     */
    static Outcome terminate(String name, Process process, Set<ProcessHandle> remembered) throws InterruptedException {
        Set<ProcessHandle> tree = new LinkedHashSet<>(remembered);
        tree.addAll(snapshot(process));
        long start = System.nanoTime();
        for (ProcessHandle handle : tree) handle.destroy();
        waitUntilDead(tree, GRACE_MS);
        int forced = 0;
        for (ProcessHandle handle : tree) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
                forced++;
            }
        }
        waitUntilDead(tree, GRACE_MS);
        long stopMs = (System.nanoTime() - start) / 1_000_000;
        List<Long> survivors = tree.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
        return new Outcome(name, tree.size(), stopMs, forced, survivors);
    }

    static void waitUntilDead(Set<ProcessHandle> tree, long limitMs) throws InterruptedException {
        long deadline = System.nanoTime() + limitMs * 1_000_000;
        while (System.nanoTime() < deadline && tree.stream().anyMatch(ProcessHandle::isAlive)) Thread.sleep(20);
    }

    static Process shell(String script) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", script);
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    /** Waits until the tree under a process has at least this many processes. */
    static void awaitTree(Process process, int size) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline && snapshot(process).size() < size) Thread.sleep(20);
    }

    static Outcome shellCase(String name, String script, int size) throws Exception {
        Process process = shell(script);
        awaitTree(process, size);
        return terminate(name, process, Set.of());
    }

    /** The parent exits and leaves a child behind. Only a snapshot taken earlier knows the child. */
    static Outcome orphanCase(boolean remember) throws Exception {
        Process process = shell("sleep 300 & sleep 1; exit 0");
        awaitTree(process, 2);
        Set<ProcessHandle> seen = remember ? snapshot(process) : Set.of();
        Set<ProcessHandle> truth = snapshot(process);
        process.waitFor(5, TimeUnit.SECONDS);
        Outcome outcome = terminate(
            remember ? "parent exited first, child remembered" : "parent exited first, child not remembered", process, seen);
        List<Long> left = truth.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
        // Do not leave the orphan running after the test.
        truth.forEach(ProcessHandle::destroyForcibly);
        return new Outcome(outcome.name(), outcome.tracked(), outcome.stopMs(), outcome.forced(), left);
    }

    static final Pattern VIZ_URL = Pattern.compile("http://127\\.0\\.0\\.1:(\\d{1,5})(?!\\d)");

    static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Outcome brainViz(String entire, String root) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(entire, "brain", "viz", "--no-open", "--port", "0");
        builder.directory(new File(root));
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        builder.redirectErrorStream(true);
        builder.environment().put("NO_COLOR", "1");
        Process process = builder.start();
        int port = -1;
        List<String> lines = new ArrayList<>();
        long deadline = System.nanoTime() + 20_000_000_000L;
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        while (System.nanoTime() < deadline && port < 0) {
            if (!reader.ready()) {
                if (!process.isAlive()) break;
                Thread.sleep(20);
                continue;
            }
            String line = reader.readLine();
            if (line == null) break;
            lines.add(line);
            Matcher matcher = VIZ_URL.matcher(line);
            if (matcher.find()) port = Integer.parseInt(matcher.group(1));
        }
        if (port < 0) {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            System.out.println("brain viz did not report an address. Its output was:");
            lines.forEach(line -> System.out.println("  " + line));
            return new Outcome("entire brain viz (did not start)", 0, 0, 0, List.of());
        }
        System.out.println("brain viz is serving on port " + port + ". Its process tree:");
        for (ProcessHandle handle : snapshot(process)) {
            System.out.println("  pid " + handle.pid() + "  " + handle.info().command().orElse("?"));
        }
        System.out.println("  port open before stop: " + portOpen(port));
        Outcome outcome = terminate("entire brain viz", process, Set.of());
        System.out.println("  port open after stop:  " + portOpen(port));
        return outcome;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ") on "
            + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("destroy() asks politely here: " + ProcessHandle.current().supportsNormalTermination());
        System.out.println();

        List<Outcome> outcomes = new ArrayList<>();
        outcomes.add(shellCase("parent with two children", "sleep 300 & sleep 300 & wait", 3));
        outcomes.add(shellCase("grandchild", "sh -c 'sleep 300 & wait' & wait", 3));
        outcomes.add(shellCase("tree that ignores the polite signal", "trap '' TERM; sleep 300 & wait", 2));
        outcomes.add(orphanCase(false));
        outcomes.add(orphanCase(true));
        if (args.length == 2) outcomes.add(brainViz(args[0], args[1]));

        System.out.println();
        System.out.printf("%-44s %8s %8s %7s  %s%n", "case", "tracked", "stop ms", "forced", "left running");
        boolean clean = true;
        for (Outcome outcome : outcomes) {
            System.out.printf("%-44s %8d %8d %7d  %s%n", outcome.name(), outcome.tracked(), outcome.stopMs(), outcome.forced(),
                outcome.survivors().isEmpty() ? "none" : outcome.survivors().toString());
            if (!outcome.survivors().isEmpty() && !outcome.name().contains("not remembered")) clean = false;
        }
        System.exit(clean ? 0 : 1);
    }
}
