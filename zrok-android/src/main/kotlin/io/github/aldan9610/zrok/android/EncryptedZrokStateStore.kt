package io.github.aldan9610.zrok.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.aldan9610.zrok.core.state.EnvironmentState
import io.github.aldan9610.zrok.core.state.ZrokStateStore
import kotlinx.serialization.json.Json

/**
 * [ZrokStateStore] cifrado para Android: guarda el token de cuenta y la
 * identidad OpenZiti del entorno en [EncryptedSharedPreferences], cifrados con
 * una clave maestra respaldada por Android Keystore.
 *
 * Es el almacenamiento recomendado del "root environment" en Android
 * (equivalente cifrado de `~/.zrok` del SDK de Go).
 *
 * @param context contexto de la aplicación.
 * @param fileName nombre del fichero de preferencias cifradas.
 */
class EncryptedZrokStateStore(
    context: Context,
    fileName: String = "zrok_environment_secure",
) : ZrokStateStore {

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): EnvironmentState? {
        val content = preferences.getString(KEY_STATE, null) ?: return null
        return runCatching { json.decodeFromString(EnvironmentState.serializer(), content) }.getOrNull()
    }

    override fun save(state: EnvironmentState?) {
        val editor = preferences.edit()
        if (state == null) {
            editor.remove(KEY_STATE)
        } else {
            editor.putString(KEY_STATE, json.encodeToString(EnvironmentState.serializer(), state))
        }
        if (!editor.commit()) {
            throw IllegalStateException("no se pudo persistir el estado del entorno zrok")
        }
    }

    override fun close() = Unit

    private companion object {
        const val KEY_STATE = "environment_state_json"
    }
}
