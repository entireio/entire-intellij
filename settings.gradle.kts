import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "entire-intellij"

pluginManagement {
    plugins {
        // The compiler is newer than the language level the modules compile at.
        // Each module pins that level to what the oldest supported IDE bundles.
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
    }
}

plugins {
    // Downloads the JDK that the modules ask for when the machine lacks it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include(":core", ":app", ":testkit", ":plugin", ":webview")
