plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.aldan9610.zrok.android"
    // compileSdk 37: requisito de ziti-android 0.36.0 (minCompileSdk=37 en su
    // aar-metadata) y de okhttp-android 5.5.1. AGP 9.3.1 soporta API 37.
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    // AGP 9 ya no registra SoftwareComponent automáticos: la variante a
    // publicar se declara aquí y el bloque afterEvaluate de más abajo la usa.
    publishing {
        singleVariant("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Built-in Kotlin de AGP 9: no se aplica org.jetbrains.kotlin.android y el
// jvmTarget de Kotlin hereda de targetCompatibility (21).

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

// Publicación Maven del AAR: AGP expone el componente "release" tras evaluar.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "zrok-android"
            }
        }
    }
}
