# libsecp256k1 JNI bridge: native method names must survive.
-keep class fr.acinq.secp256k1.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.pocketprl.**$$serializer { *; }
-keepclassmembers class dev.pocketprl.** { *** Companion; }
-keepclasseswithmembers class dev.pocketprl.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Conscrypt optional bits
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ZXing: the app only scans and generates QR codes. Keep the QR path and the
# journeyapps scanner; let R8 shrink the unused barcode formats (PDF417,
# DataMatrix, Aztec, the 1D/RSS family, MaxiCode) instead of pinning all of them.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.qrcode.** { *; }
-keep class com.google.zxing.client.result.** { *; }
-dontwarn com.google.zxing.**

# Strip logging in release: less dex, no logcat noise, and no chance of a wallet
# name or address leaking through Log on a shared device.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
