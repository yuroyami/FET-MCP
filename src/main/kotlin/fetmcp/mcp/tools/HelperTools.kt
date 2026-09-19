package fetmcp.mcp

import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.model.Mode
import fetmcp.model.StudentsLevel
import fetmcp.schema.ConstraintSchema
import kotlinx.serialization.json.JsonObject

/** Sugar over fet_add_constraints for the rules every school needs. Each one still writes plain FET constraints. */
public object HelperTools {
    /** limit name to the singular and plural FET constraint type and the field that carries the number. */
    internal data class Limit(val single: String, val all: String, val field: String)

    // Field names are FET's own XML tags. Which modes allow each type comes from the schema, not from here.
    internal val teacherLimits = mapOf(
        "max_days_per_week" to Limit("ConstraintTeacherMaxDaysPerWeek", "ConstraintTeachersMaxDaysPerWeek", "Max_Days_Per_Week"),
        "min_days_per_week" to Limit("ConstraintTeacherMinDaysPerWeek", "ConstraintTeachersMinDaysPerWeek", "Minimum_Days_Per_Week"),
        "max_hours_daily" to Limit("ConstraintTeacherMaxHoursDaily", "ConstraintTeachersMaxHoursDaily", "Maximum_Hours_Daily"),
        "min_hours_daily" to Limit("ConstraintTeacherMinHoursDaily", "ConstraintTeachersMinHoursDaily", "Minimum_Hours_Daily"),
        "max_hours_continuously" to Limit("ConstraintTeacherMaxHoursContinuously", "ConstraintTeachersMaxHoursContinuously", "Maximum_Hours_Continuously"),
        "max_gaps_per_week" to Limit("ConstraintTeacherMaxGapsPerWeek", "ConstraintTeachersMaxGapsPerWeek", "Max_Gaps"),
        "max_gaps_per_day" to Limit("ConstraintTeacherMaxGapsPerDay", "ConstraintTeachersMaxGapsPerDay", "Max_Gaps"),
        "max_span_per_day" to Limit("ConstraintTeacherMaxSpanPerDay", "ConstraintTeachersMaxSpanPerDay", "Max_Span"),
        "min_resting_hours" to Limit("ConstraintTeacherMinRestingHours", "ConstraintTeachersMinRestingHours", "Minimum_Resting_Hours"),
        "max_real_days_per_week" to Limit("ConstraintTeacherMaxRealDaysPerWeek", "ConstraintTeachersMaxRealDaysPerWeek", "Max_Days_Per_Week"),
        "min_real_days_per_week" to Limit("ConstraintTeacherMinRealDaysPerWeek", "ConstraintTeachersMinRealDaysPerWeek", "Minimum_Days_Per_Week"),
        "max_hours_daily_real_days" to Limit("ConstraintTeacherMaxHoursDailyRealDays", "ConstraintTeachersMaxHoursDailyRealDays", "Maximum_Hours_Daily"),
        "max_afternoons_per_week" to Limit("ConstraintTeacherMaxAfternoonsPerWeek", "ConstraintTeachersMaxAfternoonsPerWeek", "Max_Afternoons_Per_Week"),
        "min_afternoons_per_week" to Limit("ConstraintTeacherMinAfternoonsPerWeek", "ConstraintTeachersMinAfternoonsPerWeek", "Minimum_Afternoons_Per_Week"),
        "max_mornings_per_week" to Limit("ConstraintTeacherMaxMorningsPerWeek", "ConstraintTeachersMaxMorningsPerWeek", "Max_Mornings_Per_Week"),
        "min_mornings_per_week" to Limit("ConstraintTeacherMinMorningsPerWeek", "ConstraintTeachersMinMorningsPerWeek", "Minimum_Mornings_Per_Week"),
        "max_gaps_per_real_day" to Limit("ConstraintTeacherMaxGapsPerRealDay", "ConstraintTeachersMaxGapsPerRealDay", "Max_Gaps"),
    )

