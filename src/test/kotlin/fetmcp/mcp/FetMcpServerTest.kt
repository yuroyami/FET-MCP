package fetmcp.mcp

import fetmcp.Config
import fetmcp.model.Mode
import fetmcp.session.DocumentSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FetMcpServerTest {
    private val workspace: File = Files.createTempDirectory("fetmcp-server").toFile()
    private val config = Config(workspace = workspace, fetCl = null)
    private val app = FetMcpServer(config, DocumentSession(config))

    @Test
    fun `every tool is registered once with a description and a schema`() {
        val specs = app.tools.specs
        assertEquals(specs.map { it.name }.distinct().size, specs.size)
        assertTrue(specs.size >= 40, "only ${specs.size} tools")
        for (spec in specs) {
            assertTrue(spec.name.startsWith("fet_"), "${spec.name} must start with fet_")
            assertTrue(spec.description.length > 30, "${spec.name} needs a real description")
            for (required in spec.inputSchema.required) {
                assertTrue(required in spec.inputSchema.properties, "${spec.name} requires '$required' which is not in its schema")
            }
        }
    }

    @Test
    fun `the tool list covers every group in the spec`() {
        val names = app.tools.specs.map { it.name }.toSet()
        val expected = listOf(
            "fet_new", "fet_open", "fet_save",
            "fet_state", "fet_explain_mode", "fet_set_mode",
            "fet_set_week", "fet_set_terms",
            "fet_add_subjects", "fet_add_activity_tags", "fet_add_teachers", "fet_add_buildings", "fet_add_rooms",
            "fet_add_years", "fet_add_groups", "fet_add_subgroups", "fet_divide_year",
            "fet_update", "fet_remove", "fet_get", "fet_list",
            "fet_add_activities", "fet_update_activities", "fet_remove_activities", "fet_set_activities_active", "fet_list_activities",
            "fet_constraint_types", "fet_add_constraints", "fet_update_constraint", "fet_remove_constraints", "fet_list_constraints",
            "fet_set_unavailable", "fet_set_breaks", "fet_set_limits", "fet_set_rooms_preference", "fet_pin_activities", "fet_group_activities_in_initial_order",
            "fet_validate", "fet_precheck",
            "fet_generate_start", "fet_generate_status", "fet_generate_cancel",
            "fet_results_summary", "fet_results_placements", "fet_results_view", "fet_results_conflicts", "fet_results_adopt", "fet_export",
            "fet_lock", "fet_unlock", "fet_undo", "fet_redo",
        )
        assertEquals(emptyList(), expected.filter { it !in names })
    }

    @Test
    fun `resources answer`() {
        app.tools.call("fet_new", buildJsonObject { put("path", "r.fet"); put("mode", "Official") })
        val uris = app.resources.map { it.uri }
        assertTrue("fet://document" in uris)
        assertTrue("fet://state" in uris)
        assertTrue("fet://schema/constraints" in uris)
        assertTrue("fet://results/latest" in uris)
        val document = app.readResource("fet://document")
        assertTrue(document.startsWith("<?xml"), document.take(60))
        val state = app.readResource("fet://state")
        assertTrue("Official" in state, state.take(300))
        assertTrue("ConstraintBasicCompulsoryTime" in app.readResource("fet://schema/constraints"))
        assertTrue("real day" in app.readResource("fet://help/modes/Mornings_Afternoons").lowercase())
        assertTrue("Teacher" in app.readResource("fet://schema/constraints/ConstraintTeacherNotAvailableTimes"))
    }

    @Test
    fun `prompts are available and mention the tools they drive`() {
        assertEquals(listOf("build_timetable", "fix_impossible", "explain_timetable"), app.prompts.map { it.name })
        val build = app.renderPrompt("build_timetable", mapOf("description" to "A small school with 2 teachers"))
        assertTrue("fet_new" in build && "fet_generate_start" in build)
        assertTrue("A small school with 2 teachers" in build)
        val fix = app.renderPrompt("fix_impossible", emptyMap())
        assertTrue("fet_results_summary" in fix && "difficult" in fix.lowercase())
    }

    @Test
    fun `a tool call through the server returns the envelope as text`() {
        val result = app.callTool("fet_new", buildJsonObject { put("path", "x.fet"); put("mode", "Official") })
        assertTrue(result.contains("\"ok\": true"), result.take(200))
        val failure = app.callTool("fet_open", buildJsonObject { put("path", "missing.fet") })
        assertTrue(failure.contains("FILE_NOT_FOUND"))
    }

    @Test
    fun `the mcp server object builds with tools resources and prompts`() {
        val server = app.buildServer()
        assertEquals(app.tools.specs.size, server.tools.size)
        assertEquals(app.resources.size, server.resources.size)
        assertEquals(app.prompts.size, server.prompts.size)
        assertNotNull(server.tools["fet_state"])
    }
}
