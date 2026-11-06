# ── R8 / ProGuard 规则（release 裁剪） ──
# 目标：在保留 kotlinx.serialization / ffmpeg(JNI) / Media3 等必要符号的前提下尽量瘦身。

# 保留注解与内部类（kotlinx.serialization、reflection 元数据需要）
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations, EnclosingMethod

# ── kotlinx.serialization ──
# 官方插件会生成 $serializer；保守起见保留 model 包的 Companion 与 serializer
-keepclassmembers class com.annieplayer.annie_music.kt.data.model.** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class com.annieplayer.annie_music.kt.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── ffmpeg-kit（native JNI，类名/方法名不能被混淆） ──
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.ffmpegkit.*$* { *; }
-dontwarn com.arthenica.ffmpegkit.**
# native 方法所在类整体保留
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── TagLib（libtaglib.so，纯靠符号名绑定） ──
# so 里导出的是 Java_com_lonx_audiotag_TagLib_*，且 native 侧用 FindClass/GetMethodID
# 按全限定名构造 model 对象 —— 类名、方法名、字段名一个都不能被 R8 改。
-keep class com.lonx.audiotag.TagLib { *; }
-keep class com.lonx.audiotag.model.AudioProperties { *; }
-keep class com.lonx.audiotag.model.Picture { *; }
-keep class com.lonx.audiotag.model.Metadata { *; }

# ── 常见可选依赖缺失导致的告警（OkHttp/Conscrypt/BouncyCastle 等） ──
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ── 保留行号，便于线上崩溃定位 ──
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
