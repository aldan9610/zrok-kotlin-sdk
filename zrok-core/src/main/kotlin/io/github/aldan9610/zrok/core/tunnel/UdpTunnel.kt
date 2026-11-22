package io.github.aldan9610.zrok.core.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

// Plano de datos del backend udpTunnel.
//
// El SDK Go de zrok confía en que el ziti-go-sdk preserva los límites de
// datagrama (un Read/Write del net.Conn ziti == un datagrama). ziti-jvm 0.25.1
// NO expone eso: su AsynchronousSocketChannel es de stream y un read fusiona
// varios mensajes ziti en el buffer. Por eso aquí enmarcamos cada datagrama con
// un prefijo de longitud de 2 bytes (big-endian) sobre el stream ziti.
//
// Este framing es propio del SDK Kotlin: un túnel udpTunnel funciona cuando el
// host Y el access usan ambos este SDK (shares privados entre dos extremos
// Kotlin). Un share udpTunnel público cuyo host enfrente al frontend público de
// zrok requiere el binario Go `zrok` como host (el frontend público envía
// datagramas con el framing nativo de ziti, que este SDK no puede deshacer en
// lectura). Es una limitación documentada de ziti-jvm, no del protocolo zrok.

internal const val UDP_MAX_DATAGRAM = 65_535

/** Lee un datagrama enmarcado del stream ziti; null si el stream cerró (EOF). */
internal fun readUdpFrame(channel: AsynchronousSocketChannel): ByteArray? {
    val lenBuf = ByteBuffer.allocate(2)
    if (!readExactly(channel, lenBuf)) return null
    val len = ((lenBuf.get(0).toInt() and 0xFF) shl 8) or (lenBuf.get(1).toInt() and 0xFF)
    if (len == 0) return ByteArray(0)
    require(len <= UDP_MAX_DATAGRAM) { "datagrama demasiado grande: $len bytes" }
    val payload = ByteBuffer.allocate(len)
    if (!readExactly(channel, payload)) return null
    return payload.array()
}

/** Escribe un datagrama enmarcado (2 bytes BE de longitud + payload) al stream ziti. */
internal fun writeUdpFrame(channel: AsynchronousSocketChannel, payload: ByteArray) {
    val len = payload.size
    require(len <= UDP_MAX_DATAGRAM) { "datagrama demasiado grande: $len bytes" }
    val buf = ByteBuffer.allocate(2 + len).apply {
        put(((len ushr 8) and 0xFF).toByte())
        put((len and 0xFF).toByte())
        put(payload)
        flip()
    }
    while (buf.hasRemaining()) {
        channel.write(buf).get()
    }
}

private fun readExactly(channel: AsynchronousSocketChannel, dst: ByteBuffer): Boolean {
    while (dst.hasRemaining()) {
        val n = try {
            channel.read(dst).get()
        } catch (_: IOException) {
            return false
        }
        if (n < 0) return false
    }
    return true
}

/**
 * Lado host de un share `udpTunnel`: acepta conexiones ziti y, sobre cada una,
 * transporta datagramas enmarcados hacia/desde el backend UDP local
 * (targetHost:targetPort). Una conexión ziti = un flujo de datagramas de un
 * cliente access.
 */
