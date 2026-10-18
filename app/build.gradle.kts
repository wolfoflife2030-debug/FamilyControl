plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.family.netcontrol"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.netcontrol"
        minSdk = 24
        targetSdk = 28   // يبقي setWifiEnabled و BluetoothAdapter.enable()/disable() فعّالة
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
