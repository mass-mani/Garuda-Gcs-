plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "com.garuda.rtsplive"
 compileSdk = 36
 defaultConfig {
  applicationId = "com.garuda.rtsplive"
  // The Skydroid H16 transmitter runs Android 7.1 (API 25).
  minSdk = 25
  targetSdk = 36
  versionCode = 29
  versionName = "1.0.28"
  ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildTypes { release { isMinifyEnabled = false } }
 packaging { jniLibs { useLegacyPackaging = false } }
}
dependencies {
 implementation(files("libs/localrtsp.aar"))
 implementation("androidx.core:core-ktx:1.17.0")
 implementation("androidx.activity:activity-compose:1.11.0")
 implementation(platform("androidx.compose:compose-bom:2025.09.01"))
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui-tooling-preview")
 debugImplementation("androidx.compose.ui:ui-tooling")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
 implementation("androidx.datastore:datastore-preferences:1.1.7")
 implementation("androidx.media3:media3-exoplayer:1.8.0")
 implementation("androidx.media3:media3-exoplayer-rtsp:1.8.0")
 implementation("androidx.media3:media3-ui:1.8.0")
 implementation("dev.ffmpegkit-maintained:ffmpeg-kit-https:8.1.9")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
