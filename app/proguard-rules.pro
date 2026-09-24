# R8 rules for release builds. The libraries in use (Compose, AndroidX, OkHttp, kotlinx) ship their own rules;
# add app-specific ones here only when a release build shows they're needed.

# Keep line numbers in crash reports, with obfuscated file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
