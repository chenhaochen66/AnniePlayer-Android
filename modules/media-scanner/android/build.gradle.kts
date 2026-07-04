plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "expo.modules.mediascanner"
  compileSdk = 34
  defaultConfig { minSdk = 24 }
}

dependencies {
  implementation(project(":expo-modules-core"))
}
