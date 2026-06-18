import java.net.URL
import java.net.HttpURLConnection

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.hilt)
  alias(libs.plugins.chaquopy)
}

android {
  namespace = "com.example"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.aistudio.puredownload.kdwpa"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // We manually limit build ABIs to ARM architectures (arm64-v8a and armeabi-v7a)
    // and exclude x86/x86_64 to significantly minimize APK size, since almost all
    // active user physical devices are ARM based.
    ndk {
      abiFilters.clear()
      abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }

  packaging {
    resources {
      excludes += setOf(
        "META-INF/*.kotlin_module",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
        "**/libjsc.so"
      )
    }
  }
}

// Chaquopy plugin configuration
chaquopy {
  defaultConfig {
    version = "3.10"
    pip {
      install("yt-dlp==2026.6.9")
      install("certifi==2025.1.31")
      install("brotli==1.0.7")
      install("websockets==14.1")
    }
  }
}

dependencies {
  // Compose BOM (Bill of Materials)
  implementation(platform(libs.compose.bom))

  // Platform dependencies
  implementation(libs.core.ktx)
  implementation(libs.activity.compose)
  implementation(libs.lifecycle.runtime.ktx)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.lifecycle.viewmodel.compose)

  // Jetpack Compose components
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons.core)
  implementation(libs.compose.material.icons.extended)

  // Jetpack Navigation Compose (Requested)
  implementation(libs.navigation.compose)

  // Dependency Injection: Hilt (Requested)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.hilt.navigation.compose)

  // Local Persistence: Room (Requested)
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  // Background Task Engine: WorkManager (Requested)
  implementation(libs.work.runtime)

  // Image Loading: Coil (Requested)
  implementation(libs.coil.compose)

  // DataStore Preferences (Requested)
  implementation(libs.datastore.preferences)

  // DocumentFile support for SAF
  implementation("androidx.documentfile:documentfile:1.0.1")

  // Unit testing
  testImplementation(libs.junit)
  testImplementation(libs.androidx.junit)
}

/*
tasks.register("downloadFFmpeg") {
    val destFile = file("src/main/jniLibs/arm64-v8a/libffmpeg.so")
    inputs.property("url", "https://raw.githubusercontent.com/hzw1199/Android-FFmpeg-Prebuilt/main/ffmpeg-8.0.1/bin/ffmpeg")
    outputs.file(destFile)

    doLast {
        if (destFile.exists() && destFile.length() > 1000000) {
            println("FFmpeg binary already exists (size: ${destFile.length()} bytes). Skipping download.")
            return@doLast
        }
        if (!destFile.parentFile.exists()) {
            destFile.parentFile.mkdirs()
        }
        println("Downloading FFmpeg binary for arm64-v8a...")
        try {
            val url = URL("https://raw.githubusercontent.com/hzw1199/Android-FFmpeg-Prebuilt/main/ffmpeg-8.0.1/bin/ffmpeg")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            println("FFmpeg binary downloaded successfully to ${destFile.absolutePath}")
        } catch (e: Exception) {
            throw GradleException("Failed to download FFmpeg binary: ${e.message}", e)
        }
    }
}

tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("NativeLibs")) ||
        (name.startsWith("merge") && name.contains("JniLib"))) {
        dependsOn("downloadFFmpeg")
    }
}
*/

