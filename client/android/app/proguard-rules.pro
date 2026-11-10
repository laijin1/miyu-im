# Android onClick 反射绑定（布局中 android:onClick 引用的方法，混淆时必须保留，否则点击闪退）
-keepclassmembers class * extends android.app.Activity {
    public void on*(android.view.View);
}

# okhttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**

# Gson
-keep class com.familychat.app.api.Models { *; }
-keepattributes Signature
-keepattributes *Annotation*

# ── Gson 字段名保护（重要：曾导致登录返回 400）──────────────────────────────
# Gson 默认用「Java 字段名」当 JSON 键名。R8 会把字段名混淆成 a/b/c，键名随之改变，
# 服务端按原键名读取就会反序列化失败，返回【空响应体 400】，客户端只能显示
# "服务器错误: 400"，极难排查。以下规则确保键名与字段名解耦、注解不被裁剪。
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep @com.google.gson.annotations.SerializedName class * { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# api 包内的请求体类（AuthBody / RegBody / ChangePwdBody 等）字段名即接口契约，
# 必须原样保留；类名仍可混淆。字段名保留、类名混淆 = 既安全又不影响体积。
-keepclassmembers,allowobfuscation class com.familychat.app.api.** {
    <fields>;
}
# 模型类名保留：反序列化异常与日志排查都靠类名定位
-keepnames class com.familychat.app.api.**

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# uCrop
-keep class com.yalantis.ucrop** { *; }
-dontwarn com.yalantis.ucrop**
