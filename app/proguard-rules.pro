# Pixel10 AI API Server - ProGuard Rules

# Keep NanoHTTPD server
-keep class fi.iki.elonen.** { *; }

# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }

# Keep Gson serialization models
-keep class com.pixel10.ai.server.** { *; }
