# Media3 session callbacks are reflected over by the platform.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Glance app widgets are instantiated by name from the manifest.
-keep class com.irondigital.spindle.widget.** { *; }

# Room generated implementations.
-keep class com.irondigital.spindle.data.db.** { *; }
