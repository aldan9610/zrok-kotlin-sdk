package io.github.aldan9610.zrok.core.tunnel

import io.github.aldan9610.zrok.core.state.EnvironmentState
import org.openziti.Ziti
import org.openziti.ZitiAddress
import org.openziti.ZitiContext
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.TimeUnit

/**
 * Abstracción sobre la red OpenZiti que transporta los datos de zrok. El nombre
 * del servicio ziti ES el token del share (semántica verificada en
 * sdk/golang/sdk/listener.go y dialer.go del repo de zrok).
 *
 * Aislar ziti detrás de esta interfaz permite usar el SDK completo en JVM y en
 * Android (ziti-jvm / ziti-android) y probar el plano de datos sin red real.
 */
interface ZitiGateway : AutoCloseable {

    /** Escucha (bind) en el servicio ziti con el nombre dado (lado host del share). */
    fun host(serviceName: String): AsynchronousServerSocketChannel

    /** Conecta (dial) con el servicio ziti con el nombre dado (lado cliente del share). */
    fun dial(serviceName: String): AsynchronousSocketChannel
}

/**
 * Implementación real sobre ziti-jvm: carga el contexto desde la identidad
 * OpenZiti que devuelve `POST /api/v2/enable` (campo `cfg` del estado).
 */
class ZitiContextGateway(private val ziti: ZitiContext) : ZitiGateway {

    override fun host(serviceName: String): AsynchronousServerSocketChannel =
        ziti.openServer().apply { bind(ZitiAddress.Bind(serviceName)) }

    override fun dial(serviceName: String): AsynchronousSocketChannel =
        ziti.open().apply {
            connect(ZitiAddress.Dial(serviceName)).get(DIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

    override fun close() {
        ziti.destroy()
    }

    companion object {
        const val DIAL_TIMEOUT_SECONDS = 30L
    }
}

/**
 * Crea el [ZitiGateway] por defecto desde el estado del entorno: instancia el
 * contexto ziti con el JSON de identidad (`cfg`) almacenado al habilitar.
 */
fun defaultZitiGateway(state: EnvironmentState): ZitiGateway =
    ZitiContextGateway(
        Ziti.newContext(
            ByteArrayInputStream(state.zitiConfigJson.toByteArray(Charsets.UTF_8)),
            CharArray(0),
        ),
    )

/** Conecta un [AsynchronousSocketChannel] a host:puerto local (utilidad interna). */
internal fun AsynchronousSocketChannel.connectTo(host: String, port: Int, timeoutMillis: Int) {
    connect(InetSocketAddress(host, port)).get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
}
