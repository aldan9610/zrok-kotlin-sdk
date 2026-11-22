package io.github.aldan9610.zrok.core.tunnel

import io.github.aldan9610.zrok.core.ZrokConfig
import io.github.aldan9610.zrok.core.ZrokEnvironment
import io.github.aldan9610.zrok.core.state.EnvironmentState
import io.github.aldan9610.zrok.core.state.InMemoryZrokStateStore
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.concurrent.thread

/** Backend UDP local de prueba que responde con el eco de cada datagrama. */
class UdpEchoTarget : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val thread = thread(isDaemon = true, name = "udp-echo") {
        while (!socket.isClosed) {
            val buf = ByteArray(65_535)
            val pkt = DatagramPacket(buf, buf.size)
            try { socket.receive(pkt) } catch (_: Exception) { return@thread }
            socket.send(DatagramPacket(pkt.data, pkt.length, pkt.address, pkt.port))
        }
    }
    val port: Int get() = socket.localPort
    val host: String get() = InetAddress.getLoopbackAddress().hostAddress
    override fun close() { socket.close() }
}

class UdpTunnelTest {

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

    @org.junit.jupiter.api.Test
    fun `hostUdpTunnel + startUdpAccess transportan datagramas UDP de ida y vuelta`() = runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        UdpEchoTarget().use { target ->
            val server = tunnel.hostUdpTunnel("shr-udp", target.host, target.port)
            val access = tunnel.startUdpAccess("shr-udp", 0)

            // La app envía UDP al access local; debe recibir el eco del backend.
            DatagramSocket().use { app ->
                app.soTimeout = 5_000
                val payload = "ping-udp".toByteArray()
                app.send(DatagramPacket(payload, payload.size, InetAddress.getLoopbackAddress(), access.boundPort))
                val resp = ByteArray(payload.size)
                val pkt = DatagramPacket(resp, resp.size)
                app.receive(pkt)
                assertEquals("ping-udp", String(resp, 0, pkt.length))
            }
            assertTrue(server.activeFlows >= 1)
            assertTrue(access.activeFlows >= 1)
            access.close()
            server.close()
        }
        tunnel.close()
        gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `dos clientes UDP independientes tienen flujos separados`() = runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        UdpEchoTarget().use { target ->
            val server = tunnel.hostUdpTunnel("shr-udp2", target.host, target.port)
            val access = tunnel.startUdpAccess("shr-udp2", 0)
            val port = access.boundPort

            fun roundTrip(msg: String): String {
                DatagramSocket().use { app ->
                    app.soTimeout = 5_000
                    val payload = msg.toByteArray()
                    app.send(DatagramPacket(payload, payload.size, InetAddress.getLoopbackAddress(), port))
                    val resp = ByteArray(payload.size)
                    val pkt = DatagramPacket(resp, resp.size)
                    app.receive(pkt)
                    return String(resp, 0, pkt.length)
                }
            }
            assertEquals("uno", roundTrip("uno"))
            assertEquals("dos", roundTrip("dos"))
            assertTrue(access.activeFlows >= 2)
            access.close(); server.close()
        }
        tunnel.close()
        gateway.close()
    }
}
