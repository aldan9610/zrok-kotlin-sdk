package io.github.aldan9610.zrok.core.state

import kotlinx.serialization.Serializable

/**
 * Estado persistido de un entorno zrok habilitado. Es el equivalente en Kotlin del
 * "root environment" del SDK de Go (`~/.zrok`): el token de cuenta, la identidad
 * ziti del entorno y el JSON de identidad de OpenZiti que devuelve `POST /enable`.
 */
@Serializable
data class EnvironmentState(
    /** Token de cuenta (via header `x-token` en todas las llamadas). */
    val accountToken: String,
    /** Identidad ziti del entorno (`identity` devuelta por /enable; `envZId` en las llamadas). */
    val zId: String,
    /** JSON de identidad de OpenZiti (`cfg` devuelta por /enable): abre el contexto ziti del túnel. */
    val zitiConfigJson: String,
    /** Endpoint de la API con el que se habilitó el entorno. */
    val apiEndpoint: String,
    /** Descripción del entorno. */
    val description: String? = null,
    /** Host declarado al habilitar el entorno. */
    val host: String? = null,
    /** Marca temporal (epoch millis) en la que se habilitó. */
    val enabledAtEpochMillis: Long,
)
