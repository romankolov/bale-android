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
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.android)
}
