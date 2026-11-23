plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            artifactId = "zrok-core"
        }
    }
}

// Igual que zrok-api: se compila con el JDK actual emitiendo bytecode Java 21.
// Es el requisito de ziti-jvm 0.36.0 (publicado con bytecode Java 21); en
// Android lo desugara D8, igual que hace con las clases de ziti.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":zrok-api"))
    api(libs.kotlinx.coroutines.core)
    // ziti-jvm: versión 0.36.0 alineada con org.openziti:ziti-android:0.36.0 para
    // que JVM y Android compartan exactamente la misma implementación de ziti.
    implementation(libs.ziti.jvm)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
