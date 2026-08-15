plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.jiangdg.libuvc"
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
  implementation(libs.androidx.appcompat)
  implementation(libs.xlog)
}
