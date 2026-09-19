package fetmcp.results

import fetmcp.model.Activity
import fetmcp.model.FetDocument
import fetmcp.model.Group
import fetmcp.model.Mode
import fetmcp.model.NamedItem
import fetmcp.model.Subgroup
import fetmcp.model.Year
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewsTest {
    private fun doc(): FetDocument = FetDocument().apply {
        days += listOf(NamedItem("Mon"), NamedItem("Tue"))
        hours += listOf(NamedItem("1"), NamedItem("2"), NamedItem("3"))
        years += Year("9", groups = listOf(Group("9A", subgroups = listOf(Subgroup("9A-x")))))
        activities += Activity(1, 0, listOf("T1"), "Math", emptyList(), listOf("9A"), 2, 2)
        activities += Activity(2, 0, listOf("T2"), "Art", emptyList(), listOf("9A-x"), 1, 1)
        activities += Activity(3, 0, listOf("T1"), "PE", emptyList(), listOf("9"), 1, 1)
    }

    private fun placements(d: FetDocument) = listOf(
        Placement.of(d, 1, "Mon", "1", "R1", emptyList()),
        Placement.of(d, 2, "Mon", "3", null, emptyList()),
        Placement.of(d, 3, "Tue", "2", null, emptyList()),
    )

    @Test
    fun `teacher grid spans multi hour activities`() {
        val d = doc()
        val grid = Views.grid(d, placements(d), ViewKind.TEACHER, "T1")
        assertEquals(listOf("Mon", "Tue"), grid.columns)
        assertEquals(listOf("1", "2", "3"), grid.rows)
        assertEquals("Math", grid.cell(0, 0).single().subject)
        assertTrue(grid.cell(0, 1).single().continuation)
        assertEquals("PE", grid.cell(1, 1).single().subject)
        assertTrue(grid.cell(1, 0).isEmpty())
    }

    @Test
    fun `students grid includes parents and children of the set`() {
        val d = doc()
        val grid = Views.grid(d, placements(d), ViewKind.STUDENTS, "9A")
        assertEquals(setOf("Math", "Art", "PE"), grid.allActivities().map { it.subject }.toSet())
        val sub = Views.grid(d, placements(d), ViewKind.STUDENTS, "9A-x")
        assertEquals(setOf("Math", "Art", "PE"), sub.allActivities().map { it.subject }.toSet())
        val year = Views.grid(d, placements(d), ViewKind.STUDENTS, "9")
        assertEquals(setOf("Math", "Art", "PE"), year.allActivities().map { it.subject }.toSet())
    }

    @Test
    fun `markdown rendering has a header row per day`() {
        val d = doc()
        val md = Views.grid(d, placements(d), ViewKind.TEACHER, "T1").toMarkdown()
        assertTrue(md.lines()[0].startsWith("| Hour | Mon | Tue |"))
        assertTrue("Math" in md && "R1" in md)
    }

    @Test
    fun `mornings afternoons grid uses real days and doubled hours`() {
        val d = FetDocument(Mode.MORNINGS_AFTERNOONS).apply {
            days += listOf(NamedItem("Sun M"), NamedItem("Sun A"), NamedItem("Mon M"), NamedItem("Mon A"))
            hours += listOf(NamedItem("1"), NamedItem("2"))
            activities += Activity(1, 0, listOf("T1"), "Math", emptyList(), listOf("9A"), 1, 1)
        }
        val grid = Views.grid(d, listOf(Placement.of(d, 1, "Mon A", "2", null, emptyList())), ViewKind.TEACHER, "T1")
        assertEquals(listOf("Sun M", "Mon M"), grid.columns)
        assertEquals(listOf("H1", "H2", "H3", "H4"), grid.rows)
        assertEquals("Math", grid.cell(1, 3).single().subject)
    }
}
