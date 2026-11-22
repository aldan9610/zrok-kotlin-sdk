package io.github.aldan9610.zrok.core

import io.github.aldan9610.zrok.core.state.EnvironmentState
import io.github.aldan9610.zrok.core.state.InMemoryZrokStateStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ZrokEnvironmentTest {

    private lateinit var server: MockZrokServer

    @BeforeEach
    fun setUp() {
        server = MockZrokServer().start()
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `enable llama al endpoint de enable con x-token y guarda el estado`(): Unit = runBlocking {
        server.responder = { rec ->
            when (rec.path) {
                "/api/v2/enable" -> MockZrokServer.Response(
                    201,
                    """{"identity":"zid-xyz789","cfg":"{\"ztAPI\":\"https://ziti.example\"}"}""",
                )
                else -> MockZrokServer.Response(404)
            }
        }

        val store = InMemoryZrokStateStore()
        val env = ZrokEnvironment(ZrokConfig(apiEndpoint = server.url), store)

        val state = env.enable(accountToken = "mi-token", description = "android-test", host = "pixel-8")

        // Petición recibida por el servidor simulado
        val req = server.recorded.single()
        assertEquals("POST", req.method)
        assertEquals("/api/v2/enable", req.path)
        assertEquals("mi-token", req.header("x-token"))
        assertEquals(Zrok.CONTENT_TYPE, req.header("Content-Type"))
        assertTrue(req.body.contains("\"description\":\"android-test\""))
        assertTrue(req.body.contains("\"host\":\"pixel-8\""))

        // Estado resultante
        assertEquals("zid-xyz789", state.zId)
        assertTrue(state.zitiConfigJson.contains("ziti.example"))
        assertEquals("mi-token", state.accountToken)
        assertEquals("zid-xyz789", env.state?.zId)
        assertTrue(env.isEnabled)
        // El estado se persistió en el almacén
        assertEquals("zid-xyz789", store.load()?.zId)
    }

    @Test
    fun `enable con token invalido lanza ZrokApiException`(): Unit = runBlocking {
        server.responder = { MockZrokServer.Response(401, """{"code":401,"message":"invalid api key"}""") }
        val env = ZrokEnvironment(ZrokConfig(apiEndpoint = server.url), InMemoryZrokStateStore())

        val ex = assertThrows(ZrokApiException::class.java) {
            runBlocking { env.enable(accountToken = "malo") }
        }
        assertEquals(401, ex.statusCode)
        assertTrue(ex.errorBody!!.contains("invalid api key"))
    }

    @Test
    fun `disable limpia el estado local`(): Unit = runBlocking {
        server.responder = { rec ->
            when (rec.path) {
                "/api/v2/disable" -> MockZrokServer.Response(200)
                else -> MockZrokServer.Response(404)
            }
        }
        val env = testEnvironment(server)
        assertTrue(env.isEnabled)

        env.disable()

        val req = server.recorded.single()
        assertEquals("POST", req.method)
        assertEquals("/api/v2/disable", req.path)
        assertEquals("test-account-token", req.header("x-token"))
        assertTrue(req.body.contains("\"identity\":\"zid-abc123\""))
        assertFalse(env.isEnabled)
        assertNull(env.state)
    }

    @Test
    fun `load restaura el estado persistido`(): Unit = runBlocking {
        val env = testEnvironment(server)
        // testEnvironment ya precarga el estado en el constructor (carga automática)
        assertNotNull(env.state)
        assertEquals("zid-abc123", env.state?.zId)

        // Un entorno sin estado persistido no está habilitado
        val vacio = ZrokEnvironment(ZrokConfig(apiEndpoint = server.url), InMemoryZrokStateStore())
        assertFalse(vacio.isEnabled)
    }

    @Test
    fun `requireState sin entorno lanza ZrokNotEnabledException`(): Unit = runBlocking {
        val env = ZrokEnvironment(ZrokConfig(apiEndpoint = server.url), InMemoryZrokStateStore())
        assertThrows(ZrokNotEnabledException::class.java) { env.requireState() }
        Unit
    }
}
