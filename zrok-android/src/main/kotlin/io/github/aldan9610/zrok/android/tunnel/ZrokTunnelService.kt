package io.github.aldan9610.zrok.android.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import io.github.aldan9610.zrok.android.EncryptedZrokStateStore
import io.github.aldan9610.zrok.android.R
import io.github.aldan9610.zrok.core.ZrokConfig
import io.github.aldan9610.zrok.core.ZrokEnvironment
import io.github.aldan9610.zrok.core.tunnel.ZrokDriveServer
import io.github.aldan9610.zrok.core.tunnel.ZrokLocalAccess
import io.github.aldan9610.zrok.core.tunnel.ZrokSocksServer
import io.github.aldan9610.zrok.core.tunnel.ZrokTunnel
import io.github.aldan9610.zrok.core.tunnel.ZrokTunnelServer
import io.github.aldan9610.zrok.core.tunnel.ZrokUdpTunnelAccess
import io.github.aldan9610.zrok.core.tunnel.ZrokUdpTunnelServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Estado del servicio de túnel, observable desde la UI.
 */
sealed class ZrokTunnelServiceState {
    /** El servicio no está activo. */
    data object Idle : ZrokTunnelServiceState()

    /** Cargando entorno y abriendo el túnel. */
    data class Starting(val description: String) : ZrokTunnelServiceState()

    /** Túnel operativo. */
    data class Running(val description: String, val localPort: Int? = null) : ZrokTunnelServiceState()

    /** Error irrecuperable (sin entorno habilitado, fallo del túnel, ...). */
    data class Failed(val description: String, val cause: String?) : ZrokTunnelServiceState()
}

/**
 * ForegroundService que mantiene vivo un túnel de zrok con notificación
 * persistente (requisito de Android para trabajo en segundo plano) y
 * reconexión automática cuando cambia la red (Wi-Fi <-> datos móviles).
 *
 * Modos de uso, lanzados con los helpers estáticos:
 *  - Host TCP (compartir): [startHostTunnel] — reenvía al backend TCP local.
 *  - Access TCP (consumir): [startLocalAccess] — puentea un puerto local hacia
 *    un share privado tcpTunnel.
 *  - SOCKS (compartir IP): [startSocksTunnel] — proxy SOCKS5 que sale con la
 *    red del dispositivo; un navegador remoto que use el endpoint como proxy
 *    SOCKS5 navega con la IP de este dispositivo.
 *  - Host UDP: [startUdpHostTunnel] — backend udpTunnel (ambos extremos Kotlin).
 *  - Access UDP: [startUdpAccess] — puerto UDP local hacia un share udpTunnel.
 *  - Drive: [startDriveTunnel] — sirve un directorio como WebDAV.
 *
 * Requiere un entorno previamente habilitado con
 * [io.github.aldan9610.zrok.core.ZrokEnvironment.enable] persistido en
 * [EncryptedZrokStateStore].
 */
class ZrokTunnelService : LifecycleService() {

    private var tunnel: ZrokTunnel? = null
    private var server: ZrokTunnelServer? = null
    private var access: ZrokLocalAccess? = null
    private var socksServer: ZrokSocksServer? = null
    private var udpServer: ZrokUdpTunnelServer? = null
    private var udpAccess: ZrokUdpTunnelAccess? = null
    private var driveServer: ZrokDriveServer? = null

