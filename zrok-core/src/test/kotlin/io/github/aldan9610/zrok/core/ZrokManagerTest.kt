package io.github.aldan9610.zrok.core

import io.github.aldan9610.zrok.core.model.BackendMode
import io.github.aldan9610.zrok.core.model.BasicAuthUser
import io.github.aldan9610.zrok.core.model.ShareMode
import io.github.aldan9610.zrok.core.state.InMemoryZrokStateStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ZrokManagerTest {

    private lateinit var server: MockZrokServer
    private lateinit var manager: ZrokManager

    @BeforeEach
    fun setUp() {
        server = MockZrokServer().start()
        manager = ZrokManager(testEnvironment(server))
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    private fun respondShare() {
        server.responder = { rec ->
            when (rec.path) {
                "/api/v2/share" -> MockZrokServer.Response(
                    201,
                    """{"frontendProxyEndpoints":["https://abc123.share.zrok.io"],"shareToken":"abc123"}""",
                )
                else -> MockZrokServer.Response(404)
            }
        }
    }

    @Test
    fun `createProxyShare crea un share publico proxy`(): Unit = runBlocking {
        respondShare()

        val share = manager.createProxyShare("http://127.0.0.1:8080")

        val req = server.recorded.single()
        assertEquals("POST", req.method)
        assertEquals("/api/v2/share", req.path)
        assertEquals("test-account-token", req.header("x-token"))
        assertEquals(Zrok.CONTENT_TYPE, req.header("Content-Type"))
        assertTrue(req.body.contains("\"envZId\":\"zid-abc123\""))
        assertTrue(req.body.contains("\"shareMode\":\"public\""))
        assertTrue(req.body.contains("\"backendMode\":\"proxy\""))
        assertTrue(req.body.contains("\"target\":\"http://127.0.0.1:8080\""))
        assertTrue(req.body.contains("\"authScheme\":\"none\""))

        assertEquals("abc123", share.token)
        assertEquals(listOf("https://abc123.share.zrok.io"), share.frontendEndpoints)
        assertEquals(ShareMode.PUBLIC, share.shareMode)
        assertEquals(BackendMode.PROXY, share.backendMode)
    }

    @Test
    fun `createProxyShare privado con basic auth`(): Unit = runBlocking {
        respondShare()

        val share = manager.createProxyShare(
            targetUrl = "http://127.0.0.1:8080",
            isPrivate = true,
            options = io.github.aldan9610.zrok.core.model.ShareOptions(
                basicAuth = listOf(BasicAuthUser("admin", "secreto")),
                privateShareToken = "mi-token-privado",
            ),
        )

        val req = server.recorded.single()
        assertTrue(req.body.contains("\"shareMode\":\"private\""))
        assertTrue(req.body.contains("\"authScheme\":\"basic\""))
        assertTrue(req.body.contains("\"privateShareToken\":\"mi-token-privado\""))
        assertTrue(req.body.contains("\"username\":\"admin\""))
        assertTrue(req.body.contains("\"password\":\"secreto\""))
        assertEquals(ShareMode.PRIVATE, share.shareMode)
    }

    @Test
    fun `createTcpTunnelShare forma el target host-puerto y es privado por defecto`(): Unit = runBlocking {
        respondShare()

        val share = manager.createTcpTunnelShare("127.0.0.1", 5432)

        val req = server.recorded.single()
        assertTrue(req.body.contains("\"backendMode\":\"tcpTunnel\""))
        assertTrue(req.body.contains("\"target\":\"127.0.0.1:5432\""))
        assertTrue(req.body.contains("\"shareMode\":\"private\""))
        assertEquals(BackendMode.TCP_TUNNEL, share.backendMode)
    }

    @Test
    fun `basicAuth y oauth a la vez lanza IllegalArgumentException`(): Unit = runBlocking {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                manager.createProxyShare(
                    "http://127.0.0.1:8080",
                    options = io.github.aldan9610.zrok.core.model.ShareOptions(
                        basicAuth = listOf(BasicAuthUser("u", "p")),
                        oauthProvider = "google",
                    ),
                )
            }
        }
        assertTrue(ex.message!!.contains("mutuamente excluyentes"))
        Unit
    }

    @Test
    fun `deleteShare llama a DELETE unshare con cuerpo`(): Unit = runBlocking {
        server.responder = { rec ->
            when (rec.path) {
                "/api/v2/unshare" -> MockZrokServer.Response(200)
                else -> MockZrokServer.Response(404)
            }
        }

        manager.deleteShare("abc123")

        val req = server.recorded.single()
        assertEquals("DELETE", req.method)
        assertEquals("/api/v2/unshare", req.path)
        assertTrue(req.body.contains("\"envZId\":\"zid-abc123\""))
        assertTrue(req.body.contains("\"shareToken\":\"abc123\""))
    }

    @Test
    fun `createAccess y deleteAccess gestionan el ciclo del access`(): Unit = runBlocking {
        server.responder = { rec ->
            when (rec.path) {
                "/api/v2/access" -> MockZrokServer.Response(201, """{"frontendToken":"ft-987","backendMode":"tcpTunnel"}""")
                "/api/v2/unaccess" -> MockZrokServer.Response(200)
                else -> MockZrokServer.Response(404)
            }
        }

        val access = manager.createAccess("shr-token", bindAddress = "127.0.0.1:9191")
        val accessReq = server.recorded[0]
        assertEquals("POST", accessReq.method)
        assertEquals("/api/v2/access", accessReq.method.let { accessReq.path })
        assertTrue(accessReq.body.contains("\"shareToken\":\"shr-token\""))
        assertTrue(accessReq.body.contains("\"bindAddress\":\"127.0.0.1:9191\""))
        assertEquals("ft-987", access.token)
        assertEquals("shr-token", access.shareToken)
        assertEquals(BackendMode.TCP_TUNNEL, access.backendMode)

        manager.deleteAccess(access)
        val unaccessReq = server.recorded[1]
        assertEquals("DELETE", unaccessReq.method)
        assertEquals("/api/v2/unaccess", unaccessReq.path)
        assertTrue(unaccessReq.body.contains("\"frontendToken\":\"ft-987\""))
        assertTrue(unaccessReq.body.contains("\"shareToken\":\"shr-token\""))
    }

    @Test
    fun `listShares y listAccesses parsean las colecciones`(): Unit = runBlocking {
        server.responder = { rec ->
            when (rec.path) {
                "/api/v2/shares" -> MockZrokServer.Response(
                    200,
                    """{"shares":[{"shareToken":"tok1","shareMode":"public","backendMode":"proxy",
                       "frontendEndpoints":["https://tok1.share.zrok.io"],"target":"http://localhost:8080",
                       "createdAt":1700000000,"updatedAt":1700000001}]}""".replace("\n", ""),
                )
                "/api/v2/accesses" -> MockZrokServer.Response(
                    200,
                    """{"accesses":[{"frontendToken":"ft-1","shareToken":"tok1","backendMode":"tcpTunnel"}]}""",
                )
                else -> MockZrokServer.Response(404)
            }
        }

        val shares = manager.listShares()
        assertEquals(1, shares.size)
        assertEquals("tok1", shares[0].token)
        assertEquals(ShareMode.PUBLIC, shares[0].shareMode)
        assertEquals(BackendMode.PROXY, shares[0].backendMode)
        assertEquals(1700000000L, shares[0].createdAt)

        val accesses = manager.listAccesses()
        assertEquals(1, accesses.size)
        assertEquals("ft-1", accesses[0].token)
        assertEquals("tok1", accesses[0].shareToken)
    }

    @Test
    fun `un error 409 del servidor se propaga como ZrokApiException`(): Unit = runBlocking {
        server.responder = { MockZrokServer.Response(409, """{"error":"share token already exists"}""") }

        val ex = assertThrows(ZrokApiException::class.java) {
            runBlocking { manager.createProxyShare("http://127.0.0.1:8080") }
        }
        assertEquals(409, ex.statusCode)
        Unit
    }

    @Test
    fun `sin entorno habilitado las operaciones fallan rapido`(): Unit = runBlocking {
        val env = ZrokEnvironment(ZrokConfig(apiEndpoint = server.url), InMemoryZrokStateStore())
        val sinEntorno = ZrokManager(env)
        assertThrows(ZrokNotEnabledException::class.java) {
            runBlocking { sinEntorno.createProxyShare("http://127.0.0.1:8080") }
        }
        Unit
    }
}
