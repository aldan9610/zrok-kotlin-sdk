package io.github.aldan9610.zrok.core

import io.github.aldan9610.zrok.api.models.AccessRequest
import io.github.aldan9610.zrok.api.models.AccessSummary
import io.github.aldan9610.zrok.api.models.AuthUser
import io.github.aldan9610.zrok.api.models.Configuration
import io.github.aldan9610.zrok.api.models.Share as ApiShare
import io.github.aldan9610.zrok.api.models.ShareRequest
import io.github.aldan9610.zrok.api.models.ShareSummary
import io.github.aldan9610.zrok.api.models.UnaccessRequest
import io.github.aldan9610.zrok.api.models.UnshareRequest
import io.github.aldan9610.zrok.core.internal.bodyOrThrow
import io.github.aldan9610.zrok.core.internal.ensureSuccess
import io.github.aldan9610.zrok.core.model.Access
import io.github.aldan9610.zrok.core.model.BackendMode
import io.github.aldan9610.zrok.core.model.BasicAuthUser
import io.github.aldan9610.zrok.core.model.Share
import io.github.aldan9610.zrok.core.model.ShareMode
import io.github.aldan9610.zrok.core.model.ShareOptions
import io.github.aldan9610.zrok.core.model.ZrokConfiguration

/**
 * Fachada de alto nivel del SDK: ciclo de vida de shares y accesses sobre la API
 * REST de zrok. Requiere un [ZrokEnvironment] habilitado.
 *
 * Ejemplo:
 * ```kotlin
 * val manager = ZrokManager(environment)
 * val share = manager.createProxyShare("http://127.0.0.1:8080")
 * println(share.frontendEndpoints)   // https://<token>.share.zrok.io
 * manager.deleteShare(share.token)
 * ```
 */
