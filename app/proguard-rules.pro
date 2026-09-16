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

# ZXing
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
