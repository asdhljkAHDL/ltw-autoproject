plugins {
    id("com.android.application")
}

android {
    namespace = "com.ltw.autoproject"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.ltw.autoproject"
        minSdk = 33
        targetSdk = 35
        versionCode = 170
        versionName = "1.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/*"
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
