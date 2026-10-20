plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gv = providers.gradleProperty("gv").orElse("154.0.20260622195746").get()
val gva = providers.gradleProperty("gva").orElse("geckoview").get()

android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    namespace = "com.nish.claudemeter"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nish.claudemeter"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        ndk { abiFilters.add("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("org.mozilla.geckoview:$gva:$gv")
}
