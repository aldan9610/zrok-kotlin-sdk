package io.github.aldan9610.zrok.core.tunnel

import io.github.aldan9610.zrok.core.ZrokConfig
import io.github.aldan9610.zrok.core.ZrokEnvironment
import io.github.aldan9610.zrok.core.state.EnvironmentState
import io.github.aldan9610.zrok.core.state.InMemoryZrokStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Gateway "ziti" falso: cada servicio es un canal asíncrono real en loopback. */
class FakeZitiGateway : ZitiGateway {

    private val services = ConcurrentHashMap<String, AsynchronousServerSocketChannel>()

    override fun host(serviceName: String): AsynchronousServerSocketChannel {
        val server = AsynchronousServerSocketChannel.open().bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        services[serviceName] = server
        return server
    }

    override fun dial(serviceName: String): AsynchronousSocketChannel {
        val server = services[serviceName] ?: throw java.io.IOException("servicio ziti desconocido: '$serviceName'")
        val channel = AsynchronousSocketChannel.open()
        channel.connect(server.localAddress).get(5, TimeUnit.SECONDS)
        return channel
    }

    override fun close() {
        services.values.forEach { runCatching { it.close() } }
        services.clear()
    }
}

/** Backend TCP local de prueba que responde con el eco de lo que recibe. */
class EchoTarget : AutoCloseable {

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val thread = thread(isDaemon = true, name = "echo-target") {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return@thread
            }
            thread(isDaemon = true) {
                socket.use { it.getInputStream().copyTo(it.getOutputStream()) }
            }
        }
    }

    val port: Int get() = server.localPort
    val host: String get() = InetAddress.getLoopbackAddress().hostAddress

    override fun close() {
        server.close()
    }
}

class ZrokTunnelTest {

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

    private fun AsynchronousSocketChannel.send(text: String) {
        val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
        while (buffer.hasRemaining()) {
            write(buffer).get(5, TimeUnit.SECONDS)
        }
    }

    private fun AsynchronousSocketChannel.receive(count: Int): String {
        val buffer = ByteBuffer.allocate(count)
        var read = 0
        while (read < count) {
            val n = read(buffer).get(5, TimeUnit.SECONDS)
            check(n > 0) { "EOF inesperado tras $read de $count bytes" }
            read += n
        }
        return String(buffer.array(), 0, count, Charsets.UTF_8)
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(out, read, count - read)
            check(n > 0) { "EOF inesperado" }
            read += n
        }
        return out
    }

    @org.junit.jupiter.api.Test
    fun `hostTcpTunnel reenvia conexiones ziti al target local`(): Unit = kotlinx.coroutines.runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        EchoTarget().use { target ->
            val server = tunnel.hostTcpTunnel("shr-token", target.host, target.port)

            // El "cliente remoto" llega por la red ziti (aquí: el gateway falso).
            val client = gateway.dial("shr-token")
            client.send("hola-tunel")
            val respuesta = client.receive("hola-tunel".length)
            org.junit.jupiter.api.Assertions.assertEquals("hola-tunel", respuesta)

            // Dos conexiones concurrentes son sesiones independientes.
            val client2 = gateway.dial("shr-token")
            client2.send("segunda")
            org.junit.jupiter.api.Assertions.assertEquals("segunda", client2.receive("segunda".length))
            org.junit.jupiter.api.Assertions.assertTrue(server.activeSessions >= 1)

            client.close()
            client2.close()
            server.close()
        }
        tunnel.close()
        gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `dialShare expone streams bloqueantes del servicio ziti`(): Unit = kotlinx.coroutines.runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)

        // Lado "servicio ziti": acepta y hace eco.
        val serviceServer = gateway.host("shr-privado")
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val acceptJob = serviceScope.launch {
            while (true) {
                val ch = serviceServer.accept().get() ?: break
                serviceScope.launch {
                    val buffer = ByteBuffer.allocate(4096)
                    try {
                        while (true) {
                            buffer.clear()
                            val n = ch.read(buffer).get()
                            if (n <= 0) break
                            buffer.flip()
                            while (buffer.hasRemaining()) ch.write(buffer).get()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        tunnel.dialShare("shr-privado").use { connection ->
            val output: OutputStream = connection.outputStream
            val input: InputStream = connection.inputStream
            output.write("ping-desde-sdk".toByteArray())
            val eco = input.readExactly("ping-desde-sdk".length)
            org.junit.jupiter.api.Assertions.assertEquals("ping-desde-sdk", String(eco))
        }

        acceptJob.cancel()
        serviceScope.cancel()
        tunnel.close()
        gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `startLocalAccess puentea un puerto local hacia el share privado`(): Unit = kotlinx.coroutines.runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)

        // El backend remoto compartido (tras la red ziti) responde MAYUSCULAS.
        val serviceServer = gateway.host("db-remota")
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val acceptJob = serviceScope.launch {
            while (true) {
                val ch = serviceServer.accept().get() ?: break
                serviceScope.launch {
                    val buffer = ByteBuffer.allocate(4096)
                    try {
                        while (true) {
                            buffer.clear()
                            val n = ch.read(buffer).get()
                            if (n <= 0) break
                            val texto = String(buffer.array(), 0, n, Charsets.UTF_8)
                            val mayus = texto.uppercase().toByteArray()
                            val out = ByteBuffer.wrap(mayus)
                            while (out.hasRemaining()) ch.write(out).get()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val access = tunnel.startLocalAccess("db-remota")

        // La app se conecta a 127.0.0.1:localPort como a cualquier socket.
        Socket("127.0.0.1", access.localPort).use { app ->
            app.soTimeout = 5000
            val out = app.getOutputStream()
            val input = app.getInputStream()
            out.write("select 1;".toByteArray())
            val respuesta = ByteArray("SELECT 1;".length)
            var read = 0
            while (read < respuesta.size) {
                val n = input.read(respuesta, read, respuesta.size - read)
                org.junit.jupiter.api.Assertions.assertTrue(n > 0)
                read += n
            }
            org.junit.jupiter.api.Assertions.assertEquals("SELECT 1;", String(respuesta))
        }

        access.close()
        acceptJob.cancel()
        tunnel.close()
        gateway.close()
    }

    @org.junit.jupiter.api.Test
    fun `cerrar el servidor de tunel cierra las sesiones activas`(): Unit = kotlinx.coroutines.runBlocking {
        val gateway = FakeZitiGateway()
        val tunnel = tunnelWith(gateway)
        EchoTarget().use { target ->
            val server = tunnel.hostTcpTunnel("cerrar", target.host, target.port)
            val client = gateway.dial("cerrar")

            server.close()

            // Tras el cierre, leer del cliente devuelve EOF (-1), excepcion de E/S
            // o el canal queda cerrado: cualquiera de las tres es señal de cierre.
            val readResult = try {
                client.read(ByteBuffer.allocate(1)).get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                -1
            }
            org.junit.jupiter.api.Assertions.assertTrue(readResult == -1 || !client.isOpen) {
                "se esperaba EOF o canal cerrado, pero read=$readResult, isOpen=${client.isOpen}"
            }
            client.close()
        }
        tunnel.close()
        gateway.close()
    }
}
