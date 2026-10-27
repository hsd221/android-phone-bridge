plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.github.hsd221.phonebridge.demo"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.hsd221.phonebridge.demo"
        minSdk = 30; targetSdk = 35; versionCode = 1; versionName = "0.1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
