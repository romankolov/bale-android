plugins { `kotlin-dsl` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    implementation("com.android.tools.build:gradle:8.13.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
}
gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "bale.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
