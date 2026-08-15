plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.jiangdg.ausbc"
  compileSdk {
    version = release(36)
  }

  defaultConfig {
    minSdk = 24
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  api(project(":libuvc"))
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.xlog)
}
