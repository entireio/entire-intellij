import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// The IDE side: tool window, trees, editor tabs, actions, and settings. This
// is the only module that may import the IntelliJ Platform.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

val releaseChannel = version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" }

// The built panel pages, from :webview.
val webviewBundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":core"))
    implementation(project(":app"))
    // The panel views and saved panel files read and write JSON. The IDE has
    // the library on its own class path, so the plugin does not ship a copy.
    compileOnly(libs.kotlinx.serialization.json)
    webviewBundle(project(path = ":webview", configuration = "bundleElements"))

    intellijPlatform {
        // The oldest supported IDE. The embedded browser's classes are part of
        // the platform in this version, so they need no dependency of their own.
        intellijIdea("2025.3.1")
        bundledPlugin("Git4Idea")
        pluginVerifier()
        zipSigner()
    }
}

configurations.runtimeClasspath {
    // The IDE supplies the Kotlin standard library, coroutines, and
    // kotlinx-serialization. A second copy inside the plugin breaks the first
    // two, and only adds size for the third.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json-jvm")
    exclude(group = "org.jetbrains", module = "annotations")
}

tasks.processResources {
    from(webviewBundle) {
        into("webview")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 2025.3.1 is the first build with the com.intellij.modules.jcef alias.
            sinceBuild = "253.29346"
            untilBuild = "263.*"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.3.1")
            // The newest release of each version between sinceBuild and untilBuild.
            recommended()
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 0.2.0-eap.1 goes to the eap channel, and 0.2.0 to the default one.
        channels = listOf(releaseChannel)
    }
}
