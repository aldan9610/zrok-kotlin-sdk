package io.github.aldan9610.zrok.core.state

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Almacenamiento del estado del entorno zrok ("root environment").
 *
 * En Android se recomienda la implementación cifrada del módulo `zrok-android`
 * ([io.github.aldan9610.zrok.android.EncryptedZrokStateStore], basada en
 * EncryptedSharedPreferences); este módulo JVM ofrece [InMemoryZrokStateStore]
 * para tests y [FileZrokStateStore] para escritorio/servidor.
 */
interface ZrokStateStore : AutoCloseable {
    /** Carga el estado persistido o null si no hay entorno habilitado. */
    fun load(): EnvironmentState?

    /** Guarda el estado; `null` borra el estado almacenado. */
    fun save(state: EnvironmentState?)
}

/** Estado en memoria (útil para tests y usos efímeros). */
class InMemoryZrokStateStore : ZrokStateStore {
    @Volatile
    private var state: EnvironmentState? = null

    override fun load(): EnvironmentState? = state

    override fun save(state: EnvironmentState?) {
        this.state = state
    }

    override fun close() = Unit
}

/**
 * Estado persistido en un fichero JSON (por defecto `~/.zrok-kotlin/environment.json`),
 * con escritura atómica y permisos restringidos al propietario en sistemas POSIX.
 *
 * El fichero contiene material sensible (token de cuenta e identidad ziti); en
 * Android usa mejor el almacén cifrado del módulo `zrok-android`.
 */
class FileZrokStateStore(
    private val file: File = File(File(System.getProperty("user.home"), ".zrok-kotlin"), "environment.json"),
) : ZrokStateStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun load(): EnvironmentState? {
        if (!file.isFile) return null
        val content = file.readText().ifBlank { return null }
        return json.decodeFromString(EnvironmentState.serializer(), content)
    }

    override fun save(state: EnvironmentState?) {
        if (state == null) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile("zrok-state", ".tmp", file.parentFile)
        try {
            tmp.writeText(json.encodeToString(EnvironmentState.serializer(), state))
            restrictPermissions(tmp.toPath())
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            tmp.delete()
        }
    }

    override fun close() = Unit

    private companion object {
        private fun restrictPermissions(path: Path) {
            runCatching {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
        }
    }
}
