import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// What the tests of the other modules share: fixture repositories, the runner
// for the fake Entire CLI, and its scenarios. Nothing here ships in the plugin.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
}
