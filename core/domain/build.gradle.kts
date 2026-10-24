plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(libs.coroutines.core)
    api(libs.paging.common)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
