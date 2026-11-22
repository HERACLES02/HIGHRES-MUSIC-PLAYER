# JNI exports use these exact class and method names in native-tags/jni.cpp.
-keep class com.localmusic.player.NativeTags { *; }
-keep class com.localmusic.usb.NativeDirect { native <methods>; }

# Rust and libFLAC resolve these methods by their JNI names.
-keep class com.localmusic.player.SoulseekFixture {
    native <methods>;
}
