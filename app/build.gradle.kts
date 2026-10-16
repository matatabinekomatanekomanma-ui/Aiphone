plugins {
    alias(libs.plugins.android.application)
    // AGP 9+ built-in Kotlin — do NOT apply org.jetbrains.kotlin.android
    // Compose は未使用だが、Kotlin のバージョン固定(2.4.10)を保つためプラグインは残している
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.aiphone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "jp.hadakaotoko.aiphone"
        minSdk = 30
        targetSdk = 37
        versionCode = 6
        versionName = "0.2.1"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.okhttp)

    implementation(libs.priv.ui)

    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.hidden.api.bypass)
}
