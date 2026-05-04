# Keep ExoPlayer
-keep class androidx.media3.** { *; }
# Keep Gson model classes
-keep class com.baseballstream.StreamItem { *; }
-keep class com.baseballstream.StreamFeed { *; }
# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
