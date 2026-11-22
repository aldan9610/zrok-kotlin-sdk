package io.github.aldan9610.zrok.core.tunnel

import io.github.aldan9610.zrok.core.ZrokEnvironment
import io.github.aldan9610.zrok.core.model.Share
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Plano de datos del SDK: el transporte real del túnel sobre la red OpenZiti.
 *
 * La API REST de zrok (ZrokManager) solo crea y gestiona shares/accesses; los
 * bytes los transporta la red ziti, donde el servicio se llama igual que el
 * token del share. Este facade encapsula esa lógica:
 *
 *  - Lado host (quien comparte): [hostTcpTunnel] escucha en el servicio ziti y
 *    reenvía cada conexión al backend local.
 *  - Lado cliente (quien accede): [dialShare] conecta con el servicio y expone
 *    streams, o [startLocalAccess] puentea un puerto local hacia el share
 *    privado (equivalente a `zrok access private`).
 *
 * El contexto ziti se crea perezosamente desde la identidad OpenZiti (`cfg`)
 * devuelta por `POST /api/v2/enable` y almacenada en el entorno.
 *
 * @param environment entorno zrok habilitado.
 * @param scope ámbito de corrutinas para los bucles de aceptación y copia.
 * @param gatewayFactory fábrica del gateway ziti; inyectable para tests o para
 * sustituir la implementación de red.
 */
class ZrokTunnel(
    private val environment: ZrokEnvironment,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val gatewayFactory: (io.github.aldan9610.zrok.core.state.EnvironmentState) -> ZitiGateway = ::defaultZitiGateway,
) : AutoCloseable {

    private var gateway: ZitiGateway? = null
    private val endpoints = CopyOnWriteArraySet<AutoCloseable>()

    /** Escucha en el servicio ziti del share y reenvía al target del propio share. */
    fun hostTcpTunnel(share: Share): ZrokTunnelServer {
        val target = requireNotNull(share.target) { "el share no tiene target (host:puerto)" }
        val (host, port) = parseHostPort(target)
        return hostTcpTunnel(share.token, host, port)
    }

    /**
     * Escucha en el servicio ziti con nombre [shareToken] y reenvía cada conexión
     * aceptada al backend local [targetHost]:[targetPort]. Usar tras crear el
     * share tcpTunnel con ZrokManager.
     */
    fun hostTcpTunnel(shareToken: String, targetHost: String, targetPort: Int): ZrokTunnelServer {
        val server = gateway().host(shareToken)
        return ZrokTunnelServer(server, targetHost, targetPort, scope).also {
            endpoints.add(it)
            it.onClosed { endpoints.remove(it) }
        }
    }

    /**
     * Lado host de un share `socks`: acepta conexiones ziti y les habla SOCKS5
     * (solo CONNECT), abriendo cada destino con la red del host. Así el host
     * comparte su IP: un navegador que use el endpoint del share como proxy
     * SOCKS5 sale con la IP de esta máquina.
     *
     * @param username usuario opcional (RFC 1929). Recomendado en shares
     * públicos para no dejar un proxy abierto; null = sin auth.
     * @param password contraseña opcional (ignorada si [username] es null).
     */
    fun hostSocks(
        shareToken: String,
        username: String? = null,
        password: String? = null,
    ): ZrokSocksServer {
        val server = gateway().host(shareToken)
        return ZrokSocksServer(server, username, password, scope).also { endpoints.add(it) }
    }

    /**
     * Lado host de un share `udpTunnel`: acepta conexiones ziti y transporta
     * datagramas hacia/desde el backend UDP local [targetHost]:[targetPort]
     * usando el framing de prefijo de longitud (ver [ZrokUdpTunnelServer]).
     *
     * Requiere que el access también use este SDK Kotlin (framing propio). Para
     * shares udpTunnel públicos use el binario Go `zrok` como host.
     */
    fun hostUdpTunnel(share: Share): ZrokUdpTunnelServer {
        val target = requireNotNull(share.target) { "el share no tiene target (host:puerto)" }
        val (host, port) = parseHostPort(target)
        return hostUdpTunnel(share.token, host, port)
    }

    fun hostUdpTunnel(
        shareToken: String,
        targetHost: String,
        targetPort: Int,
    ): ZrokUdpTunnelServer {
        val server = gateway().host(shareToken)
        return ZrokUdpTunnelServer(server, targetHost, targetPort, scope).also { endpoints.add(it) }
    }

    /**
     * Lado access de un share `udpTunnel` privado: abre un listener UDP local
     * (puerto efímero si [localPort] es 0) y transporta cada datagrama al share
     * remoto. La app envía UDP a 127.0.0.1:[ZrokUdpTunnelAccess.boundPort].
     *
     * Requiere que el host también use este SDK Kotlin (framing propio).
     */
    fun startUdpAccess(shareToken: String, localPort: Int = 0): ZrokUdpTunnelAccess {
        val access = ZrokUdpTunnelAccess(localPort, { gateway().dial(shareToken) }, scope)
        endpoints.add(access)
        return access
    }

    /**
     * Conecta directamente con el servicio ziti del share y devuelve la conexión
     * con streams bloqueantes listos para leer y escribir.
     */
    fun dialShare(shareToken: String): ZrokTunnelConnection {
        val channel = gateway().dial(shareToken)
        return ZrokTunnelConnection(channel)
    }

    /**
     * Abre un listener local (por defecto en un puerto efímero) y puentea cada
     * conexión hacia el servicio ziti del share privado. Devuelve el access
     * local, cuyo [ZrokLocalAccess.localPort] es el puerto al que debe conectarse
     * la aplicación.
     */
    fun startLocalAccess(shareToken: String, localPort: Int = 0): ZrokLocalAccess {
        val serverSocket = ServerSocket(localPort)
        val access = ZrokLocalAccess(serverSocket.localPort, serverSocket, { gateway().dial(shareToken) }, scope)
        endpoints.add(access)
        return access
    }

    /** Cierra todos los endpoints abiertos por este túnel y destruye el contexto ziti. */
    override fun close() {
        endpoints.forEach { runCatching { it.close() } }
        endpoints.clear()
        runCatching { gateway?.close() }
        gateway = null
        scope.cancel()
    }

    @Synchronized
    private fun gateway(): ZitiGateway =
        gateway ?: gatewayFactory(environment.requireState()).also { gateway = it }

    private companion object {
        fun parseHostPort(target: String): Pair<String, Int> {
            val idx = target.lastIndexOf(':')
            require(idx > 0 && idx < target.length - 1) { "target invalido: '$target' (se esperaba host:puerto)" }
            return target.substring(0, idx) to target.substring(idx + 1).toInt()
        }
    }
}
