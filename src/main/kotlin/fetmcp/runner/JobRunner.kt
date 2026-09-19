package fetmcp.runner

import fetmcp.Config
import fetmcp.results.ResultReader
import fetmcp.xml.FetReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

public class JobException(public val code: String, message: String) : RuntimeException(message)

public enum class JobState { RUNNING, SUCCEEDED, IMPOSSIBLE, TIME_EXCEEDED, INTERRUPTED, FAILED }

/** Everything about one fet-cl run. Written to `job.json` in the job directory at start and at the end. */
@Serializable
public data class JobRecord(
    val id: String,
    val inputFile: String,
    val stem: String,
    val outputDir: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val state: JobState,
    val seed: Seed,
    val timeLimitSeconds: Int,
    val total: Int,
    val command: List<String>,
    val markerLine: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
)

public data class JobStatus(
    val id: String,
    val state: JobState,
    val elapsedSeconds: Long,
    val placed: Int?,
    val total: Int,
    val lastLine: String?,
    val seed: Seed,
    val outputDir: String,
    val error: String?,
)

/**
 * Runs fet-cl as a background process, one at a time. The exit code never decides success;
 * the last marker line on stdout does. See the spec, section 12.
 */
public class JobRunner(
    private val config: Config,
    private val env: Map<String, String> = emptyMap(),
    private val runsDir: File = File(config.workspace, ".fet-mcp/runs"),
) {
    private class Job(@Volatile var record: JobRecord, val process: Process?, val stdout: File) {
        val lock = Any()
        @Volatile var hardCancel = false
        @Volatile var watcher: Thread? = null
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val jobs = LinkedHashMap<String, Job>()
    private var counter = 0

    init {
        loadFromDisk()
    }

    public fun start(inputFile: File, options: GenerateOptions): JobStatus {
        val fetCl = config.fetCl ?: throw JobException("NO_FET_CL", "FET_CL_PATH is not set, so fet-cl cannot be run. Build fet-cl and point FET_CL_PATH at it.")
        if (!fetCl.canExecute()) throw JobException("NO_FET_CL", "fet-cl at ${fetCl.path} does not exist or is not executable")
        synchronized(jobs) {
            jobs.values.firstOrNull { it.record.state == JobState.RUNNING }?.let {
                throw JobException("JOB_RUNNING", "Job ${it.record.id} is still running. Wait for it or cancel it first.")
            }
            val id = newId()
            val jobDir = File(runsDir, id).also { it.mkdirs() }
            val stem = inputFile.name.removeSuffix(".fet").ifBlank { "timetable" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val input = File(jobDir, "$stem.fet")
            inputFile.copyTo(input, overwrite = true)
            val total = FetReader.read(input).document.activities.count { it.active }
            val seed = options.seed ?: Seed.random()
            val command = FetCommand.build(fetCl, input, jobDir, options.copy(seed = seed))
            val stdout = File(jobDir, "stdout.log")
            val process = ProcessBuilder(command)
                .directory(jobDir)
                .redirectErrorStream(true)
                .redirectOutput(stdout)
                .also { it.environment().putAll(env) }
                .start()
            val record = JobRecord(
                id = id, inputFile = input.path, stem = stem, outputDir = jobDir.path,
                startedAt = Instant.now().toString(), state = JobState.RUNNING, seed = seed,
                timeLimitSeconds = options.timeLimitSeconds, total = total, command = command,
            )
            val job = Job(record, process, stdout)
            persist(job)
            jobs[id] = job
            // Assign the field before starting, so a cancel arriving at once can still join the watcher.
            val watcher = Thread({ watch(job, options.timeLimitSeconds) }, "fet-cl-$id")
            watcher.isDaemon = true
            job.watcher = watcher
            watcher.start()
            return status(job)
        }
    }

    /** Waits up to `waitSeconds` for the job to finish, then reports. */
    public fun status(jobId: String, waitSeconds: Int = 0): JobStatus {
        val job = find(jobId)
        val deadline = System.currentTimeMillis() + waitSeconds.coerceIn(0, 60) * 1000L
        while (job.record.state == JobState.RUNNING && System.currentTimeMillis() < deadline) Thread.sleep(200)
        return status(job)
    }

    /** SIGTERM keeps the partial result (FET writes it on the way out). SIGINT drops everything. */
    public fun cancel(jobId: String, keepPartial: Boolean): JobStatus {
        val job = find(jobId)
        val process = job.process
        if (process == null || job.record.state != JobState.RUNNING) return status(job)
        if (keepPartial) {
            // SIGTERM: fet-cl stops the search and still writes the best timetable it reached.
            process.destroy()
        } else {
            job.hardCancel = true
            runCatching { ProcessBuilder("kill", "-INT", process.pid().toString()).start().waitFor(5, TimeUnit.SECONDS) }
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(15, TimeUnit.SECONDS)
        }
        job.watcher?.join(45_000)
        if (job.record.state == JobState.RUNNING) {
            // The watcher did not get there. Close the record ourselves rather than leaving the runner wedged.
            val exit = runCatching { process.exitValue() }.getOrNull()
            finish(job, JobState.INTERRUPTED, null, exit, null)
        }
        return status(job)
    }

    public fun record(jobId: String): JobRecord? = synchronized(jobs) { jobs[jobId]?.record }
    public fun latest(): JobRecord? = synchronized(jobs) { jobs.values.lastOrNull()?.record }
    public fun list(): List<JobRecord> = synchronized(jobs) { jobs.values.map { it.record } }
    public fun jobDir(jobId: String): File = File(find(jobId).record.outputDir)

    // ---- internals ----

    private fun watch(job: Job, timeLimitSeconds: Int) {
        val process = job.process!!
        var killed = false
        try {
            // The time limit bounds the search only. Writing the timetables afterwards is unbounded and can take
            // minutes on a big school, so the grace period is generous before we step in.
            if (!process.waitFor(timeLimitSeconds + WRITE_GRACE_SECONDS, TimeUnit.SECONDS)) {
                killed = true
                process.destroy()
                if (!process.waitFor(WRITE_GRACE_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
            }
            val exit = runCatching { process.exitValue() }.getOrNull()
            val lines = runCatching { job.stdout.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
            val marker = lines.lastOrNull { it in MARKERS }
            when {
                job.hardCancel -> finish(job, JobState.INTERRUPTED, marker, exit, null)
                // A marker means fet-cl reached a verdict, even if we had to kill it while it wrote the last files.
                marker != null -> finish(job, MARKERS.getValue(marker), marker, exit, if (killed) "fet-cl was still writing files after the time limit and was stopped. Some output may be missing." else null)
                killed -> finish(job, JobState.INTERRUPTED, null, exit, "fet-cl did not finish within the time limit plus $WRITE_GRACE_SECONDS seconds and was stopped.")
                exit != 0 -> finish(job, JobState.FAILED, null, exit, failureText(job, lines))
                else -> finish(job, JobState.FAILED, null, exit, "fet-cl ended without a result line. " + failureText(job, lines))
            }
        } catch (e: Exception) {
            finish(job, JobState.FAILED, null, null, "The job watcher failed: ${e::class.simpleName}: ${e.message}")
        } finally {
            // Never leave a record RUNNING: start() would refuse every later job until the server restarts.
            finish(job, JobState.FAILED, null, null, "The job ended without a result.")
        }
    }

    private fun failureText(job: Job, lines: List<String>): String {
        val logErrors = runCatching { ResultReader.logMessages(File(job.record.outputDir), "errors.txt").map { it.message } }.getOrDefault(emptyList())
        val tail = lines.takeLast(5)
        return (tail + logErrors).joinToString("\n").ifBlank { "fet-cl produced no output." }
    }

    private fun finish(job: Job, state: JobState, marker: String?, exit: Int?, error: String?) {
        synchronized(job.lock) {
            if (job.record.state != JobState.RUNNING) return
            job.record = job.record.copy(state = state, finishedAt = Instant.now().toString(), markerLine = marker, exitCode = exit, error = error)
            persist(job)
        }
    }

    private fun status(job: Job): JobStatus {
        val r = job.record
        val start = Instant.parse(r.startedAt)
        val end = r.finishedAt?.let { Instant.parse(it) } ?: Instant.now()
        val placed = when (r.state) {
            JobState.SUCCEEDED -> r.total
            else -> progress(File(r.outputDir))
        }
        val lastLine = r.markerLine ?: runCatching { job.stdout.readLines().lastOrNull { it.isNotBlank() } }.getOrNull()
        return JobStatus(r.id, r.state, Duration.between(start, end).seconds, placed, r.total, lastLine, r.seed, r.outputDir, r.error)
    }

    /** The last "FET reached N activities placed" line of the live progress log. */
    private fun progress(jobDir: File): Int? {
        val file = File(jobDir, "logs/max_placed_activities.txt")
        if (!file.isFile) return null
        return runCatching { file.readLines() }.getOrDefault(emptyList())
            .mapNotNull { PROGRESS.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .lastOrNull()
    }

    private fun find(jobId: String): Job = synchronized(jobs) { jobs[jobId] } ?: throw JobException("JOB_NOT_FOUND", "No job with id $jobId")

    private fun newId(): String {
        counter++
        return Instant.now().toString().replace(Regex("[^0-9]"), "").take(14) + "-" + counter
    }

    private fun persist(job: Job) {
        File(job.record.outputDir, "job.json").writeText(json.encodeToString(JobRecord.serializer(), job.record))
    }

    /** Records from earlier server runs. A job that was still running cannot be resumed; it is marked interrupted. */
    private fun loadFromDisk() {
        val dirs = runsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return
        for (dir in dirs) {
            val file = File(dir, "job.json")
            if (!file.isFile) continue
            val record = runCatching { json.decodeFromString(JobRecord.serializer(), file.readText()) }.getOrNull() ?: continue
            val job = Job(record, null, File(dir, "stdout.log"))
            if (record.state == JobState.RUNNING) {
                job.record = record.copy(state = JobState.INTERRUPTED, finishedAt = Instant.now().toString(), error = "The server restarted while this job was running")
                persist(job)
            }
            jobs[record.id] = job
        }
    }

    private companion object {
        /** fet-cl writes these to stdout untranslated, so the language does not matter. */
        val MARKERS = mapOf(
            "Generation successful" to JobState.SUCCEEDED,
            "Impossible" to JobState.IMPOSSIBLE,
            "Time exceeded" to JobState.TIME_EXCEEDED,
            "Generation interrupted" to JobState.INTERRUPTED,
        )
        /** How long fet-cl may take to write its files after the search ends before we stop it. */
        const val WRITE_GRACE_SECONDS = 300L
        /** The progress line is translated, so match the last number on it rather than the English words. */
        val PROGRESS = Regex("(\\d+)\\D*$")
    }
}
