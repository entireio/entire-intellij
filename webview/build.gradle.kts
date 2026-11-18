// The panel pages. npm builds and tests them, and these tasks run npm, so
// Node 22.18 or later has to be on the PATH.
plugins {
    base
}

val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val npmCi by tasks.registering(Exec::class) {
    description = "Installs the npm dependencies of the panel pages from the lock file."
    inputs.files("package.json", "package-lock.json")
    outputs.file("node_modules/.package-lock.json")
    commandLine(npm, "ci", "--no-audit", "--no-fund")
}

val bundle by tasks.registering(Exec::class) {
    description = "Builds panel.js, panel.css, shim.js, and the list of theme variables into dist."
    dependsOn(npmCi)
    inputs.dir("src")
    inputs.files("esbuild.mjs", "package.json", "package-lock.json", "tsconfig.json")
    outputs.dir("dist")
    commandLine(npm, "run", "bundle")
}

val webviewCheck by tasks.registering(Exec::class) {
    description = "Type-checks the panel pages and runs their tests."
    dependsOn(npmCi)
    inputs.dir("src")
    inputs.dir("test")
    inputs.files("package.json", "package-lock.json", "tsconfig.json", "vitest.config.mts")
    commandLine(npm, "run", "check")
}

tasks.named("check") {
    dependsOn(webviewCheck)
}

tasks.named<Delete>("clean") {
    delete("dist")
}

// What :plugin puts into its resources.
val bundleElements: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(bundleElements.name, layout.projectDirectory.dir("dist")) {
        builtBy(bundle)
    }
}
