package fetmcp.session

import fetmcp.Config
import fetmcp.model.Mode
import fetmcp.model.Subject
import fetmcp.model.Teacher
import fetmcp.xml.FetReader
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentSessionTest {
    private val examples = File(System.getProperty("fet.examples"))
    private val workspace: File = Files.createTempDirectory("fetmcp-session").toFile()
    private val session = DocumentSession(Config(workspace = workspace, fetCl = null))

    @Test
    fun `new writes a minimal valid file with the basic constraints`() {
        val file = session.new("school.fet", Mode.MORNINGS_AFTERNOONS, "Test School", "")
        assertTrue(file.exists())
        val doc = FetReader.read(file).document
        assertEquals(Mode.MORNINGS_AFTERNOONS, doc.mode)
        assertEquals("Test School", doc.institution)
        assertEquals(listOf("ConstraintBasicCompulsoryTime"), doc.timeConstraints.map { it.type })
        assertEquals(listOf("ConstraintBasicCompulsorySpace"), doc.spaceConstraints.map { it.type })
        assertTrue(file.readBytes().take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), "file must start with a BOM like FET")
    }

    @Test
    fun `open reports version and unknown tags`() {
        File(examples, "FET-5-block-planning/small-example.fet").copyTo(File(workspace, "small.fet"))
        val report = session.open("small.fet")
        assertEquals("5.37.5-bp", report.fileVersion)
        assertEquals(Mode.BLOCK_PLANNING, session.doc.mode)
        assertEquals(28, session.doc.activities.size)
        assertTrue(report.unknownTags.isEmpty())
    }

    @Test
    fun `paths outside the workspace are refused`() {
        assertFailsWith<SessionException> { session.open("../outside.fet") }
        assertFailsWith<SessionException> { session.new("/tmp/x.fet", Mode.OFFICIAL, "x", "") }
    }

    @Test
    fun `mutate snapshots writes and can be undone and redone`() {
        session.new("s.fet", Mode.OFFICIAL, "S", "")
        session.mutate("add subject") { it.subjects += Subject("Math") }
        session.mutate("add teacher") { it.teachers += Teacher("T1") }
        assertEquals(1, FetReader.read(File(workspace, "s.fet")).document.teachers.size)

        assertEquals("add teacher", session.undo(1))
        assertEquals(0, session.doc.teachers.size)
        assertEquals(1, session.doc.subjects.size)
        assertEquals(0, FetReader.read(File(workspace, "s.fet")).document.teachers.size)

        assertEquals("add teacher", session.redo(1))
        assertEquals(1, session.doc.teachers.size)

        session.undo(2)
        assertEquals(0, session.doc.subjects.size)
        assertFailsWith<SessionException> { session.undo(1) }
    }

    @Test
    fun `a new mutation after undo drops the redo branch`() {
        session.new("s.fet", Mode.OFFICIAL, "S", "")
        session.mutate("a") { it.subjects += Subject("A") }
        session.undo(1)
        session.mutate("b") { it.subjects += Subject("B") }
        assertFailsWith<SessionException> { session.redo(1) }
        assertEquals(listOf("B"), session.doc.subjects.map { it.name })
        assertEquals(listOf("b"), session.history().map { it.description })
    }

    @Test
    fun `a failing mutation leaves document and disk untouched`() {
        session.new("s.fet", Mode.OFFICIAL, "S", "")
        session.mutate("teacher") { it.teachers += Teacher("T1") }
        val before = File(workspace, "s.fet").readText()
        assertFailsWith<SessionException> {
            session.mutate("dup") { it.teachers += Teacher("T1") }
        }
        assertEquals(1, session.doc.teachers.size)
        assertEquals(before, File(workspace, "s.fet").readText())
        assertEquals(listOf("teacher"), session.history().map { it.description })
    }

    @Test
    fun `errors that were already there do not block edits`() {
        File(examples, "FET-5-block-planning/small-example.fet").copyTo(File(workspace, "small.fet"))
        session.open("small.fet")
        session.mutate("subject") { it.subjects += Subject("New") }
        assertTrue(session.doc.subjects.any { it.name == "New" })
    }

    @Test
    fun `edits from outside are detected`() {
        session.new("s.fet", Mode.OFFICIAL, "S", "")
        assertFalse(session.isStale())
        val f = File(workspace, "s.fet")
        f.writeText(f.readText() + "\n<!-- edited in the GUI -->\n")
        assertTrue(session.isStale())
        assertFailsWith<SessionException> { session.mutate("x") { it.subjects += Subject("X") } }
        session.open("s.fet")
        assertFalse(session.isStale())
    }

    @Test
    fun `undo redo and save refuse to overwrite a file edited elsewhere`() {
        session.new("s.fet", Mode.OFFICIAL, "S", "")
        session.mutate("subject") { it.subjects += Subject("Math") }
        val f = File(workspace, "s.fet")
        val edited = f.readText() + "\n<!-- edited in the GUI -->\n"
        f.writeText(edited)
        assertFailsWith<SessionException> { session.undo(1) }
        assertFailsWith<SessionException> { session.redo(1) }
        assertFailsWith<SessionException> { session.save() }
        assertEquals(edited, f.readText(), "the file on disk must be untouched")
    }

    @Test
    fun `nothing open means no mutation`() {
        assertFailsWith<SessionException> { session.mutate("x") { } }
    }
}
