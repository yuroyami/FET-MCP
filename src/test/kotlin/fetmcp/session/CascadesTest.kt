package fetmcp.session

import fetmcp.model.Activity
import fetmcp.model.ActivityTag
import fetmcp.model.Building
import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.model.Group
import fetmcp.model.NamedItem
import fetmcp.model.Room
import fetmcp.model.Subgroup
import fetmcp.model.Subject
import fetmcp.model.Teacher
import fetmcp.model.Year
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CascadesTest {
    private fun doc(): FetDocument = FetDocument().apply {
        days += listOf(NamedItem("Mon"), NamedItem("Tue"))
        hours += listOf(NamedItem("1"), NamedItem("2"))
        subjects += listOf(Subject("Math"), Subject("Art"))
        activityTags += ActivityTag("Lab")
        teachers += listOf(Teacher("A", qualifiedSubjects = listOf("Math")), Teacher("B"))
        buildings += Building("Main")
        rooms += listOf(Room("R1", building = "Main"), Room("R2"), Room("V", virtual = true, realRoomSets = listOf(listOf("R1"), listOf("R2"))))
        years += Year("9", groups = listOf(Group("9A", subgroups = listOf(Subgroup("9A-x"))), Group("Shared")))
        years += Year("10", groups = listOf(Group("Shared")))
        activities += Activity(1, 0, listOf("A"), "Math", listOf("Lab"), listOf("9A"), 1, 1)
        activities += Activity(2, 0, listOf("A", "B"), "Art", emptyList(), listOf("9A-x", "Shared"), 1, 1)
        activities += Activity(3, 3, listOf("B"), "Math", emptyList(), listOf("10"), 1, 2)
        activities += Activity(4, 3, listOf("B"), "Math", emptyList(), listOf("10"), 1, 2)
        timeConstraints += Constraint(Family.TIME, "ConstraintTeacherMaxDaysPerWeek", fields = mapOf("Teacher" to FieldValue.of("A"), "Max_Days_Per_Week" to FieldValue.of(2)))
        timeConstraints += Constraint(Family.TIME, "ConstraintMinDaysBetweenActivities", fields = mapOf("Consecutive_If_Same_Day" to FieldValue.of(true), "Activity_Id" to FieldValue.strings(listOf("3", "4")), "MinDays" to FieldValue.of(1)))
        timeConstraints += Constraint(Family.TIME, "ConstraintActivitiesSameStartingTime", fields = mapOf("Activity_Id" to FieldValue.strings(listOf("1", "2", "3"))))
        timeConstraints += Constraint(Family.TIME, "ConstraintTeacherNotAvailableTimes", fields = mapOf("Teacher" to FieldValue.of("B"), "Not_Available_Time" to FieldValue.list(FieldValue.obj("Day" to FieldValue.of("Mon"), "Hour" to FieldValue.of("1")))))
        spaceConstraints += Constraint(Family.SPACE, "ConstraintActivityTagPreferredRoom", fields = mapOf("Activity_Tag" to FieldValue.of("Lab"), "Room" to FieldValue.of("R1")))
        spaceConstraints += Constraint(Family.SPACE, "ConstraintStudentsSetHomeRoom", fields = mapOf("Students" to FieldValue.of("9A"), "Room" to FieldValue.of("R2")))
    }

    @Test
    fun `removing a teacher drops activities left without one and constraints naming it`() {
        val d = doc()
        val report = Cascades.remove(d, EntityKind.TEACHER, "A")
        assertEquals(listOf(1), report.removedActivities)
        assertEquals(listOf("B"), d.activity(2)!!.teachers)
        assertNull(d.teacher("A"))
        assertTrue(d.timeConstraints.none { it.type == "ConstraintTeacherMaxDaysPerWeek" })
        assertEquals(1, report.removedConstraints.size)
        assertEquals(1, report.changedConstraints.size)
        // activity 1 vanished, so the same-starting-time constraint shrinks to (2, 3)
        val same = d.timeConstraints.single { it.type == "ConstraintActivitiesSameStartingTime" }
        assertEquals(listOf("2", "3"), same.scalars("Activity_Id"))
    }

    @Test
    fun `renaming a teacher reaches activities and constraints`() {
        val d = doc()
        val report = Cascades.rename(d, EntityKind.TEACHER, "B", "Bea")
        assertEquals(listOf("A", "Bea"), d.activity(2)!!.teachers)
        assertEquals("Bea", d.timeConstraints.single { it.type == "ConstraintTeacherNotAvailableTimes" }.scalar("Teacher"))
        assertEquals(listOf(2, 3, 4), report.changedActivities)
        assertEquals(1, report.changedConstraints.size)
        assertNull(d.teacher("B"))
    }

    @Test
    fun `removing a subject removes its activities and qualifications`() {
        val d = doc()
        val report = Cascades.remove(d, EntityKind.SUBJECT, "Math")
        assertEquals(listOf(1, 3, 4), report.removedActivities)
        assertEquals(emptyList(), d.teacher("A")!!.qualifiedSubjects)
        assertTrue(d.timeConstraints.none { it.type == "ConstraintMinDaysBetweenActivities" })
        // (1,2,3) loses 1 and 3, only one activity is left, so the constraint goes too
        assertTrue(d.timeConstraints.none { it.type == "ConstraintActivitiesSameStartingTime" })
    }

    @Test
    fun `removing an activity tag strips it from activities and drops tag constraints`() {
        val d = doc()
        Cascades.remove(d, EntityKind.ACTIVITY_TAG, "Lab")
        assertEquals(emptyList(), d.activity(1)!!.tags)
        assertTrue(d.spaceConstraints.none { it.type == "ConstraintActivityTagPreferredRoom" })
        assertEquals(4, d.activities.size)
    }

    @Test
    fun `removing a room strips it from virtual rooms the way fet does, keeping the set`() {
        val d = doc()
        Cascades.remove(d, EntityKind.ROOM, "R1")
        // FET empties the set rather than dropping it, so the virtual room still has two sets to report on.
        assertEquals(listOf(emptyList(), listOf("R2")), d.room("V")!!.realRoomSets)
        assertTrue(d.spaceConstraints.none { it.type == "ConstraintActivityTagPreferredRoom" })
        assertTrue(d.spaceConstraints.any { it.type == "ConstraintStudentsSetHomeRoom" })
        // The document is now one FET would also call invalid, and the validator says so.
        assertTrue(fetmcp.validate.Validator.errors(d).any { it.code == "VIRTUAL_ROOM_INVALID" })
    }

    @Test
    fun `a preferred room constraint survives losing its real rooms`() {
        val d = doc()
        d.spaceConstraints += Constraint(
            Family.SPACE, "ConstraintActivityPreferredRoom",
            fields = linkedMapOf(
                "Activity_Id" to FieldValue.of(1),
                "Room" to FieldValue.of("V"),
                "Real_Room" to FieldValue.strings(listOf("R1")),
                "Permanently_Locked" to FieldValue.of(false),
            ),
        )
        Cascades.remove(d, EntityKind.ROOM, "R1")
        val c = d.spaceConstraints.single { it.type == "ConstraintActivityPreferredRoom" }
        assertEquals(emptyList(), c.scalars("Real_Room"))
        assertEquals("V", c.scalar("Room"))
    }

    @Test
    fun `a cascade that makes two constraints identical keeps only one`() {
        val d = doc()
        d.activities += Activity(40, 0, listOf("A"), "Art", emptyList(), listOf("9A"), 1, 1)
        d.activities += Activity(41, 0, listOf("A"), "Art", emptyList(), listOf("9A"), 1, 1)
        d.timeConstraints += Constraint(Family.TIME, "ConstraintActivitiesSameStartingHour", fields = mapOf("Activity_Id" to FieldValue.strings(listOf("1", "2", "40"))))
        d.timeConstraints += Constraint(Family.TIME, "ConstraintActivitiesSameStartingHour", fields = mapOf("Activity_Id" to FieldValue.strings(listOf("1", "2", "41"))))
        Cascades.removeActivities(d, listOf(40, 41))
        assertEquals(1, d.timeConstraints.count { it.type == "ConstraintActivitiesSameStartingHour" })
        assertTrue(fetmcp.validate.Validator.errors(d).none { it.code == "DUPLICATE_CONSTRAINT" })
    }

    @Test
    fun `removing a building only clears it on rooms`() {
        val d = doc()
        Cascades.remove(d, EntityKind.BUILDING, "Main")
        assertEquals("", d.room("R1")!!.building)
        assertEquals(3, d.rooms.size)
    }

    @Test
    fun `a group that also lives in another year survives its removal from one year`() {
        val d = doc()
        val report = Cascades.removeStudents(d, yearName = "9", groupName = "Shared", subgroupName = null)
        assertEquals(listOf("9A"), d.year("9")!!.groups.map { it.name })
        assertEquals(listOf("9A-x", "Shared"), d.activity(2)!!.students)
        assertTrue(report.removedActivities.isEmpty())
    }

    @Test
    fun `removing a students set strips it from activities and drops empty ones`() {
        val d = doc()
        val report = Cascades.remove(d, EntityKind.STUDENTS, "9A")
        // year 9 keeps "Shared"; 9A and its subgroup 9A-x are gone
        assertEquals(listOf("Shared"), d.year("9")!!.groups.map { it.name })
        assertEquals(listOf(1), report.removedActivities)
        assertEquals(listOf("Shared"), d.activity(2)!!.students)
        assertTrue(d.spaceConstraints.none { it.type == "ConstraintStudentsSetHomeRoom" })
    }

    @Test
    fun `removing one component removes the whole split activity`() {
        val d = doc()
        val report = Cascades.removeActivities(d, listOf(4))
        assertEquals(listOf(3, 4), report.removedActivities)
        assertTrue(d.timeConstraints.none { it.type == "ConstraintMinDaysBetweenActivities" })
        assertEquals(listOf("1", "2"), d.timeConstraints.single { it.type == "ConstraintActivitiesSameStartingTime" }.scalars("Activity_Id"))
    }

    @Test
    fun `a teacher removal takes the whole split activity, never a lone sibling`() {
        val d = doc()
        // 20/21/22 is a three way split. Only the first block has the teacher that goes.
        d.activities += Activity(20, 20, listOf("A"), "Math", emptyList(), listOf("9A"), 1, 3)
        d.activities += Activity(21, 20, listOf("A", "B"), "Math", emptyList(), listOf("9A"), 1, 3)
        d.activities += Activity(22, 20, listOf("A", "B"), "Math", emptyList(), listOf("9A"), 1, 3)
        val report = Cascades.remove(d, EntityKind.TEACHER, "A")
        assertTrue(report.removedActivities.containsAll(listOf(20, 21, 22)), "the whole group must go, got ${report.removedActivities}")
        assertTrue(d.activities.none { it.groupId == 20 })
        // Nothing is left pointing at a group whose first component is gone.
        assertTrue(fetmcp.validate.Validator.errors(d).none { it.code == "GROUP_ID_INVALID" })
    }

    @Test
    fun `a students set removal takes the whole split activity too`() {
        val d = doc()
        d.activities += Activity(30, 30, listOf("A"), "Math", emptyList(), listOf("9A-x"), 1, 2)
        d.activities += Activity(31, 30, listOf("A"), "Math", emptyList(), listOf("9A-x", "Shared"), 1, 2)
        Cascades.remove(d, EntityKind.SUBGROUP, "9A-x")
        assertTrue(d.activities.none { it.groupId == 30 })
        assertTrue(fetmcp.validate.Validator.errors(d).none { it.code == "GROUP_ID_INVALID" })
    }

    @Test
    fun `renaming a day reaches constraints`() {
        val d = doc()
        Cascades.rename(d, EntityKind.DAY, "Mon", "Monday")
        val slot = d.timeConstraints.single { it.type == "ConstraintTeacherNotAvailableTimes" }.items("Not_Available_Time").single() as FieldValue.Obj
        assertEquals("Monday", (slot.fields["Day"] as FieldValue.Scalar).text)
        assertEquals("Monday", d.days[0].name)
    }

    @Test
    fun `a constraint is kept or dropped by fet's own minimum activity count`() {
        val d = doc()
        // Max hourly span needs two activities, the same as FET.
        d.timeConstraints += Constraint(Family.TIME, "ConstraintActivitiesMaxHourlySpan", fields = mapOf("Activity_Id" to FieldValue.strings(listOf("1", "2")), "Max_Hourly_Span" to FieldValue.of(3)))
        // Max in a term is happy with one.
        d.timeConstraints += Constraint(Family.TIME, "ConstraintActivitiesMaxInATerm", fields = mapOf("Activity_Id" to FieldValue.strings(listOf("1", "2")), "Max_Number_of_Activities_in_A_Term" to FieldValue.of(1)))
        Cascades.removeActivities(d, listOf(2))
        assertTrue(d.timeConstraints.none { it.type == "ConstraintActivitiesMaxHourlySpan" }, "span constraint should go when only one activity is left")
        assertEquals(listOf("1"), d.timeConstraints.single { it.type == "ConstraintActivitiesMaxInATerm" }.scalars("Activity_Id"))
    }

    @Test
    fun `references to a name are listed`() {
        val d = doc()
        assertEquals(1, Cascades.referencesTo(d, EntityKind.DAY, "Mon").size)
        assertEquals(0, Cascades.referencesTo(d, EntityKind.DAY, "Tue").size)
    }
}
