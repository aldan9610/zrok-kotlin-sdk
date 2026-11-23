plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.aldan9610.zrok.sample"
    // compileSdk 37: requisito de ziti-android 0.36.0 (minCompileSdk=37) y de
    // okhttp-android 5.5.1. AGP 9.3.1 soporta API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.aldan9610.zrok.sample"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Built-in Kotlin de AGP 9: no se aplica org.jetbrains.kotlin.android y el
// jvmTarget de Kotlin hereda de targetCompatibility (21).

dependencies {
    implementation(project(":zrok-android"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
}
