# protobuf-javalite uses generated field metadata for serialization.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class ai.bale.client.protocol.** { *; }

# Generated Retrofit interfaces are resolved reflectively by method annotations.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# Bot Mini-Apps call these methods by name from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
