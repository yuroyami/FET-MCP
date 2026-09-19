package fetmcp.mcp

import fetmcp.model.Activity
import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.session.Cascades
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** fet_add_activities, fet_update_activities, fet_remove_activities, fet_set_activities_active, fet_list_activities. */
public object ActivityTools {
    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_add_activities",
            description = "Add lessons. One entry is one subject taught to one students set by one or more teachers. " +
                "Give total_duration for the weekly hours and split for how many separate blocks, or durations for uneven blocks. " +
                "A split also gets a 'min days between activities' constraint so the blocks land on different days.",
            mutating = true,
            inputSchema = schema {
                arr("activities", "The lessons to add", itemsObj {
                    arr("teachers", "Teacher names. Empty means no teacher, which some modes use.", itemsStr())
                    str("subject", "Subject name", required = true)
                    arr("students", "Students set names: years, groups or subgroups", itemsStr())
                    arr("tags", "Activity tag names", itemsStr())
                    int("total_duration", "Weekly hours for this subject and class (default 1)")
                    int("split", "How many separate blocks per week (default 1)")
                    arr("durations", "Length of each block, for uneven splits. Overrides split and total_duration.", itemsInt())
                    int("min_days_between", "Days between the blocks of a split (default 1)")
                    num("min_days_weight", "How hard that rule is, 0 to 100 (default 95, FET's recommendation)")
                    bool("consecutive_if_same_day", "If two blocks land on the same day, keep them next to each other (default true)")
                    bool("active", "Include this lesson in generation (default true)")
                    int("number_of_students", "Override the computed student count")
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val items = args.objects("activities")
            val results = args.session.mutate("add ${items.size} activity group(s)") { d ->
                items.map { addOne(d, it) }
            }
            ToolResult(
                jsonOf(
                    "items" to results.map { jsonOf("subject" to it.subject, "ids" to it.ids, "group_id" to it.groupId, "constraint" to it.constraintRef) },
                    "total_added" to results.sumOf { it.ids.size },
                ),
            )
        }

        tools.tool(
            name = "fet_update_activities",
            description = "Change lessons that already exist. By default only the named component changes; pass apply_to_group to change every block of a split.",
            mutating = true,
            inputSchema = schema {
                arr("updates", "The changes to apply", itemsObj {
                    int("id", "Activity id", required = true)
                    bool("apply_to_group", "Apply to every block of this split activity")
                    obj("changes", "What to change", required = true) {
                        arr("teachers", "New teacher list", itemsStr())
                        str("subject", "New subject")
                        arr("students", "New students set list", itemsStr())
                        arr("tags", "New activity tag list", itemsStr())
                        int("duration", "New block length")
                        bool("active", "Include in generation")
                        int("number_of_students", "Override the computed student count")
                        str("comments", "Notes")
                    }
                }, required = true)
            },
        ) { args ->
            val updates = args.objects("updates")
            val touched = args.session.mutate("update ${updates.size} activity update(s)") { d ->
                val ids = LinkedHashSet<Int>()
                for (u in updates) {
                    val id = u.int("id")
                    val activity = d.activity(id) ?: throw ToolFailure("NOT_FOUND", "There is no activity with id $id", mapOf("activity" to id.toString()))
                    val targets = if (u.bool("apply_to_group", false) && activity.isSplit) d.activities.filter { it.groupId == activity.groupId }.map { it.id } else listOf(id)
                    val changes = u.obj("changes")
                    checkReferences(d, changes)
                    for (target in targets) {
                        ids += target
                        d.activities.replaceAll { a -> if (a.id != target) a else applyChanges(a, changes) }
                    }
                }
                // A changed block length changes the group total.
                for ((groupId, members) in d.activities.filter { it.isSplit }.groupBy { it.groupId }) {
                    val total = members.sumOf { it.duration }
                    d.activities.replaceAll { if (it.groupId == groupId) it.copy(totalDuration = total) else it }
                }
                d.activities.replaceAll { if (!it.isSplit && it.totalDuration != it.duration) it.copy(totalDuration = it.duration) else it }
                ids.toList()
            }
            ToolResult(jsonOf("updated" to touched))
        }

        tools.tool(
            name = "fet_remove_activities",
            description = "Remove lessons. Removing one block of a split removes the whole split, the way FET does it, because the blocks share one id. " +
                "To make a split shorter instead, change its durations with fet_update_activities. " +
                "Constraints that name the removed lessons are shrunk or removed with them.",
            mutating = true,
            inputSchema = schema {
                arr("ids", "Activity ids to remove", itemsInt())
                arr("group_ids", "Activity group ids to remove entirely", itemsInt())
            },
        ) { args ->
            val doc = args.session.doc
            val ids = args.intsOrEmpty("ids") + args.intsOrEmpty("group_ids").flatMap { g -> doc.activities.filter { it.groupId == g }.map { it.id } }
            if (ids.isEmpty()) throw ToolFailure("INVALID_ARGUMENT", "Pass ids or group_ids", mapOf("argument" to "ids"))
            for (id in ids) if (doc.activity(id) == null) throw ToolFailure("NOT_FOUND", "There is no activity with id $id", mapOf("activity" to id.toString()))
            val report = args.session.mutate("remove ${ids.size} activity(ies)") { d ->
                Cascades.removeActivities(d, ids)
            }
            ToolResult(
                jsonOf(
                    "removed_activities" to report.removedActivities,
                    "changed_activities" to report.changedActivities,
                    "removed_constraints" to stringsOf(report.removedConstraints),
                    "changed_constraints" to stringsOf(report.changedConstraints),
                ),
            )
        }

        tools.tool(
            name = "fet_set_activities_active",
            description = "Turn lessons on or off without deleting them. Inactive lessons are ignored by generation.",
            mutating = true,
            inputSchema = schema {
                arr("ids", "Activity ids", itemsInt())
                str("teacher", "Instead of ids: every activity of this teacher")
                str("students", "Instead of ids: every activity of this students set")
                str("subject", "Instead of ids: every activity of this subject")
                bool("active", "True to include, false to skip", required = true)
            },
        ) { args ->
            val doc = args.session.doc
            val active = args.boolOrNull("active") ?: throw ToolFailure("INVALID_ARGUMENT", "active must be true or false", mapOf("argument" to "active"))
            val ids = selection(doc, args)
            val changed = args.session.mutate("${if (active) "activate" else "deactivate"} ${ids.size} activity(ies)") { d ->
                d.activities.replaceAll { if (it.id in ids && it.active != active) it.copy(active = active) else it }
                ids.toList()
            }
            ToolResult(jsonOf("changed" to changed, "active" to active))
        }

        tools.tool(
            name = "fet_list_activities",
            description = "List lessons, with filters. Use include_constraints to also see which constraints touch each one.",
            inputSchema = schema {
                str("teacher", "Only activities of this teacher")
                str("students", "Only activities of this students set")
                str("subject", "Only activities of this subject")
                str("tag", "Only activities with this activity tag")
                int("group_id", "Only this split activity")
                bool("active_only", "Skip deactivated activities")
                bool("include_constraints", "Add the refs of constraints that name each activity")
                int("limit", "Return at most this many (default 200)")
            },
        ) { args ->
            val doc = args.session.doc
            val limit = args.int("limit", 200)
            val filtered = doc.activities.filter { a ->
                (args.strOrNull("teacher")?.let { it in a.teachers } ?: true) &&
                    (args.strOrNull("students")?.let { it in a.students } ?: true) &&
                    (args.strOrNull("subject")?.let { it == a.subject } ?: true) &&
                    (args.strOrNull("tag")?.let { it in a.tags } ?: true) &&
                    (args.intOrNull("group_id")?.let { it == a.groupId } ?: true) &&
                    (!args.bool("active_only", false) || a.active)
            }
            val withConstraints = args.bool("include_constraints", false)
            ToolResult(
                jsonOf(
                    "count" to filtered.size,
                    "items" to filtered.take(limit).map { a ->
                        if (!withConstraints) activityJson(a) else buildJsonObject {
                            for ((k, v) in activityJson(a)) put(k, v)
                            put("constraints", stringsOf(constraintsFor(doc, a.id)))
                        }
                    },
                    "truncated" to (filtered.size > limit),
                ),
            )
        }
    }

    // ---- helpers ----

    internal fun activityJson(a: Activity): JsonObject = jsonOf(
        "id" to a.id,
        "group_id" to a.groupId,
        "teachers" to stringsOf(a.teachers),
        "subject" to a.subject,
        "students" to stringsOf(a.students),
        "tags" to stringsOf(a.tags),
        "duration" to a.duration,
        "total_duration" to a.totalDuration,
        "active" to a.active,
        "number_of_students" to a.numberOfStudents,
        "comments" to a.comments.ifEmpty { null },
    )

    private fun constraintsFor(doc: FetDocument, id: Int): List<String> =
        doc.constraints().filter { c -> c.fields.values.any { containsActivity(it, id) } }.map { it.ref }.toList()

    private fun containsActivity(value: FieldValue, id: Int): Boolean = when (value) {
        is FieldValue.Scalar -> value.text.toIntOrNull() == id
        is FieldValue.Items -> value.items.any { containsActivity(it, id) }
        is FieldValue.Obj -> value.fields.values.any { containsActivity(it, id) }
    }

    private data class AddResult(val subject: String, val ids: List<Int>, val groupId: Int, val constraintRef: String?)

    private fun addOne(doc: FetDocument, a: Args): AddResult {
        val teachers = a.stringsOrEmpty("teachers")
        val subject = a.str("subject")
        val students = a.stringsOrEmpty("students")
        val tags = a.stringsOrEmpty("tags")
        for (t in teachers) if (doc.teacher(t) == null) throw ToolFailure("REFERENCE_MISSING", "There is no teacher called '$t'", mapOf("teacher" to t))
        if (doc.subject(subject) == null) throw ToolFailure("REFERENCE_MISSING", "There is no subject called '$subject'", mapOf("subject" to subject))
        for (s in students) if (doc.studentsSet(s) == null) throw ToolFailure("REFERENCE_MISSING", "There is no students set called '$s'", mapOf("students" to s))
        for (t in tags) if (doc.activityTag(t) == null) throw ToolFailure("REFERENCE_MISSING", "There is no activity tag called '$t'", mapOf("activity_tag" to t))

        val durations = when {
            "durations" in a -> a.ints("durations")
            else -> {
                val total = a.int("total_duration", 1)
                val split = a.int("split", 1)
                if (split < 1) throw ToolFailure("INVALID_ARGUMENT", "split must be at least 1", mapOf("argument" to "split"))
                if (total < split) throw ToolFailure("INVALID_ARGUMENT", "total_duration ($total) cannot be smaller than split ($split)", mapOf("argument" to "total_duration"))
                val base = total / split
                val extra = total % split
                List(split) { i -> base + if (i < extra) 1 else 0 }
            }
        }
        if (durations.isEmpty() || durations.any { it < 1 }) throw ToolFailure("INVALID_ARGUMENT", "Every block must last at least one hour", mapOf("argument" to "durations"))
        if (doc.hours.isNotEmpty() && durations.any { it > doc.hours.size }) {
            throw ToolFailure("INVALID_ARGUMENT", "A block of ${durations.max()} hours does not fit in a day of ${doc.hours.size} hours", mapOf("argument" to "durations"))
        }
        val total = durations.sum()
        val firstId = doc.nextActivityId()
        val ids = durations.indices.map { firstId + it }
        val groupId = if (durations.size > 1) firstId else 0
        val active = a.bool("active", true)
        val number = a.intOrNull("number_of_students")
        val comments = a.str("comments", "")
        durations.forEachIndexed { i, duration ->
            doc.activities += Activity(ids[i], groupId, teachers, subject, tags, students, duration, total, active, number, comments)
        }
        var ref: String? = null
        if (durations.size > 1) {
            // Same as Rules::addSplitActivityFast: the split gets a min days constraint so the blocks spread out.
            // In Mornings-Afternoons mode FET counts real days here, so two blocks never share a real day.
            val constraint = Constraint(
                family = Family.TIME,
                type = "ConstraintMinDaysBetweenActivities",
                weight = a.num("min_days_weight", 95.0),
                fields = linkedMapOf(
                    "Consecutive_If_Same_Day" to FieldValue.of(a.bool("consecutive_if_same_day", true)),
                    "Activity_Id" to FieldValue.strings(ids.map { it.toString() }),
                    "MinDays" to FieldValue.of(a.int("min_days_between", 1)),
                ),
            )
            doc.timeConstraints += constraint
            ref = constraint.ref
        }
        return AddResult(subject, ids, groupId, ref)
    }

    private fun checkReferences(doc: FetDocument, changes: Args) {
        if ("teachers" in changes) for (t in changes.strings("teachers")) if (doc.teacher(t) == null) throw ToolFailure("REFERENCE_MISSING", "There is no teacher called '$t'", mapOf("teacher" to t))
        changes.strOrNull("subject")?.let { if (doc.subject(it) == null) throw ToolFailure("REFERENCE_MISSING", "There is no subject called '$it'", mapOf("subject" to it)) }
        if ("students" in changes) for (s in changes.strings("students")) if (doc.studentsSet(s) == null) throw ToolFailure("REFERENCE_MISSING", "There is no students set called '$s'", mapOf("students" to s))
        if ("tags" in changes) for (t in changes.strings("tags")) if (doc.activityTag(t) == null) throw ToolFailure("REFERENCE_MISSING", "There is no activity tag called '$t'", mapOf("activity_tag" to t))
    }

    private fun applyChanges(a: Activity, changes: Args): Activity = a.copy(
        teachers = if ("teachers" in changes) changes.strings("teachers") else a.teachers,
        subject = changes.str("subject", a.subject),
        students = if ("students" in changes) changes.strings("students") else a.students,
        tags = if ("tags" in changes) changes.strings("tags") else a.tags,
        duration = changes.int("duration", a.duration),
        active = changes.bool("active", a.active),
        numberOfStudents = changes.intOrNull("number_of_students") ?: a.numberOfStudents,
        comments = changes.str("comments", a.comments),
    )

    private fun selection(doc: FetDocument, args: Args): Set<Int> {
        val ids = args.intsOrEmpty("ids").toMutableSet()
        args.strOrNull("teacher")?.let { t -> ids += doc.activities.filter { t in it.teachers }.map { it.id } }
        args.strOrNull("students")?.let { s -> ids += doc.activities.filter { s in it.students }.map { it.id } }
        args.strOrNull("subject")?.let { s -> ids += doc.activities.filter { s == it.subject }.map { it.id } }
        if (ids.isEmpty()) throw ToolFailure("INVALID_ARGUMENT", "Nothing selected. Pass ids, teacher, students or subject.", mapOf("argument" to "ids"))
        for (id in ids) if (doc.activity(id) == null) throw ToolFailure("NOT_FOUND", "There is no activity with id $id", mapOf("activity" to id.toString()))
        return ids
    }
}
