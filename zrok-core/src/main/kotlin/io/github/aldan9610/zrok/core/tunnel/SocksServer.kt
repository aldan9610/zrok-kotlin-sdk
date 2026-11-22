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
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

// Implementación del protocolo SOCKS5 (RFC 1928) y de la autenticación por
// usuario/contraseña (RFC 1929). Adaptada de la implementación de referencia
// del SDK Go de zrok (endpoints/socks/socks5.go, Apache-2.0) que a su vez la
// toma de Tailscale (BSD-3-Clause). Solo se soporta el comando CONNECT: es lo
// que usa un navegador para enrutar HTTP/HTTPS a través del proxy y salir con
// la IP del host que comparte (el caso de uso principal del backend socks).
// BIND y UDP ASSOCIATE se rechazan con commandNotSupported.

private const val SOCKS_VERSION: Byte = 5
private const val NO_AUTH: Byte = 0
private const val PASSWORD_AUTH: Byte = 2
private const val NO_ACCEPTABLE_AUTH: Byte = 255.toByte()
private const val PASSWORD_AUTH_VERSION: Byte = 1

private const val CMD_CONNECT: Byte = 1

private const val ATYP_IPV4: Byte = 1
private const val ATYP_DOMAIN: Byte = 3
private const val ATYP_IPV6: Byte = 4

private const val REP_SUCCESS: Byte = 0
private const val REP_GENERAL_FAILURE: Byte = 1
private const val REP_COMMAND_NOT_SUPPORTED: Byte = 7
private const val REP_ADDR_TYPE_NOT_SUPPORTED: Byte = 8

private const val CONNECT_TIMEOUT_MILLIS = 15_000

/** Rechazo limpio del handshake con un código de reply SOCKS concreto. */
private class SocksReject(val reply: Byte, message: String) : IOException(message)

/**
 * Lado host de un share de backend `socks`: escucha en el servicio ziti del
 * share y, por cada conexión entrante, habla SOCKS5 con el cliente (típicamente
 * el frontend público de zrok reenviando el stream TCP de un navegador) y abre
 * una conexión TCP al destino que el cliente pida usando la red del host.
 *
 * Es decir: el host comparte su IP. Un navegador remoto se configura con el
 * endpoint del share como proxy SOCKS5 y su tráfico sale con la IP de este host.
 *
 * @param server canal de escucha ziti del share (devuelto por [ZitiGateway.host]).
 * @param username usuario opcional para autenticación RFC 1929; si es null no
 * se exige auth. Recomendado para shares públicos (evita proxy abierto).
 * @param password contraseña opcional (ignorada si [username] es null).
 * @param parentScope ámbito de corrutinas para el bucle de aceptación.
 */
