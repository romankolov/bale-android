plugins {
    id("bale.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    api(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
}
