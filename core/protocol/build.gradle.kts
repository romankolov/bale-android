import com.google.protobuf.gradle.*

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}
kotlin { jvmToolchain(17) }
dependencies { api(libs.protobuf.lite) }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks { all().configureEach { builtins { named("java") { option("lite") } } } }
}
