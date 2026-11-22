package io.github.aldan9610.zrok.core

import com.sun.net.httpserver.HttpServer
import io.github.aldan9610.zrok.core.state.EnvironmentState
import io.github.aldan9610.zrok.core.state.InMemoryZrokStateStore
import io.github.aldan9610.zrok.core.state.ZrokStateStore
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Servidor HTTP local (JDK HttpServer) que simula la API REST de zrok y graba
 * las peticiones recibidas para poder verificar cabeceras y cuerpos exactos.
 */
class MockZrokServer {

    data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    }

    data class Response(
        val status: Int,
        val body: String = "",
        val contentType: String = Zrok.CONTENT_TYPE,
    )

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val recorded = CopyOnWriteArrayList<Recorded>()

    @Volatile
    var responder: (Recorded) -> Response = { Response(404, """{"error":"not found"}""") }

    fun start(): MockZrokServer {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.toList() }
            val rec = Recorded(exchange.requestMethod, exchange.requestURI.path, headers, body)
            recorded.add(rec)
            val response = responder(rec)
            val bytes = response.body.toByteArray()
            exchange.responseHeaders.add("Content-Type", response.contentType)
            exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) {
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        return this
    }

    fun stop() {
        server.stop(0)
    }

    /** URL base del mock, sin el basePath `/api/v2`. */
    val url: String
        get() = "http://127.0.0.1:${server.address.port}"
}

/** Entorno ya habilitado contra el servidor mock (sin pasar por /enable). */
fun testEnvironment(server: MockZrokServer): ZrokEnvironment =
    ZrokEnvironment(
        config = ZrokConfig(apiEndpoint = server.url),
        stateStore = seededStateStore(server),
    )

fun seededStateStore(server: MockZrokServer): ZrokStateStore = InMemoryZrokStateStore().apply {
    save(
        EnvironmentState(
            accountToken = "test-account-token",
            zId = "zid-abc123",
            zitiConfigJson = """{"ztAPI":"https://ziti.example","id":{"cert":"...","key":"..."}}""",
            apiEndpoint = server.url,
            description = "test",
            enabledAtEpochMillis = 0L,
        ),
    )
}
