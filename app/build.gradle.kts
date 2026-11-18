import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Repository state, row building, refresh, and the panel controllers. Like
// :core, this module has no IntelliJ Platform dependency. It reaches the IDE
// through the interfaces in its ports package, which :plugin implements.
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
    // The IDE supplies these three when the plugin runs. The panel controllers
    // build the JSON messages they post to the panel pages.
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)

    testImplementation(project(":testkit"))
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
