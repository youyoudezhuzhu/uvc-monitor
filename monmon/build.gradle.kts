plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.honksoft.monmon"
  compileSdk {
    version = release(36)
  }

  defaultConfig {
    applicationId = "com.youyoudezhuzhu.uvcmonitor"
    minSdk = 24
    targetSdk = 36
    versionCode = 8
    versionName = "2.1.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // 只保留 arm64-v8a，OpenCV/UVC 原生库大幅瘦身
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    viewBinding = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.datastore.preferences)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)

  // Custom
  implementation(project(":libausbc"))
  implementation(libs.openCV)
}
