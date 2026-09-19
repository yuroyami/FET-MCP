package fetmcp.mcp

import fetmcp.Config
import fetmcp.model.MaBehavior
import fetmcp.model.Mode
import fetmcp.session.DocumentSession
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataToolsTest {
    private val workspace: File = Files.createTempDirectory("fetmcp-data").toFile()
    private val config = Config(workspace = workspace, fetCl = null)
    private val context = ToolContext(config, DocumentSession(config), runner = null)
    private val tools = ToolRegistry(context).also {
        FileTools.register(it); StateTools.register(it); WeekTools.register(it)
        EntityTools.register(it); ActivityTools.register(it); ConstraintTools.register(it)
    }

    private fun call(name: String, block: JsonObjectBuilder.() -> Unit = {}): ToolResponse = tools.call(name, buildJsonObject(block))
    private fun ok(name: String, block: JsonObjectBuilder.() -> Unit = {}): ToolResponse =
        call(name, block).also { assertTrue(it.ok, "$name failed: ${it.errors}") }

    private fun school(mode: Mode = Mode.OFFICIAL) {
        ok("fet_new") { put("path", "s-${mode.name}.fet"); put("mode", mode.xml); put("institution", "S") }
        if (mode == Mode.MORNINGS_AFTERNOONS) {
            ok("fet_set_week") {
                putJsonArray("real_days") { add("Sun"); add("Mon") }
                putJsonArray("hours") { add("1"); add("2") }
            }
        } else {
            ok("fet_set_week") {
                putJsonArray("days") { add("Mon"); add("Tue"); add("Wed") }
                putJsonArray("hours") { add("1"); add("2"); add("3") }
            }
        }
        ok("fet_add_subjects") { putJsonArray("subjects") { add(buildJsonObject { put("name", "Math") }); add(buildJsonObject { put("name", "Art") }) } }
        ok("fet_add_teachers") { putJsonArray("teachers") { add(buildJsonObject { put("name", "T1") }); add(buildJsonObject { put("name", "T2") }) } }
        ok("fet_add_years") { putJsonArray("years") { add(buildJsonObject { put("name", "9"); put("number_of_students", 60) }) } }
        ok("fet_add_groups") { put("year", "9"); putJsonArray("groups") { add(buildJsonObject { put("name", "9A") }) } }
    }

    // ---- week ----

    @Test
    fun `set week names days and hours`() {
        school()
        val week = ok("fet_state").data.jsonObject.getValue("week").jsonObject
        assertEquals(listOf("Mon", "Tue", "Wed"), week.getValue("days").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `mornings afternoons week expands real days into halves`() {
        school(Mode.MORNINGS_AFTERNOONS)
        val week = ok("fet_state").data.jsonObject.getValue("week").jsonObject
        assertEquals(listOf("Sun Morning", "Sun Afternoon", "Mon Morning", "Mon Afternoon"), week.getValue("days").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Sun Morning", "Mon Morning"), week.getValue("real_days").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("H1", "H2", "H3", "H4"), week.getValue("real_hours").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `renaming a day reaches constraints and dropping a used day is refused`() {
        school()
        ok("fet_add_constraints") {
            putJsonArray("constraints") {
                add(buildJsonObject {
                    put("type", "ConstraintTeacherNotAvailableTimes")
                    putJsonObject("fields") {
                        put("Teacher", "T1")
                        putJsonArray("Not_Available_Time") { add(buildJsonObject { put("Day", "Wed"); put("Hour", "1") }) }
                    }
                })
            }
        }
        // Same count, same order, one renamed: the constraint follows the new name.
        ok("fet_set_week") { putJsonArray("days") { add("Mon"); add("Tue"); add("Thu") }; putJsonArray("hours") { add("1"); add("2"); add("3") } }
        val slot = context.session.doc.timeConstraints.single { it.type == "ConstraintTeacherNotAvailableTimes" }
            .items("Not_Available_Time").single() as fetmcp.model.FieldValue.Obj
        assertEquals("Thu", (slot.fields.getValue("Day") as fetmcp.model.FieldValue.Scalar).text)

        val refused = call("fet_set_week") { putJsonArray("days") { add("Mon"); add("Tue") }; putJsonArray("hours") { add("1"); add("2"); add("3") } }
        assertFalse(refused.ok)
        assertEquals("DAY_IN_USE", refused.errors.single().code)
        assertEquals(3, context.session.doc.days.size)
    }

    @Test
    fun `terms tool checks the arithmetic`() {
        ok("fet_new") { put("path", "t.fet"); put("mode", "Terms"); put("institution", "T") }
        ok("fet_set_week") { putJsonArray("days") { repeat(4) { i -> add("D${i + 1}") } }; putJsonArray("hours") { add("1") } }
        val bad = call("fet_set_terms") { put("terms", 3); put("days_per_term", 2) }
        assertFalse(bad.ok)
        assertEquals("TERMS_MISMATCH", bad.errors.single().code)
        ok("fet_set_terms") { put("terms", 2); put("days_per_term", 2) }
        assertEquals(2, ok("fet_state").data.jsonObject.getValue("week").jsonObject.getValue("terms").jsonObject.getValue("terms").jsonPrimitive.content.toInt())
    }

    // ---- entities ----

    @Test
    fun `entities are added listed and fetched`() {
        school()
        ok("fet_add_rooms") { putJsonArray("rooms") { add(buildJsonObject { put("name", "R1"); put("capacity", 30) }) } }
        val list = ok("fet_list") { put("kind", "room") }.data.jsonObject.getValue("items").jsonArray
        assertEquals("R1", list.single().jsonObject.getValue("name").jsonPrimitive.content)
        val got = ok("fet_get") { put("kind", "teacher"); put("name", "T1") }.data.jsonObject
        assertEquals("T1", got.getValue("entity").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(0, got.getValue("activities").jsonArray.size)
        val students = ok("fet_list") { put("kind", "students") }.data.jsonObject.getValue("items").jsonArray
        assertEquals(listOf("9", "9A"), students.map { it.jsonObject.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun `duplicate names are refused`() {
        school()
        val r = call("fet_add_subjects") { putJsonArray("subjects") { add(buildJsonObject { put("name", "Math") }) } }
        assertFalse(r.ok)
        assertEquals("DUPLICATE", r.errors.single().code)
    }

    @Test
    fun `bulk add is all or nothing`() {
        school()
        val r = call("fet_add_teachers") {
            putJsonArray("teachers") { add(buildJsonObject { put("name", "T3") }); add(buildJsonObject { put("name", "T1") }) }
        }
        assertFalse(r.ok)
        assertNull(context.session.doc.teacher("T3"))
    }

    @Test
    fun `teacher behaviour is only allowed in mornings afternoons mode`() {
        school()
        val r = call("fet_add_teachers") {
            putJsonArray("teachers") { add(buildJsonObject { put("name", "T9"); put("mornings_afternoons_behavior", "Exclusive") }) }
        }
        assertFalse(r.ok)
        assertEquals("MODE_FORBIDS", r.errors.single().code)

        school(Mode.MORNINGS_AFTERNOONS)
        ok("fet_add_teachers") { putJsonArray("teachers") { add(buildJsonObject { put("name", "T9"); put("mornings_afternoons_behavior", "One day exception") }) } }
        assertEquals(MaBehavior.ONE_DAY_EXCEPTION, context.session.doc.teacher("T9")?.morningsAfternoonsBehavior)
    }

    @Test
    fun `update renames and cascades`() {
        school()
        ok("fet_add_activities") { putJsonArray("activities") { add(activity("T1", "Math", "9A", 2)) } }
        val r = ok("fet_update") { put("kind", "teacher"); put("name", "T1"); putJsonObject("changes") { put("name", "Tania") } }
        assertEquals(2, r.data.jsonObject.getValue("changed_activities").jsonArray.size)
        assertEquals(listOf("Tania"), context.session.doc.activities.first().teachers)
        val missing = call("fet_update") { put("kind", "room"); put("name", "X"); putJsonObject("changes") { put("name", "Y") } }
        assertFalse(missing.ok)
        assertEquals("NOT_FOUND", missing.errors.single().code)
    }

    @Test
    fun `remove cascades and needs confirmation when it is large`() {
        school()
        ok("fet_add_activities") { putJsonArray("activities") { add(activity("T1", "Math", "9A", 12)) } }
        val refused = call("fet_remove") { put("kind", "teacher"); putJsonArray("names") { add("T1") } }
        assertFalse(refused.ok)
        assertEquals("CASCADE_TOO_LARGE", refused.errors.single().code)
        val done = ok("fet_remove") { put("kind", "teacher"); putJsonArray("names") { add("T1") }; put("confirm", true) }
        assertEquals(12, done.data.jsonObject.getValue("removed_activities").jsonArray.size)
        assertEquals(0, context.session.doc.activities.size)
    }

    @Test
    fun `divide year builds groups and subgroups`() {
        school()
        ok("fet_add_years") { putJsonArray("years") { add(buildJsonObject { put("name", "10") }) } }
        val r = ok("fet_divide_year") {
            put("year", "10")
            putJsonArray("categories") {
                add(buildJsonArray { add("A"); add("B") })
                add(buildJsonArray { add("Boys"); add("Girls") })
            }
            put("separator", "-")
        }
        assertEquals(listOf("10-A", "10-B"), r.data.jsonObject.getValue("groups").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("10-A-Boys", "10-A-Girls", "10-B-Boys", "10-B-Girls"), r.data.jsonObject.getValue("subgroups").jsonArray.map { it.jsonPrimitive.content })
    }

    // ---- activities ----

    @Test
    fun `single and split activities`() {
        school()
        val single = ok("fet_add_activities") { putJsonArray("activities") { add(activity("T1", "Math", "9A", 1)) } }
        assertEquals(listOf(1), single.data.jsonObject.getValue("items").jsonArray.first().jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals(0, context.session.doc.activity(1)!!.groupId)

        val split = ok("fet_add_activities") { putJsonArray("activities") { add(activity("T2", "Art", "9A", 3)) } }
        val ids = split.data.jsonObject.getValue("items").jsonArray.first().jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content.toInt() }
        assertEquals(listOf(2, 3, 4), ids)
        assertTrue(context.session.doc.activities.filter { it.id in ids }.all { it.groupId == 2 && it.totalDuration == 3 })
        val minDays = context.session.doc.timeConstraints.single { it.type == "ConstraintMinDaysBetweenActivities" }
        assertEquals(listOf("2", "3", "4"), minDays.scalars("Activity_Id"))
        assertEquals(95.0, minDays.weight)
    }

    @Test
    fun `split activities in ma mode use real days`() {
        school(Mode.MORNINGS_AFTERNOONS)
        ok("fet_add_activities") { putJsonArray("activities") { add(activity("T1", "Math", "9A", 2)) } }
        assertEquals("ConstraintMinDaysBetweenActivities", context.session.doc.timeConstraints.single { "Between" in it.type }.type)
    }

    @Test
    fun `durations list controls the split`() {
        school()
        ok("fet_add_activities") {
            putJsonArray("activities") {
                add(buildJsonObject {
                    putJsonArray("teachers") { add("T1") }
                    put("subject", "Math")
                    putJsonArray("students") { add("9A") }
                    putJsonArray("durations") { add(2); add(1) }
                })
            }
        }
        assertEquals(listOf(2, 1), context.session.doc.activities.map { it.duration })
        assertTrue(context.session.doc.activities.all { it.totalDuration == 3 })
    }

    @Test
    fun `activities can be deactivated updated and removed`() {
        school()
        ok("fet_add_activities") { putJsonArray("activities") { add(activity("T1", "Math", "9A", 2)) } }
        ok("fet_set_activities_active") { putJsonArray("ids") { add(1) }; put("active", false) }
        assertFalse(context.session.doc.activity(1)!!.active)
        ok("fet_update_activities") { putJsonArray("updates") { add(buildJsonObject { put("id", 1); putJsonObject("changes") { put("subject", "Art") } }) } }
        assertEquals("Art", context.session.doc.activity(1)!!.subject)
        assertEquals("Math", context.session.doc.activity(2)!!.subject)
        ok("fet_update_activities") { putJsonArray("updates") { add(buildJsonObject { put("id", 1); put("apply_to_group", true); putJsonObject("changes") { putJsonArray("teachers") { add("T2") } } }) } }
        assertTrue(context.session.doc.activities.all { it.teachers == listOf("T2") })
        val removed = ok("fet_remove_activities") { putJsonArray("ids") { add(2) } }
        assertEquals(listOf(1, 2), removed.data.jsonObject.getValue("removed_activities").jsonArray.map { it.jsonPrimitive.content.toInt() })
    }

    @Test
    fun `activity list filters`() {
        school()
        ok("fet_add_activities") { putJsonArray("activities") { add(activity("T1", "Math", "9A", 1)); add(activity("T2", "Art", "9A", 1)) } }
        val filtered = ok("fet_list_activities") { put("teacher", "T2") }.data.jsonObject.getValue("items").jsonArray
        assertEquals(1, filtered.size)
        assertEquals("Art", filtered.single().jsonObject.getValue("subject").jsonPrimitive.content)
    }

    // ---- constraints ----

    @Test
    fun `constraint types search returns fields and modes`() {
        school()
        val r = ok("fet_constraint_types") { put("search", "teacher max days"); put("include_schema", true) }
        val types = r.data.jsonObject.getValue("items").jsonArray
        val one = types.first { it.jsonObject.getValue("name").jsonPrimitive.content == "ConstraintTeacherMaxDaysPerWeek" }.jsonObject
        assertTrue("Teacher" in one.getValue("fields").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertTrue(one.getValue("modes").jsonArray.map { it.jsonPrimitive.content }.contains("Official"))
    }

    @Test
    fun `add update list and remove a constraint`() {
        school()
        val added = ok("fet_add_constraints") {
            putJsonArray("constraints") {
                add(buildJsonObject {
                    put("type", "ConstraintTeacherMaxDaysPerWeek")
                    putJsonObject("fields") { put("Teacher", "T1"); put("Max_Days_Per_Week", 2) }
                })
            }
        }
        val ref = added.data.jsonObject.getValue("refs").jsonArray.single().jsonPrimitive.content
        assertTrue(ref.startsWith("t:"))
        val listed = ok("fet_list_constraints") { put("teacher", "T1") }.data.jsonObject.getValue("items").jsonArray
        assertEquals(ref, listed.single().jsonObject.getValue("ref").jsonPrimitive.content)
        assertTrue("Teacher max days per week" in listed.single().jsonObject.getValue("description").jsonPrimitive.content)
        val updated = ok("fet_update_constraint") { put("ref", ref); put("weight", 80.0) }
        val newRef = updated.data.jsonObject.getValue("ref").jsonPrimitive.content
        assertTrue(newRef != ref)
        assertEquals(80.0, context.session.doc.constraint(newRef)!!.weight)
        ok("fet_remove_constraints") { putJsonArray("refs") { add(newRef) } }
        assertNull(context.session.doc.constraint(newRef))
    }

    @Test
    fun `unknown fields and unknown types are refused`() {
        school()
        val badField = call("fet_add_constraints") {
            putJsonArray("constraints") {
                add(buildJsonObject { put("type", "ConstraintTeacherMaxDaysPerWeek"); putJsonObject("fields") { put("Teacher", "T1"); put("Nope", 1) } })
            }
        }
        assertFalse(badField.ok)
        assertEquals("SCHEMA_MISMATCH", badField.errors.single().code)

        val badType = call("fet_add_constraints") { putJsonArray("constraints") { add(buildJsonObject { put("type", "ConstraintNope") }) } }
        assertEquals("UNKNOWN_CONSTRAINT_TYPE", badType.errors.single().code)

        val missing = call("fet_add_constraints") {
            putJsonArray("constraints") { add(buildJsonObject { put("type", "ConstraintTeacherMaxDaysPerWeek"); putJsonObject("fields") { put("Teacher", "Ghost"); put("Max_Days_Per_Week", 2) } }) }
        }
        assertEquals("REFERENCE_MISSING", missing.errors.first().code)
    }

    @Test
    fun `count fields are computed not accepted`() {
        school()
        ok("fet_add_activities") { putJsonArray("activities") { add(activity("T1", "Math", "9A", 1)); add(activity("T2", "Art", "9A", 1)) } }
        val r = ok("fet_add_constraints") {
            putJsonArray("constraints") {
                add(buildJsonObject {
                    put("type", "ConstraintActivitiesSameStartingTime")
                    putJsonObject("fields") { putJsonArray("Activity_Id") { add(1); add(2) } }
                })
            }
        }
        val ref = r.data.jsonObject.getValue("refs").jsonArray.single().jsonPrimitive.content
        val xml = fetmcp.xml.FetWriter.constraintXml(context.session.doc.constraint(ref)!!)
        assertTrue("<Number_of_Activities>2</Number_of_Activities>" in xml, xml)
        val withCount = call("fet_add_constraints") {
            putJsonArray("constraints") {
                add(buildJsonObject {
                    put("type", "ConstraintActivitiesSameStartingHour")
                    putJsonObject("fields") { put("Number_of_Activities", 2); putJsonArray("Activity_Id") { add(1); add(2) } }
                })
            }
        }
        assertFalse(withCount.ok)
        assertEquals("SCHEMA_MISMATCH", withCount.errors.single().code)
    }

    @Test
    fun `nested slot fields are accepted as objects`() {
        school()
        val r = ok("fet_add_constraints") {
            putJsonArray("constraints") {
                add(buildJsonObject {
                    put("type", "ConstraintTeacherNotAvailableTimes")
                    putJsonObject("fields") {
                        put("Teacher", "T1")
                        putJsonArray("Not_Available_Time") {
                            add(buildJsonObject { put("Day", "Mon"); put("Hour", "1") })
                            add(buildJsonObject { put("Day", "Mon"); put("Hour", "2") })
                        }
                    }
                })
            }
        }
        val c = context.session.doc.constraint(r.data.jsonObject.getValue("refs").jsonArray.single().jsonPrimitive.content)!!
        assertEquals(2, c.items("Not_Available_Time").size)
    }

    @Test
    fun `mode forbidden constraints are refused`() {
        school()
        val r = call("fet_add_constraints") {
            putJsonArray("constraints") {
                add(buildJsonObject { put("type", "ConstraintTeacherMaxRealDaysPerWeek"); putJsonObject("fields") { put("Teacher", "T1"); put("Max_Days_Per_Week", 1) } })
            }
        }
        assertFalse(r.ok)
        assertEquals("MODE_FORBIDS", r.errors.single().code)
    }

    private fun activity(teacher: String, subject: String, students: String, total: Int) = buildJsonObject {
        putJsonArray("teachers") { add(teacher) }
        put("subject", subject)
        putJsonArray("students") { add(students) }
        put("total_duration", total)
        put("split", total)
    }
}
