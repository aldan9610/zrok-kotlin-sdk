package io.github.aldan9610.zrok.core.internal

import io.github.aldan9610.zrok.api.apis.EnvironmentApi
import io.github.aldan9610.zrok.api.apis.MetadataApi
import io.github.aldan9610.zrok.api.apis.ShareApi
import io.github.aldan9610.zrok.core.Zrok
import io.github.aldan9610.zrok.core.ZrokApiException
import io.github.aldan9610.zrok.core.ZrokConfig
import io.ktor.client.HttpClientConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.github.aldan9610.zrok.api.infrastructure.HttpResponse
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * Instancias de las APIs generadas, ya configuradas con:
 *  - baseUrl = endpoint + basePath (`/api/v2`),
 *  - el converter kotlinx-json registrado para `application/zrok.v1+json` y
 *    `application/json` (el ApiClient generado instala un ContentNegotiation
 *    vacío; Ktor combina las configuraciones del mismo plugin),
 *  - la cabecera Content-Type de zrok en las peticiones con cuerpo, y
 *  - el token de cuenta en la cabecera `x-token`.
 */
internal class ZrokApis(
    config: ZrokConfig,
    engine: HttpClientEngine? = null,
    extraHttpClientConfig: (HttpClientConfig<*>.() -> Unit)? = null,
) {
    private val accountToken = AtomicReference<String?>(null)

    private val httpConfig: HttpClientConfig<*>.() -> Unit = {
        install(ContentNegotiation) {
            json(JSON, contentType = ContentType.parse(Zrok.CONTENT_TYPE))
            json(JSON, contentType = ContentType.Application.Json)
        }
        install(ZrokContentType)
        extraHttpClientConfig?.invoke(this)
    }

    val environment = EnvironmentApi(config.apiUrl, engine, httpConfig)
    val share = ShareApi(config.apiUrl, engine, httpConfig)
    val metadata = MetadataApi(config.apiUrl, engine, httpConfig)

    /** Establece el token de cuenta usado en la cabecera `x-token` de todas las llamadas. */
    fun setAccountToken(token: String?) {
        accountToken.set(token)
        if (token == null) {
            environment.setApiKey("")
            share.setApiKey("")
            metadata.setApiKey("")
        } else {
            environment.setApiKey(token)
            share.setApiKey(token)
            metadata.setApiKey(token)
        }
    }

    fun requireAccountToken(): String =
        requireNotNull(accountToken.get()) {
            "no hay token de cuenta: usa ZrokEnvironment.enable(accountToken = ...) o ZrokEnvironment.load()"
        }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/**
 * Plugin de cliente que reescribe la cabecera Content-Type de las peticiones con
 * cuerpo JSON hacia el tipo de contenido que exige zrok. El ApiClient generado
 * envía `application/json` por defecto y el servidor go-openapi valida la
 * cabecera contra los `consumes` declarados en la especificación
 * (`application/zrok.v1+json`).
 */
/**
 * Plugin de cliente que reescribe la cabecera Content-Type de las peticiones con
 * cuerpo JSON hacia el tipo que exige zrok. El ApiClient generado envía
 * `application/json` por defecto y el servidor go-openapi valida la cabecera
 * contra los `consumes` declarados en la especificación
 * (`application/zrok.v1+json`).
 *
 * La reescritura ocurre en la fase [HttpRequestPipeline.Before], antes de que
 * ContentNegotiation serialice el cuerpo: así el converter registrado para el
 * tipo de zrok es el que se usa y el Content-Type final del mensaje sale del
 * contenido serializado (los motores de Ktor toman el tipo del body, no de la
 * cabecera, vía `mergeHeaders`).
 */
private object ZrokContentType : HttpClientPlugin<Unit, ZrokContentType> {
    override val key = io.ktor.util.AttributeKey<ZrokContentType>("ZrokContentType")

    override fun prepare(block: Unit.() -> Unit): ZrokContentType = ZrokContentType

    override fun install(plugin: ZrokContentType, scope: HttpClient) {
        scope.requestPipeline.intercept(HttpRequestPipeline.Before) {
            val current = context.headers[HttpHeaders.ContentType]
            if (current == null || current.startsWith(ContentType.Application.Json.toString())) {
                context.headers[HttpHeaders.ContentType] = Zrok.CONTENT_TYPE
            }
        }
    }
}

/**
 * Devuelve el cuerpo de una respuesta exitosa o lanza [ZrokApiException] con el
 * detalle del error devuelto por la API.
 */
internal suspend fun <T> HttpResponse<T>.bodyOrThrow(): T {
    ensureSuccess()
    return body()
}

/**
 * Verifica que la respuesta sea exitosa (para operaciones que no devuelven
 * cuerpo, como /unshare o /unaccess) o lanza [ZrokApiException].
 */
internal suspend fun HttpResponse<*>.ensureSuccess() {
    if (!success) {
        val body = runCatching { response.bodyAsText() }.getOrNull()
        throw ZrokApiException(status, response.status.description, body)
    }
}