class ZrokSocksServer internal constructor(
    private val server: AsynchronousServerSocketChannel,
    private val username: String?,
    private val password: String?,
    parentScope: CoroutineScope,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val sessions = CopyOnWriteArraySet<TunnelSession>()

    private val acceptJob = scope.launch {
        while (isActive && !closed.get()) {
            val zitiChannel = try {
                server.accept().get() ?: break
            } catch (_: Exception) {
                break
            }
            if (!closed.get()) {
                scope.launch { handle(zitiChannel) }
            } else {
                runCatching { zitiChannel.close() }
            }
        }
    }

    private fun handle(zitiChannel: AsynchronousSocketChannel) {
        val input = AsyncChannelInputStream(zitiChannel)
        val output = AsyncChannelOutputStream(zitiChannel)
        var bridged = false
        try {
            val needAuth = !username.isNullOrBlank()
            if (!negotiateAuth(input, output, needAuth)) return
            val request = parseRequest(input)
            if (request.command != CMD_CONNECT) {
                writeReply(output, REP_COMMAND_NOT_SUPPORTED); return
            }
            val target = Socket()
            try {
                target.connect(InetSocketAddress(request.destination, request.port), CONNECT_TIMEOUT_MILLIS)
            } catch (_: Exception) {
                writeReply(output, REP_GENERAL_FAILURE); return
            }
            val bindPort = (target.localAddress as? InetSocketAddress)?.port ?: 0
            writeReply(output, REP_SUCCESS, bindAddress = target.localAddress, bindPort = bindPort)
            // El puente bidireccional reutiliza TunnelSession: cierra ambos al
            // detectar EOF/error en cualquier direccion, como el backend Go.
            val session = TunnelSession(zitiChannel, target, scope)
            session.onClosed { sessions.remove(session) }
            sessions.add(session)
            bridged = true
        } catch (reject: SocksReject) {
            runCatching { writeReply(output, reject.reply) }
        } catch (_: IOException) {
            // Cliente desconectado a mitad de handshake: fin normal.
        } catch (_: Exception) {
            runCatching { writeReply(output, REP_GENERAL_FAILURE) }
        } finally {
            // Si no se creo TunnelSession, el handler es propietario del canal.
            if (!bridged) runCatching { zitiChannel.close() }
        }
    }

    /** Número de sesiones activas (conexiones SOCKS reenviadas ahora mismo). */
    val activeSessions: Int get() = sessions.size

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptJob.cancel()
        runCatching { server.close() }
        sessions.forEach { runCatching { it.close() } }
    }

    // --- Protocolo SOCKS5 -------------------------------------------------

    private fun negotiateAuth(input: InputStream, output: OutputStream, needAuth: Boolean): Boolean {
        val version = input.read()
        val methodCount = input.read()
        if (version != SOCKS_VERSION.toInt() || methodCount < 0) {
            runCatching { output.write(byteArrayOf(SOCKS_VERSION, NO_ACCEPTABLE_AUTH)) }
            return false
        }
        val methods = ByteArray(methodCount)
        readFully(input, methods)
        val selected = when {
            needAuth && methods.any { it == PASSWORD_AUTH } -> PASSWORD_AUTH
            !needAuth && methods.any { it == NO_AUTH } -> NO_AUTH
            else -> {
                runCatching { output.write(byteArrayOf(SOCKS_VERSION, NO_ACCEPTABLE_AUTH)) }
                return false
            }
        }
        output.write(byteArrayOf(SOCKS_VERSION, selected))
        output.flush()
        if (!needAuth) return true
        // RFC 1929: ver/version(1) | ulen | user | plen | pass
        val authVersion = input.read()
        val ulen = input.read()
        if (authVersion != PASSWORD_AUTH_VERSION.toInt() || ulen < 0) return false
        val user = ByteArray(ulen).also { readFully(input, it) }
        val plen = input.read()
        if (plen < 0) return false
        val pass = ByteArray(plen).also { readFully(input, it) }
        val ok = String(user) == username && String(pass) == (password ?: "")
        output.write(byteArrayOf(PASSWORD_AUTH_VERSION, if (ok) 0 else 1))
        output.flush()
        return ok
    }

    private data class SocksRequest(val command: Byte, val destination: String, val port: Int)

    private fun parseRequest(input: InputStream): SocksRequest {
        val version = input.read()
        val command = input.read().toByte()
        input.read() // reserved
        val addrType = input.read()
        if (version != SOCKS_VERSION.toInt()) throw SocksReject(REP_GENERAL_FAILURE, "version no válida")
        val destination: String = when (addrType) {
            ATYP_IPV4.toInt() -> {
                val ip = ByteArray(4); readFully(input, ip)
                InetAddress.getByAddress(ip).hostAddress
            }
            ATYP_DOMAIN.toInt() -> {
                val len = input.read(); if (len <= 0) throw SocksReject(REP_GENERAL_FAILURE, "dominio vacío")
                val name = ByteArray(len); readFully(input, name); String(name)
            }
            ATYP_IPV6.toInt() -> {
                val ip = ByteArray(16); readFully(input, ip)
                InetAddress.getByAddress(ip).hostAddress
            }
            else -> throw SocksReject(REP_ADDR_TYPE_NOT_SUPPORTED, "tipo de dirección no soportado")
        }
        val portHi = input.read(); val portLo = input.read()
        if (portHi < 0 || portLo < 0) throw SocksReject(REP_GENERAL_FAILURE, "puerto incompleto")
        val port = ((portHi and 0xFF) shl 8) or (portLo and 0xFF)
        return SocksRequest(command, destination, port)
    }

    private fun writeReply(
        output: OutputStream,
        reply: Byte,
        bindAddress: java.net.InetAddress? = null,
        bindPort: Int = 0,
    ) {
        val pkt = mutableListOf<Byte>(SOCKS_VERSION, reply, 0)
        when {
            reply != REP_SUCCESS -> {
                pkt += ATYP_IPV4; pkt += 0; pkt += 0; pkt += 0; pkt += 0; pkt += 0; pkt += 0
            }
            bindAddress is Inet6Address -> {
                pkt += ATYP_IPV6; bindAddress.address.forEach { pkt += it }
                pkt += ((bindPort ushr 8) and 0xFF).toByte()
                pkt += (bindPort and 0xFF).toByte()
            }
            else -> {
                val v4 = (bindAddress as? Inet4Address) ?: InetAddress.getLoopbackAddress()
                pkt += ATYP_IPV4; v4.address.forEach { pkt += it }
                pkt += ((bindPort ushr 8) and 0xFF).toByte()
                pkt += (bindPort and 0xFF).toByte()
            }
        }
        output.write(pkt.toByteArray())
        output.flush()
    }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) throw IOException("lectura corta en handshake SOCKS5")
            off += n
        }
    }
}
