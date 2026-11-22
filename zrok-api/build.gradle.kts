/*
 * Módulo zrok-api: cliente REST generado automáticamente a partir de
 * specs/zrok.yml con OpenAPI Generator (kotlin / jvm-ktor / kotlinx-serialization).
 *
 * Nota de diseño: la combinación library=jvm-ktor + serializationLibrary=kotlinx_serialization
 * no está soportada de forma nativa por el generador (fuerza jackson internamente).
 * Sin embargo, el flag de plantilla kotlinx_serialization sí se propaga, de modo que los
 * modelos se generan con @Serializable/@SerialName usando las ramas oficiales de las
 * plantillas. El único hueco es que el ApiClient generado instala un ContentNegotiation
 * vacío; la capa zrok-core lo completa inyectando el converter kotlinx-json a través del
 * parámetro httpClientConfig del constructor del ApiClient (sin parchear plantillas).
 *
 * Regenerar: ./gradlew :zrok-api:openApiGenerate
 */

import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

// Se compila con el JDK actual (21) emitiendo bytecode Java 17, consumible
// tanto desde JVM como desde Android (AGP/D8).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // El código generado usa kotlin.io.encoding.Base64 (ExperimentalEncodingApi).
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
    }
}

val generatedDir = layout.buildDirectory.dir("generated")

tasks.named<GenerateTask>("openApiGenerate") {
    generatorName.set("kotlin")
    library.set("jvm-ktor")
    inputSpec.set("$rootDir/specs/zrok.yml")
    outputDir.set(generatedDir.get().asFile.absolutePath)
    packageName.set("io.github.aldan9610.zrok.api")
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "serializationLibrary" to "kotlinx_serialization",
            "collectionType" to "list",
        ),
    )
    cleanupOutput.set(true)
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn("openApiGenerate")
}

dependencies {
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    api(libs.kotlinx.serialization.json)
}
