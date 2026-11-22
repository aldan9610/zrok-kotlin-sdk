package io.github.aldan9610.zrok.core.tunnel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel

/**
 * InputStream bloqueante sobre un [AsynchronousSocketChannel] (p. ej. un canal
 * de ziti). Las lecturas bloquean el hilo llamante hasta que hay datos o EOF.
 */
class AsyncChannelInputStream(private val channel: AsynchronousSocketChannel) : InputStream() {

    override fun read(): Int {
        val buffer = ByteBuffer.allocate(1)
        val n = read0(buffer)
        return if (n < 0) -1 else buffer.get(0).toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        return read0(ByteBuffer.wrap(b, off, len))
    }

    private fun read0(buffer: ByteBuffer): Int {
        while (true) {
            val n = try {
                channel.read(buffer).get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("lectura interrumpida")
            }
            if (n > 0) return n
            if (n < 0) return -1
            // n == 0: reintentar
        }
    }

    override fun available(): Int = if (channel.isOpen) 1 else 0

    override fun close() {
        // Cerrar el stream cierra el canal subyacente (sin medio-cierre).
        channel.close()
    }
}

/**
 * OutputStream bloqueante sobre un [AsynchronousSocketChannel]. Las escrituras
 * bloquean hasta que todos los bytes del buffer se han enviado.
 */
class AsyncChannelOutputStream(private val channel: AsynchronousSocketChannel) : OutputStream() {

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val buffer = ByteBuffer.wrap(b, off, len)
        while (buffer.hasRemaining()) {
            try {
                channel.write(buffer).get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("escritura interrumpida")
            }
        }
    }

    override fun flush() = Unit

    override fun close() {
        channel.close()
    }
}
