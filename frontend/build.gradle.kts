// The frontend is built with npm (see package.json); these tasks let Gradle drive it when Node is installed.
val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "frontend"
    commandLine(npm, "ci")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
}

val npmBuild by tasks.registering(Exec::class) {
    group = "frontend"
    dependsOn(npmInstall)
    commandLine(npm, "run", "build")
    inputs.dir("src")
    inputs.files("index.html", "vite.config.ts", "tsconfig.json", "package-lock.json")
    outputs.dir("dist")
}
