# Remove app-process Log calls from release artifacts. This cannot control logs emitted by a
# separate PLEOS system-service process; that platform acceptance gate is documented separately.
# Android's uncaught exception/FATAL reporting is outside these methods and remains available.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Keep actionable line numbers in crash reports without retaining source filenames.
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile
