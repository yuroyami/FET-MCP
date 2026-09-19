package fetmcp.validate

import fetmcp.model.Activity
import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.model.Group
import fetmcp.model.MaBehavior
import fetmcp.model.Mode
import fetmcp.model.NamedItem
import fetmcp.model.Room
import fetmcp.model.Subject
import fetmcp.model.Teacher
import fetmcp.model.Terms
import fetmcp.model.Year
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidatorTest {
    /** A small, valid school. Every test breaks one thing. */
    private fun school(mode: Mode = Mode.OFFICIAL): FetDocument = FetDocument(mode).apply {
        days += (1..4).map { NamedItem("D$it") }
        hours += (1..3).map { NamedItem("H$it") }
        subjects += Subject("Math")
        teachers += Teacher("T1")
        years += Year("9", groups = listOf(Group("9A")))
        rooms += Room("R1", capacity = 30)
        activities += Activity(1, 0, listOf("T1"), "Math", emptyList(), listOf("9A"), 1, 1)
        timeConstraints += Constraint(Family.TIME, "ConstraintBasicCompulsoryTime")
        spaceConstraints += Constraint(Family.SPACE, "ConstraintBasicCompulsorySpace")
    }

    private fun codes(doc: FetDocument): List<String> = Validator.validate(doc).map { it.code }
    private fun errors(doc: FetDocument): List<Issue> = Validator.validate(doc).filter { it.severity == Severity.ERROR }

    @Test
    fun `a valid school has no issues`() {
        assertEquals(emptyList(), Validator.validate(school()))
    }

    @Test
    fun `missing basic compulsory constraints`() {
        val doc = school().also { it.timeConstraints.clear() }
        assertTrue("BASIC_CONSTRAINT_MISSING" in codes(doc))
        val weak = school().also { it.spaceConstraints[0] = it.spaceConstraints[0].copy(weight = 90.0) }
        assertTrue("BASIC_CONSTRAINT_MISSING" in codes(weak))
    }

    @Test
    fun `unknown references in activities and constraints`() {
        val doc = school()
        doc.activities += Activity(2, 0, listOf("Nobody"), "Math", emptyList(), listOf("9A"), 1, 1)
        doc.timeConstraints += Constraint(Family.TIME, "ConstraintTeacherMaxDaysPerWeek", fields = mapOf("Teacher" to FieldValue.of("Ghost"), "Max_Days_Per_Week" to FieldValue.of(3)))
        val issues = errors(doc)
        assertTrue(issues.any { it.code == "REFERENCE_MISSING" && it.where["teacher"] == "Nobody" && it.where["activity"] == "2" })
        assertTrue(issues.any { it.code == "REFERENCE_MISSING" && it.where["teacher"] == "Ghost" && it.where["constraint"] != null })
    }

    @Test
    fun `duplicate names and ids`() {
        val doc = school()
        doc.teachers += Teacher("T1")
        doc.activities += Activity(1, 0, listOf("T1"), "Math", emptyList(), listOf("9A"), 1, 1)
        val c = codes(doc)
        assertTrue("DUPLICATE_NAME" in c)
        assertTrue("DUPLICATE_ACTIVITY_ID" in c)
    }

    @Test
    fun `students names share one namespace`() {
        val doc = school()
        doc.years += Year("9A")
        assertTrue("DUPLICATE_NAME" in codes(doc))
    }

    @Test
    fun `split activity durations must add up`() {
        val doc = school()
        doc.activities += Activity(10, 10, listOf("T1"), "Math", emptyList(), listOf("9A"), 1, 3)
        doc.activities += Activity(11, 10, listOf("T1"), "Math", emptyList(), listOf("9A"), 1, 3)
        assertTrue("TOTAL_DURATION_MISMATCH" in codes(doc))
        val long = school().also { it.activities[0] = it.activities[0].copy(duration = 5, totalDuration = 5) }
        assertTrue("DURATION_TOO_LONG" in codes(long))
    }

    @Test
    fun `constraint type unknown or not allowed in mode`() {
        val doc = school()
        doc.timeConstraints += Constraint(Family.TIME, "ConstraintTeacherMaxRealDaysPerWeek", fields = mapOf("Teacher" to FieldValue.of("T1"), "Max_Days_Per_Week" to FieldValue.of(3)))
        doc.timeConstraints += Constraint(Family.TIME, "ConstraintMadeUp")
        val c = codes(doc)
        assertTrue("MODE_FORBIDS" in c)
        assertTrue("UNKNOWN_CONSTRAINT_TYPE" in c)
    }

    @Test
    fun `weight range and duplicates`() {
        val doc = school()
        doc.timeConstraints += Constraint(Family.TIME, "ConstraintTeacherMaxDaysPerWeek", weight = 120.0, fields = mapOf("Teacher" to FieldValue.of("T1"), "Max_Days_Per_Week" to FieldValue.of(3)))
        doc.timeConstraints += Constraint(Family.TIME, "ConstraintTeacherMaxDaysPerWeek", weight = 120.0, fields = mapOf("Teacher" to FieldValue.of("T1"), "Max_Days_Per_Week" to FieldValue.of(3)))
        val c = codes(doc)
        assertTrue("INVALID_WEIGHT" in c)
        assertTrue("DUPLICATE_CONSTRAINT" in c)
    }

    @Test
    fun `mornings afternoons needs an even day count`() {
        val doc = school(Mode.MORNINGS_AFTERNOONS).also { it.days += NamedItem("D5") }
        assertTrue("MA_ODD_DAYS" in codes(doc))
        assertTrue("MA_ODD_DAYS" !in codes(school(Mode.MORNINGS_AFTERNOONS)))
    }

    @Test
    fun `terms arithmetic`() {
        val doc = school(Mode.TERMS).also { it.terms = Terms(3, 2) }
        assertTrue("TERMS_MISMATCH" in codes(doc))
        assertTrue("TERMS_MISMATCH" !in codes(school(Mode.TERMS).also { it.terms = Terms(2, 2) }))
    }

    @Test
    fun `no active activity`() {
        val doc = school().also { it.activities[0] = it.activities[0].copy(active = false) }
        assertTrue("NO_ACTIVE_ACTIVITY" in codes(doc))
    }

    @Test
    fun `virtual room rules`() {
        val doc = school()
        doc.rooms += Room("V", virtual = true, realRoomSets = listOf(listOf("R1")))
        assertTrue("VIRTUAL_ROOM_INVALID" in codes(doc))
        val doc2 = school()
        doc2.rooms += Room("V", virtual = true, realRoomSets = listOf(listOf("R1"), listOf("V")))
        assertTrue("VIRTUAL_ROOM_INVALID" in codes(doc2))
    }

    @Test
    fun `warnings for split larger than days, soft max hours daily, ma teacher overload`() {
        val doc = school()
        (20..25).forEach { doc.activities += Activity(it, 20, listOf("T1"), "Math", emptyList(), listOf("9A"), 1, 6) }
        doc.timeConstraints += Constraint(Family.TIME, "ConstraintTeacherMaxHoursDaily", weight = 90.0, fields = mapOf("Teacher" to FieldValue.of("T1"), "Maximum_Hours_Daily" to FieldValue.of(2)))
        val issues = Validator.validate(doc)
        assertTrue(issues.any { it.code == "SPLIT_EXCEEDS_DAYS" && it.severity == Severity.WARNING })
        assertTrue(issues.any { it.code == "SOFT_MAX_HOURS_DAILY" && it.severity == Severity.WARNING })

        val ma = school(Mode.MORNINGS_AFTERNOONS)
        ma.teachers[0] = ma.teachers[0].copy(morningsAfternoonsBehavior = MaBehavior.EXCLUSIVE)
        (30..37).forEach { ma.activities += Activity(it, 0, listOf("T1"), "Math", emptyList(), listOf("9A"), 1, 1) }
        assertTrue(Validator.validate(ma).any { it.code == "MA_TEACHER_OVERLOAD" })
    }

    @Test
    fun `activity id references in constraints must exist`() {
        val doc = school()
        doc.timeConstraints += Constraint(Family.TIME, "ConstraintActivityPreferredStartingTime", fields = mapOf("Activity_Id" to FieldValue.of(99), "Preferred_Day" to FieldValue.of("D1"), "Preferred_Hour" to FieldValue.of("H1"), "Permanently_Locked" to FieldValue.of(false)))
        assertTrue(errors(doc).any { it.code == "REFERENCE_MISSING" && it.where["activity"] == "99" })
    }
}
