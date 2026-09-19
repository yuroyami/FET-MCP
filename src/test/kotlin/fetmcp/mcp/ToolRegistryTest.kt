package fetmcp.mcp

import fetmcp.Config
import fetmcp.session.DocumentSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRegistryTest {
    private val examples = File(System.getProperty("fet.examples"))
    private val workspace: File = Files.createTempDirectory("fetmcp-tools").toFile()
    private val context = ToolContext(Config(workspace = workspace, fetCl = null), DocumentSession(Config(workspace = workspace, fetCl = null)), runner = null)
    private val tools = ToolRegistry(context).also { FileTools.register(it); StateTools.register(it) }

    private fun call(name: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): ToolResponse =
        tools.call(name, buildJsonObject(block))

    @Test
    fun `schema builder produces json schema`() {
        val s = schema {
            str("name", "the name", required = true)
            int("count", "how many")
            bool("flag", "a flag")
            arr("items", "some items", itemsStr())
            obj("nested", "an object") { num("weight", "0 to 100") }
            str("mode", "a mode", enum = listOf("A", "B"))
        }
        assertEquals(listOf("name"), s.required)
        assertEquals("string", s.properties.getValue("name").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("integer", s.properties.getValue("count").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("array", s.properties.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("number", s.properties.getValue("nested").jsonObject.getValue("properties").jsonObject.getValue("weight").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(2, s.properties.getValue("mode").jsonObject.getValue("enum").jsonArray.size)
    }

    @Test
    fun `unknown tool and bad arguments give error envelopes`() {
        val r = tools.call("fet_nope", JsonObject(emptyMap()))
        assertFalse(r.ok)
        assertEquals("UNKNOWN_TOOL", r.errors.single().code)
        val bad = call("fet_new") { put("mode", "Official") } // path missing
        assertFalse(bad.ok)
        assertEquals("INVALID_ARGUMENT", bad.errors.single().code)
    }

    @Test
    fun `new open save and state`() {
        val created = call("fet_new") { put("path", "school.fet"); put("mode", "Mornings_Afternoons"); put("institution", "Lycee") }
        assertTrue(created.ok, created.errors.toString())
        val state = call("fet_state")
        assertTrue(state.ok)
        val data = state.data.jsonObject
        assertEquals("Mornings_Afternoons", data.getValue("mode").jsonPrimitive.content)
        assertEquals("Lycee", data.getValue("institution").jsonPrimitive.content)
        assertEquals(1, data.getValue("counts").jsonObject.getValue("time_constraints").jsonPrimitive.content.toInt())
        assertEquals("school.fet", data.getValue("file").jsonPrimitive.content)

        File(examples, "FET-5-block-planning/small-example.fet").copyTo(File(workspace, "small.fet"))
        val opened = call("fet_open") { put("path", "small.fet") }
        assertTrue(opened.ok)
        assertEquals("5.37.5-bp", opened.data.jsonObject.getValue("file_version").jsonPrimitive.content)
        assertEquals(28, call("fet_state").data.jsonObject.getValue("counts").jsonObject.getValue("activities").jsonPrimitive.content.toInt())

        val saved = call("fet_save") { put("path", "copy/small2.fet") }
        assertTrue(saved.ok)
        assertTrue(File(workspace, "copy/small2.fet").isFile)
        assertEquals("copy/small2.fet", call("fet_state").data.jsonObject.getValue("file").jsonPrimitive.content)
    }

    @Test
    fun `mutating tools are flagged and refuse without a document`() {
        assertTrue(tools.specs.first { it.name == "fet_new" }.mutating)
        assertFalse(tools.specs.first { it.name == "fet_state" }.mutating)
        val r = call("fet_set_mode") { put("mode", "Official") }
        assertFalse(r.ok)
        assertEquals("NO_DOCUMENT", r.errors.single().code)
    }

    @Test
    fun `explain mode returns help and mode only constraint types`() {
        val r = call("fet_explain_mode") { put("mode", "Mornings_Afternoons") }
        assertTrue(r.ok)
        val data = r.data.jsonObject
        assertTrue("real day" in data.getValue("help").jsonPrimitive.content.lowercase())
        assertEquals(128, data.getValue("mode_only_constraint_types").jsonArray.size)
    }

    @Test
    fun `set mode enforces the whitelist and reports what would be removed`() {
        // This one uses ConstraintStudentsMaxAfternoonsPerWeek and friends, which exist only in Mornings-Afternoons.
        File(examples, "mornings-afternoons/problem-cannot-be-solved/test-liviu7.fet").copyTo(File(workspace, "ma.fet"))
        call("fet_open") { put("path", "ma.fet") }
        val refused = call("fet_set_mode") { put("mode", "Official") }
        assertFalse(refused.ok)
        assertEquals("MODE_FORBIDS", refused.errors.single().code)
        assertTrue(refused.errors.single().where.getValue("count").toInt() > 0)
        val forced = call("fet_set_mode") { put("mode", "Official"); put("remove_incompatible", true) }
        assertTrue(forced.ok, forced.errors.toString())
        assertTrue(forced.data.jsonObject.getValue("removed_constraints").jsonArray.size > 0)
        assertEquals("Official", call("fet_state").data.jsonObject.getValue("mode").jsonPrimitive.content)
        assertTrue(context.session.doc.teachers.all { it.morningsAfternoonsBehavior == null })
    }

    @Test
    fun `envelope json shape`() {
        val r = call("fet_state")
        val json = r.toJson()
        assertEquals(JsonPrimitive(false), json.getValue("ok"))
        assertTrue("errors" in json)
    }
}
