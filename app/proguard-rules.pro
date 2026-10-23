# protobuf-javalite uses generated field metadata for serialization.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
