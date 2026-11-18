package io.entire.spikes

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.scale.JBUIScale
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Registered only by the optional descriptor, so it exists only when the IDE has the embedded browser. */
class JcefAvailable

/** One line for each finding, written as it is found, so that a run that hangs still leaves a report. */
class Report(private val file: Path) {
    @Synchronized
    private fun line(status: String, key: String, detail: String) {
        val text = "$status $key" + (if (detail.isEmpty()) "" else ": " + detail.replace('\n', ' ')) + "\n"
        Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun pass(key: String, detail: String = "") = line("PASS", key, detail)

    fun fail(key: String, detail: String = "") = line("FAIL", key, detail)

    fun info(key: String, detail: String = "") = line("INFO", key, detail)

    fun check(key: String, ok: Boolean, detail: String = "") = if (ok) pass(key, detail) else fail(key, detail)
}

fun elapsedMs(sinceNanos: Long): Long = (System.nanoTime() - sinceNanos) / 1_000_000

/**
 * Runs the spikes when the IDE starts with `-Dentire.spike.out=<file>`, writes
 * what it finds to that file, and closes the IDE. Without the property it does
 * nothing.
 *
 * Phase 1 runs everything and leaves a panel tab open. Phase 2 is a second
 * start of the same project, and checks that the tab came back.
 */
class SpikeActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val inspect = System.getProperty("entire.spike.inspect")
        val screenshot = System.getProperty("entire.spike.screenshot")
        if (inspect != null || screenshot != null) {
            val report = inspect?.let { Report(Path.of(it)) }
            if (report != null) step(report, "inspect") { PluginInspection.run(project, report, Path.of(inspect).resolveSibling("settings.png")) }
            if (screenshot != null) showPlugin(project, Path.of(screenshot))
            report?.info("done", "inspect")
            if (System.getProperty("entire.spike.exit") == "true") exitIde()
            return
        }
        val out = System.getProperty("entire.spike.out") ?: return
        val phase = System.getProperty("entire.spike.phase", "1")
        val report = Report(Path.of(out))
        coroutineScope {
            val watchdog = launch {
                delay(240_000)
                report.fail("watchdog", "the spikes did not finish in 240 s")
                exitIde()
            }
            try {
                step(report, "platform") { PlatformChecks.run(project, report) }
                if (phase == "1") {
                    step(report, "S1") { JcefSpike(project, report).run() }
                    step(report, "S5") { LargeFileSpike.run(project, report) }
                    step(report, "S2") { SlotFileSpike.openAndRetarget(project, report) }
                } else {
                    step(report, "S2") { SlotFileSpike.checkRestored(project, report) }
                }
            } finally {
                watchdog.cancel()
                report.info("done", "phase $phase")
                exitIde()
            }
        }
    }

    /**
     * Opens the Entire tool window and has the IDE paint its own window into a
     * picture. The IDE stays open. Started with `-Dentire.spike.screenshot=<file>`.
     */
    private suspend fun showPlugin(project: Project, picture: Path) {
        val toolWindow = withContext(Dispatchers.EDT) { ToolWindowManager.getInstance(project).getToolWindow("Entire") } ?: return
        val shown = CompletableDeferred<Unit>()
        withContext(Dispatchers.EDT) { toolWindow.activate { shown.complete(Unit) } }
        withTimeoutOrNull(10_000) { shown.await() }
        // Give the window time to lay out and draw before it is painted again into the picture.
        delay(2_000)
        withContext(Dispatchers.EDT) {
            val root = WindowManager.getInstance().getFrame(project)?.rootPane ?: return@withContext
            val scale = JBUIScale.sysScale(root).toDouble()
            val image = BufferedImage((root.width * scale).toInt(), (root.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.scale(scale, scale)
            root.paint(graphics)
            graphics.dispose()
            ImageIO.write(image, "png", picture.toFile())
        }
    }

    private suspend fun step(report: Report, name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            report.fail("$name.exception", "$t at ${t.stackTrace.take(4).joinToString(" < ")}")
        }
    }

    private fun exitIde() {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManagerEx.getApplicationEx().exit(true, true)
        }
    }
}
