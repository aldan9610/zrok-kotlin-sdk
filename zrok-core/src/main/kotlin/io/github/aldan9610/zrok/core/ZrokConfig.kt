package io.github.aldan9610.zrok.core

/**
 * Configuración del SDK de zrok.
 *
 * @property apiEndpoint URL base de la API de zrok, sin el [Zrok.API_BASE_PATH]
 * (que se añade automáticamente). Por defecto el servicio gestionado
 * [Zrok.DEFAULT_API_ENDPOINT].
 */
data class ZrokConfig(
    val apiEndpoint: String = Zrok.DEFAULT_API_ENDPOINT,
) {
    /** URL completa de la API, incluido el basePath. */
    val apiUrl: String get() = apiEndpoint.trimEnd('/') + Zrok.API_BASE_PATH
}
