# WebView JS interfaces
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class io.github.lijiahua1024.dsnative.ui.session.** {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.lijiahua1024.dsnative.**$$serializer { *; }
-keepclassmembers class io.github.lijiahua1024.dsnative.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.lijiahua1024.dsnative.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keep class <1> {
    static <1>$Companion Companion;
}

# OkHttp & CommonMark optional references
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.commonmark.**
