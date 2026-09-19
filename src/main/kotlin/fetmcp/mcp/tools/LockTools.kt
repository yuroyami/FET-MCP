package fetmcp.mcp

import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.results.Placement
import fetmcp.schema.ConstraintSchema

/** fet_lock and fet_unlock: keep parts of a generated timetable and let FET move the rest. */
public object LockTools {
    private const val TIME_TYPE = "ConstraintActivityPreferredStartingTime"
    private const val ROOM_TYPE = "ConstraintActivityPreferredRoom"

    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_lock",
            description = "Keep lessons where the last run put them, so the next run cannot move them. This is how you keep the parts a person liked and let FET redo the rest.",
            mutating = true,
            inputSchema = schema {
                obj("scope", "Which lessons to lock. Pass exactly one.", required = true) {
                    bool("all", "Every placed lesson")
                    arr("activity_ids", "These lessons", itemsInt())
                    str("teacher", "Every lesson of this teacher")
                    str("students", "Every lesson of this students set")
                    str("subject", "Every lesson of this subject")
                    str("activity_tag", "Every lesson with this tag")
                    str("day", "Every lesson on this day")
                }
                bool("time", "Lock the day and hour (default true)")
                bool("room", "Lock the room too (default true)")
                str("job_id", "Which run to take the placements from. Leave empty for the latest one.")
            },
        ) { args ->
            val (record, generatedDoc) = ResultTools.resolve(args)
            val placements = ResultTools.load(record, generatedDoc).filter { it.placed }
            val scope = args.obj("scope")
            val chosen = select(placements, scope)
            val lockTime = args.bool("time", true)
            val lockRoom = args.bool("room", true)
            val locked = args.session.mutate("lock ${chosen.size} activity(ies)") { d ->
                chosen.mapNotNull { p ->
                    if (d.activity(p.id) == null) null else {
                        val before = d.timeConstraints.size + d.spaceConstraints.size
                        pin(d, p.id, if (lockTime) p.day else null, if (lockTime) p.hour else null, if (lockRoom) p.room else null, p.realRooms)
                        if (d.timeConstraints.size + d.spaceConstraints.size > before) p.id else null
                    }
                }
            }
            ToolResult(
                jsonOf(
                    "job_id" to record.id,
                    "locked_activities" to locked,
                    "already_locked" to (chosen.size - locked.size),
                    "note" to "Locked lessons are pinned with preferred starting time and room at 100 percent. Free them again with fet_unlock.",
                ),
            )
        }

        tools.tool(
            name = "fet_unlock",
            description = "Free lessons that were locked, so the next run may move them again.",
            mutating = true,
            inputSchema = schema {
                obj("scope", "Which lessons to unlock. Pass exactly one.", required = true) {
                    bool("all", "Every locked lesson")
                    arr("activity_ids", "These lessons", itemsInt())
                    str("teacher", "Every lesson of this teacher")
                    str("students", "Every lesson of this students set")
                    str("subject", "Every lesson of this subject")
                    str("activity_tag", "Every lesson with this tag")
                    str("day", "Every lesson pinned to this day")
                }
                bool("time", "Free the day and hour (default true)")
                bool("room", "Free the room (default true)")
                bool("force", "Also free lessons FET marked permanently locked")
            },
        ) { args ->
            val doc = args.session.doc
            val scope = args.obj("scope")
            val freeTime = args.bool("time", true)
            val freeRoom = args.bool("room", true)
            val force = args.bool("force", false)
            val protectedIds = ArrayList<Int>()
            val unlocked = args.session.mutate("unlock activities") { d ->
                val ids = pinnedIds(d).filter { id -> matches(d, id, scope, pinnedDay(d, id)) }
                val out = ArrayList<Int>()
                for (id in ids) {
                    val locked = d.constraints().any { c ->
                        (c.type == TIME_TYPE || c.type == ROOM_TYPE) && c.scalar("Activity_Id")?.toIntOrNull() == id && c.scalar("Permanently_Locked") == "true"
                    }
                    if (locked && !force) { protectedIds += id; continue }
                    if (freeTime) d.timeConstraints.removeAll { it.type == TIME_TYPE && it.scalar("Activity_Id")?.toIntOrNull() == id }
                    if (freeRoom) d.spaceConstraints.removeAll { it.type == ROOM_TYPE && it.scalar("Activity_Id")?.toIntOrNull() == id }
                    out += id
                }
                out
            }
            ToolResult(
                jsonOf(
                    "unlocked_activities" to unlocked,
                    "kept_permanently_locked" to protectedIds,
                    "note" to if (protectedIds.isEmpty()) null else "Some lessons are marked permanently locked in the file. Pass force true to free them.",
                ),
            )
        }
    }

    /** Adds the pinning constraints FET itself uses when it locks a timetable. Skips what is already pinned. */
    internal fun pin(doc: FetDocument, id: Int, day: String?, hour: String?, room: String?, realRooms: List<String> = emptyList()) {
        if (day != null && hour != null && doc.timeConstraints.none { it.type == TIME_TYPE && it.scalar("Activity_Id")?.toIntOrNull() == id }) {
            doc.timeConstraints += Constraint(
                family = Family.TIME, type = TIME_TYPE, weight = 100.0,
                fields = linkedMapOf(
                    "Activity_Id" to FieldValue.of(id),
                    "Day" to FieldValue.of(day),
                    "Hour" to FieldValue.of(hour),
                    "Permanently_Locked" to FieldValue.of(false),
                ),
            )
        }
        if (room != null && doc.spaceConstraints.none { it.type == ROOM_TYPE && it.scalar("Activity_Id")?.toIntOrNull() == id }) {
            val fields = linkedMapOf<String, FieldValue>(
                "Activity_Id" to FieldValue.of(id),
                "Room" to FieldValue.of(room),
            )
            if (realRooms.isNotEmpty()) fields["Real_Room"] = FieldValue.strings(realRooms)
            fields["Permanently_Locked"] = FieldValue.of(false)
            doc.spaceConstraints += Constraint(Family.SPACE, ROOM_TYPE, 100.0, fields = fields)
        }
    }

    private fun select(placements: List<Placement>, scope: Args): List<Placement> {
        if (scope.bool("all", false)) return placements
        val ids = scope.intsOrEmpty("activity_ids").toSet()
        val hits = placements.filter { p ->
            p.id in ids ||
                scope.strOrNull("teacher")?.let { it in p.teachers } == true ||
                scope.strOrNull("students")?.let { it in p.students } == true ||
                scope.strOrNull("subject")?.let { it == p.subject } == true ||
                scope.strOrNull("activity_tag")?.let { it in p.tags } == true ||
                scope.strOrNull("day")?.let { it == p.day || it == p.realDay } == true
        }
        if (hits.isEmpty() && ids.isEmpty() && scope.keys.none { it != "all" }) {
            throw ToolFailure("INVALID_ARGUMENT", "scope needs one of all, activity_ids, teacher, students, subject, activity_tag, day", mapOf("argument" to "scope"))
        }
        return hits
    }

    private fun pinnedIds(doc: FetDocument): List<Int> =
        doc.constraints().filter { it.type == TIME_TYPE || it.type == ROOM_TYPE }
            .mapNotNull { it.scalar("Activity_Id")?.toIntOrNull() }.distinct().toList()

    private fun pinnedDay(doc: FetDocument, id: Int): String? =
        doc.timeConstraints.firstOrNull { it.type == TIME_TYPE && it.scalar("Activity_Id")?.toIntOrNull() == id }?.scalar("Day")

    private fun matches(doc: FetDocument, id: Int, scope: Args, day: String?): Boolean {
        if (scope.bool("all", false)) return true
        val activity = doc.activity(id) ?: return false
        return id in scope.intsOrEmpty("activity_ids").toSet() ||
            scope.strOrNull("teacher")?.let { it in activity.teachers } == true ||
            scope.strOrNull("students")?.let { it in activity.students } == true ||
            scope.strOrNull("subject")?.let { it == activity.subject } == true ||
            scope.strOrNull("activity_tag")?.let { it in activity.tags } == true ||
            scope.strOrNull("day")?.let { it == day } == true
    }
}