    private var mode = MODE_HOST
    private var shareToken: String? = null
    private var targetHost: String? = null
    private var targetPort = 0
    private var localPort = 0
    private var socksUser: String? = null
    private var socksPass: String? = null
    private var driveRoot: String? = null
    private var channelId = DEFAULT_CHANNEL_ID
    private var channelName = DEFAULT_CHANNEL_NAME

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkLost = false
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        mode = intent.getIntExtra(EXTRA_MODE, MODE_HOST)
        shareToken = intent.getStringExtra(EXTRA_SHARE_TOKEN)
        targetHost = intent.getStringExtra(EXTRA_TARGET_HOST)
        targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, 0)
        localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 0)
        socksUser = intent.getStringExtra(EXTRA_SOCKS_USER)
        socksPass = intent.getStringExtra(EXTRA_SOCKS_PASS)
        driveRoot = intent.getStringExtra(EXTRA_DRIVE_ROOT)
        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: DEFAULT_CHANNEL_NAME

        startAsForeground(getString(R.string.zrok_tunnel_starting))

        val token = shareToken
        if (token.isNullOrBlank()) {
            fail("falta el token del share (EXTRA_SHARE_TOKEN)")
            return START_NOT_STICKY
        }

        _state.value = ZrokTunnelServiceState.Starting(descriptionFor(mode, token))
        serviceScope.launch { startTunnel(token) }
        registerReconnectListener()
        return START_STICKY
    }

    private suspend fun startTunnel(token: String) {
        try {
            withTimeout(STARTUP_TIMEOUT_MILLIS) {
                val environment = ZrokEnvironment(
                    config = ZrokConfig(),
                    stateStore = EncryptedZrokStateStore(applicationContext),
                )
                if (!environment.isEnabled) {
                    throw IllegalStateException("no hay entorno zrok habilitado: llama a ZrokEnvironment.enable() primero")
                }
                tunnel = openEndpoints(ZrokTunnel(environment), token)
            }
            _state.value = ZrokTunnelServiceState.Running(descriptionFor(mode, token), localPortForRunning())
            startAsForeground(successNotificationText(token))
        } catch (t: Throwable) {
            fail("no se pudo abrir el túnel", t)
        }
    }

    /** Abre los endpoints del modo actual sobre [zrokTunnel] y los devuelve. */
    private fun openEndpoints(zrokTunnel: ZrokTunnel, token: String): ZrokTunnel {
        when (mode) {
            MODE_HOST -> {
                val host = requireNotNull(targetHost) { "falta EXTRA_TARGET_HOST" }
                require(targetPort > 0) { "EXTRA_TARGET_PORT invalido" }
                server = zrokTunnel.hostTcpTunnel(token, host, targetPort)
            }
            MODE_ACCESS -> {
                access = zrokTunnel.startLocalAccess(token, localPort)
            }
            MODE_SOCKS -> {
                socksServer = zrokTunnel.hostSocks(token, socksUser?.takeIf { it.isNotBlank() }, socksPass)
            }
            MODE_UDP_HOST -> {
                val host = requireNotNull(targetHost) { "falta EXTRA_TARGET_HOST" }
                require(targetPort > 0) { "EXTRA_TARGET_PORT invalido" }
                udpServer = zrokTunnel.hostUdpTunnel(token, host, targetPort)
            }
            MODE_UDP_ACCESS -> {
                udpAccess = zrokTunnel.startUdpAccess(token, localPort)
            }
            MODE_DRIVE -> {
                val root = requireNotNull(driveRoot) { "falta EXTRA_DRIVE_ROOT" }
                driveServer = zrokTunnel.hostDrive(token, root)
            }
        }
        return zrokTunnel
    }

    private fun localPortForRunning(): Int? = when (mode) {
        MODE_ACCESS -> access?.localPort
        MODE_UDP_ACCESS -> udpAccess?.boundPort
        else -> null
    }

    /** Reconexión: recrea el túnel completo cuando la red vuelve tras una pérdida. */
    private fun restartTunnel() {
        val token = shareToken ?: return
        if (_state.value is ZrokTunnelServiceState.Failed) return
        serviceScope.launch {
            _state.value = ZrokTunnelServiceState.Starting("reconectando: ${descriptionFor(mode, token)}")
            closeTunnelEndpoints()
            try {
                val environment = ZrokEnvironment(
                    config = ZrokConfig(),
                    stateStore = EncryptedZrokStateStore(applicationContext),
                )
                tunnel = openEndpoints(ZrokTunnel(environment), token)
                _state.value = ZrokTunnelServiceState.Running(descriptionFor(mode, token), localPortForRunning())
            } catch (t: Throwable) {
                _state.value = ZrokTunnelServiceState.Failed("reconexion fallida", t.message)
            }
        }
    }

    private fun registerReconnectListener() {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (connectivityCallback != null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                networkLost = true
            }

            override fun onAvailable(network: Network) {
                if (networkLost) {
                    networkLost = false
                    restartTunnel()
                }
            }
        }
        connectivityCallback = callback
        connectivity.registerNetworkCallback(request, callback)
    }

    private fun closeTunnelEndpoints() {
        runCatching { server?.close() }
        runCatching { access?.close() }
        runCatching { socksServer?.close() }
        runCatching { udpServer?.close() }
        runCatching { udpAccess?.close() }
        runCatching { driveServer?.close() }
        runCatching { tunnel?.close() }
        server = null
        access = null
        socksServer = null
        udpServer = null
        udpAccess = null
        driveServer = null
        tunnel = null
    }

    /** Detiene el tunel y el servicio (accion de la notificacion o stop()). */
    private fun stopTunnel() {
        closeTunnelEndpoints()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String, cause: Throwable? = null) {
        _state.value = ZrokTunnelServiceState.Failed(message, cause?.message)
        startAsForeground("$message${cause?.let { ": ${it.message}" } ?: ""}")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW),
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ZrokTunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download) // icono estandar; la app puede sobrescribir con su notificacion propia
            .setContentTitle(getString(R.string.zrok_tunnel_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.zrok_tunnel_stop), stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun successNotificationText(token: String): String = when (mode) {
        MODE_ACCESS -> getString(R.string.zrok_tunnel_access_running, token, access?.localPort ?: localPort)
        MODE_UDP_ACCESS -> getString(R.string.zrok_tunnel_access_running, token, udpAccess?.boundPort ?: localPort)
        else -> getString(R.string.zrok_tunnel_host_running, token)
    }

    private fun descriptionFor(mode: Int, token: String): String = when (mode) {
        MODE_ACCESS -> "access $token"
        MODE_SOCKS -> "socks $token"
        MODE_UDP_HOST -> "udp-host $token"
        MODE_UDP_ACCESS -> "udp-access $token"
        MODE_DRIVE -> "drive $token"
        else -> "host $token"
    }

    override fun onDestroy() {
        connectivityCallback?.let { callback ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(callback)
            }
        }
        connectivityCallback = null
        closeTunnelEndpoints()
        serviceScope.cancel()
        _state.value = ZrokTunnelServiceState.Idle
        super.onDestroy()
    }

    /** Trata la accion de parada enviada desde la notificacion. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {

        private const val STARTUP_TIMEOUT_MILLIS = 30_000L

        const val EXTRA_MODE = "io.github.aldan9610.zrok.android.EXTRA_MODE"
        const val EXTRA_SHARE_TOKEN = "io.github.aldan9610.zrok.android.EXTRA_SHARE_TOKEN"
        const val EXTRA_TARGET_HOST = "io.github.aldan9610.zrok.android.EXTRA_TARGET_HOST"
        const val EXTRA_TARGET_PORT = "io.github.aldan9610.zrok.android.EXTRA_TARGET_PORT"
        const val EXTRA_LOCAL_PORT = "io.github.aldan9610.zrok.android.EXTRA_LOCAL_PORT"
        const val EXTRA_SOCKS_USER = "io.github.aldan9610.zrok.android.EXTRA_SOCKS_USER"
        const val EXTRA_SOCKS_PASS = "io.github.aldan9610.zrok.android.EXTRA_SOCKS_PASS"
        const val EXTRA_DRIVE_ROOT = "io.github.aldan9610.zrok.android.EXTRA_DRIVE_ROOT"
        const val EXTRA_CHANNEL_ID = "io.github.aldan9610.zrok.android.EXTRA_CHANNEL_ID"
        const val EXTRA_CHANNEL_NAME = "io.github.aldan9610.zrok.android.EXTRA_CHANNEL_NAME"

        const val ACTION_STOP = "io.github.aldan9610.zrok.android.ACTION_STOP"

        const val MODE_HOST = 0
        const val MODE_ACCESS = 1
        const val MODE_SOCKS = 2
        const val MODE_UDP_HOST = 3
        const val MODE_UDP_ACCESS = 4
        const val MODE_DRIVE = 5

        const val DEFAULT_CHANNEL_ID = "zrok_tunnel"
        const val DEFAULT_CHANNEL_NAME = "Túnel zrok"
        const val NOTIFICATION_ID = 0x7A2C

        private val _state = MutableStateFlow<ZrokTunnelServiceState>(ZrokTunnelServiceState.Idle)

        /** Estado actual del servicio (observable desde la UI). */
        val state: StateFlow<ZrokTunnelServiceState> get() = _state

        /** Lanza el servicio en modo host TCP (compartir un servicio local). */
        fun startHostTunnel(
            context: Context,
            shareToken: String,
            targetHost: String,
            targetPort: Int,
            channelId: String = DEFAULT_CHANNEL_ID,
            channelName: String = DEFAULT_CHANNEL_NAME,
        ) {
            val intent = baseIntent(context)
                .putExtra(EXTRA_MODE, MODE_HOST)
                .putExtra(EXTRA_SHARE_TOKEN, shareToken)
                .putExtra(EXTRA_TARGET_HOST, targetHost)
                .putExtra(EXTRA_TARGET_PORT, targetPort)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_CHANNEL_NAME, channelName)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Lanza el servicio en modo access TCP (consumir un share privado). */
        fun startLocalAccess(
            context: Context,
            shareToken: String,
            localPort: Int = 0,
            channelId: String = DEFAULT_CHANNEL_ID,
            channelName: String = DEFAULT_CHANNEL_NAME,
        ) {
            val intent = baseIntent(context)
                .putExtra(EXTRA_MODE, MODE_ACCESS)
                .putExtra(EXTRA_SHARE_TOKEN, shareToken)
                .putExtra(EXTRA_LOCAL_PORT, localPort)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_CHANNEL_NAME, channelName)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Lanza el servicio en modo SOCKS: el dispositivo comparte su IP como
         * proxy SOCKS5. Un navegador que use el endpoint del share como proxy
         * SOCKS5 navega con la IP de este dispositivo.
         *
         * @param socksUser usuario opcional (RFC 1929); null = sin auth. En
         * shares públicos recomienda usarlo para no dejar un proxy abierto.
         * @param socksPass contraseña opcional (ignorada si [socksUser] es null).
         */
        fun startSocksTunnel(
            context: Context,
            shareToken: String,
            socksUser: String? = null,
            socksPass: String? = null,
            channelId: String = DEFAULT_CHANNEL_ID,
            channelName: String = DEFAULT_CHANNEL_NAME,
        ) {
            val intent = baseIntent(context)
                .putExtra(EXTRA_MODE, MODE_SOCKS)
                .putExtra(EXTRA_SHARE_TOKEN, shareToken)
                .putExtra(EXTRA_SOCKS_USER, socksUser)
                .putExtra(EXTRA_SOCKS_PASS, socksPass)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_CHANNEL_NAME, channelName)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Lanza el servicio en modo host UDP (backend udpTunnel, extremo Kotlin). */
        fun startUdpHostTunnel(
            context: Context,
            shareToken: String,
            targetHost: String,
            targetPort: Int,
            channelId: String = DEFAULT_CHANNEL_ID,
            channelName: String = DEFAULT_CHANNEL_NAME,
        ) {
            val intent = baseIntent(context)
                .putExtra(EXTRA_MODE, MODE_UDP_HOST)
                .putExtra(EXTRA_SHARE_TOKEN, shareToken)
                .putExtra(EXTRA_TARGET_HOST, targetHost)
                .putExtra(EXTRA_TARGET_PORT, targetPort)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_CHANNEL_NAME, channelName)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Lanza el servicio en modo access UDP (puerto UDP local hacia un share udpTunnel). */
        fun startUdpAccess(
            context: Context,
            shareToken: String,
            localPort: Int = 0,
            channelId: String = DEFAULT_CHANNEL_ID,
            channelName: String = DEFAULT_CHANNEL_NAME,
        ) {
            val intent = baseIntent(context)
                .putExtra(EXTRA_MODE, MODE_UDP_ACCESS)
                .putExtra(EXTRA_SHARE_TOKEN, shareToken)
                .putExtra(EXTRA_LOCAL_PORT, localPort)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_CHANNEL_NAME, channelName)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Lanza el servicio en modo drive: sirve [driveRoot] como WebDAV a
         * través del share. Montable desde el Explorador de Windows, Cyberduck,
         * rclone o Finder vía el endpoint del share.
         */
        fun startDriveTunnel(
            context: Context,
            shareToken: String,
            driveRoot: String,
            channelId: String = DEFAULT_CHANNEL_ID,
            channelName: String = DEFAULT_CHANNEL_NAME,
        ) {
            val intent = baseIntent(context)
                .putExtra(EXTRA_MODE, MODE_DRIVE)
                .putExtra(EXTRA_SHARE_TOKEN, shareToken)
                .putExtra(EXTRA_DRIVE_ROOT, driveRoot)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_CHANNEL_NAME, channelName)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Detiene el servicio y el túnel. */
        fun stop(context: Context) {
            context.stopService(baseIntent(context))
        }

        private fun baseIntent(context: Context): Intent =
            Intent(context, ZrokTunnelService::class.java)
    }
}