class ZrokManager internal constructor(
    private val environment: ZrokEnvironment,
    private val apis: io.github.aldan9610.zrok.core.internal.ZrokApis,
) {

    constructor(environment: ZrokEnvironment) : this(environment, environment.apis())

    /** Crea un share de backend proxy: expone un servicio web local (HTTP(S)). */
    suspend fun createProxyShare(
        targetUrl: String,
        isPrivate: Boolean = false,
        options: ShareOptions = ShareOptions(),
    ): Share = createShare(BackendMode.PROXY, targetUrl, if (isPrivate) ShareMode.PRIVATE else ShareMode.PUBLIC, options)

    /** Crea un share de backend web: sirve ficheros estáticos desde rootPath. */
    suspend fun createWebShare(
        rootPath: String,
        isPrivate: Boolean = false,
        options: ShareOptions = ShareOptions(),
    ): Share = createShare(BackendMode.WEB, rootPath, if (isPrivate) ShareMode.PRIVATE else ShareMode.PUBLIC, options)

    /**
     * Crea un share de backend tcpTunnel: tuneliza un servicio TCP local
     * (base de datos, servidor de juegos, ...). El target se forma como
     * `host:port`.
     */
    suspend fun createTcpTunnelShare(
        targetHost: String,
        targetPort: Int,
        isPrivate: Boolean = true,
        options: ShareOptions = ShareOptions(),
    ): Share = createShare(
        BackendMode.TCP_TUNNEL,
        "$targetHost:$targetPort",
        if (isPrivate) ShareMode.PRIVATE else ShareMode.PUBLIC,
        options,
    )

    /** Crea un share de backend udpTunnel. El target se forma como `host:port`. */
    suspend fun createUdpTunnelShare(
        targetHost: String,
        targetPort: Int,
        isPrivate: Boolean = true,
        options: ShareOptions = ShareOptions(),
    ): Share = createShare(
        BackendMode.UDP_TUNNEL,
        "$targetHost:$targetPort",
        if (isPrivate) ShareMode.PRIVATE else ShareMode.PUBLIC,
        options,
    )

    /**
     * Crea un share de backend `socks`: expone un proxy SOCKS5 cuya salida es la
     * red del host que comparte. Un cliente remoto (un navegador) que use el
     * endpoint del share como proxy SOCKS5 navega con la IP de este host.
     *
     * El target del share es vacío: el destino lo decide cada CONNECT del cliente
     * SOCKS (igual que `zrok share socks` no toma target).
     *
     * Para shares públicos conviene añadir auth ([ShareOptions.basicAuth] o un
     * [io.github.aldan9610.zrok.core.model.ShareOptions.permissionMode] "closed")
     * para no dejar un proxy abierto.
     */
    suspend fun createSocksShare(
        isPrivate: Boolean = false,
        options: ShareOptions = ShareOptions(),
    ): Share = createShare(
        BackendMode.SOCKS,
        "",
        if (isPrivate) ShareMode.PRIVATE else ShareMode.PUBLIC,
        options,
    )

    /**
     * Crea un share de backend `drive`: sirve el directorio [rootPath] como un
     * WebDAV accesible desde cualquier cliente WebDAV (Explorador de Windows,
     * Cyberduck, rclone, Finder) a través del endpoint del share. Usar junto a
     * [ZrokTunnel.hostDrive] para el plano de datos.
     */
    suspend fun createDriveShare(
        rootPath: String,
        isPrivate: Boolean = false,
        options: ShareOptions = ShareOptions(),
    ): Share = createShare(
        BackendMode.DRIVE,
        rootPath,
        if (isPrivate) ShareMode.PRIVATE else ShareMode.PUBLIC,
        options,
    )

    /**
     * Crea un share arbitrario (caso general de los métodos de conveniencia).
     */
    suspend fun createShare(
        backendMode: BackendMode,
        target: String,
        shareMode: ShareMode = ShareMode.PUBLIC,
        options: ShareOptions = ShareOptions(),
    ): Share {
        val state = environment.requireState()
        val hasBasicAuth = options.basicAuth.isNotEmpty()
        val hasOauth = !options.oauthProvider.isNullOrBlank()
        require(!(hasBasicAuth && hasOauth)) { "basicAuth y oauthProvider son mutuamente excluyentes" }

        val authScheme = when {
            hasBasicAuth -> "basic"
            hasOauth -> "oauth"
            else -> "none"
        }

        val request = ShareRequest(
            envZId = state.zId,
            shareMode = shareMode.toWire(),
            backendMode = backendMode.toWire(),
            target = target,
            authScheme = authScheme,
            privateShareToken = if (shareMode == ShareMode.PRIVATE) options.privateShareToken else null,
            basicAuthUsers = options.basicAuth.map { AuthUser(username = it.username, password = it.password) },
            oauthProvider = options.oauthProvider,
            oauthEmailDomains = options.oauthEmailDomains,
            oauthRefreshInterval = options.oauthRefreshInterval,
            permissionMode = options.permissionMode.toWire(),
            accessGrants = options.accessGrants,
        )
        val response = apis.share.share(request).bodyOrThrow()
        return Share(
            token = requireNotNull(response.shareToken) { "la respuesta de /share no trajo 'shareToken'" },
            frontendEndpoints = response.frontendProxyEndpoints.orEmpty(),
            shareMode = shareMode,
            backendMode = backendMode,
            target = target,
            envZId = state.zId,
        )
    }

    /** Elimina un share (`DELETE /api/v2/unshare`) por su token. */
    suspend fun deleteShare(shareToken: String) {
        val state = environment.requireState()
        apis.share.unshare(
            UnshareRequest(envZId = state.zId, shareToken = shareToken),
        ).ensureSuccess()
    }

    /**
     * Crea un access para consumir un share privado desde este entorno
     * (`POST /api/v2/access`).
     *
     * @param shareToken token del share privado al que se quiere acceder.
     * @param bindAddress dirección local solicitada para el frontend del access
     * (p. ej. "127.0.0.1:9191"); opcional, el servidor asigna una si se omite.
     */
    suspend fun createAccess(shareToken: String, bindAddress: String? = null): Access {
        val state = environment.requireState()
        val response = apis.share.access(
            AccessRequest(
                envZId = state.zId,
                shareToken = shareToken,
                bindAddress = bindAddress,
            ),
        ).bodyOrThrow()
        return Access(
            token = requireNotNull(response.frontendToken) { "la respuesta de /access no trajo 'frontendToken'" },
            shareToken = shareToken,
            backendMode = BackendMode.fromWire(response.backendMode),
        )
    }

    /** Elimina un access (`DELETE /api/v2/unaccess`). */
    suspend fun deleteAccess(access: Access) {
        deleteAccess(access.token, access.shareToken)
    }

    /** Elimina un access por su frontendToken y shareToken. */
    suspend fun deleteAccess(frontendToken: String, shareToken: String) {
        val state = environment.requireState()
        apis.share.unaccess(
            UnaccessRequest(
                frontendToken = frontendToken,
                shareToken = shareToken,
                envZId = state.zId,
            ),
        ).ensureSuccess()
    }

    /** Lista los shares del entorno (`GET /api/v2/shares`). */
    suspend fun listShares(): List<Share> {
        environment.requireState()
        return apis.metadata.listShares().bodyOrThrow().shares.orEmpty().map { it.toDomain() }
    }

    /** Lista los accesses del entorno (`GET /api/v2/accesses`). */
    suspend fun listAccesses(): List<Access> {
        environment.requireState()
        return apis.metadata.listAccesses().bodyOrThrow().accesses.orEmpty().map {
            Access(
                token = it.frontendToken.orEmpty(),
                shareToken = it.shareToken.orEmpty(),
                backendMode = BackendMode.fromWire(it.backendMode),
            )
        }
    }

    /** Detalle de un share (`GET /api/v2/detail/share/{shareToken}`). */
    suspend fun shareDetail(shareToken: String): Share {
        environment.requireState()
        return apis.metadata.getShareDetail(shareToken).bodyOrThrow().toDomain()
    }

    /** Configuración pública de la instancia (`GET /api/v2/configuration`). */
    suspend fun configuration(): ZrokConfiguration {
        val cfg: Configuration = apis.metadata.configuration().bodyOrThrow()
        return ZrokConfiguration(
            version = cfg.version,
            touLink = cfg.touLink,
            newAccountLink = cfg.newAccountLink,
            invitesOpen = cfg.invitesOpen,
            requiresInviteToken = cfg.requiresInviteToken,
        )
    }

    private fun ShareMode.toWire(): ShareRequest.ShareMode = when (this) {
        ShareMode.PUBLIC -> ShareRequest.ShareMode.public
        ShareMode.PRIVATE -> ShareRequest.ShareMode.private
    }

    private fun BackendMode.toWire(): ShareRequest.BackendMode = when (this) {
        BackendMode.PROXY -> ShareRequest.BackendMode.proxy
        BackendMode.WEB -> ShareRequest.BackendMode.web
        BackendMode.TCP_TUNNEL -> ShareRequest.BackendMode.tcpTunnel
        BackendMode.UDP_TUNNEL -> ShareRequest.BackendMode.udpTunnel
        BackendMode.CADDY -> ShareRequest.BackendMode.caddy
        BackendMode.DRIVE -> ShareRequest.BackendMode.drive
        BackendMode.SOCKS -> ShareRequest.BackendMode.socks
    }

    private fun String.toWire(): ShareRequest.PermissionMode = when (this.lowercase()) {
        "closed" -> ShareRequest.PermissionMode.closed
        else -> ShareRequest.PermissionMode.open
    }

    private fun ApiShare.toDomain(): Share = Share(
        token = shareToken.orEmpty(),
        frontendEndpoints = frontendEndpoints.orEmpty(),
        shareMode = ShareMode.fromWire(shareMode),
        backendMode = BackendMode.fromWire(backendMode),
        target = target,
        zId = zId,
        envZId = envZId,
        createdAt = createdAt?.toLong(),
        updatedAt = updatedAt?.toLong(),
    )

    private fun ShareSummary.toDomain(): Share = Share(
        token = shareToken.orEmpty(),
        frontendEndpoints = frontendEndpoints.orEmpty(),
        shareMode = ShareMode.fromWire(shareMode),
        backendMode = BackendMode.fromWire(backendMode),
        target = target,
        zId = zId,
        envZId = envZId,
        createdAt = createdAt?.toLong(),
        updatedAt = updatedAt?.toLong(),
    )
}
