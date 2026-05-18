tasks.register("build") {
    group = "build"
    description = "Builds all Gradle services."
    dependsOn(
        gradle.includedBuild("coordinator").task(":build"),
        gradle.includedBuild("agent").task(":build"),
    )
}

tasks.register("clean") {
    group = "build"
    description = "Cleans all Gradle services."
    dependsOn(
        gradle.includedBuild("coordinator").task(":clean"),
        gradle.includedBuild("agent").task(":clean"),
    )
}
