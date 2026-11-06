plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.annieplayer.annie_music.kt"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

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

    packaging {
        jniLibs {
            // `libc++_shared.so` 有两个来源：
            //   · 本工程 CMake（NDK 27）的 STL 运行时会自动打包一份
            //   · jniLibs 里那份是 ffmpeg-kit 自带、与 libffmpegkit.so 同一次工具链构建出来的
            // 后者必须优先 —— libc++ 的 ABI 在 NDK 大版本之间不保证兼容，
            // 让旧编的 libffmpegkit.so 去配新 libc++ 会在首次 ffprobe/抽封面时崩。
            pickFirsts += "**/libc++_shared.so"
        }
    }

    // ── Baseline Profile ──────────────────────────────────────────────────────────────
    // 来源：`src/main/baseline-prof.txt`（**手写基线版**，只覆盖启动与首屏路径的类，
    // 外加 MainActivity.onCreate 一个方法级条目）。
    //
    // 只在 **release** 变体生效：AGP 要在 R8 阶段把 profile 里的原始类名 / 方法签名
    // 重写成混淆后的名字，所以 debug 不打包它 —— `binary_art_profile/debug/` 为空属正常，
    // 产物在 `binary_art_profile/release/compileReleaseArtProfile/baseline.prof`，
    // 最终落进 APK 的 `assets/dexopt/baseline.prof`。
    //
    // 为什么是手写：本工程没有 macrobenchmark 模块，拿不到真机热点。手写版的价值集中在
    // 「启动路径的类加载 + 静态初始化」；**不要**往里堆猜测的方法级条目 —— 写错的条目
    // 不报错，但会占掉 AOT 编译预算、把真正热的代码挤掉。要方法级收益就跑
    // Macrobenchmark 重新生成后整体覆盖该文件。

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// ================================================================================================
// Temari（Apple FairPlay 解密库，Rust）→ jniLibs/arm64-v8a/libtemari.so
// ================================================================================================
//
// CMake 里 `temari` 是 **IMPORTED** target（只引用预编译产物），Rust 源码不参与 Gradle 构建。
// 于是这里有一条「改了 .rs 但 APK 里还是旧 .so」的静默失效路径 —— 本任务把源码接回构建图：
// `src/` 或 crate 元数据比产物新时重编，否则 UP-TO-DATE 跳过（Gradle 按内容快照判定）。
//
// ## 三层降级（逻辑在脚本里，Gradle 只负责在 Windows 上调起来）
//   有 cargo                 → 交叉编译
//   没 cargo 但仓库里有 .so  → 跳过 + WARN（用入库产物）
//   两者都没有               → 构建失败，提示装 Rust
//
// ## 产物**随仓库分发**
// `libtemari.so` 是入库的（`git ls-files` 可见），所以克隆下来即使没装 Rust 也能直接编出 APK；
// 装了 Rust 的人改源码后则会自动重编。改 Rust 时记得把新产物一起提交。
//
// ## 为什么命令行写死 powershell
// 本项目的 NDK 路径、脚本本身都是 Windows 语义；跨平台构建不在支持范围。
// 非 Windows 直接跳过（`onlyIf` 是执行期谓词，不影响 configuration cache），
// 用入库的 .so 兜底 —— 构建照样成功。macOS/Linux 想自己重编就手工跑 `build-android.sh`。
val temariDir = rootProject.file("native/temari")
val temariSo = file("src/main/jniLibs/arm64-v8a/libtemari.so")

val buildTemari = tasks.register<Exec>("buildTemari") {
    group = "build"
    description = "交叉编译 native/temari（Rust）→ jniLibs/arm64-v8a/libtemari.so"
    workingDir = temariDir
    inputs.dir(File(temariDir, "src"))
    inputs.files(
        File(temariDir, "Cargo.toml"),
        File(temariDir, "Cargo.lock"),
        File(temariDir, "rust-toolchain.toml"),
        File(temariDir, "build-android.ps1"),
    )
    outputs.file(temariSo)
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        File(temariDir, "build-android.ps1").absolutePath,
    )
}

