rootProject.name = "selfcheck"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://packages.confluent.io/maven/")
    }
}

include("contracts", "knowledge", "simulator", "engine", "api", "frontend")