    internal val studentsLimits = mapOf(
        "max_days_per_week" to Limit("ConstraintStudentsSetMaxDaysPerWeek", "ConstraintStudentsMaxDaysPerWeek", "Max_Days_Per_Week"),
        "max_hours_daily" to Limit("ConstraintStudentsSetMaxHoursDaily", "ConstraintStudentsMaxHoursDaily", "Maximum_Hours_Daily"),
        "min_hours_daily" to Limit("ConstraintStudentsSetMinHoursDaily", "ConstraintStudentsMinHoursDaily", "Minimum_Hours_Daily"),
        "max_hours_continuously" to Limit("ConstraintStudentsSetMaxHoursContinuously", "ConstraintStudentsMaxHoursContinuously", "Maximum_Hours_Continuously"),
        "max_gaps_per_week" to Limit("ConstraintStudentsSetMaxGapsPerWeek", "ConstraintStudentsMaxGapsPerWeek", "Max_Gaps"),
        "max_gaps_per_day" to Limit("ConstraintStudentsSetMaxGapsPerDay", "ConstraintStudentsMaxGapsPerDay", "Max_Gaps"),
        "max_span_per_day" to Limit("ConstraintStudentsSetMaxSpanPerDay", "ConstraintStudentsMaxSpanPerDay", "Max_Span"),
        "min_resting_hours" to Limit("ConstraintStudentsSetMinRestingHours", "ConstraintStudentsMinRestingHours", "Minimum_Resting_Hours"),
        "max_real_days_per_week" to Limit("ConstraintStudentsSetMaxRealDaysPerWeek", "ConstraintStudentsMaxRealDaysPerWeek", "Max_Days_Per_Week"),
        "max_hours_daily_real_days" to Limit("ConstraintStudentsSetMaxHoursDailyRealDays", "ConstraintStudentsMaxHoursDailyRealDays", "Maximum_Hours_Daily"),
        "max_afternoons_per_week" to Limit("ConstraintStudentsSetMaxAfternoonsPerWeek", "ConstraintStudentsMaxAfternoonsPerWeek", "Max_Afternoons_Per_Week"),
        "max_mornings_per_week" to Limit("ConstraintStudentsSetMaxMorningsPerWeek", "ConstraintStudentsMaxMorningsPerWeek", "Max_Mornings_Per_Week"),
        "max_gaps_per_real_day" to Limit("ConstraintStudentsSetMaxGapsPerRealDay", "ConstraintStudentsMaxGapsPerRealDay", "Max_Gaps"),
    )

    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_set_unavailable",
            description = "Say when a teacher, a students set or a room cannot be used. Replaces any earlier not-available rule for that name. " +
                "A slot is {day, hour}, or {day, all_hours} for a whole day, or {hour, all_days} for one period every day. " +
                "In Mornings-Afternoons mode you can also write {real_day, half, hour} or {real_day, half, all_hours}.",
            mutating = true,
            inputSchema = schema {
                str("kind", "Who or what is unavailable", required = true, enum = listOf("teacher", "students", "room"))
                str("name", "Its name", required = true)
                arr("slots", "The slots. An empty list clears the rule.", itemsObj {
                    str("day", "Day name")
                    str("hour", "Hour name")
                    str("real_day", "Mornings-Afternoons: real day name")
                    str("half", "Mornings-Afternoons: morning or afternoon", enum = listOf("morning", "afternoon"))
                    bool("all_hours", "Every hour of that day")
                    bool("all_days", "That hour on every day")
                }, required = true)
                num("weight", "0 to 100 (default 100)")
            },
        ) { args ->
            val kind = args.str("kind")
            val name = args.str("name")
            val doc = args.session.doc
            val type = when (kind) {
                "teacher" -> { requireName(doc.teacher(name) != null, "teacher", name); "ConstraintTeacherNotAvailableTimes" }
                "students" -> { requireName(doc.studentsSet(name) != null, "students set", name); "ConstraintStudentsSetNotAvailableTimes" }
                else -> { requireName(doc.room(name) != null, "room", name); "ConstraintRoomNotAvailableTimes" }
            }
            val field = when (kind) { "teacher" -> "Teacher"; "students" -> "Students"; else -> "Room" }
            val slots = readSlots(doc, args, "slots")
            val count = args.session.mutate("set $kind $name unavailable") { d ->
                d.timeConstraints.removeAll { it.type == type && it.scalar(field) == name }
                if (slots.isNotEmpty()) {
                    d.timeConstraints += Constraint(
                        family = Family.TIME, type = type, weight = args.num("weight", 100.0),
                        fields = linkedMapOf(field to FieldValue.of(name), "Not_Available_Time" to FieldValue.Items(slots)),
                    )
                }
                slots.size
            }
            ToolResult(jsonOf("kind" to kind, "name" to name, "slots" to count))
        }

        tools.tool(
            name = "fet_set_breaks",
            description = "Mark slots where the whole school stops, for example a lunch break. Replaces any earlier break rule.",
            mutating = true,
            inputSchema = schema {
                arr("slots", "The break slots, in the same shape as fet_set_unavailable", itemsObj {
                    str("day", "Day name")
                    str("hour", "Hour name")
                    str("real_day", "Mornings-Afternoons: real day name")
                    str("half", "Mornings-Afternoons: morning or afternoon", enum = listOf("morning", "afternoon"))
                    bool("all_hours", "Every hour of that day")
                    bool("all_days", "That hour on every day")
                }, required = true)
                num("weight", "0 to 100 (default 100)")
            },
        ) { args ->
            val slots = readSlots(args.session.doc, args, "slots")
            args.session.mutate("set break times") { d ->
                d.timeConstraints.removeAll { it.type == "ConstraintBreakTimes" }
                if (slots.isNotEmpty()) {
                    d.timeConstraints += Constraint(
                        family = Family.TIME, type = "ConstraintBreakTimes", weight = args.num("weight", 100.0),
                        fields = linkedMapOf("Break_Time" to FieldValue.Items(slots)),
                    )
                }
            }
            ToolResult(jsonOf("slots" to slots.size))
        }

        tools.tool(
            name = "fet_set_limits",
            description = "Set the common numeric rules for one teacher or students set, or for all of them with name 'all'. " +
                "Each key becomes the right FET constraint. Pass null to remove a rule. Add _weight to any key to set its weight, for example max_gaps_per_week_weight.",
            mutating = true,
            inputSchema = schema {
                str("kind", "Who the limits are about", required = true, enum = listOf("teacher", "students"))
                str("name", "The name, or 'all' for everyone", required = true)
                obj("limits", "The rules. Keys are listed in the description of each value.", required = true) {
                    int("max_days_per_week", "Work on at most this many days (half-days in Mornings-Afternoons mode)")
                    int("min_days_per_week", "Teachers only: work on at least this many days")
                    int("max_hours_daily", "At most this many hours in one day")
                    int("min_hours_daily", "At least this many hours on a day that is used")
                    int("max_hours_continuously", "At most this many hours in a row")
                    int("max_gaps_per_week", "At most this many free periods between lessons per week")
                    int("max_gaps_per_day", "At most this many free periods between lessons per day")
                    int("max_span_per_day", "First to last lesson spans at most this many hours. Students: not in Mornings-Afternoons mode")
                    int("min_resting_hours", "At least this many hours between the last lesson and the first of the next day. Not in Mornings-Afternoons mode")
                    int("max_real_days_per_week", "Mornings-Afternoons only")
                    int("min_real_days_per_week", "Mornings-Afternoons, teachers only")
                    int("max_hours_daily_real_days", "Mornings-Afternoons only: hours per whole real day")
                    int("max_afternoons_per_week", "Mornings-Afternoons only")
                    int("min_afternoons_per_week", "Mornings-Afternoons, teachers only")
                    int("max_mornings_per_week", "Mornings-Afternoons only")
                    int("min_mornings_per_week", "Mornings-Afternoons, teachers only")
                    int("max_gaps_per_real_day", "Mornings-Afternoons only")
                }
            },
        ) { args ->
            val kind = args.str("kind")
            val name = args.str("name")
            val forAll = name.equals("all", ignoreCase = true)
            val doc = args.session.doc
            if (!forAll) {
                if (kind == "teacher") requireName(doc.teacher(name) != null, "teacher", name) else requireName(doc.studentsSet(name) != null, "students set", name)
            }
            val table = if (kind == "teacher") teacherLimits else studentsLimits
            val limits = args.obj("limits")
            val applied = ArrayList<String>()
            val removed = ArrayList<String>()
            args.session.mutate("set limits for $kind $name") { d ->
                for (key in limits.keys) {
                    if (key.endsWith("_weight")) continue
                    val limit = table[key] ?: throw ToolFailure(
                        "INVALID_ARGUMENT",
                        "There is no limit called '$key' for $kind. Known limits: ${table.keys.joinToString()}.",
                        mapOf("argument" to key),
                    )
                    val type = if (forAll) limit.all else limit.single
                    val spec = ConstraintSchema.get(type) ?: error("The limit '$key' names $type, which the FET schema does not know")
                    if (d.mode !in spec.modes) {
                        throw ToolFailure("MODE_FORBIDS", "The limit '$key' does not exist in ${d.mode.xml} mode.", mapOf("argument" to key))
                    }
                    val nameField = if (kind == "teacher") "Teacher" else "Students"
                    d.timeConstraints.removeAll { it.type == type && (forAll || it.scalar(nameField) == name) }
                    val value = limits.intOrNull(key)
                    if (value == null) { removed += key; continue }
                    val fields = LinkedHashMap<String, FieldValue>()
                    if (!forAll) fields[nameField] = FieldValue.of(name)
                    fields[limit.field] = FieldValue.of(value)
                    // FET writes "allow empty days" style flags on some min constraints; the schema tells us which.
                    spec.fields.forEach { f ->
                        if (f.name !in fields && f.value == fetmcp.schema.ValueType.BOOL) fields[f.name] = FieldValue.of(false)
                    }
                    d.timeConstraints += Constraint(Family.TIME, type, limits.num("${key}_weight", 100.0), fields = fields)
                    applied += key
                }
            }
            ToolResult(jsonOf("kind" to kind, "name" to name, "applied" to stringsOf(applied), "removed" to stringsOf(removed)))
        }

        tools.tool(
            name = "fet_set_rooms_preference",
            description = "Say which rooms something should use. The target decides the FET constraint: an activity gets a preferred room, a students set a home room, " +
                "a subject or an activity tag a preferred room for all of its activities.",
            mutating = true,
            inputSchema = schema {
                obj("target", "Exactly one of these", required = true) {
                    int("activity_id", "One lesson")
                    str("students", "A students set: this becomes its home room")
                    str("subject", "Every lesson of this subject")
                    str("activity_tag", "Every lesson with this tag")
                    str("teacher", "A teacher: this becomes their home room")
                }
                arr("rooms", "Room names. One name makes the single-room constraint, more makes the list one.", itemsStr(), required = true)
                num("weight", "0 to 100 (default 100)")
            },
        ) { args ->
            val doc = args.session.doc
            val target = args.obj("target")
            val rooms = args.strings("rooms")
            if (rooms.isEmpty()) throw ToolFailure("INVALID_ARGUMENT", "Pass at least one room", mapOf("argument" to "rooms"))
            for (r in rooms) requireName(doc.room(r) != null, "room", r)
            val many = rooms.size > 1
            val (type, key, value) = when {
                "activity_id" in target -> {
                    val id = target.int("activity_id")
                    requireName(doc.activity(id) != null, "activity", id.toString())
                    Triple(if (many) "ConstraintActivityPreferredRooms" else "ConstraintActivityPreferredRoom", "Activity_Id", id.toString())
                }
                "students" in target -> {
                    val n = target.str("students")
                    requireName(doc.studentsSet(n) != null, "students set", n)
                    Triple(if (many) "ConstraintStudentsSetHomeRooms" else "ConstraintStudentsSetHomeRoom", "Students", n)
                }
                "teacher" in target -> {
                    val n = target.str("teacher")
                    requireName(doc.teacher(n) != null, "teacher", n)
                    Triple(if (many) "ConstraintTeacherHomeRooms" else "ConstraintTeacherHomeRoom", "Teacher", n)
                }
                "subject" in target -> {
                    val n = target.str("subject")
                    requireName(doc.subject(n) != null, "subject", n)
                    Triple(if (many) "ConstraintSubjectPreferredRooms" else "ConstraintSubjectPreferredRoom", "Subject", n)
                }
                "activity_tag" in target -> {
                    val n = target.str("activity_tag")
                    requireName(doc.activityTag(n) != null, "activity tag", n)
                    Triple(if (many) "ConstraintActivityTagPreferredRooms" else "ConstraintActivityTagPreferredRoom", "Activity_Tag", n)
                }
                else -> throw ToolFailure("INVALID_ARGUMENT", "target needs one of activity_id, students, teacher, subject, activity_tag", mapOf("argument" to "target"))
            }
            val ref = args.session.mutate("set rooms for $key $value") { d ->
                val fields = LinkedHashMap<String, FieldValue>()
                fields[key] = FieldValue.of(value)
                if (many) fields["Preferred_Room"] = FieldValue.strings(rooms) else fields["Room"] = FieldValue.of(rooms.single())
                // The single-room activity constraint also carries the lock flag FET writes.
                if (ConstraintSchema.get(type)?.fields?.any { it.name == "Permanently_Locked" } == true) fields["Permanently_Locked"] = FieldValue.of(false)
                val c = Constraint(Family.SPACE, type, args.num("weight", 100.0), fields = fields)
                d.spaceConstraints.removeAll { it.type == type && it.scalar(key) == value }
                d.spaceConstraints += c
                c.ref
            }
            ToolResult(jsonOf("type" to type, "ref" to ref, "rooms" to stringsOf(rooms)))
        }

        tools.tool(
            name = "fet_pin_activities",
            description = "Fix lessons to a slot, and to a room when given. This is what a lock is: a preferred starting time at 100 percent.",
            mutating = true,
            inputSchema = schema {
                arr("activities", "The lessons to pin", itemsObj {
                    int("id", "Activity id", required = true)
                    str("day", "Day name")
                    str("hour", "Hour name", required = true)
                    str("real_day", "Mornings-Afternoons: real day name")
                    str("half", "Mornings-Afternoons: morning or afternoon", enum = listOf("morning", "afternoon"))
                    str("room", "Room name")
                }, required = true)
            },
        ) { args ->
            val items = args.objects("activities")
            val pinned = args.session.mutate("pin ${items.size} activity(ies)") { d ->
                items.map { item ->
                    val id = item.int("id")
                    requireName(d.activity(id) != null, "activity", id.toString())
                    val day = resolveDay(d, item)
                    val hour = item.str("hour")
                    requireName(d.hour(hour) != null, "hour", hour)
                    LockTools.pin(d, id, day, hour, item.strOrNull("room"))
                    id
                }
            }
            ToolResult(jsonOf("pinned" to pinned))
        }

        tools.tool(
            name = "fet_group_activities_in_initial_order",
            description = "Ask FET to place these lessons together, right after the one it rates hardest. Tie lessons that look easy to a hard one " +
                "so FET tries them early. A speed hint for hard timetables: it never changes which timetables are allowed.",
            mutating = true,
            inputSchema = schema { arr("activity_ids", "Two or more activity ids. Their order does not matter.", itemsInt(), required = true) },
        ) { args ->
            val ids = args.ints("activity_ids")
            if (ids.size < 2) throw ToolFailure("INVALID_ARGUMENT", "Group at least two activities", mapOf("argument" to "activity_ids"))
            if (ids.toSet().size != ids.size) throw ToolFailure("INVALID_ARGUMENT", "The same activity is listed twice", mapOf("argument" to "activity_ids"))
            args.session.mutate("group ${ids.size} activities in initial order") { d ->
                for (id in ids) requireName(d.activity(id) != null, "activity", id.toString())
                d.generationOptions += fetmcp.model.GroupInInitialOrder(ids)
            }
            ToolResult(jsonOf("activity_ids" to ids))
        }
    }

    // ---- shared helpers ----

    internal fun requireName(present: Boolean, kind: String, name: String) {
        if (!present) throw ToolFailure("REFERENCE_MISSING", "There is no $kind called '$name'", mapOf(kind.replace(' ', '_') to name))
    }

    /** One slot entry, or several when the caller asked for a whole day, a whole hour or a whole half day. */
    internal fun readSlots(doc: FetDocument, args: Args, field: String): List<FieldValue> {
        val out = ArrayList<FieldValue>()
        for (slot in args.objectsOrEmpty(field)) {
            val allHours = slot.bool("all_hours", false)
            val allDays = slot.bool("all_days", false)
            val days = when {
                allDays -> doc.days.map { it.name }
                else -> listOf(resolveDay(doc, slot))
            }
            val hours = if (allHours) doc.hours.map { it.name } else listOf(slot.str("hour"))
            for (d in days) {
                requireName(doc.day(d) != null, "day", d)
                for (h in hours) {
                    requireName(doc.hour(h) != null, "hour", h)
                    out += FieldValue.obj("Day" to FieldValue.of(d), "Hour" to FieldValue.of(h))
                }
            }
        }
        return out
    }

    /** Accepts a plain day name, or a real day plus half in Mornings-Afternoons mode. */
    internal fun resolveDay(doc: FetDocument, slot: Args): String {
        slot.strOrNull("day")?.let { return it }
        val realDay = slot.strOrNull("real_day")
            ?: throw ToolFailure("INVALID_ARGUMENT", "A slot needs day, or real_day and half in Mornings-Afternoons mode", mapOf("argument" to "day"))
        if (doc.mode != Mode.MORNINGS_AFTERNOONS) {
            throw ToolFailure("MODE_FORBIDS", "real_day only exists in Mornings-Afternoons mode. The document is in ${doc.mode.xml}.", mapOf("argument" to "real_day"))
        }
        val half = slot.str("half", "morning")
        val index = doc.fetDayIndex(realDay, afternoon = half.equals("afternoon", ignoreCase = true))
            ?: throw ToolFailure("REFERENCE_MISSING", "There is no real day called '$realDay'. The real days are: ${doc.realDays().joinToString { it.name }}.", mapOf("real_day" to realDay))
        return doc.days[index].name
    }
}
