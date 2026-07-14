plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "expo.modules.kkboxdecoder"
  compileSdk = 34
  defaultConfig { minSdk = 24 }
}

dependencies {
  implementation(project(":expo-modules-core"))
  implementation("com.squareup.okhttp3:okhttp:3.14.9")
}
