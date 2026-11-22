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
import io.github.aldan9610.zrok.core.tunnel.ZrokLocalAccess
import io.github.aldan9610.zrok.core.tunnel.ZrokTunnel
import io.github.aldan9610.zrok.core.tunnel.ZrokTunnelServer
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
 * Dos modos de uso, lanzados con los helpers estáticos:
 *  - Host (compartir): [startHostTunnel] — escucha en el servicio ziti del
 *    share y reenvía al backend local.
 *  - Access (consumir): [startLocalAccess] — puentea un puerto local hacia un
 *    share privado de otro dispositivo/usuario.
 *
 * Requiere un entorno previamente habilitado con
 * [io.github.aldan9610.zrok.core.ZrokEnvironment.enable] persistido en
 * [EncryptedZrokStateStore].
 */
class ZrokTunnelService : LifecycleService() {

    private var tunnel: ZrokTunnel? = null
    private var server: ZrokTunnelServer? = null
    private var access: ZrokLocalAccess? = null

    private var mode = MODE_HOST
    private var shareToken: String? = null
    private var targetHost: String? = null
    private var targetPort = 0
    private var localPort = 0
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
                val zrokTunnel = ZrokTunnel(environment)
                when (mode) {
                    MODE_HOST -> {
                        val host = requireNotNull(targetHost) { "falta EXTRA_TARGET_HOST" }
                        require(targetPort > 0) { "EXTRA_TARGET_PORT invalido" }
                        server = zrokTunnel.hostTcpTunnel(token, host, targetPort)
                    }
                    MODE_ACCESS -> {
                        access = zrokTunnel.startLocalAccess(token, localPort)
                    }
                }
                tunnel = zrokTunnel
            }
            _state.value = ZrokTunnelServiceState.Running(descriptionFor(mode, token), access?.localPort)
            startAsForeground(successNotificationText(token))
        } catch (t: Throwable) {
            fail("no se pudo abrir el túnel", t)
        }
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
                val zrokTunnel = ZrokTunnel(environment)
                when (mode) {
                    MODE_HOST -> {
                        server = zrokTunnel.hostTcpTunnel(token, requireNotNull(targetHost), targetPort)
                    }
                    MODE_ACCESS -> {
                        access = zrokTunnel.startLocalAccess(token, localPort)
                    }
                }
                tunnel = zrokTunnel
                _state.value = ZrokTunnelServiceState.Running(descriptionFor(mode, token), access?.localPort)
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
        runCatching { tunnel?.close() }
        server = null
        access = null
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
        else -> getString(R.string.zrok_tunnel_host_running, token)
    }

    private fun descriptionFor(mode: Int, token: String): String = when (mode) {
        MODE_ACCESS -> "access $token"
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
        const val EXTRA_CHANNEL_ID = "io.github.aldan9610.zrok.android.EXTRA_CHANNEL_ID"
        const val EXTRA_CHANNEL_NAME = "io.github.aldan9610.zrok.android.EXTRA_CHANNEL_NAME"

        const val ACTION_STOP = "io.github.aldan9610.zrok.android.ACTION_STOP"

        const val MODE_HOST = 0
        const val MODE_ACCESS = 1

        const val DEFAULT_CHANNEL_ID = "zrok_tunnel"
        const val DEFAULT_CHANNEL_NAME = "Túnel zrok"
        const val NOTIFICATION_ID = 0x7A2C

        private val _state = MutableStateFlow<ZrokTunnelServiceState>(ZrokTunnelServiceState.Idle)

        /** Estado actual del servicio (observable desde la UI). */
        val state: StateFlow<ZrokTunnelServiceState> get() = _state

        /** Lanza el servicio en modo host (compartir un servicio local). */
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

        /** Lanza el servicio en modo access (consumir un share privado). */
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

        /** Detiene el servicio y el túnel. */
        fun stop(context: Context) {
            context.stopService(baseIntent(context))
        }

        private fun baseIntent(context: Context): Intent =
            Intent(context, ZrokTunnelService::class.java)
    }
}
