package fetmcp

import fetmcp.xml.FetReader
import fetmcp.xml.FetWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The whole FET example corpus: no constraint may keep a common field in its map or write it twice. */
class CorpusLeakTest {
    @Test
    fun `common fields are never duplicated into the field map`() {
        val root = File(System.getProperty("fet.examples"))
        val leaks = mutableListOf<String>()
        var doubled = 0
        var constraints = 0
        for (f in root.walkTopDown().filter { it.extension == "fet" }) {
            for (c in FetReader.read(f).document.constraints()) {
                constraints++
                for (common in listOf("Weight_Percentage", "Active", "Comments")) {
                    if (common in c.fields) leaks += "${f.name}: ${c.type} kept $common"
                }
                val xml = FetWriter.constraintXml(c)
                if (Regex("<Active>").findAll(xml).count() != 1) doubled++
            }
        }
        assertEquals(emptyList(), leaks.take(5))
        assertEquals(0, doubled, "$doubled constraints wrote <Active> more than once")
        assert(constraints > 100000) { "only $constraints constraints read" }
    }
}
