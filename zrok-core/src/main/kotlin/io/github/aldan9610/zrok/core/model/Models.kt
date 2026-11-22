package io.github.aldan9610.zrok.core.model

/** Modo de un share (visibilidad). */
enum class ShareMode(val wire: String) {
    PUBLIC("public"),
    PRIVATE("private"),
    ;

    companion object {
        fun fromWire(value: String?): ShareMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Modo de backend de un share (cómo se alcanza el servicio local). */
enum class BackendMode(val wire: String) {
    PROXY("proxy"),
    WEB("web"),
    TCP_TUNNEL("tcpTunnel"),
    UDP_TUNNEL("udpTunnel"),
    CADDY("caddy"),
    DRIVE("drive"),
    SOCKS("socks"),
    ;

    companion object {
        fun fromWire(value: String?): BackendMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Usuario para la autenticación básica del frontend de un share público. */
data class BasicAuthUser(val username: String, val password: String)

/**
 * Share de zrok: el token identifica el servicio ziti subyacente (el nombre del
 * servicio ziti ES el token del share).
 */
data class Share(
    val token: String,
    val frontendEndpoints: List<String> = emptyList(),
    val shareMode: ShareMode? = null,
    val backendMode: BackendMode? = null,
    val target: String? = null,
    val zId: String? = null,
    val envZId: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

/** Access a un share privado (permite consumirlo desde este entorno). */
data class Access(
    /** Token del frontend del access (`frontendToken`); se usa para eliminarlo. */
    val token: String,
    val shareToken: String,
    val backendMode: BackendMode? = null,
)

/** Opciones avanzadas al crear un share. */
data class ShareOptions(
    /** Modo de permisos del share: "open" (cualquiera con el token) o "closed" (solo grants). */
    val permissionMode: String = "open",
    /** Token personalizado para shares privados. */
    val privateShareToken: String? = null,
    /** Autenticación básica en el frontend público. */
    val basicAuth: List<BasicAuthUser> = emptyList(),
    /** Proveedor OAuth ("google", "github") para frontends públicos. */
    val oauthProvider: String? = null,
    /** Patrones de dominios de email admitidos con OAuth. */
    val oauthEmailDomains: List<String> = emptyList(),
    /** Intervalo de refresco de OAuth (p. ej. "1h"). */
    val oauthRefreshInterval: String? = null,
    /** Grants de acceso (emails/identidades) para permissionMode "closed". */
    val accessGrants: List<String> = emptyList(),
)

/** Configuración pública de la instancia zrok (endpoint `/configuration`). */
data class ZrokConfiguration(
    val version: String? = null,
    val touLink: String? = null,
    val newAccountLink: String? = null,
    val invitesOpen: Boolean? = null,
    val requiresInviteToken: Boolean? = null,
)
