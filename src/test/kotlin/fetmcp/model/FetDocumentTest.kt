package fetmcp.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FetDocumentTest {
    private fun maDoc(): FetDocument = FetDocument(Mode.MORNINGS_AFTERNOONS).apply {
        days += listOf(
            NamedItem("Sun M", "Sunday Morning"), NamedItem("Sun A", "Sunday Afternoon"),
            NamedItem("Mon M", "Monday Morning"), NamedItem("Mon A", "Monday Afternoon"),
        )
        hours += listOf(NamedItem("1", "08:00"), NamedItem("2", "09:00"))
    }

    @Test
    fun `mode maps to and from its xml name`() {
        assertEquals(Mode.MORNINGS_AFTERNOONS, Mode.fromXml("Mornings_Afternoons"))
        assertEquals("Block_Planning", Mode.BLOCK_PLANNING.xml)
        assertNull(Mode.fromXml("Nope"))
    }

    @Test
    fun `ma behavior knows its exception days`() {
        assertEquals(0, MaBehavior.EXCLUSIVE.exceptionDays)
        assertEquals(3, MaBehavior.THREE_DAYS_EXCEPTION.exceptionDays)
        assertEquals(MaBehavior.ONE_DAY_EXCEPTION, MaBehavior.fromXml("One day exception"))
    }

    @Test
    fun `real days are the even indexed fet days`() {
        val doc = maDoc()
        assertEquals(listOf("Sun M", "Mon M"), doc.realDays().map { it.name })
    }

    @Test
    fun `real hours double the hours with am and pm long names`() {
        val doc = maDoc()
        val real = doc.realHours()
        assertEquals(listOf("H1", "H2", "H3", "H4"), real.map { it.name })
        assertEquals("08:00 AM", real[0].longName)
        assertEquals("08:00 PM", real[2].longName)
    }

    @Test
    fun `real days are empty outside mornings afternoons mode`() {
        val doc = maDoc().also { it.mode = Mode.OFFICIAL }
        assertTrue(doc.realDays().isEmpty())
    }

    @Test
    fun `next activity id is max plus one`() {
        val doc = FetDocument()
        assertEquals(1, doc.nextActivityId())
        doc.activities += Activity(id = 7, groupId = 0, teachers = listOf("T"), subject = "S", tags = emptyList(), students = listOf("9A"), duration = 1, totalDuration = 1)
        assertEquals(8, doc.nextActivityId())
    }

    @Test
    fun `students set lookup reaches all three levels`() {
        val doc = FetDocument()
        doc.years += Year(
            name = "9", groups = listOf(
                Group(name = "9A", subgroups = listOf(Subgroup(name = "9A French", numberOfStudents = 12))),
            ),
        )
        assertEquals(StudentsLevel.YEAR, doc.studentsSet("9")?.level)
        assertEquals(StudentsLevel.GROUP, doc.studentsSet("9A")?.level)
        assertEquals(12, doc.studentsSet("9A French")?.numberOfStudents)
        assertNull(doc.studentsSet("9B"))
    }

    @Test
    fun `constraint ref ignores field insertion order`() {
        val a = Constraint(Family.TIME, "ConstraintTeacherMaxDaysPerWeek", fields = linkedMapOf("Teacher" to FieldValue.Scalar("T1"), "Max_Days_Per_Week" to FieldValue.Scalar("4")))
        val b = Constraint(Family.TIME, "ConstraintTeacherMaxDaysPerWeek", fields = linkedMapOf("Max_Days_Per_Week" to FieldValue.Scalar("4"), "Teacher" to FieldValue.Scalar("T1")))
        assertEquals(a.ref, b.ref)
        assertTrue(a.ref.startsWith("t:"))
        assertEquals(10, a.ref.length)
    }

    @Test
    fun `constraint ref changes with weight and family`() {
        val a = Constraint(Family.TIME, "ConstraintBasicCompulsoryTime")
        val b = a.copy(weight = 95.0)
        val c = Constraint(Family.SPACE, "ConstraintBasicCompulsoryTime")
        assertNotEquals(a.ref, b.ref)
        assertTrue(c.ref.startsWith("s:"))
    }

    @Test
    fun `constraint lookup by ref`() {
        val doc = FetDocument()
        val c = Constraint(Family.SPACE, "ConstraintBasicCompulsorySpace")
        doc.spaceConstraints += c
        assertEquals(c, doc.constraint(c.ref))
        assertNull(doc.constraint("t:00000000"))
    }
}
