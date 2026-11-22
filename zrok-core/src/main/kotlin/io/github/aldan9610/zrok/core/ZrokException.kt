package io.github.aldan9610.zrok.core

/**
 * Error devuelto por la API REST de zrok.
 *
 * @property statusCode código HTTP de la respuesta (401, 404, 409, 422, 500, ...).
 * @property statusText texto de estado HTTP.
 * @property errorBody cuerpo de la respuesta de error, si se pudo leer (por ejemplo
 * `{"code":401,"message":"invalid api key"}`).
 */
class ZrokApiException(
    val statusCode: Int,
    val statusText: String = "",
    val errorBody: String? = null,
) : RuntimeException(
    buildString {
        append("zrok API error")
        if (statusCode > 0) append(" (HTTP ").append(statusCode)
        if (statusText.isNotBlank()) append(' ').append(statusText)
        if (statusCode > 0) append(')')
        if (!errorBody.isNullOrBlank()) append(": ").append(errorBody)
    },
)

/** El entorno no está habilitado: falta llamar a [ZrokEnvironment.enable] o [ZrokEnvironment.load]. */
class ZrokNotEnabledException(message: String = "el entorno zrok no está habilitado") : IllegalStateException(message)

/** El estado persistido no es válido (p. ej. falta el token de cuenta o la identidad ziti). */
class ZrokInvalidStateException(message: String) : IllegalStateException(message)
