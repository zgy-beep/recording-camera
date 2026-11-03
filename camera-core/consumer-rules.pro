# Keep the public API surface of the camera core when a consumer enables R8.
-keep public class com.recordingcamera.core.** { public *; }
-keep public interface com.recordingcamera.core.** { *; }

# CameraX ships its own consumer rules; these are the reflective bits it relies on.
-dontwarn androidx.camera.**
