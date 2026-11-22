package io.github.aldan9610.zrok.core.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lado host de un túnel TCP: escucha en el servicio ziti del share y reenvía
 * cada conexión aceptada hacia el backend local (targetHost:targetPort),
 * replicando el comportamiento del backend tcpTunnel del SDK Go de zrok
 * (`NewListener` + bucle de aceptación con piping bidireccional).
 */
class ZrokTunnelServer internal constructor(
    private val server: AsynchronousServerSocketChannel,
    private val targetHost: String,
    private val targetPort: Int,
    parentScope: CoroutineScope,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val sessions = CopyOnWriteArraySet<TunnelSession>()
    private val closeListeners = CopyOnWriteArraySet<() -> Unit>()

    private val acceptJob = scope.launch {
        while (isActive && !closed.get()) {
            val zitiChannel = try {
                server.accept().get() ?: break
            } catch (_: Exception) {
                break // canal de escucha cerrado
            }
            if (!closed.get()) {
                acceptSession(zitiChannel)
            } else {
                runCatching { zitiChannel.close() }
            }
        }
    }

    private fun acceptSession(zitiChannel: AsynchronousSocketChannel) {
        val target = Socket()
        try {
            target.connect(java.net.InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MILLIS)
            val session = TunnelSession(zitiChannel, target, scope)
            session.onClosed { sessions.remove(session) }
            sessions.add(session)
        } catch (_: Exception) {
            runCatching { zitiChannel.close() }
            runCatching { target.close() }
        }
    }

    /** Número de sesiones activas (conexiones reenviadas ahora mismo). */
    val activeSessions: Int get() = sessions.size

    /** Registra un callback para cuando el servidor se cierre. */
    fun onClosed(listener: () -> Unit) {
        closeListeners.add(listener)
        if (closed.get()) listener()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptJob.cancel()
        runCatching { server.close() }
        sessions.forEach { it.close() }
        closeListeners.forEach { runCatching(it) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
    }
}

/**
 * Lado cliente de un access: escucha en un [ServerSocket] local y reenvía cada
 * conexión que la app haga a ese puerto hacia el servicio ziti del share
 * privado. Es el equivalente a `zrok access private <token>`: la app se conecta
 * a 127.0.0.1:[localPort] como si fuera una conexión de red normal.
 */
class ZrokLocalAccess internal constructor(
    val localPort: Int,
    private val serverSocket: ServerSocket,
    private val dialZiti: () -> AsynchronousSocketChannel,
    parentScope: CoroutineScope,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val sessions = CopyOnWriteArraySet<TunnelSession>()

    private val acceptJob = scope.launch {
        while (isActive && !closed.get()) {
            val local = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            if (closed.get()) {
                runCatching { local.close() }
                break
            }
            bridgeToZiti(local)
        }
    }

    private fun bridgeToZiti(local: Socket) {
        try {
            val zitiChannel = dialZiti()
            val session = TunnelSession(zitiChannel, local, scope)
            session.onClosed { sessions.remove(session) }
            sessions.add(session)
        } catch (_: Exception) {
            runCatching { local.close() }
        }
    }

    val activeSessions: Int get() = sessions.size

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptJob.cancel()
        runCatching { serverSocket.close() }
        sessions.forEach { it.close() }
    }
}

/**
 * Conexión directa al servicio ziti de un share (lado cliente sin listener
 * local): expone [inputStream]/[outputStream] bloqueantes listos para usar.
 */
class ZrokTunnelConnection internal constructor(
    private val channel: AsynchronousSocketChannel,
) : AutoCloseable {

    val inputStream: java.io.InputStream = AsyncChannelInputStream(channel)
    val outputStream: java.io.OutputStream = AsyncChannelOutputStream(channel)

    override fun close() {
        runCatching { channel.close() }
    }
}
