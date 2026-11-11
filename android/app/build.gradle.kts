plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.hsd221.phonebridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.hsd221.phonebridge"
        minSdk = 30
        targetSdk = 35
        versionCode = 13
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { aidl = true; buildConfig = true }
    val releaseKeystore = providers.environmentVariable("PHONE_BRIDGE_KEYSTORE")
    if (releaseKeystore.isPresent) {
        signingConfigs {
            create("distribution") {
                storeFile = file(releaseKeystore.get())
                storePassword = providers.environmentVariable("PHONE_BRIDGE_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("PHONE_BRIDGE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("PHONE_BRIDGE_KEY_PASSWORD").get()
            }
        }
        buildTypes.getByName("release") { signingConfig = signingConfigs.getByName("distribution") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true; checkReleaseBuilds = true }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
