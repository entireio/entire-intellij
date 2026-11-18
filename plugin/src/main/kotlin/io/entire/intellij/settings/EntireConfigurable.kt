package io.entire.intellij.settings

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.entire.core.cli.configuredCliFile
import io.entire.core.cli.isExecutableFile
import io.entire.core.web.webBaseOf
import io.entire.intellij.EntireBundle
import io.entire.intellij.EntireService

/**
 * The plugin's page in the IDE's settings, under Tools.
 *
 * A value is checked here by the rule the plugin reads it by, so the page
 * accepts what the plugin would use and questions what it would not.
 */
class EntireConfigurable : BoundConfigurable(EntireBundle.message("settings.title")) {
    override fun createPanel(): DialogPanel {
        val settings = EntireSettings.getInstance()
        return panel {
            row(EntireBundle.message("settings.cliPath")) {
                textFieldWithBrowseButton(
                    fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                        .withTitle(EntireBundle.message("settings.cliPath.choose")),
                )
                    .bindText({ settings.cliPath.orEmpty() }, { settings.cliPath = it })
                    // The field takes the width the dialog has to give.
                    .resizableColumn()
                    .align(AlignX.FILL)
                    .comment(EntireBundle.message("settings.cliPath.comment"), COMMENT_COLUMNS)
                    // A warning and not an error: the file may be on a disk that is not attached now.
                    .validationOnInput { field ->
                        if (pointsNowhere(field.text)) warning(EntireBundle.message("settings.cliPath.notFound")) else null
                    }
            }
            row(EntireBundle.message("settings.webBaseUrl")) {
                textField()
                    .bindText({ settings.webBaseUrl.orEmpty() }, { settings.webBaseUrl = it })
                    .resizableColumn()
                    .align(AlignX.FILL)
                    .comment(EntireBundle.message("settings.webBaseUrl.comment"), COMMENT_COLUMNS)
                    .validationOnInput { field ->
                        if (notAnAddress(field.text)) error(EntireBundle.message("settings.webBaseUrl.invalid")) else null
                    }
                    .validationOnApply { field ->
                        if (notAnAddress(field.text)) error(EntireBundle.message("settings.webBaseUrl.invalid")) else null
                    }
            }
            row {
                checkBox(EntireBundle.message("settings.loadOnScroll"))
                    .bindSelected({ settings.loadOnScroll }, { settings.loadOnScroll = it })
                    .comment(EntireBundle.message("settings.loadOnScroll.comment"), COMMENT_COLUMNS)
            }
            row {
                checkBox(EntireBundle.message("settings.trails"))
                    .bindSelected({ settings.trailsEnabled }, { settings.trailsEnabled = it })
                    .comment(EntireBundle.message("settings.trails.comment"), COMMENT_COLUMNS)
            }
        }
    }

    override fun reset() {
        super.reset()
        // A path that was already wrong is questioned as the page opens, and not only once it is edited.
        val panel = createComponent() as? DialogPanel ?: return
        for (field in panel.validationsOnInput.keys) ComponentValidator.getInstance(field).ifPresent { it.revalidate() }
    }

    override fun apply() {
        // A value the plugin would ignore is not stored. The IDE shows the reason and keeps the page open.
        val refused = (createComponent() as? DialogPanel)?.validateAll()?.firstOrNull { !it.warning }
        if (refused != null) throw ConfigurationException(refused.message)

        val settings = EntireSettings.getInstance()
        val path = settings.cliPath
        val trails = settings.trailsEnabled
        super.apply()
        // A project that has shown nothing of Entire yet has nothing to read again.
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val service = project.serviceIfCreated<EntireService>() ?: continue
            // Each open project looks for the CLI again.
            if (settings.cliPath != path) service.settingsChanged()
            // The Trails tab comes or goes, and an open trail says that trails are off or reads its trail.
            if (settings.trailsEnabled != trails) service.trailsSettingChanged()
        }
    }

    companion object {
        /**
         * How wide a comment under a field is, in characters. A page is as
         * wide as its widest row, whatever the dialog can show, and a comment
         * of the usual 70 characters beside a label is wider than the Settings
         * dialog at the size it first opens with. The end of each field would
         * be out of sight.
         */
        private const val COMMENT_COLUMNS = 50

        /** Whether a path is set and no file that can be run is there. */
        fun pointsNowhere(text: String): Boolean {
            if (text.isBlank()) return false
            val file = configuredCliFile(text, System.getProperty("user.home"))
            return file == null || !isExecutableFile(file)
        }

        /** Whether text is set and is not an address the plugin would build links from. */
        fun notAnAddress(text: String): Boolean = text.isNotBlank() && webBaseOf(text) == null
    }
}
