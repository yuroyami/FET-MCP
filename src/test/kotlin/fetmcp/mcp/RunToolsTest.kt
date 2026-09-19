package fetmcp.mcp

import fetmcp.Config
import fetmcp.model.Mode
import fetmcp.runner.JobRunner
import fetmcp.schema.ConstraintSchema
import fetmcp.session.DocumentSession
import fetmcp.validate.Validator
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunToolsTest {
    private val workspace: File = Files.createTempDirectory("fetmcp-run").toFile()
    private val stub: File = File("src/test/resources/fake-fet-cl.sh").absoluteFile.also { it.setExecutable(true) }
    private val config = Config(workspace = workspace, fetCl = stub)
    private val session = DocumentSession(config)

    private fun toolsWith(outcome: String): ToolRegistry {
        val context = ToolContext(config, session, JobRunner(config, env = mapOf("FAKE_FET_OUTCOME" to outcome)))
        return ToolRegistry(context).also {
            FileTools.register(it); StateTools.register(it); WeekTools.register(it)
            EntityTools.register(it); ActivityTools.register(it); ConstraintTools.register(it)
            HelperTools.register(it); ValidationTools.register(it); GenerationTools.register(it)
            ResultTools.register(it); LockTools.register(it); SnapshotTools.register(it)
        }
    }

    private var tools = toolsWith("success")

    private fun call(name: String, block: JsonObjectBuilder.() -> Unit = {}): ToolResponse = tools.call(name, buildJsonObject(block))
    private fun ok(name: String, block: JsonObjectBuilder.() -> Unit = {}): ToolResponse =
        call(name, block).also { assertTrue(it.ok, "$name failed: ${it.errors}") }

    private fun school(mode: Mode = Mode.OFFICIAL) {
        ok("fet_new") { put("path", "s.fet"); put("mode", mode.xml); put("institution", "S") }
        if (mode == Mode.MORNINGS_AFTERNOONS) {
            ok("fet_set_week") { putJsonArray("real_days") { add("Sun"); add("Mon") }; putJsonArray("hours") { add("1"); add("2") } }
        } else {
            ok("fet_set_week") { putJsonArray("days") { add("Mon"); add("Tue") }; putJsonArray("hours") { add("1"); add("2") } }
        }
        ok("fet_add_subjects") { putJsonArray("subjects") { add(buildJsonObject { put("name", "Math") }) } }
        ok("fet_add_teachers") { putJsonArray("teachers") { add(buildJsonObject { put("name", "T1") }) } }
        ok("fet_add_years") { putJsonArray("years") { add(buildJsonObject { put("name", "9") }) } }
        ok("fet_add_groups") { put("year", "9"); putJsonArray("groups") { add(buildJsonObject { put("name", "9A") }) } }
        ok("fet_add_rooms") { putJsonArray("rooms") { add(buildJsonObject { put("name", "R1"); put("capacity", 40) }) } }
        ok("fet_add_activities") {
            putJsonArray("activities") {
                add(buildJsonObject {
                    putJsonArray("teachers") { add("T1") }
                    put("subject", "Math")
                    putJsonArray("students") { add("9A") }
                    put("total_duration", 2); put("split", 2)
                })
            }
        }
    }

    // ---- helper tools ----

    @Test
    fun `set unavailable accepts day hour and whole day forms`() {
        school()
        ok("fet_set_unavailable") {
            put("kind", "teacher"); put("name", "T1")
            putJsonArray("slots") {
                add(buildJsonObject { put("day", "Mon"); put("hour", "1") })
                add(buildJsonObject { put("day", "Tue"); put("all_hours", true) })
            }
        }
        val c = session.doc.timeConstraints.single { it.type == "ConstraintTeacherNotAvailableTimes" }
        assertEquals(3, c.items("Not_Available_Time").size)
        // Calling again replaces rather than adding a second constraint.
        ok("fet_set_unavailable") { put("kind", "teacher"); put("name", "T1"); putJsonArray("slots") { add(buildJsonObject { put("day", "Mon"); put("hour", "1") }) } }
        assertEquals(1, session.doc.timeConstraints.count { it.type == "ConstraintTeacherNotAvailableTimes" })
        assertEquals(1, session.doc.timeConstraints.single { it.type == "ConstraintTeacherNotAvailableTimes" }.items("Not_Available_Time").size)
    }

    @Test
    fun `set unavailable takes real day and half in ma mode`() {
        school(Mode.MORNINGS_AFTERNOONS)
        ok("fet_set_unavailable") {
            put("kind", "teacher"); put("name", "T1")
            putJsonArray("slots") { add(buildJsonObject { put("real_day", "Sun"); put("half", "afternoon"); put("all_hours", true) }) }
        }
        val slots = session.doc.timeConstraints.single { it.type == "ConstraintTeacherNotAvailableTimes" }.items("Not_Available_Time")
        assertEquals(2, slots.size)
        assertTrue(slots.all { ((it as fetmcp.model.FieldValue.Obj).fields.getValue("Day") as fetmcp.model.FieldValue.Scalar).text == "Sun Afternoon" })
        // FET names a real day after its morning FET day, so the full name works too.
        ok("fet_set_unavailable") {
            put("kind", "teacher"); put("name", "T1")
            putJsonArray("slots") { add(buildJsonObject { put("real_day", "Sun Morning"); put("half", "morning"); put("hour", "1") }) }
        }
        val one = session.doc.timeConstraints.single { it.type == "ConstraintTeacherNotAvailableTimes" }.items("Not_Available_Time").single()
        assertEquals("Sun Morning", ((one as fetmcp.model.FieldValue.Obj).fields.getValue("Day") as fetmcp.model.FieldValue.Scalar).text)
    }

    @Test
    fun `set limits maps names to the right constraint types`() {
        school()
        ok("fet_set_limits") {
            put("kind", "teacher"); put("name", "T1")
            putJsonObject("limits") { put("max_days_per_week", 2); put("max_gaps_per_day", 1); put("max_gaps_per_day_weight", 80.0) }
        }
        val types = session.doc.timeConstraints.map { it.type }
        assertTrue("ConstraintTeacherMaxDaysPerWeek" in types)
        assertEquals(80.0, session.doc.timeConstraints.single { it.type == "ConstraintTeacherMaxGapsPerDay" }.weight)
        // null removes the rule
        ok("fet_set_limits") { put("kind", "teacher"); put("name", "T1"); putJsonObject("limits") { put("max_days_per_week", null as String?) } }
        assertFalse(session.doc.timeConstraints.any { it.type == "ConstraintTeacherMaxDaysPerWeek" })
    }

    @Test
    fun `set limits for all uses the plural constraint types`() {
        school()
        ok("fet_set_limits") { put("kind", "students"); put("name", "all"); putJsonObject("limits") { put("max_hours_daily", 6) } }
        assertTrue(session.doc.timeConstraints.any { it.type == "ConstraintStudentsMaxHoursDaily" })
    }

    @Test
    fun `ma only limits are refused outside ma mode`() {
        school()
        val r = call("fet_set_limits") { put("kind", "teacher"); put("name", "T1"); putJsonObject("limits") { put("max_afternoons_per_week", 2) } }
        assertFalse(r.ok)
        assertEquals("MODE_FORBIDS", r.errors.single().code)
    }

    @Test
    fun `every limit names a real FET type and field`() {
        for ((key, limit) in listOf(HelperTools.teacherLimits, HelperTools.studentsLimits).flatMap { it.entries }) {
            for (type in listOf(limit.single, limit.all)) {
                val spec = assertNotNull(ConstraintSchema.get(type), "$key: FET has no type $type")
                assertTrue(spec.fields.any { it.name == limit.field }, "$key: $type has no field ${limit.field}")
            }
        }
    }

    @Test
    fun `ma limits write fields FET reads and official only limits are refused`() {
        school(Mode.MORNINGS_AFTERNOONS)
        ok("fet_set_limits") {
            put("kind", "teacher"); put("name", "T1")
            putJsonObject("limits") { put("max_real_days_per_week", 1); put("min_afternoons_per_week", 1); put("max_span_per_day", 2) }
        }
        assertEquals("1", session.doc.timeConstraints.single { it.type == "ConstraintTeacherMaxRealDaysPerWeek" }.scalar("Max_Days_Per_Week"))
        val fieldIssues = Validator.validate(session.doc).filter { it.code == "FIELD_UNKNOWN" || it.code == "FIELD_MISSING" }
        assertTrue(fieldIssues.isEmpty(), "$fieldIssues")
        val r = call("fet_set_limits") { put("kind", "teacher"); put("name", "T1"); putJsonObject("limits") { put("min_resting_hours", 2) } }
        assertEquals("MODE_FORBIDS", r.errors.single().code)
    }

    @Test
    fun `room preference picks the type from the target`() {
        school()
        ok("fet_set_rooms_preference") { putJsonObject("target") { put("students", "9A") }; putJsonArray("rooms") { add("R1") } }
        assertTrue(session.doc.spaceConstraints.any { it.type == "ConstraintStudentsSetHomeRoom" })
        ok("fet_set_rooms_preference") { putJsonObject("target") { put("subject", "Math") }; putJsonArray("rooms") { add("R1") } }
        assertTrue(session.doc.spaceConstraints.any { it.type == "ConstraintSubjectPreferredRoom" })
    }

    @Test
    fun `breaks and grouping tools`() {
        school()
        ok("fet_set_breaks") { putJsonArray("slots") { add(buildJsonObject { put("day", "Mon"); put("hour", "2") }) } }
        assertEquals(1, session.doc.timeConstraints.count { it.type == "ConstraintBreakTimes" })
        ok("fet_group_activities_in_initial_order") { putJsonArray("activity_ids") { add(1); add(2) } }
        assertEquals(listOf(1, 2), session.doc.generationOptions.single().activityIds)
    }

    // ---- validation ----

    @Test
    fun `validate reports errors and precheck runs fet-cl`() {
        school()
        val v = ok("fet_validate")
        assertTrue(v.data.jsonObject.getValue("ready_to_generate").jsonPrimitive.content.toBoolean())
        val p = ok("fet_precheck")
        assertTrue(p.data.jsonObject.getValue("passed").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `precheck reports a fet data error`() {
        school()
        tools = toolsWith("dataerror")
        val p = ok("fet_precheck")
        assertFalse(p.data.jsonObject.getValue("passed").jsonPrimitive.content.toBoolean())
        assertTrue("no allowed slot" in p.data.jsonObject.getValue("fet_messages").jsonArray.joinToString())
    }

    // ---- generation and results ----

    @Test
    fun `generate then read the result`() {
        school()
        val started = ok("fet_generate_start") { put("time_limit_seconds", 5) }
        val jobId = started.data.jsonObject.getValue("job_id").jsonPrimitive.content
        val status = ok("fet_generate_status") { put("job_id", jobId); put("wait_seconds", 20) }
        assertEquals("SUCCEEDED", status.data.jsonObject.getValue("state").jsonPrimitive.content)

        val summary = ok("fet_results_summary").data.jsonObject
        assertEquals("SUCCEEDED", summary.getValue("state").jsonPrimitive.content)
        assertEquals(2, summary.getValue("placed").jsonPrimitive.content.toInt())
        assertEquals(1, summary.getValue("broken_soft_constraints").jsonPrimitive.content.toInt())
        assertTrue(summary.getValue("files").jsonObject.containsKey("index"))

        val placements = ok("fet_results_placements").data.jsonObject.getValue("items").jsonArray
        assertEquals(2, placements.size)
        assertEquals("Mon", placements.first().jsonObject.getValue("day").jsonPrimitive.content)

        val view = ok("fet_results_view") { put("kind", "teacher"); put("name", "T1") }.data.jsonObject
        assertTrue(view.getValue("markdown").jsonPrimitive.content.startsWith("| Hour |"))
        assertEquals(2, view.getValue("grid").jsonObject.getValue("columns").jsonArray.size)

        val conflicts = ok("fet_results_conflicts").data.jsonObject.getValue("items").jsonArray
        assertEquals(12.5, conflicts.single().jsonObject.getValue("weight").jsonPrimitive.content.toDouble())
    }

    @Test
    fun `generation is refused when validation fails`() {
        ok("fet_new") { put("path", "empty.fet"); put("mode", "Official"); put("institution", "E") }
        val r = call("fet_generate_start") { put("time_limit_seconds", 5) }
        assertFalse(r.ok)
        assertEquals("VALIDATION", r.errors.first().code)
    }

    @Test
    fun `generate takes seeds past the int range and refuses bad ones`() {
        school()
        val big = listOf(4_000_000_000L, 2L, 3L, 4_294_944_442L, 5L, 6L)
        val started = ok("fet_generate_start") { put("time_limit_seconds", 5); putJsonArray("seed") { big.forEach { add(it) } } }.data.jsonObject
        assertEquals(big, started.getValue("seed").jsonArray.map { it.jsonPrimitive.content.toLong() })
        ok("fet_generate_status") { put("job_id", started.getValue("job_id").jsonPrimitive.content); put("wait_seconds", 20) }
        // the first three parts may not all be zero
        val bad = call("fet_generate_start") { put("time_limit_seconds", 5); putJsonArray("seed") { repeat(3) { add(0) }; repeat(3) { add(1) } } }
        assertEquals("INVALID_ARGUMENT", bad.errors.single().code)
    }

    @Test
    fun `an impossible run explains which activity broke it`() {
        school()
        tools = toolsWith("impossible")
        val jobId = ok("fet_generate_start") { put("time_limit_seconds", 5) }.data.jsonObject.getValue("job_id").jsonPrimitive.content
        ok("fet_generate_status") { put("job_id", jobId); put("wait_seconds", 20) }
        val summary = ok("fet_results_summary").data.jsonObject
        assertEquals("IMPOSSIBLE", summary.getValue("state").jsonPrimitive.content)
        val difficult = summary.getValue("difficult_activities").jsonArray
        assertTrue(difficult.isNotEmpty())
        assertTrue("what to try" in summary.getValue("advice").jsonPrimitive.content.lowercase())
        // The partial timetable is still readable.
        assertEquals(2, ok("fet_results_placements").data.jsonObject.getValue("items").jsonArray.size)
        assertEquals(1, ok("fet_results_placements") { put("placed_only", true) }.data.jsonObject.getValue("items").jsonArray.size)
    }

    @Test
    fun `cancel a running job`() {
        school()
        tools = toolsWith("slow")
        val jobId = ok("fet_generate_start") { put("time_limit_seconds", 600) }.data.jsonObject.getValue("job_id").jsonPrimitive.content
        val running = ok("fet_generate_status") { put("job_id", jobId); put("wait_seconds", 2) }
        assertEquals("RUNNING", running.data.jsonObject.getValue("state").jsonPrimitive.content)
        val cancelled = ok("fet_generate_cancel") { put("job_id", jobId); put("keep_partial", true) }
        assertEquals("INTERRUPTED", cancelled.data.jsonObject.getValue("state").jsonPrimitive.content)
    }

    // ---- adopt, lock, undo ----

    @Test
    fun `adopt the result then lock and unlock`() {
        school()
        val jobId = ok("fet_generate_start") { put("time_limit_seconds", 5) }.data.jsonObject.getValue("job_id").jsonPrimitive.content
        ok("fet_generate_status") { put("job_id", jobId); put("wait_seconds", 20) }

        val locked = ok("fet_lock") { putJsonObject("scope") { put("all", true) } }
        assertEquals(2, locked.data.jsonObject.getValue("locked_activities").jsonArray.size)
        assertEquals(2, session.doc.timeConstraints.count { it.type == "ConstraintActivityPreferredStartingTime" })
        assertEquals(2, session.doc.spaceConstraints.count { it.type == "ConstraintActivityPreferredRoom" })

        // Locking again is a no-op, not a duplicate.
        assertEquals(0, ok("fet_lock") { putJsonObject("scope") { put("all", true) } }.data.jsonObject.getValue("locked_activities").jsonArray.size)

        val unlocked = ok("fet_unlock") { putJsonObject("scope") { putJsonArray("activity_ids") { add(1) } } }
        assertEquals(listOf(1), unlocked.data.jsonObject.getValue("unlocked_activities").jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals(1, session.doc.timeConstraints.count { it.type == "ConstraintActivityPreferredStartingTime" })
    }

    @Test
    fun `adopt replaces the document with the generated file`() {
        school()
        val jobId = ok("fet_generate_start") { put("time_limit_seconds", 5) }.data.jsonObject.getValue("job_id").jsonPrimitive.content
        ok("fet_generate_status") { put("job_id", jobId); put("wait_seconds", 20) }
        val adopted = ok("fet_results_adopt")
        assertTrue(adopted.data.jsonObject.getValue("activities").jsonPrimitive.content.toInt() > 0)
        assertEquals("s.fet", session.relativePath)
    }

    @Test
    fun `undo and redo through the tools`() {
        school()
        val before = session.doc.subjects.size
        ok("fet_add_subjects") { putJsonArray("subjects") { add(buildJsonObject { put("name", "Art") }) } }
        assertEquals("add 1 subject(s)", ok("fet_undo").data.jsonObject.getValue("undone").jsonPrimitive.content)
        assertEquals(before, session.doc.subjects.size)
        ok("fet_redo")
        assertEquals(before + 1, session.doc.subjects.size)
        val nothing = call("fet_undo") { put("steps", 99) }
        assertFalse(nothing.ok)
        assertEquals("NOTHING_TO_UNDO", nothing.errors.single().code)
    }

    @Test
    fun `results tools say so when nothing has been generated`() {
        school()
        val r = call("fet_results_summary")
        assertFalse(r.ok)
        assertEquals("NO_RESULT", r.errors.single().code)
    }
}
