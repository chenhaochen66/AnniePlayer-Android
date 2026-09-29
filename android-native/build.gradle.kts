// 根构建脚本：仅声明插件 classpath，具体配置在 app 模块
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}
