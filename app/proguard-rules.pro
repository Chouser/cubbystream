# Keep ExoPlayer
-keep class androidx.media3.** { *; }
# Keep Gson model classes
-keep class us.chouser.cubbystream.StreamItem { *; }
-keep class us.chouser.cubbystream.StreamFeed { *; }
# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
