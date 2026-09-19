package fetmcp.results

import fetmcp.model.Activity
import fetmcp.model.FetDocument
import fetmcp.model.Mode
import fetmcp.model.NamedItem
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultReaderTest {
    private val dir: File = Files.createTempDirectory("fetmcp-results").toFile()

    private fun maDoc(): FetDocument = FetDocument(Mode.MORNINGS_AFTERNOONS).apply {
        days += listOf(NamedItem("Sun M"), NamedItem("Sun A"), NamedItem("Mon M"), NamedItem("Mon A"))
        hours += listOf(NamedItem("1"), NamedItem("2"))
        activities += Activity(1, 0, listOf("T1"), "Math", emptyList(), listOf("9A"), 1, 1)
        activities += Activity(2, 0, listOf("T2"), "Art", emptyList(), listOf("9B"), 2, 2)
        activities += Activity(3, 0, listOf("T1"), "Math", emptyList(), listOf("9B"), 1, 1)
    }

    private val activitiesXml = """<?xml version="1.0" encoding="UTF-8"?>
<Activities_Timetable>
  <Activity>
    <Id>1</Id>
    <Day>Mon A</Day>
    <Hour>2</Hour>
    <Room>R1</Room>
  </Activity>
  <Activity>
    <Id>2</Id>
    <Day>Sun M</Day>
    <Hour>1</Hour>
    <Room>V</Room>
    <Real_Room>R2</Real_Room>
    <Real_Room>R3</Real_Room>
  </Activity>
  <Activity>
    <Id>3</Id>
    <Day></Day>
    <Hour></Hour>
    <Room></Room>
  </Activity>
</Activities_Timetable>
"""

    @Test
    fun `placements join the document and carry real day and half in ma mode`() {
        File(dir, "s_activities.xml").writeText(activitiesXml)
        val p = ResultReader.placements(dir, "s", maDoc())
        assertEquals(3, p.size)
        val first = p[0]
        assertEquals(1, first.id)
        assertEquals("Mon A", first.day)
        assertEquals(3, first.dayIndex)
        assertEquals("Mon M", first.realDay)
        assertEquals("afternoon", first.half)
        assertEquals("2", first.hour)
        assertEquals(1, first.hourIndex)
        assertEquals("R1", first.room)
        assertEquals(listOf("T1"), first.teachers)
        assertEquals("Math", first.subject)
        assertEquals(listOf("9A"), first.students)
        assertEquals(listOf("R2", "R3"), p[1].realRooms)
        assertEquals("morning", p[1].half)
        assertNull(p[2].day)
        assertTrue(!p[2].placed)
    }

    @Test
    fun `conflicts are parsed with weight and activity ids`() {
        File(dir, "s_soft_conflicts.txt").writeText(
            """Soft conflicts of s
Generated with FET 7.10.4 on now

Number of broken soft constraints: 2
Total soft conflicts: 22.50

Soft conflicts list (in descending order):

Time constraint min days between activities broken: activity with id=1 (x) conflicts with activity with id=2 (y), being 0 days too close - this increases the conflicts total by 12.5
Time constraint teacher max gaps per week broken for teacher T1, has 3 gaps, allowed 2 - this increases the conflicts total by 10

End of file.
""",
        )
        val c = ResultReader.conflicts(dir, "s")
        assertEquals(2, c.size)
        assertEquals(12.5, c[0].weight)
        assertEquals(listOf(1, 2), c[0].activityIds)
        assertEquals(10.0, c[1].weight)
        assertTrue(c[1].text.startsWith("Time constraint teacher max gaps"))
        val totals = ResultReader.conflictTotals(dir, "s")
        assertEquals(2, totals.first)
        assertEquals(22.5, totals.second)
    }

    @Test
    fun `difficult activities are parsed`() {
        val logs = File(dir, "logs").also { it.mkdirs() }
        File(logs, "difficult_activities.txt").writeText(
            "Here are the placed activities which led to an inconsistency, in order:\n\nNo: 1, Id: 7 (Teacher: T1, Subject: Math, Students: 9A)\nNo: 2, Id: 12 (Teacher: T2, Subject: Art, Students: 9B)\n",
        )
        val d = ResultReader.difficultActivities(dir)
        assertEquals(listOf(7, 12), d.map { it.id })
        assertEquals(2, d[1].order)
        assertTrue("Art" in d[1].description)
    }

    @Test
    fun `log files are read into messages`() {
        val logs = File(dir, "logs").also { it.mkdirs() }
        File(logs, "warnings.txt").writeText("Title: FET warning\nMessage: There are 3 unrecognized XML tags in your input file.\n\nTitle: FET information\nMessage: ok\n\n")
        val w = ResultReader.logMessages(dir, "warnings.txt")
        assertEquals(2, w.size)
        assertEquals("FET warning", w[0].title)
        assertTrue("unrecognized" in w[0].message)
        assertEquals(emptyList(), ResultReader.logMessages(dir, "errors.txt"))
    }

    @Test
    fun `result directory depends on the state`() {
        val job = Files.createTempDirectory("job").toFile()
        File(job, "timetables/s").mkdirs()
        File(job, "timetables/s-highest").mkdirs()
        assertEquals(File(job, "timetables/s"), ResultReader.timetableDir(job, "s", fetmcp.runner.JobState.SUCCEEDED))
        assertEquals(File(job, "timetables/s-highest"), ResultReader.timetableDir(job, "s", fetmcp.runner.JobState.IMPOSSIBLE))
        assertNull(ResultReader.timetableDir(job, "s", fetmcp.runner.JobState.FAILED))
    }

    @Test
    fun `files are grouped by kind`() {
        File(dir, "s_activities.xml").writeText(activitiesXml)
        File(dir, "s_index.html").writeText("<html></html>")
        File(dir, "s_teachers_days_horizontal.html").writeText("<html></html>")
        File(dir, "s_soft_conflicts.txt").writeText("x")
        val files = ResultReader.files(dir, "s")
        assertTrue(files.getValue("html").any { it.endsWith("s_teachers_days_horizontal.html") })
        assertTrue(files.getValue("index").single().endsWith("s_index.html"))
        assertTrue(files.getValue("xml").single().endsWith("s_activities.xml"))
    }
}
