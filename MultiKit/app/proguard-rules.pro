# FFmpegKit (maintained fork keeps the original package)
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }

# PDFBox
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn sun.misc.**

# Commons Compress / XZ / junrar
-dontwarn org.apache.commons.compress.**
-keep class org.tukaani.xz.** { *; }
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**

# dnsjava
-keep class org.xbill.DNS.** { *; }
-dontwarn org.xbill.DNS.**

# ZXing
-keep class com.google.zxing.** { *; }

# ML Kit barcode
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coroutines
-dontwarn kotlinx.coroutines.**