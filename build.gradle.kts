plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.openapi.generator) apply false
}

// Coordenadas Maven de la librería. En JitPack el grupo se sobrescribe
// automáticamente (com.github.aldan9610.zrok-kotlin-sdk), pero se publican
// con este grupo cuando se consuma desde Maven Central u otro repositorio.
allprojects {
    group = "io.github.aldan9610.zrok"
    version = "0.2.0"
}
