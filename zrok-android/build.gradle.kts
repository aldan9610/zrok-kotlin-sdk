plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.aldan9610.zrok.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":zrok-core"))
    api(libs.ziti.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    // ziti-android ya trae lazysodium-android; se excluye la variante de
    // escritorio que llega via ziti-jvm para evitar binarios nativos de Linux/mac/Win.
    configurations.all {
        exclude(group = "com.goterl", module = "lazysodium-java")
    }
}
