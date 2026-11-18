package io.entire.spikes

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spike S5: what does the IDE do with a raw transcript of 178 MB?
 *
 * One measured transcript was that size. The plugin streams it to a read-only
 * file and opens the file, so this does the same with made-up lines.
 */
object LargeFileSpike {
    private const val SIZE = 178L * 1024 * 1024

    suspend fun run(project: Project, report: Report) {
        val directory = Path.of(System.getProperty("entire.spike.out")).parent.resolve("large")
        Files.createDirectories(directory)
        val path = directory.resolve("transcript.jsonl")
        val line = ("{\"type\":\"assistant\",\"content\":\"" + "x".repeat(1000) + "\"}\n").toByteArray()
        Files.newOutputStream(path).use { out ->
            var written = 0L
            while (written < SIZE) {
                out.write(line)
                written += line.size
            }
        }
        path.toFile().setWritable(false)
        try {
            val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) { "the IDE did not find $path" }
            val started = System.nanoTime()
            val editors = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFile(file, false) }
            val elapsed = elapsedMs(started)
            val text = editors.filterIsInstance<TextEditor>().firstOrNull()
            val shown = withContext(Dispatchers.EDT) { text?.editor?.document?.textLength }
            report.check(
                "S5.opensWithoutError",
                editors.isNotEmpty(),
                "${file.length} bytes in $elapsed ms, as ${editors.joinToString { it.javaClass.simpleName }}",
            )
            report.info(
                "S5.whatTheEditorHolds",
                "document has $shown of ${file.length} chars, document writable ${text?.editor?.document?.isWritable}, " +
                    "file writable ${file.isWritable}",
            )
            withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).closeFile(file) }
        } finally {
            path.toFile().setWritable(true)
            Files.deleteIfExists(path)
        }
    }
}
