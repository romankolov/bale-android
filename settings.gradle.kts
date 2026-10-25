pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "BaleAndroid"
include(":app", ":core:domain", ":core:protocol", ":core:network", ":core:data", ":core:designsystem", ":feature:auth", ":feature:messaging")