tasks.named("preBuild") { dependsOn(buildTemari) }

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
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    // 分片/播放列表请求走共享 OkHttp（连接池 + HTTP/2 复用），见 MediaPlayerBackend.httpFactory
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // ── Oboe（AAudio / OpenSL ES 统一封装）────────────────────────────────────────────
    // 用途：**只做「Bit Perfect 独占内核」的可选后端**，由 `AaudioBackend` 开关切换
    //   · RAW  = 手写 AAudio（`native-aaudio-probe.cpp` 的写线程内核）
    //   · OBOE = `native-oboe-stream.cpp` 的回调拉模式 + 环形缓冲
    // 契约层（`AaudioSink` 的位置/结束/待判/isEnded 语义）**完全不变**，
    // 换的只是"谁在往设备里塞样本"。
    //
    // 为什么值得换：
    //   1. 回调拉模式由 HAL 决定何时要数据，不再需要"阻塞写超时 → sleep 重试"那套；
    //   2. `onErrorAfterClose` 能接住设备拔出/被抢占（现有 `onEngineFailure` 正好对接）；
    //   3. API 24/25 走 OpenSL ES 后端 —— 我们的 minSdk 就是 24，这是唯一能在
    //      Android 7 上拿到低延迟轨道的路（AAudio 要 26+）。
    // 明确**不期待**的收益：独占成功率（同一个 HAL profile）、硬件时钟
    //   （`getTimestamp` 在本机 100% 失败，Oboe 也拿不到）。
    //
    // ## 为什么**不是** Gradle 依赖（`com.google.oboe:oboe`）
    //
    // 试过了，AGP 9.1.0 不会把 AAR 里的 `prefab` 包透给 `externalNativeBuild` ——
    // `find_package(oboe REQUIRED CONFIG)` 直接报 `Could not find oboeConfig.cmake`
    // （configure 阶段就死，`oboe_DIR` 从未被注入）。prefab 的自动消费在 AGP 9 上不能用。
    //
    // 于是改成 **vendoring**，与 `libtemari.so` 完全同一套做法（IMPORTED target + jniLibs）：
    //   · 头文件 → `cpp/thirdparty/oboe/include/oboe/`（16 个头，178 KB）
    //   · 库     → `jniLibs/arm64-v8a/liboboe.so`（`llvm-strip --strip-unneeded` 后 320 KB）
    // 来源与版本记录在 `cpp/thirdparty/oboe/README.md`。AAR 本身是从
    // **阿里云 Google 镜像**（`maven.aliyun.com/repository/google`，即 `settings.gradle.kts`
    // 里已配的那个）拉的 `com.google.oboe:oboe:1.11.0`，没有直连 dl.google.com。
    // 这样做的额外好处：换机器/离线也能编，不依赖 Gradle 再去解析一个含 .so 的 AAR。

    // 数据模型序列化（Models.kt 的 @Serializable）；版本由 Kotlin 插件对齐
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // 网络层（对齐 Flutter 端 dio 的角色）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // ffmpeg：**只用它的 Java / JNI 层**（classes.jar + libffmpegkit*.so + 许可证文本），
    // `libav*.so` 换成 `native/ffmpeg/` 里自编的裁剪版。
    //
    // 为什么不再直接依赖上游 AAR：`ffmpeg-kit-audio:8.1.9` 的 libavcodec.so 有 11.09 MB，
    // 里面编进了**完整的视频解码器**（h264/hevc/vp8/vp9/av1/mpeg4/theora）—— 一个音频
    // 播放器永远用不到；再加上白带的 avfilter/swscale/avdevice，单 ABI 有 20.35 MB。
    // 自编版只开实际用到的音频编解码器 + 抽封面用的 scale/mjpeg/image2，见
    // `native/ffmpeg/configure.sh` 的清单。
    //
    // 保留上游 so 的理由：`libffmpegkit.so`（FFmpegKit/FFprobeKit 的 JNI 实现）的
    // DT_NEEDED 与符号版本（`avcodec_open2@LIBAVCODEC_62`）必须与 libav*.so 的 major 对齐，
    // 自己重写这套 JNI 不划算。改动只发生在「libav 那 7 个 so」这一层。
    implementation(files("libs/ffmpeg-kit-audio-8.1.9.jar"))
    // ffmpeg-kit 的**运行时硬依赖**：`FFmpegKitConfig` 的静态初始化会直接引用
    // `com.arthenica.smartexception.java.Exceptions`，缺了它就是
    // `NoClassDefFoundError` → `FFmpegKitConfig.<clinit>` 失败 → 之后**所有**
    // `FFprobeKit.getMediaInformation` / `FFmpegKit.execute` 调用从第一次起就抛异常，
    // 再被各处的 `catch (_: Throwable)` 吞掉，表现成「歌词 / 流媒体音质胶囊静默消失」。
    //
    // 以前是 AAR 依赖，Gradle 会自动带上它；换成 `files(...)` 之后传递依赖就断了，
    // 必须显式声明。（本地 Gradle 缓存里已有 0.2.1，离线也能构建。）
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QuickJS（Kotlin 绑定）：运行「落雪（LX Music）自定义源」插件。
    // 选 dokar3/quickjs-kt 而非 cashapp/quickjs-android：后者不 drain microtask
    // 队列（native 只做 JS_EvalFunction），插件里的 Promise/async 永远 resolve 不了；
    // 本库是协程原生实现（evaluate 为 suspend），能正确驱动异步。
    implementation("io.github.dokar3:quickjs-kt:1.0.15")

    // FLAC 软解：用成熟的 jFLAC（LGPL，纯 Java），不再手写解码器。
    // 只负责把一帧解成 PCM，渲染仍走 Media3 的 SimpleDecoder/Renderer。
    implementation("org.jflac:jflac-codec:1.5.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // AnnieTag（标签编解码）是纯 JVM 代码，单测直接在 PC 上跑字节级往返验证
    // （`gradlew :app:testDebugUnitTest`），不需要真机。
    testImplementation("junit:junit:4.13.2")
}