class ZrokUdpTunnelServer internal constructor(
    private val server: AsynchronousServerSocketChannel,
    private val targetHost: String,
    private val targetPort: Int,
    parentScope: CoroutineScope,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val flows = CopyOnWriteArraySet<DatagramChannel>()

    private val acceptJob = scope.launch {
        while (isActive && !closed.get()) {
            val zitiChannel = try {
                server.accept().get() ?: break
            } catch (_: Exception) {
                break
            }
            if (!closed.get()) scope.launch { handleFlow(zitiChannel) } else runCatching { zitiChannel.close() }
        }
    }

    private fun handleFlow(zitiChannel: AsynchronousSocketChannel) {
        val udp = DatagramChannel.open()
        try {
            udp.configureBlocking(true)
            udp.connect(InetSocketAddress(targetHost, targetPort))
            flows.add(udp)
        } catch (_: Exception) {
            runCatching { zitiChannel.close() }; runCatching { udp.close() }; return
        }
        // ziti -> udp: desencuadra datagramas y los envía al target local.
        val toUdp = scope.launch {
            try {
                while (isActive && !closed.get()) {
                    val pkt = readUdpFrame(zitiChannel) ?: break
                    udp.write(ByteBuffer.wrap(pkt))
                }
            } catch (_: Exception) {
            } finally {
                runCatching { closeQuiet() }
            }
        }
        // udp -> ziti: recibe respuestas del target y las enmarca en el stream.
        val toZiti = scope.launch {
            try {
                val buf = ByteBuffer.allocate(UDP_MAX_DATAGRAM)
                while (isActive && !closed.get()) {
                    buf.clear()
                    udp.receive(buf)
                    if (buf.position() == 0) continue
                    buf.flip()
                    val payload = ByteArray(buf.remaining()); buf.get(payload)
                    writeUdpFrame(zitiChannel, payload)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { closeQuiet() }
            }
        }
        toUdp.invokeOnCompletion { runCatching { toZiti.cancel() } }
        toZiti.invokeOnCompletion { runCatching { toUdp.cancel() } }
        listOf(toUdp, toZiti).forEach { it.invokeOnCompletion { runCatching { zitiChannel.close() }; runCatching { flows.remove(udp) }; runCatching { udp.close() } } }
    }

    private fun closeQuiet() = Unit // los closures reales se hacen en invokeOnCompletion

    /** Número de flujos UDP activos ahora mismo. */
    val activeFlows: Int get() = flows.size

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptJob.cancel()
        runCatching { server.close() }
        flows.forEach { runCatching { it.close() } }
        flows.clear()
    }
}

/**
 * Lado access de un share `udpTunnel` privado: escucha en un puerto UDP local y
 * transporta cada datagrama al share remoto (y de vuelta) usando el mismo
 * framing de prefijo de longitud que [ZrokUdpTunnelServer]. La app envía UDP a
 * 127.0.0.1:[localPort] como a cualquier servicio UDP.
 *
 * Cada dirección de origen (srcAddr) se asocia a su propia conexión ziti, igual
 * que el frontend udpTunnel del SDK Go.
 */
class ZrokUdpTunnelAccess internal constructor(
    val localPort: Int,
    private val dialZiti: () -> AsynchronousSocketChannel,
    parentScope: CoroutineScope,
) : AutoCloseable {

    private data class Flow(val channel: AsynchronousSocketChannel, val job: Job)

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val flows = ConcurrentHashMap<SocketAddress, Flow>()
    private val udp: DatagramChannel = DatagramChannel.open().apply {
        configureBlocking(true)
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), localPort))
    }

    /** Puerto UDP local real en el que escucha el access (puede ser efímero). */
    val boundPort: Int get() = (udp.localAddress as? InetSocketAddress)?.port ?: 0

    private val receiveJob = scope.launch {
        val buf = ByteBuffer.allocate(UDP_MAX_DATAGRAM)
        while (isActive && !closed.get()) {
            buf.clear()
            val src = try {
                udp.receive(buf)
            } catch (_: Exception) {
                break
            } ?: break
            if (closed.get()) break
            if (buf.position() == 0) continue
            buf.flip()
            val payload = ByteArray(buf.remaining()); buf.get(payload)
            sendToFlow(src, payload)
        }
    }

    private fun sendToFlow(src: SocketAddress, payload: ByteArray) {
        val flow = flows[src] ?: try {
            val channel = dialZiti()
            val newFlow = Flow(channel, scope.launch { readBack(channel, src) })
            flows[src] = newFlow
            // Si el flujo de vuelta termina, limpiar el map.
            newFlow.job.invokeOnCompletion { flows.remove(src, newFlow); runCatching { channel.close() } }
            newFlow
        } catch (_: Exception) {
            return
        }
        try {
            writeUdpFrame(flow.channel, payload)
        } catch (_: Exception) {
            runCatching { flow.channel.close() }
            flows.remove(src, flow)
        }
    }

    private suspend fun readBack(channel: AsynchronousSocketChannel, src: SocketAddress) {
        try {
            while (scope.isActive && !closed.get()) {
                val pkt = readUdpFrame(channel) ?: break
                udp.send(ByteBuffer.wrap(pkt), src)
            }
        } catch (_: Exception) {
        }
    }

    /** Número de flujos UDP activos ahora mismo. */
    val activeFlows: Int get() = flows.size

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        receiveJob.cancel()
        flows.values.forEach { runCatching { it.channel.close() }; runCatching { it.job.cancel() } }
        flows.clear()
        runCatching { udp.close() }
    }
}
