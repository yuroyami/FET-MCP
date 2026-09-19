package fetmcp.mcp

import fetmcp.runner.GenerateOptions
import fetmcp.runner.JobState
import fetmcp.runner.JobStatus
import fetmcp.runner.Seed
import fetmcp.runner.View
import fetmcp.validate.Severity
import fetmcp.validate.Validator
import kotlinx.serialization.json.JsonObject

/** fet_generate_start, fet_generate_status, fet_generate_cancel. */
public object GenerationTools {
    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_generate_start",
            description = "Start building a timetable. Returns at once with a job id; watch it with fet_generate_status. " +
                "A time limit is required, because a hard timetable can run for a very long time. The same input and the same seed give the same result.",
            inputSchema = schema {
                int("time_limit_seconds", "Stop after this many seconds and keep the best partial result", minimum = 1)
                arr("seed", "Six numbers to repeat an earlier run exactly. Leave empty for a fresh one.", itemsInt())
                int("html_level", "How rich the HTML timetables are, 0 to 7 (default 2)", minimum = 0, maximum = 7)
                arr("views", "Which HTML views to write. Leave empty for all of them.", itemsStr())
                str("language", "Language of the written timetables, for example fr or ar")
            },
        ) { args ->
            val runner = args.context.requireRunner()
            val session = args.session
            val file = session.file ?: throw ToolFailure("NO_DOCUMENT", "No document is open")
            val errors = Validator.validate(session.doc).filter { it.severity == Severity.ERROR }
            if (errors.isNotEmpty()) {
                throw ToolFailure(
                    "VALIDATION",
                    "The data is not ready:\n" + errors.take(10).joinToString("\n") { "- " + it.message } +
                        if (errors.size > 10) "\n(and ${errors.size - 10} more, see fet_validate)" else "",
                    mapOf("errors" to errors.size.toString()),
                )
            }
            session.save()
            // FET seed parts go up to about 4.29 billion, past what an Int holds
            val seedNumbers = args.longsOrEmpty("seed")
            val seed = when {
                seedNumbers.isEmpty() -> null
                seedNumbers.size == 6 -> runCatching { Seed(seedNumbers[0], seedNumbers[1], seedNumbers[2], seedNumbers[3], seedNumbers[4], seedNumbers[5]) }
                    .getOrElse { throw ToolFailure("INVALID_ARGUMENT", it.message ?: "Invalid seed", mapOf("argument" to "seed")) }
                else -> throw ToolFailure("INVALID_ARGUMENT", "seed takes exactly six numbers", mapOf("argument" to "seed"))
            }
            val views = args.stringsOrEmpty("views").map { name ->
                View.parse(name) ?: throw ToolFailure("INVALID_ARGUMENT", "Unknown view '$name'. Known views: ${View.entries.joinToString { it.name.lowercase() }}.", mapOf("argument" to "views"))
            }.toSet().ifEmpty { View.entries.toSet() }
            val status = runner.start(
                file,
                GenerateOptions(
                    timeLimitSeconds = args.int("time_limit_seconds", args.context.config.defaultTimeLimitSeconds),
                    seed = seed,
                    htmlLevel = args.int("html_level", 2),
                    views = views,
                    language = args.str("language", args.context.config.language),
                ),
            )
            ToolResult(
                jsonOf(
                    "job_id" to status.id,
                    "state" to status.state.name,
                    "total_activities" to status.total,
                    "seed" to listOf(status.seed.s10, status.seed.s11, status.seed.s12, status.seed.s20, status.seed.s21, status.seed.s22),
                    "next_step" to "Call fet_generate_status with this job_id and wait_seconds up to 60. Then read the result with fet_results_summary.",
                ),
                warnings = Validator.validate(session.doc).filter { it.severity == Severity.WARNING },
            )
        }

        tools.tool(
            name = "fet_generate_status",
            description = "How a generation job is doing. Pass wait_seconds to wait for it to finish instead of asking again and again. " +
                "While it runs, placed shows how far FET got.",
            inputSchema = schema {
                str("job_id", "The job to check. Leave empty for the latest one.")
                int("wait_seconds", "Wait up to this long for the job to end, 0 to 60", minimum = 0, maximum = 60)
            },
        ) { args ->
            val runner = args.context.requireRunner()
            val id = args.strOrNull("job_id") ?: runner.latest()?.id ?: throw ToolFailure("NO_RESULT", "No generation job has been started yet")
            ToolResult(statusJson(runner.status(id, args.int("wait_seconds", 0))))
        }

        tools.tool(
            name = "fet_generate_cancel",
            description = "Stop a running job. keep_partial true asks FET to stop and write the best timetable it reached; false stops at once and writes nothing.",
            inputSchema = schema {
                str("job_id", "The job to stop. Leave empty for the latest one.")
                bool("keep_partial", "Write the partial timetable before stopping (default true)")
            },
        ) { args ->
            val runner = args.context.requireRunner()
            val id = args.strOrNull("job_id") ?: runner.latest()?.id ?: throw ToolFailure("NO_RESULT", "No generation job has been started yet")
            ToolResult(statusJson(runner.cancel(id, keepPartial = args.bool("keep_partial", true))))
        }
    }

    internal fun statusJson(status: JobStatus): JsonObject = jsonOf(
        "job_id" to status.id,
        "state" to status.state.name,
        "meaning" to meaning(status.state),
        "elapsed_seconds" to status.elapsedSeconds,
        "placed" to status.placed,
        "total" to status.total,
        "last_line" to status.lastLine,
        "error" to status.error,
        "seed" to listOf(status.seed.s10, status.seed.s11, status.seed.s12, status.seed.s20, status.seed.s21, status.seed.s22),
    )

    private fun meaning(state: JobState): String = when (state) {
        JobState.RUNNING -> "FET is still placing lessons."
        JobState.SUCCEEDED -> "Every lesson was placed. Read it with fet_results_summary."
        JobState.IMPOSSIBLE -> "FET proved the rules contradict each other. fet_results_summary lists the lesson that broke it."
        JobState.TIME_EXCEEDED -> "The time limit ran out. The best partial timetable was kept. Try a longer limit or fewer hard rules."
        JobState.INTERRUPTED -> "The job was stopped."
        JobState.FAILED -> "fet-cl could not run this file. See the error."
    }
}
