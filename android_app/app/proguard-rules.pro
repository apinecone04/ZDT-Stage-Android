# Keep models for kotlinx.serialization
-keepclassmembers class * {
    companion *;
}
-keepattributes *Annotation*,InnerClasses
-keepclassmembers class **$$serializer {
    *;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Keep usb-serial-for-android
-keep class com.hoho.android.usbserial.** { *; }
