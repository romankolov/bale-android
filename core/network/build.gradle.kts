plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:domain"))
    api(project(":core:protocol"))
    implementation(libs.coroutines.core)
    api(libs.okhttp)
    api(libs.retrofit)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}
