package io.github.aldan9610.zrok.core.tunnel

import io.github.aldan9610.zrok.core.ZrokConfig
import io.github.aldan9610.zrok.core.ZrokEnvironment
import io.github.aldan9610.zrok.core.state.EnvironmentState
import io.github.aldan9610.zrok.core.state.InMemoryZrokStateStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.channels.AsynchronousSocketChannel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir

class DriveServerTest {

    private fun tunnelWith(gateway: ZitiGateway): ZrokTunnel {
        val store = InMemoryZrokStateStore().apply {
            save(
                EnvironmentState(
                    accountToken = "tok", zId = "zid", zitiConfigJson = "{}",
                    apiEndpoint = "http://127.0.0.1:1", enabledAtEpochMillis = 0L,
                ),
            )
        }
        val environment = ZrokEnvironment(ZrokConfig(apiEndpoint = "http://127.0.0.1:1"), store)
        return ZrokTunnel(environment, gatewayFactory = { gateway })
    }

    /** Cliente HTTP/WebDAV mínimo sobre un canal ziti (aquí el gateway falso). */
    private class HttpWebDavClient(channel: AsynchronousSocketChannel) : AutoCloseable {
        private val input = AsyncChannelInputStream(channel)
        private val output = AsyncChannelOutputStream(channel)
        private val channelRef = channel

        /** Envía una petición y devuelve (statusLine, headers, body) como texto. */
        fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)): Response {
            val req = StringBuilder("$method $path HTTP/1.1\r\nHost: drive.test\r\n")
            if (body.isNotEmpty()) req.append("Content-Length: ${body.size}\r\n")
            headers.forEach { (k, v) -> req.append("$k: $v\r\n") }
            req.append("Connection: close\r\n\r\n")
            output.write(req.toString().toByteArray())
            if (body.isNotEmpty()) output.write(body)
            output.flush()
            return readResponse()
        }

        class Response(val statusLine: String, val headers: Map<String, String>, val bodyBytes: ByteArray) {
            val code: Int get() = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val body: String get() = String(bodyBytes, Charsets.UTF_8)
        }

        private fun readResponse(): Response {
            val statusLine = readLine() ?: return Response("", emptyMap(), ByteArray(0))
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (len > 0) ByteArray(len).also { var o = 0; while (o < len) { val n = input.read(it, o, len - o); if (n < 0) break; o += n } } else ByteArray(0)
            return Response(statusLine, headers, body)
        }

        private fun readLine(): String? {
            val bytes = ArrayList<Byte>()
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray())
                if (b == '\r'.code) { val n = input.read(); if (n != '\n'.code && n >= 0) bytes.add(n.toByte()); return String(bytes.toByteArray()) }
                if (b == '\n'.code) return String(bytes.toByteArray())
                bytes.add(b.toByte())
            }
        }

        override fun close() { runCatching { channelRef.close() } }
    }

    @org.junit.jupiter.api.Test
    fun `hostDrive responde PROPFIND con multistatus y lista el contenido`(@TempDir root: File) = runBlocking {
        File(root, "hola.txt").writeText("contenido")
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        val server = tunnel.hostDrive("shr-drive", root.absolutePath)
        HttpWebDavClient(gateway.dial("shr-drive")).use { client ->
            val resp = client.request("PROPFIND", "/", mapOf("Depth" to "1"))
            assertEquals(207, resp.code)
            assertTrue(resp.body.contains("multistatus"), "esperaba XML multistatus")
            assertTrue(resp.body.contains("hola.txt"), "esperaba el fichero en el listado")
        }
        server.close(); tunnel.close(); gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `hostDrive sirve GET del contenido de un fichero`(@TempDir root: File) = runBlocking {
        File(root, "doc.txt").writeText("hola-drive")
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        val server = tunnel.hostDrive("shr-get", root.absolutePath)
        HttpWebDavClient(gateway.dial("shr-get")).use { client ->
            val resp = client.request("GET", "/doc.txt")
            assertEquals(200, resp.code)
            assertEquals("hola-drive", resp.body)
        }
        server.close(); tunnel.close(); gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `hostDrive permite PUT crear y luego GET`(@TempDir root: File) = runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        val server = tunnel.hostDrive("shr-put", root.absolutePath)
        HttpWebDavClient(gateway.dial("shr-put")).use { client ->
            val put = client.request("PUT", "/nuevo.txt", emptyMap(), "subido-por-webdav".toByteArray())
            assertTrue(put.code == 201 || put.code == 204, "PUT debería crear (201/204), fue ${put.code}")
        }
        HttpWebDavClient(gateway.dial("shr-put")).use { client ->
            val get = client.request("GET", "/nuevo.txt")
            assertEquals(200, get.code)
            assertEquals("subido-por-webdav", get.body)
            assertTrue(File(root, "nuevo.txt").exists())
        }
        server.close(); tunnel.close(); gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `hostDrive soporta MKCOL y DELETE`(@TempDir root: File) = runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        val server = tunnel.hostDrive("shr-mkcol", root.absolutePath)
        HttpWebDavClient(gateway.dial("shr-mkcol")).use { client ->
            val mkcol = client.request("MKCOL", "/carpeta")
            assertTrue(mkcol.code == 201, "MKCOL debería crear (201), fue ${mkcol.code}")
            assertTrue(File(root, "carpeta").isDirectory)
        }
        HttpWebDavClient(gateway.dial("shr-mkcol")).use { client ->
            val del = client.request("DELETE", "/carpeta")
            assertTrue(del.code == 204, "DELETE debería 204, fue ${del.code}")
            assertTrue(!File(root, "carpeta").exists())
        }
        server.close(); tunnel.close(); gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `hostDrive rechaza path traversal fuera de la raiz`(@TempDir root: File) = runBlocking {
        File(root, "ok.txt").writeText("x")
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        val server = tunnel.hostDrive("shr-trav", root.absolutePath)
        HttpWebDavClient(gateway.dial("shr-trav")).use { client ->
            val resp = client.request("GET", "/../etc/passwd")
            assertTrue(resp.code == 403 || resp.code == 404, "traversal debe rechazarse, fue ${resp.code}")
        }
        server.close(); tunnel.close(); gateway.close()
    }
}
