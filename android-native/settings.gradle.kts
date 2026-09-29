// 独立 Gradle 项目设置（原生 Kotlin/Jetpack Compose 重写，与 Flutter 工程完全解耦）
pluginManagement {
    repositories {
        // 国内镜像优先（直连下载在本机会超时）
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/central")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    // kotlinx.serialization 编译器插件（版本必须与 Kotlin 一致）
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
}

rootProject.name = "annie-music-kt"
include(":app")
