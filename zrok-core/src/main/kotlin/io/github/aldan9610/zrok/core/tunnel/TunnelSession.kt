package io.github.aldan9610.zrok.core.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Puente bidireccional entre un canal de ziti (el extremo remoto del túnel) y un
 * socket local (el backend target en el lado host, o la conexión local de la app
 * en el lado cliente). Cuando una dirección termina (EOF o error de E/S) la
 * sesión completa se cierra, igual que hace el backend del SDK Go de zrok.
 */
internal class TunnelSession(
    private val zitiChannel: AsynchronousSocketChannel,
    private val local: Socket,
    parentScope: CoroutineScope,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    private val zitiToTarget = scope.launchCopy(
        read = { buffer -> zitiChannel.read(buffer).get() },
        write = { buffer ->
            val out: OutputStream = local.getOutputStream()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            out.write(bytes)
            out.flush()
        },
    )

    private val targetToZiti = scope.launchCopy(
        read = { buffer ->
            val input: InputStream = local.getInputStream()
            val head = buffer.position()
            val n = input.read(buffer.array(), head, buffer.remaining())
            if (n > 0) buffer.position(head + n)
            n
        },
        write = { buffer ->
            while (buffer.hasRemaining()) zitiChannel.write(buffer).get()
        },
    )

    /** Registra un callback que se invoca una sola vez cuando la sesión termina. */
    fun onClosed(listener: () -> Unit) {
        listeners.add(listener)
        if (closed.get()) listener()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        zitiToTarget.cancel()
        targetToZiti.cancel()
        runCatching { zitiChannel.close() }
        runCatching { local.close() }
        listeners.forEach { runCatching(it) }
    }

    private fun CoroutineScope.launchCopy(
        read: (ByteBuffer) -> Int,
        write: (ByteBuffer) -> Unit,
    ) = launch {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        try {
            while (isActive) {
                buffer.clear()
                val n = read(buffer)
                if (n < 0) break
                if (n == 0) continue
                buffer.flip()
                write(buffer)
            }
        } catch (_: IOException) {
            // Canal cerrado por el otro extremo o por close(): fin normal.
        } catch (_: Exception) {
            // Cualquier otro fallo de E/S termina la direccion.
        } finally {
            close()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}
