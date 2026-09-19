package fetmcp.runner

import fetmcp.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JobRunnerTest {
    private val examples = File(System.getProperty("fet.examples"))
    private val stub: File = File("src/test/resources/fake-fet-cl.sh").absoluteFile.also { it.setExecutable(true) }
    private val workspace: File = Files.createTempDirectory("fetmcp-runner").toFile()
    private val input: File = File(examples, "FET-5-block-planning/small-example.fet").copyTo(File(workspace, "small.fet"))

    private fun runner(outcome: String) = JobRunner(Config(workspace = workspace, fetCl = stub), env = mapOf("FAKE_FET_OUTCOME" to outcome))
    private val options = GenerateOptions(timeLimitSeconds = 5)

    @Test
    fun `command line carries the essentials`() {
        val cmd = FetCommand.build(stub, input, File(workspace, "out"), GenerateOptions(timeLimitSeconds = 42, seed = Seed(1, 2, 3, 4, 5, 6), htmlLevel = 3))
        assertEquals(stub.path, cmd[0])
        assertTrue("--inputfile=${input.path}" in cmd)
        assertTrue("--outputdir=${File(workspace, "out").path}" in cmd)
        assertTrue("--timelimitseconds=42" in cmd)
        assertTrue("--htmllevel=3" in cmd)
        assertTrue("--writetimetablesxml=true" in cmd)
        assertTrue("--randomseeds10=1" in cmd && "--randomseeds22=6" in cmd)
        assertTrue("--writetimetablesdayshorizontal=true" in cmd)
    }

    @Test
    fun `seed ranges are checked`() {
        assertFailsWith<IllegalArgumentException> { Seed(0, 0, 0, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { Seed(4294967087, 1, 1, 1, 1, 1) }
        val s = Seed.random()
        assertTrue(s.s10 > 0 || s.s11 > 0 || s.s12 > 0)
    }

    @Test
    fun `a successful run`() {
        val r = runner("success")
        val started = r.start(input, options)
        assertEquals(JobState.RUNNING, started.state)
        val done = r.status(started.id, waitSeconds = 20)
        assertEquals(JobState.SUCCEEDED, done.state)
        assertEquals(28, done.total)
        assertEquals(28, done.placed)
        val record = assertNotNull(r.record(started.id))
        assertTrue(File(record.outputDir, "timetables/small/small_activities.xml").isFile)
        assertTrue(File(record.outputDir, "timetables/small/small_data_and_timetable.fet").isFile)
        assertEquals("Generation successful", done.lastLine)
        assertTrue(File(record.outputDir, "job.json").isFile)
        assertEquals(started.id, r.latest()?.id)
    }

    @Test
    fun `an impossible run keeps exit code zero but is not a success`() {
        val r = runner("impossible")
        val id = r.start(input, options).id
        val done = r.status(id, waitSeconds = 20)
        assertEquals(JobState.IMPOSSIBLE, done.state)
        assertEquals(0, r.record(id)?.exitCode)
        assertTrue(File(r.record(id)!!.outputDir, "timetables/small-highest/small_activities.xml").isFile)
        assertTrue(File(r.record(id)!!.outputDir, "logs/difficult_activities.txt").isFile)
    }

    @Test
    fun `time exceeded`() {
        val r = runner("timeexceeded")
        val id = r.start(input, GenerateOptions(timeLimitSeconds = 1)).id
        assertEquals(JobState.TIME_EXCEEDED, r.status(id, waitSeconds = 20).state)
    }

    @Test
    fun `a data error is a failure with the fet message`() {
        val r = runner("dataerror")
        val id = r.start(input, options).id
        val done = r.status(id, waitSeconds = 20)
        assertEquals(JobState.FAILED, done.state)
        assertTrue("data is wrong" in (done.error ?: ""), "error was: ${done.error}")
        assertTrue((done.error ?: "").contains("has no allowed slot"), "errors.txt should be included")
    }

    @Test
    fun `bad arguments fail with the stdout text`() {
        val r = runner("badargs")
        val id = r.start(input, options).id
        val done = r.status(id, waitSeconds = 20)
        assertEquals(JobState.FAILED, done.state)
        assertTrue("Incorrect command-line parameters" in (done.error ?: ""))
    }

    @Test
    fun `progress is visible while running and a graceful cancel keeps the partial result`() {
        val r = runner("slow")
        val id = r.start(input, GenerateOptions(timeLimitSeconds = 600)).id
        val running = r.status(id, waitSeconds = 3)
        assertEquals(JobState.RUNNING, running.state)
        assertTrue((running.placed ?: 0) >= 1, "placed=${running.placed}")
        assertEquals(28, running.total)
        val cancelled = r.cancel(id, keepPartial = true)
        assertEquals(JobState.INTERRUPTED, cancelled.state)
        assertTrue(File(r.record(id)!!.outputDir, "timetables/small-highest/small_activities.xml").isFile)
    }

    @Test
    fun `a hard cancel writes nothing`() {
        val r = runner("slow")
        val id = r.start(input, GenerateOptions(timeLimitSeconds = 600)).id
        r.status(id, waitSeconds = 2)
        val cancelled = r.cancel(id, keepPartial = false)
        assertEquals(JobState.INTERRUPTED, cancelled.state)
        assertTrue(!File(r.record(id)!!.outputDir, "timetables/small-highest").exists())
    }

    @Test
    fun `only one job at a time`() {
        val r = runner("slow")
        val id = r.start(input, GenerateOptions(timeLimitSeconds = 600)).id
        val e = assertFailsWith<JobException> { r.start(input, options) }
        assertEquals("JOB_RUNNING", e.code)
        r.cancel(id, keepPartial = false)
    }

    @Test
    fun `missing binary is reported`() {
        val r = JobRunner(Config(workspace = workspace, fetCl = null))
        assertEquals("NO_FET_CL", assertFailsWith<JobException> { r.start(input, options) }.code)
    }
}
