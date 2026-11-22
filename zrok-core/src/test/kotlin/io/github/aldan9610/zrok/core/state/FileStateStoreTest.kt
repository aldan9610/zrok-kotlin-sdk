package io.github.aldan9610.zrok.core.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileStateStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun state() = EnvironmentState(
        accountToken = "tok-123",
        zId = "zid-456",
        zitiConfigJson = """{"ztAPI":"https://ziti"}""",
        apiEndpoint = "https://api-v2.zrok.io",
        description = "test",
        enabledAtEpochMillis = 42L,
    )

    @Test
    fun `save y load hacen round-trip completo`() {
        val store = FileZrokStateStore(File(tempDir, "environment.json"))
        store.save(state())

        val loaded = store.load()
        assertEquals("tok-123", loaded?.accountToken)
        assertEquals("zid-456", loaded?.zId)
        assertEquals("""{"ztAPI":"https://ziti"}""", loaded?.zitiConfigJson)
        assertEquals("https://api-v2.zrok.io", loaded?.apiEndpoint)
        assertEquals("test", loaded?.description)
        assertEquals(42L, loaded?.enabledAtEpochMillis)
    }

    @Test
    fun `load sobre almacen vacio devuelve null`() {
        val store = FileZrokStateStore(File(tempDir, "environment.json"))
        assertNull(store.load())
    }

    @Test
    fun `save con null borra el estado`() {
        val file = File(tempDir, "environment.json")
        val store = FileZrokStateStore(file)
        store.save(state())
        assertTrue(file.isFile)

        store.save(null)
        assertFalse(file.isFile)
        assertNull(store.load())
    }

    @Test
    fun `los ficheros ignoran claves desconocidas al cargar`() {
        val file = File(tempDir, "environment.json")
        file.writeText(
            """{"accountToken":"t","zId":"z","zitiConfigJson":"c","apiEndpoint":"e",
               "enabledAtEpochMillis":7,"claveFutura":"valor"}""".trimIndent().replace("\n", ""),
        )
        val loaded = FileZrokStateStore(file).load()
        assertEquals("t", loaded?.accountToken)
        assertEquals(7L, loaded?.enabledAtEpochMillis)
    }
}
