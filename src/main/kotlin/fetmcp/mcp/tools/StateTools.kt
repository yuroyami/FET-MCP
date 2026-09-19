package fetmcp.mcp

import fetmcp.model.FetDocument
import fetmcp.model.Mode
import fetmcp.schema.ConstraintSchema
import fetmcp.session.Cascades
import fetmcp.validate.Severity
import fetmcp.validate.Validator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** fet_state, fet_explain_mode, fet_set_mode. */
public object StateTools {
    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_state",
            description = "Everything about the open document at a glance: mode, week shape, entity counts, validation summary, the latest generation job, and recent changes.",
            inputSchema = schema {
                bool("include_history", "Add the list of changes that can be undone")
                bool("include_issues", "Add the full validation issue list instead of counts only")
            },
        ) { args ->
            val session = args.session
            val doc = session.doc
            val issues = Validator.validate(doc)
            val latest = args.context.runner?.latest()
            ToolResult(
                buildJsonObject {
                    put("file", session.relativePath)
                    put("file_version", session.fileVersion)
                    put("mode", doc.mode.xml)
                    put("institution", doc.institution)
                    put("comments", doc.comments)
                    put("week", week(doc))
                    put("counts", counts(doc))
                    put("validation", jsonOf(
                        "errors" to issues.count { it.severity == Severity.ERROR },
                        "warnings" to issues.count { it.severity == Severity.WARNING },
                        "ready_to_generate" to issues.none { it.severity == Severity.ERROR },
                    ))
                    if (args.bool("include_issues", false)) put("issues", issues.toJsonElement())
                    put("stale_on_disk", session.isStale())
                    put("undo_available", session.history().count { it.applied })
                    put("redo_available", session.history().count { !it.applied })
                    if (args.bool("include_history", false)) {
                        put("history", buildJsonArray {
                            for (h in session.history().takeLast(50)) add(jsonOf("index" to h.index, "description" to h.description, "applied" to h.applied, "at" to h.at))
                        })
                    }
                    put(
                        "latest_job",
                        latest?.let {
                            jsonOf("id" to it.id, "state" to it.state.name, "started_at" to it.startedAt, "finished_at" to it.finishedAt, "total_activities" to it.total)
                        }.toJsonElement(),
                    )
                    put("fet_cl", args.context.config.fetCl?.path)
                },
            )
        }

        tools.tool(
            name = "fet_explain_mode",
            description = "What a FET mode means and which constraint types only exist in it. Read this before working in Mornings-Afternoons, Block Planning or Terms.",
            inputSchema = schema { str("mode", "Mode to explain. Defaults to the open document's mode.", enum = Mode.entries.map { it.xml }) },
        ) { args ->
            val mode = args.strOrNull("mode")?.let { text ->
                Mode.fromXml(text) ?: throw ToolFailure("INVALID_ARGUMENT", "mode must be one of ${Mode.entries.joinToString { it.xml }}", mapOf("argument" to "mode"))
            } ?: args.session.doc.mode
            val allowed = ConstraintSchema.allowedIn(mode).map { it.name }.toSet()
            val elsewhere = Mode.entries.filter { it != mode }.flatMap { ConstraintSchema.allowedIn(it).map { t -> t.name } }.toSet()
            ToolResult(
                jsonOf(
                    "mode" to mode.xml,
                    "help" to ConstraintSchema.help(mode),
                    "constraint_types_allowed" to allowed.size,
                    "mode_only_constraint_types" to stringsOf(allowed.filter { it !in elsewhere }.sorted()),
                    "not_allowed_here" to stringsOf(ConstraintSchema.types.keys.filter { it !in allowed }.sorted().take(40)),
                ),
            )
        }

        tools.tool(
            name = "fet_set_mode",
            description = "Change the timetabling mode. Constraints that the new mode does not allow block the change unless remove_incompatible is true.",
            mutating = true,
            inputSchema = schema {
                str("mode", "The new mode", required = true, enum = Mode.entries.map { it.xml })
                bool("remove_incompatible", "Delete constraints the new mode does not allow (default false)")
            },
        ) { args ->
            val target = Mode.fromXml(args.str("mode"))
                ?: throw ToolFailure("INVALID_ARGUMENT", "mode must be one of ${Mode.entries.joinToString { it.xml }}", mapOf("argument" to "mode"))
            val doc = args.session.doc
            if (doc.mode == target) return@tool ToolResult(jsonOf("mode" to target.xml, "removed_constraints" to stringsOf(emptyList())))
            if (target == Mode.MORNINGS_AFTERNOONS && doc.days.size % 2 != 0) {
                throw ToolFailure("MA_ODD_DAYS", "Mornings-Afternoons mode needs an even number of FET days (two per real day). The week has ${doc.days.size}.")
            }
            val incompatible = doc.constraints().filter { c -> ConstraintSchema.get(c.type)?.modes?.contains(target) == false }.toList()
            if (incompatible.isNotEmpty() && !args.bool("remove_incompatible", false)) {
                val sample = incompatible.take(10).joinToString("; ") { "${it.type} (${it.ref})" }
                throw ToolFailure(
                    "MODE_FORBIDS",
                    "${incompatible.size} constraint(s) are not allowed in ${target.xml}: $sample. Pass remove_incompatible true to delete them.",
                    mapOf("count" to incompatible.size.toString()),
                )
            }
            val removed = args.session.mutate("set mode to ${target.xml}") { d ->
                val refs = incompatible.map { it.ref }.toSet()
                d.timeConstraints.removeAll { it.ref in refs }
                d.spaceConstraints.removeAll { it.ref in refs }
                d.mode = target
                if (target == Mode.MORNINGS_AFTERNOONS) {
                    d.teachers.replaceAll { if (it.morningsAfternoonsBehavior == null) it.copy(morningsAfternoonsBehavior = fetmcp.model.MaBehavior.UNRESTRICTED) else it }
                } else {
                    d.teachers.replaceAll { it.copy(morningsAfternoonsBehavior = null) }
                }
                if (target == Mode.TERMS) {
                    if (d.terms == null) d.terms = fetmcp.model.Terms(1, d.days.size.coerceAtLeast(1))
                } else {
                    d.terms = null
                }
                refs.toList()
            }
            ToolResult(
                jsonOf(
                    "mode" to target.xml,
                    "removed_constraints" to stringsOf(removed),
                    "week" to week(args.session.doc),
                    "note" to when (target) {
                        Mode.MORNINGS_AFTERNOONS -> "Every teacher now has the behaviour 'Unrestricted'. Set Exclusive for Morocco style schools with fet_update."
                        Mode.TERMS -> "Set the terms with fet_set_terms. FET needs days = terms x days per term."
                        else -> "Mornings-Afternoons data was dropped."
                    },
                ),
            )
        }
    }

    internal fun counts(doc: FetDocument): JsonObject = jsonOf(
        "days" to doc.days.size,
        "hours" to doc.hours.size,
        "subjects" to doc.subjects.size,
        "activity_tags" to doc.activityTags.size,
        "teachers" to doc.teachers.size,
        "years" to doc.years.size,
        "groups" to doc.years.sumOf { it.groups.size },
        "subgroups" to doc.years.sumOf { y -> y.groups.sumOf { it.subgroups.size } },
        "activities" to doc.activities.size,
        "active_activities" to doc.activities.count { it.active },
        "weekly_hours" to doc.activities.filter { it.active }.sumOf { it.duration },
        "buildings" to doc.buildings.size,
        "rooms" to doc.rooms.size,
        "time_constraints" to doc.timeConstraints.size,
        "space_constraints" to doc.spaceConstraints.size,
        "generation_options" to doc.generationOptions.size,
    )

    internal fun week(doc: FetDocument): JsonObject = buildJsonObject {
        put("days", stringsOf(doc.days.map { it.name }))
        put("hours", stringsOf(doc.hours.map { it.name }))
        if (doc.mode == Mode.MORNINGS_AFTERNOONS) {
            put("real_days", stringsOf(doc.realDays().map { it.name }))
            put("halves", stringsOf(listOf("morning", "afternoon")))
            put("hours_per_half_day", doc.hours.size)
            put("real_hours", stringsOf(doc.realHours().map { it.name }))
            put("slot_hint", "Give slots as {real_day, half, hour} or as {day, hour}. FET day 2i is the morning of real day i, 2i+1 the afternoon.")
        }
        doc.terms?.let { put("terms", jsonOf("terms" to it.terms, "days_per_term" to it.daysPerTerm)) }
    }
}
