# 다온: 쓰지 않는 코드만 덜어 내고, 리플렉션·JNI로 불리는 것은 그대로 둔다
-dontobfuscate

# 우리 앱 (Gleo 신호 받는 곳, 서비스, 서버 흉내 등 시스템이 이름으로 부른다)
-keep class ai.gilwi.anbu.** { *; }

# Pleos Playground SDK (차 신호 · 내비 · TTS · STT). 안에서 리플렉션·AIDL·직렬화를 쓴다
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# 지도 엔진: 네이티브(libmaplibre.so)가 자바 클래스를 이름으로 찾는다
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# JNA (네이티브 음성 엔진이 쓴다)
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**

# Gson · kotlinx.serialization 모델
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class * implements com.google.gson.TypeAdapterFactory
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }

-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
