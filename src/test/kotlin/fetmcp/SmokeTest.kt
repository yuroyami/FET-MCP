package fetmcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class SmokeTest {
    @Test
    fun `build works`() {
        assertEquals(2, 1 + 1)
    }

    @Test
    fun `example corpus is reachable`() {
        val dir = File(System.getProperty("fet.examples"))
        assertTrue(dir.isDirectory, "missing FET examples at $dir")
    }
}
