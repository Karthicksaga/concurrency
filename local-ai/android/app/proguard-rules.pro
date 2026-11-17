# JNI: native methods and the callback interface invoked from C++ (la_jni.cpp) by name.
-keep class com.localai.app.runtime.inference.LlamaNative { *; }
-keep interface com.localai.app.runtime.inference.LlamaNative$Callback { *; }
-keep class * implements com.localai.app.runtime.inference.LlamaNative$Callback { *; }
-keepclasseswithmembernames class * { native <methods>; }

# pdfbox-android: optional JPEG2000 / crypto dependencies are not used (text extraction only).
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
