plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.annieplayer.annie_music.kt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.annieplayer.annie_music.kt"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // 与 Flutter 工程一致：仅打包 arm64-v8a
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // 正式版开启代码/资源裁剪（R8）；debug 保持关闭便于调试。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // 说明：镜像上最新稳定 BOM 2026.09.00（Compose 1.12.1）要求 compileSdk 37，
    // 与本项目强制的 compileSdk 36 冲突；2026.06.01（Compose 1.11.4 / material3 1.4.0）
    // 是仍兼容 compileSdk 36 的最新稳定 BOM。
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 图标：material3 不含 Icons，需显式加 core 图标集（扩展图标集按需再加）
    implementation("androidx.compose.material:material-icons-core")
    // 网络图片加载（对应 Flutter Image.network 的磁盘/内存缓存）
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Media3：播放引擎 + 媒体会话（替代 Flutter 的 just_audio + audio_service）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // 数据模型序列化（Models.kt 的 @Serializable）；版本由 Kotlin 插件对齐
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // 网络层（对齐 Flutter 端 dio 的角色）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // ffmpeg（社区维护分叉，同 com.arthenica.ffmpegkit API）：写元数据（封面 attached_pic +
    // 歌词 + 曲目号 + 年份），并支持音频转码（audio 档含 libmp3lame/AAC/opus/vorbis 编码器）。
    // 覆盖 mp3/flac/m4a 等全部封装；替代此前手写的 ID3v2.4 / Vorbis 写入器。
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QuickJS（Kotlin 绑定）：运行「落雪（LX Music）自定义源」插件。
    // 选 dokar3/quickjs-kt 而非 cashapp/quickjs-android：后者不 drain microtask
    // 队列（native 只做 JS_EvalFunction），插件里的 Promise/async 永远 resolve 不了；
    // 本库是协程原生实现（evaluate 为 suspend），能正确驱动异步。
    implementation("io.github.dokar3:quickjs-kt:1.0.15")

    debugImplementation("androidx.compose.ui:ui-tooling")
}