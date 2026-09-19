package fetmcp.mcp

import fetmcp.model.FetDocument
import fetmcp.results.Placement
import fetmcp.results.ResultReader
import fetmcp.results.ViewKind
import fetmcp.results.Views
import fetmcp.runner.JobRecord
import fetmcp.runner.JobState
import fetmcp.xml.FetReader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Reading a finished run: summary, placements, a grid for chat, conflicts, and adopting the result. */
public object ResultTools {
    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_results_summary",
            description = "What the last generation produced: how many lessons were placed, how many soft rules broke, where the files are, " +
                "and when it failed, which lesson FET could not fit.",
            inputSchema = schema { str("job_id", "Which run. Leave empty for the latest one.") },
        ) { args ->
            val (record, doc) = resolve(args)
            val jobDir = File(record.outputDir)
            val dir = ResultReader.timetableDir(jobDir, record.stem, record.state)
            val placements = dir?.let { ResultReader.placements(it, record.stem, doc) } ?: emptyList()
            val (broken, total) = dir?.let { ResultReader.conflictTotals(it, record.stem) } ?: (0 to 0.0)
            val difficult = ResultReader.difficultActivities(jobDir)
            ToolResult(
                buildJsonObject {
                    put("job_id", record.id)
                    put("state", record.state.name)
                    put("placed", placements.count { it.placed })
                    put("total", record.total)
                    put("broken_soft_constraints", broken)
                    put("soft_conflict_total", total)
                    put("seed", listOf(record.seed.s10, record.seed.s11, record.seed.s12, record.seed.s20, record.seed.s21, record.seed.s22).toJsonElement())
                    put("difficult_activities", buildJsonArray {
                        for (d in difficult.takeLast(5)) add(jsonOf("order" to d.order, "id" to d.id, "description" to d.description))
                    })
                    put("fet_warnings", stringsOf(ResultReader.logMessages(jobDir, "warnings.txt").map { it.message }))
                    put("fet_errors", stringsOf(ResultReader.logMessages(jobDir, "errors.txt").map { it.message }))
                    put("files", (dir?.let { ResultReader.files(it, record.stem) } ?: emptyMap<String, List<String>>()).toJsonElement())
                    put("timetable_dir", dir?.absolutePath)
                    put("advice", advice(record, difficult.lastOrNull()?.id))
                },
            )
        }

        tools.tool(
            name = "fet_results_placements",
            description = "Where every lesson landed: day, hour and room, joined with its teachers, subject and students. " +
                "In Mornings-Afternoons mode each entry also carries the real day and which half it is.",
            inputSchema = schema {
                str("job_id", "Which run. Leave empty for the latest one.")
                str("teacher", "Only lessons of this teacher")
                str("students", "Only lessons of this students set")
                str("subject", "Only lessons of this subject")
                str("room", "Only lessons in this room")
                str("day", "Only lessons on this day")
                bool("placed_only", "Skip lessons FET could not place")
                int("limit", "Return at most this many (default 500)")
            },
        ) { args ->
            val (record, doc) = resolve(args)
            val placements = load(record, doc)
            val filtered = placements.filter { p ->
                (args.strOrNull("teacher")?.let { it in p.teachers } ?: true) &&
                    (args.strOrNull("students")?.let { it in p.students } ?: true) &&
                    (args.strOrNull("subject")?.let { it == p.subject } ?: true) &&
                    (args.strOrNull("room")?.let { it == p.room || it in p.realRooms } ?: true) &&
                    (args.strOrNull("day")?.let { it == p.day || it == p.realDay } ?: true) &&
                    (!args.bool("placed_only", false) || p.placed)
            }
            val limit = args.int("limit", 500)
            ToolResult(
                jsonOf(
                    "job_id" to record.id,
                    "count" to filtered.size,
                    "unplaced" to placements.count { !it.placed },
                    "truncated" to (filtered.size > limit),
                    "items" to filtered.take(limit).map { placementJson(it) },
                ),
            )
        }

        tools.tool(
            name = "fet_results_view",
            description = "One timetable as a grid you can show a person: days across, hours down. Works for a teacher, a students set or a room.",
            inputSchema = schema {
                str("kind", "Whose timetable", required = true, enum = listOf("teacher", "students", "room"))
                str("name", "Its name", required = true)
                str("job_id", "Which run. Leave empty for the latest one.")
            },
        ) { args ->
            val (record, doc) = resolve(args)
            val kind = ViewKind.parse(args.str("kind")) ?: throw ToolFailure("INVALID_ARGUMENT", "kind must be teacher, students or room", mapOf("argument" to "kind"))
            val name = args.str("name")
            when (kind) {
                ViewKind.TEACHER -> HelperTools.requireName(doc.teacher(name) != null, "teacher", name)
                ViewKind.STUDENTS -> HelperTools.requireName(doc.studentsSet(name) != null, "students set", name)
                ViewKind.ROOM -> HelperTools.requireName(doc.room(name) != null, "room", name)
            }
            val grid = Views.grid(doc, load(record, doc), kind, name)
            ToolResult(
                jsonOf(
                    "job_id" to record.id,
                    "kind" to kind.name.lowercase(),
                    "name" to name,
                    "markdown" to grid.toMarkdown(),
                    "grid" to jsonOf(
                        "columns" to stringsOf(grid.columns),
                        "rows" to stringsOf(grid.rows),
                        "cells" to grid.columns.indices.map { col ->
                            grid.rows.indices.map { row ->
                                grid.cell(col, row).map { a ->
                                    jsonOf("id" to a.id, "subject" to a.subject, "teachers" to stringsOf(a.teachers), "students" to stringsOf(a.students), "room" to a.room, "continuation" to a.continuation)
                                }
                            }
                        },
                    ),
                ),
            )
        }

        tools.tool(
            name = "fet_results_conflicts",
            description = "The soft rules the timetable broke, worst first, with the lessons each one mentions.",
            inputSchema = schema {
                str("job_id", "Which run. Leave empty for the latest one.")
                int("limit", "Return at most this many (default 50)")
            },
        ) { args ->
            val (record, doc) = resolve(args)
            val dir = ResultReader.timetableDir(File(record.outputDir), record.stem, record.state)
                ?: throw ToolFailure("NO_RESULT", "Run ${record.id} produced no timetable", mapOf("job_id" to record.id))
            val conflicts = ResultReader.conflicts(dir, record.stem).sortedByDescending { it.weight ?: 0.0 }
            val limit = args.int("limit", 50)
            ToolResult(
                jsonOf(
                    "job_id" to record.id,
                    "count" to conflicts.size,
                    "items" to conflicts.take(limit).map { jsonOf("weight" to it.weight, "text" to it.text, "activity_ids" to it.activityIds) },
                ),
            )
        }

        tools.tool(
            name = "fet_results_adopt",
            description = "Take the finished timetable into the open document. The result file pins every lesson to its slot and room, " +
                "so after this you can unlock the parts you want moved and generate again.",
            inputSchema = schema { str("job_id", "Which run. Leave empty for the latest one.") },
        ) { args ->
            val (record, _) = resolve(args)
            if (record.state != JobState.SUCCEEDED) {
                throw ToolFailure("NO_RESULT", "Run ${record.id} is ${record.state.name}. FET only writes the full result file when every lesson was placed.", mapOf("job_id" to record.id))
            }
            val dir = ResultReader.timetableDir(File(record.outputDir), record.stem, record.state)
            val file = dir?.let { File(it, "${record.stem}_data_and_timetable.fet") }
            if (file == null || !file.isFile) throw ToolFailure("NO_RESULT", "Run ${record.id} has no data and timetable file", mapOf("job_id" to record.id))
            val replacement = FetReader.read(file).document
            args.session.replace("adopt result of job ${record.id}", replacement)
            ToolResult(
                jsonOf(
                    "job_id" to record.id,
                    "activities" to replacement.activities.size,
                    "pinned_times" to replacement.timeConstraints.count { it.type == "ConstraintActivityPreferredStartingTime" },
                    "pinned_rooms" to replacement.spaceConstraints.count { it.type == "ConstraintActivityPreferredRoom" },
                    "next_step" to "Use fet_unlock to free the lessons you want moved, then fet_generate_start again.",
                ),
            )
        }

        tools.tool(
            name = "fet_export",
            description = "Write the timetable files again with different options, for example a richer HTML level or another language. " +
                "Only works after a successful run, because it re-runs FET on the fully pinned result file.",
            inputSchema = schema {
                str("job_id", "Which run. Leave empty for the latest one.")
                int("html_level", "How rich the HTML is, 0 to 7", minimum = 0, maximum = 7)
                arr("views", "Which HTML views to write", itemsStr())
                str("language", "Language of the written timetables")
            },
        ) { args ->
            val runner = args.context.requireRunner()
            val (record, _) = resolve(args)
            if (record.state != JobState.SUCCEEDED) throw ToolFailure("NO_RESULT", "Run ${record.id} did not finish successfully", mapOf("job_id" to record.id))
            val dir = ResultReader.timetableDir(File(record.outputDir), record.stem, record.state)
            val source = dir?.let { File(it, "${record.stem}_data_and_timetable.fet") }
            if (source == null || !source.isFile) throw ToolFailure("NO_RESULT", "Run ${record.id} has no data and timetable file", mapOf("job_id" to record.id))
            val views = args.stringsOrEmpty("views").map { name ->
                fetmcp.runner.View.parse(name) ?: throw ToolFailure("INVALID_ARGUMENT", "Unknown view '$name'", mapOf("argument" to "views"))
            }.toSet().ifEmpty { fetmcp.runner.View.entries.toSet() }
            val started = runner.start(
                source,
                fetmcp.runner.GenerateOptions(
                    timeLimitSeconds = 60,
                    htmlLevel = args.int("html_level", 2),
                    views = views,
                    language = args.str("language", args.context.config.language),
                ),
            )
            val status = runner.status(started.id, waitSeconds = 60)
            val outDir = ResultReader.timetableDir(File(status.outputDir), source.name.removeSuffix(".fet"), status.state)
            ToolResult(
                jsonOf(
                    "job_id" to status.id,
                    "state" to status.state.name,
                    "files" to (outDir?.let { ResultReader.files(it, source.name.removeSuffix(".fet")) } ?: emptyMap<String, List<String>>()),
                ),
            )
        }
    }

    // ---- shared ----

    /** The run to read plus the document it was generated from, so the join is right even after later edits. */
    internal fun resolve(args: Args): Pair<JobRecord, FetDocument> {
        val runner = args.context.requireRunner()
        val id = args.strOrNull("job_id")
        val record = (if (id != null) runner.record(id) else runner.latest())
            ?: throw ToolFailure("NO_RESULT", if (id != null) "There is no job with id $id" else "No timetable has been generated yet. Call fet_generate_start first.", mapOfNotNull("job_id", id))
        if (record.state == JobState.RUNNING) {
            throw ToolFailure("JOB_RUNNING", "Job ${record.id} is still running. Wait for it with fet_generate_status.", mapOf("job_id" to record.id))
        }
        val input = File(record.inputFile)
        val doc = if (input.isFile) FetReader.read(input).document else args.session.doc
        return record to doc
    }

    internal fun load(record: JobRecord, doc: FetDocument): List<Placement> {
        val dir = ResultReader.timetableDir(File(record.outputDir), record.stem, record.state)
            ?: throw ToolFailure("NO_RESULT", "Run ${record.id} produced no timetable (${record.state.name})", mapOf("job_id" to record.id))
        return ResultReader.placements(dir, record.stem, doc)
    }

    private fun placementJson(p: Placement): JsonObject = jsonOf(
        "id" to p.id,
        "placed" to p.placed,
        "day" to p.day,
        "hour" to p.hour,
        "real_day" to p.realDay,
        "half" to p.half,
        "room" to p.room,
        "real_rooms" to stringsOf(p.realRooms),
        "teachers" to stringsOf(p.teachers),
        "subject" to p.subject,
        "students" to stringsOf(p.students),
        "duration" to p.duration,
    )

    private fun advice(record: JobRecord, blame: Int?): String = when (record.state) {
        JobState.SUCCEEDED -> "Every lesson was placed. Show it with fet_results_view, keep it with fet_lock, or adopt it with fet_results_adopt."
        JobState.IMPOSSIBLE -> "FET proved the rules contradict each other." + blameText(blame) +
            " What to try: read the constraints on that lesson with fet_list_constraints, then lower a weight or remove the tightest rule and generate again."
        JobState.TIME_EXCEEDED -> "The clock ran out, so this is the best partial timetable." + blameText(blame) +
            " What to try: a longer time limit first, then relax the rules around that lesson."
        JobState.INTERRUPTED -> "The run was stopped early." + blameText(blame)
        JobState.FAILED -> "fet-cl could not run this file. Read fet_errors above."
        JobState.RUNNING -> "Still running."
    }

    private fun blameText(blame: Int?): String = if (blame == null) "" else " The last lesson it tried was activity $blame, which is the one to look at first."

    private fun mapOfNotNull(key: String, value: String?): Map<String, String> = if (value == null) emptyMap() else mapOf(key to value)
}
