# Media3 session callbacks are reflected over by the platform.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Glance app widgets are instantiated by name from the manifest.
-keep class com.irondigital.spindle.widget.** { *; }

# Room generated implementations.
-keep class com.irondigital.spindle.data.db.** { *; }

# jaudiotagger builds ID3 frame bodies by class name at runtime, so shrinking
# them away would make every tag write fail. It also references desktop image
# classes that only exist off Android; it never reaches them there.
-keep class org.jaudiotagger.** { *; }
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn java.beans.**

# FFmpegKit formats its error messages with an optional helper library that is
# not shipped with it, and its native side calls back into Java by name.
-dontwarn com.arthenica.smartexception.java.Exceptions
-keep class com.arthenica.ffmpegkit.** { *; }
