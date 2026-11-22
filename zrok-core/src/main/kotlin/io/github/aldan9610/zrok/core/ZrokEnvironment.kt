package io.github.aldan9610.zrok.core

import io.github.aldan9610.zrok.api.models.DisableRequest
import io.github.aldan9610.zrok.api.models.EnableRequest
import io.github.aldan9610.zrok.core.internal.ZrokApis
import io.github.aldan9610.zrok.core.internal.bodyOrThrow
import io.github.aldan9610.zrok.core.internal.ensureSuccess
import io.github.aldan9610.zrok.core.state.EnvironmentState
import io.github.aldan9610.zrok.core.state.ZrokStateStore
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine

/**
 * Gestiona el ciclo de vida del entorno zrok ("root environment" del SDK de Go):
 * habilitar, cargar y deshabilitar.
 *
 * El estado (token de cuenta, identidad ziti y JSON de identidad OpenZiti) se
 * persiste en el [ZrokStateStore] proporcionado; en Android debe usarse el
 * almacén cifrado del módulo `zrok-android` para cumplir la fase de
 * "almacenamiento seguro del token".
 *
 * Ejemplo:
 * ```kotlin
 * val env = ZrokEnvironment(config, stateStore = EncryptedZrokStateStore(context))
 * val state = env.enable(accountToken = "tu-token")
 * val manager = ZrokManager(env)
 * ```
 */
class ZrokEnvironment(
    val config: ZrokConfig = ZrokConfig(),
    private val stateStore: ZrokStateStore,
    engine: HttpClientEngine? = null,
    extraHttpClientConfig: (HttpClientConfig<*>.() -> Unit)? = null,
) {
    private val apis = ZrokApis(config, engine, extraHttpClientConfig)

    /** Estado del entorno habilitado, o null si no está habilitado. */
    var state: EnvironmentState? = null
        private set

    val isEnabled: Boolean get() = state != null

    init {
        // Carga automática inicial: si hay estado persistido, configura el token.
        load()
    }

    /**
     * Carga el estado persistido en el [ZrokStateStore] y prepara el cliente REST
     * con el token de cuenta. Devuelve el estado cargado o null.
     */
    fun load(): EnvironmentState? {
        state = stateStore.load()
        apis.setAccountToken(state?.accountToken)
        return state
    }

    /**
     * Habilita un entorno zrok para el token de cuenta dado: llama a
     * `POST /api/v2/enable`, recibe la identidad ziti y el JSON de identidad de
     * OpenZiti, y persiste el estado.
     *
     * @param accountToken token de cuenta de zrok (zrok console -> Your Account -> Account Token).
     * @param description descripción del entorno que se mostrará en la consola.
     * @param host nombre de host declarado en el entorno (opcional).
     * @return el estado del entorno habilitado.
     * @throws ZrokApiException si la API rechaza la petición (token inválido, etc.).
     */
    suspend fun enable(
        accountToken: String,
        description: String = "zrok-kotlin-sdk",
        host: String? = null,
    ): EnvironmentState {
        require(accountToken.isNotBlank()) { "accountToken no puede estar vacío" }
        apis.setAccountToken(accountToken)
        val response = apis.environment.enable(
            EnableRequest(description = description, host = host),
        ).bodyOrThrow()
        val newState = EnvironmentState(
            accountToken = accountToken,
            zId = requireNotNull(response.identity) { "la respuesta de /enable no trajo 'identity'" },
            zitiConfigJson = requireNotNull(response.cfg) { "la respuesta de /enable no trajo 'cfg'" },
            apiEndpoint = config.apiEndpoint,
            description = description,
            host = host,
            enabledAtEpochMillis = System.currentTimeMillis(),
        )
        stateStore.save(newState)
        state = newState
        return newState
    }

    /**
     * Deshabilita el entorno actual en el servidor (`POST /api/v2/disable`) y
     * borra el estado persistido.
     *
     * @throws ZrokNotEnabledException si no hay entorno habilitado.
     * @throws ZrokApiException si la API rechaza la petición.
     */
    suspend fun disable() {
        val current = requireState()
        runCatching {
            apis.environment.disable(DisableRequest(identity = current.zId)).ensureSuccess()
        }.onFailure {
            // Aunque falle la llamada remota, el estado local se invalida: el usuario
            // pidió deshabilitar y mantener un estado inconsistente sería peor.
            stateStore.save(null)
            state = null
            apis.setAccountToken(null)
            throw it
        }
        stateStore.save(null)
        state = null
        apis.setAccountToken(null)
    }

    /** Estado del entorno o [ZrokNotEnabledException] si no está habilitado. */
    fun requireState(): EnvironmentState =
        state ?: throw ZrokNotEnabledException()

    /** APIs internas; compartidas con [ZrokManager]. */
    internal fun apis(): ZrokApis = apis
}
