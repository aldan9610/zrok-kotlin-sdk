package io.github.aldan9610.zrok.core

/**
 * Constantes del SDK de zrok.
 */
object Zrok {
    /** Endpoint por defecto de la API de zrok v2 (servicio gestionado por NetFoundry). */
    const val DEFAULT_API_ENDPOINT: String = "https://api-v2.zrok.io"

    /**
     * Prefijo de todas las rutas de la API REST de zrok (basePath de la especificación).
     * Los paths generados son relativos ("/share", "/access", ...), por lo que este
     * prefijo se añade al endpoint al construir el cliente.
     */
    const val API_BASE_PATH: String = "/api/v2"

    /**
     * Content-Type que la especificación de zrok declara en consumes/produces.
     * El servidor (go-openapi) valida la cabecera Content-Type de las peticiones con
     * cuerpo contra este valor, así que el SDK la envía siempre en lugar de
     * "application/json".
     */
    const val CONTENT_TYPE: String = "application/zrok.v1+json"

    /** Nombre del esquema de seguridad de la API (apiKey en cabecera `x-token`). */
    const val SECURITY_SCHEME: String = "key"
}
