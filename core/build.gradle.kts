import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Git and Entire CLI logic. This module has no IntelliJ Platform dependency,
// so an import of an IDE class does not compile here.
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
    // The IDE supplies these three when the plugin runs, so the plugin does not ship them.
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// A program that prints what the plugin would know about a real repository.
// scripts/parity.sh compares its output with what entire-vscode's own code reads.
val smoke: SourceSet = sourceSets.create("smoke") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
}

tasks.register<JavaExec>("smoke") {
    description = "Prints what the plugin would know about a repository: ./gradlew :core:smoke --args=<repository>"
    classpath = smoke.runtimeClasspath
    mainClass = "io.entire.core.smoke.SmokeKt"
}
