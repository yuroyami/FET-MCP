package fetmcp.mcp

import fetmcp.runner.GenerateOptions
import fetmcp.runner.JobState
import fetmcp.runner.View
import fetmcp.results.ResultReader
import fetmcp.validate.Severity
import fetmcp.validate.Validator
import java.io.File

/** fet_validate (server rules) and fet_precheck (FET's own rules, through a one second run). */
public object ValidationTools {
    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_validate",
            description = "Check the document against the rules this server knows: missing names, broken references, split totals, mode rules, duplicate constraints. Fast, no FET run.",
            inputSchema = schema { bool("warnings_only", "Return warnings only") },
        ) { args ->
            val issues = Validator.validate(args.session.doc)
            val errors = issues.filter { it.severity == Severity.ERROR }
            val warnings = issues.filter { it.severity == Severity.WARNING }
            ToolResult(
                jsonOf(
                    "ready_to_generate" to errors.isEmpty(),
                    "error_count" to errors.size,
                    "warning_count" to warnings.size,
                    "errors" to if (args.bool("warnings_only", false)) emptyList<Any>() else errors,
                    "warnings" to warnings,
                ),
                warnings = warnings,
            )
        }

        tools.tool(
            name = "fet_precheck",
            description = "Ask FET itself whether the data is sound, by running fet-cl with a one second limit. This catches everything FET checks before it starts placing lessons. Takes a few seconds.",
        ) { args ->
            val runner = args.context.requireRunner()
            val file = args.session.file ?: throw ToolFailure("NO_DOCUMENT", "No document is open")
            args.session.save()
            val started = runner.start(file, GenerateOptions(timeLimitSeconds = 1, views = emptySet(), htmlLevel = 0, language = args.context.config.language))
            val status = runner.status(started.id, waitSeconds = 60)
            val jobDir = File(status.outputDir)
            val messages = (ResultReader.logMessages(jobDir, "errors.txt") + ResultReader.logMessages(jobDir, "warnings.txt")).map { it.message }
            // Reaching the placement stage at all means the data passed FET's own checks.
            val passed = status.state != JobState.FAILED
            ToolResult(
                jsonOf(
                    "passed" to passed,
                    "state" to status.state.name,
                    "last_line" to status.lastLine,
                    "fet_messages" to stringsOf(messages),
                    "error" to status.error,
                    "advice" to if (passed) "The data is sound. Run fet_generate_start with a real time limit." else "Fix the messages above, then call fet_precheck again.",
                ),
            )
        }
    }
}
