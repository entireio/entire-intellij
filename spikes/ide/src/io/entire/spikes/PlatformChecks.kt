package io.entire.spikes

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.EnvironmentUtil
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Does the real plugin load in this IDE, and are the pieces the design leans on where it expects them? */
object PlatformChecks {
    suspend fun run(project: Project, report: Report) {
        val info = ApplicationInfo.getInstance()
        report.info("ide", "${info.fullApplicationName}, build ${info.build.asString()}")
        report.info("runtime", "${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
        report.info("kotlin.stdlib", KotlinVersion.CURRENT.toString())

        val entire = PluginManagerCore.getPlugin(PluginId.getId("io.entire.explorer"))
        report.check(
            "plugin.loaded",
            entire != null && entire.isEnabled && entire.pluginClassLoader != null,
            "version ${entire?.version}, enabled ${entire?.isEnabled}",
        )

        val jcef = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.modules.jcef"))
        report.info("jcef.plugin", if (jcef == null) "no plugin has this ID" else "${jcef.name} ${jcef.version}, enabled ${jcef.isEnabled}")
        report.check(
            "jcef.optionalDescriptorLoaded",
            ApplicationManager.getApplication().getService(JcefAvailable::class.java) != null,
        )

        val toolWindow = withContext(Dispatchers.EDT) { ToolWindowManager.getInstance(project).getToolWindow("Entire") }
        report.check("toolWindow.registered", toolWindow != null, "anchor ${toolWindow?.anchor}, has icon ${toolWindow?.icon != null}")
        if (toolWindow != null) {
            val shown = CompletableDeferred<Unit>()
            withContext(Dispatchers.EDT) { toolWindow.show { shown.complete(Unit) } }
            withTimeoutOrNull(10_000) { shown.await() }
            val state = withContext(Dispatchers.EDT) {
                val component = toolWindow.contentManager.contents.firstOrNull()?.component
                val text = (component as? JBPanelWithEmptyText)?.emptyText?.text
                "contents ${toolWindow.contentManager.contentCount}, visible ${toolWindow.isVisible}, empty text \"$text\""
            }
            report.check("toolWindow.content", state.startsWith("contents 1, visible true"), state)
            withContext(Dispatchers.EDT) { toolWindow.hide() }
        }

        // The spike is compiled against the copy of kotlinx-serialization-json that the IDE bundles, and carries none.
        val parsed = Json.parseToJsonElement("""{"a":[1,true,null,"x"]}""")
        report.check(
            "serialization.fromPlatform",
            parsed.jsonObject["a"]?.jsonArray?.size == 4,
            "loaded by ${Json::class.java.classLoader}",
        )

        report.info("git.path", GitExecutableManager.getInstance().getPathToGit(project))
        var repositories = GitRepositoryManager.getInstance(project).repositories
        withTimeoutOrNull(15_000) {
            while (repositories.isEmpty()) {
                delay(250)
                repositories = GitRepositoryManager.getInstance(project).repositories
            }
        }
        report.check(
            "git.repositoryFound",
            repositories.size == 1,
            repositories.joinToString { "${it.root.path} on ${it.currentBranchName}" },
        )

        report.info("trust.isProjectTrusted", TrustedProjects.isProjectTrusted(project).toString())
        val path = EnvironmentUtil.getEnvironmentMap()["PATH"].orEmpty()
        report.info("environment.PATH", "${path.split(':').size} entries, has ~/.local/bin ${path.contains("/.local/bin")}")
    }
}
