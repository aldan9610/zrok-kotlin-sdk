package io.github.aldan9610.zrok.sample

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.aldan9610.zrok.android.EncryptedZrokStateStore
import io.github.aldan9610.zrok.android.tunnel.ZrokTunnelService
import io.github.aldan9610.zrok.android.tunnel.ZrokTunnelServiceState
import io.github.aldan9610.zrok.core.ZrokEnvironment
import io.github.aldan9610.zrok.core.ZrokManager
import io.github.aldan9610.zrok.core.model.Share
import kotlinx.coroutines.launch

/**
 * Ejemplo de uso del SDK de zrok en Android:
 *  1. Habilita el entorno con el token de cuenta (se guarda cifrado).
 *  2. Crea un share tcpTunnel privado y mantiene el túnel con un
 *     ForegroundService.
 *  3. Crea un access a un share privado de otro dispositivo y puentea un
 *     puerto local a través de la red OpenZiti.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var environment: ZrokEnvironment
    private var manager: ZrokManager? = null
    private var currentShare: Share? = null

    private lateinit var status: TextView
    private lateinit var accessInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        accessInfo = findViewById(R.id.access_info)

        environment = ZrokEnvironment(stateStore = EncryptedZrokStateStore(this))
        if (environment.isEnabled) {
            manager = ZrokManager(environment)
            status.text = "Entorno habilitado (zId ${environment.state?.zId?.take(12)}…)"
        }

        lifecycleScope.launch {
            ZrokTunnelService.state.collect { renderState(it) }
        }

        findViewById<Button>(R.id.btn_enable).setOnClickListener { enableEnvironment() }
        findViewById<Button>(R.id.btn_share).setOnClickListener { shareService() }
        findViewById<Button>(R.id.btn_access).setOnClickListener { accessShare() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            ZrokTunnelService.stop(this)
            accessInfo.text = ""
        }
    }

    private fun enableEnvironment() {
        val token = findViewById<EditText>(R.id.account_token).text.toString().trim()
        if (token.isEmpty()) {
            toast("Introduce el token de cuenta")
            return
        }
        lifecycleScope.launch {
            try {
                val state = environment.enable(accountToken = token, description = "android-sample")
                manager = ZrokManager(environment)
                status.text = "Entorno habilitado (zId ${state.zId.take(12)}…)"
                toast("Entorno habilitado")
            } catch (t: Throwable) {
                toast("Error al habilitar: ${t.message}")
            }
        }
    }

    private fun shareService() {
        val m = manager
        if (m == null) {
            toast("Primero habilita el entorno")
            return
        }
        val host = findViewById<EditText>(R.id.target_host).text.toString().trim().ifEmpty { "127.0.0.1" }
        val port = findViewById<EditText>(R.id.target_port).text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            toast("Puerto local no válido")
            return
        }
        lifecycleScope.launch {
            try {
                val share = m.createTcpTunnelShare(host, port, isPrivate = true)
                currentShare = share
                // El servicio en primer plano mantiene el túnel vivo y reconecta
                // automáticamente si cambia la red.
                ZrokTunnelService.startHostTunnel(this@MainActivity, share.token, host, port)
                toast("Share creado: ${share.token}")
            } catch (t: Throwable) {
                toast("Error al compartir: ${t.message}")
            }
        }
    }

    private fun accessShare() {
        val m = manager
        if (m == null) {
            toast("Primero habilita el entorno")
            return
        }
        val shareToken = findViewById<EditText>(R.id.share_token).text.toString().trim()
        if (shareToken.isEmpty()) {
            toast("Introduce el token del share")
            return
        }
        lifecycleScope.launch {
            try {
                val access = m.createAccess(shareToken)
                // Puente local: conecta a 127.0.0.1:puerto y el tráfico viaja por
                // la red OpenZiti hasta el backend compartido.
                ZrokTunnelService.startLocalAccess(this@MainActivity, shareToken)
                accessInfo.text = "Access ${access.token} creado; puente iniciándose…"
            } catch (t: Throwable) {
                toast("Error al acceder: ${t.message}")
            }
        }
    }

    private fun renderState(state: ZrokTunnelServiceState) {
        val text = when (state) {
            is ZrokTunnelServiceState.Idle -> "Túnel inactivo"
            is ZrokTunnelServiceState.Starting -> "Túnel iniciando: ${state.description}"
            is ZrokTunnelServiceState.Running ->
                if (state.localPort != null) {
                    "Túnel activo en 127.0.0.1:${state.localPort} — ya puedes conectarte"
                } else {
                    "Túnel activo: ${state.description}"
                }
            is ZrokTunnelServiceState.Failed -> "Túnel con error: ${state.description} (${state.cause})"
        }
        runOnUiThread {
            status.text = text
            if (status.text.startsWith("Entorno habilitado") && text != "Túnel inactivo") {
                // Mantener visible la info del entorno + estado del túnel.
                status.text = "Entorno habilitado · $text"
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
