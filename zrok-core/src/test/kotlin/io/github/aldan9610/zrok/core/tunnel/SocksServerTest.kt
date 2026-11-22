package io.github.aldan9610.zrok.core.tunnel

import io.github.aldan9610.zrok.core.ZrokConfig
import io.github.aldan9610.zrok.core.ZrokEnvironment
import io.github.aldan9610.zrok.core.state.EnvironmentState
import io.github.aldan9610.zrok.core.state.InMemoryZrokStateStore
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.nio.channels.AsynchronousSocketChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class SocksServerTest {

    private fun tunnelWith(gateway: ZitiGateway): ZrokTunnel {
        val store = InMemoryZrokStateStore().apply {
            save(
                EnvironmentState(
                    accountToken = "tok",
                    zId = "zid",
                    zitiConfigJson = "{}",
                    apiEndpoint = "http://127.0.0.1:1",
                    enabledAtEpochMillis = 0L,
                ),
            )
        }
        val environment = ZrokEnvironment(ZrokConfig(apiEndpoint = "http://127.0.0.1:1"), store)
        return ZrokTunnel(environment, gatewayFactory = { gateway })
    }

    /** Cliente SOCKS5 sobre un canal ziti (aquí el gateway falso). */
    private class SocksClient(channel: AsynchronousSocketChannel) {
        private val input = AsyncChannelInputStream(channel)
        private val output = AsyncChannelOutputStream(channel)

        fun connectNoAuth(host: String, port: Int): Int {
            // Greeting: VER=5, NMETHODS=1, METHOD=0 (no auth)
            output.write(byteArrayOf(5, 1, 0)); output.flush()
            val rep = ByteArray(2); readFully(rep)
            assertEquals(5, rep[0].toInt())
            assertEquals(0, rep[1].toInt(), "se esperaba no-auth")
            return sendConnect(host, port)
        }

        fun connectWithAuth(host: String, port: Int, user: String, pass: String): Int {
            output.write(byteArrayOf(5, 1, 2)); output.flush() // pide user/pass
            val rep = ByteArray(2); readFully(rep)
            assertEquals(5, rep[0].toInt()); assertEquals(2, rep[1].toInt(), "se esperaba password auth")
            val u = user.toByteArray(); val p = pass.toByteArray()
            output.write(byteArrayOf(1, u.size.toByte())); output.write(u)
            output.write(p.size.toInt()); output.write(p); output.flush()
            val authRep = ByteArray(2); readFully(authRep)
            assertEquals(1, authRep[0].toInt())
            assertEquals(0, authRep[1].toInt(), "auth rechazada")
            return sendConnect(host, port)
        }

        /** Envía CONNECT y devuelve el código de reply (0 = success). */
        private fun sendConnect(host: String, port: Int): Int {
            val ip = InetAddress.getByName(host).address
            val pkt = mutableListOf<Byte>(5, 1, 0, 1)
            ip.forEach { pkt += it }
            pkt += ((port ushr 8) and 0xFF).toByte()
            pkt += (port and 0xFF).toByte()
            output.write(pkt.toByteArray()); output.flush()
            val reply = ByteArray(10); readFully(reply)
            assertEquals(5, reply[0].toInt())
            return reply[1].toInt()
        }

        fun sendAndReceive(payload: String, expect: Int): String {
            output.write(payload.toByteArray()); output.flush()
            val out = ByteArray(expect); readFully(out)
            return String(out)
        }

        private fun readFully(dst: ByteArray) {
            var off = 0
            while (off < dst.size) {
                val n = input.read(dst, off, dst.size - off)
                check(n > 0) { "EOF inesperado tras $off de ${dst.size}" }
                off += n
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun `hostSocks reenvia un CONNECT SOCKS5 al target local y hace eco`() = runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        EchoTarget().use { target ->
            val server = tunnel.hostSocks("shr-socks")
            val client = SocksClient(gateway.dial("shr-socks"))
            assertEquals(0, client.connectNoAuth(target.host, target.port), "CONNECT debería success")
            assertEquals("hola-socks", client.sendAndReceive("hola-socks", "hola-socks".length))
            assertTrue(server.activeSessions >= 1)
            server.close()
        }
        tunnel.close()
        gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `hostSocks con auth acepta credenciales validas y reenvia`() = runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        EchoTarget().use { target ->
            val server = tunnel.hostSocks("shr-auth", username = "user", password = "secret")
            val client = SocksClient(gateway.dial("shr-auth"))
            assertEquals(0, client.connectWithAuth(target.host, target.port, "user", "secret"))
            assertEquals("eco", client.sendAndReceive("eco", 3))
            server.close()
        }
        tunnel.close()
        gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `hostSocks rechaza credenciales malas sin reenviar`() = runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        EchoTarget().use { target ->
            val server = tunnel.hostSocks("shr-badauth", username = "user", password = "secret")
            val channel = gateway.dial("shr-badauth")
            val output = AsyncChannelOutputStream(channel)
            val input = AsyncChannelInputStream(channel)
            output.write(byteArrayOf(5, 1, 2)); output.flush()
            ByteArray(2).also { var o = 0; while (o < 2) { val n = input.read(it, o, 2 - o); check(n > 0); o += n } }
            val u = "user".toByteArray(); val p = "wrong".toByteArray()
            output.write(byteArrayOf(1, u.size.toByte())); output.write(u)
            output.write(p.size.toInt()); output.write(p); output.flush()
            val authRep = ByteArray(2).also { var o = 0; while (o < 2) { val n = input.read(it, o, 2 - o); check(n > 0); o += n } }
            assertEquals(1, authRep[0].toInt())
            assertEquals(1, authRep[1].toInt(), "se esperaba fallo de auth (0x01)")
            assertEquals(0, server.activeSessions)
            channel.close()
            server.close()
        }
        tunnel.close()
        gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `hostSocks rechaza BIND con commandNotSupported`() = runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        val server = tunnel.hostSocks("shr-bind")
        val channel = gateway.dial("shr-bind")
        val output = AsyncChannelOutputStream(channel)
        val input = AsyncChannelInputStream(channel)
        // Greeting no-auth
        output.write(byteArrayOf(5, 1, 0)); output.flush()
        ByteArray(2).also { var o = 0; while (o < 2) { val n = input.read(it, o, 2 - o); check(n > 0); o += n } }
        // BIND (cmd=2) a 127.0.0.1:0
        output.write(byteArrayOf(5, 2, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
        val reply = ByteArray(10).also { var o = 0; while (o < 10) { val n = input.read(it, o, 10 - o); check(n > 0); o += n } }
        assertEquals(5, reply[0].toInt())
        assertEquals(7, reply[1].toInt(), "BIND debería responder commandNotSupported (0x07)")
        channel.close()
        server.close()
        tunnel.close()
        gateway.close()
    }
}
