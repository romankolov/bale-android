import org.gradle.api.file.Directory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("bale.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

class RoomSchemaArgProvider(@get:OutputDirectory val schemaDir: Directory) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("room.schemaLocation=${schemaDir.asFile.path}")
}

ksp {
    arg(RoomSchemaArgProvider(layout.projectDirectory.dir("schemas")))
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.android)
}
