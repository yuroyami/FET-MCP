package fetmcp.validate

import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.model.MaBehavior
import fetmcp.model.Mode
import fetmcp.schema.ConstraintSchema
import fetmcp.schema.FieldKind
import fetmcp.schema.FieldSpec
import fetmcp.schema.RefKind
import fetmcp.schema.ValueType
import fetmcp.validate.Issue.Companion.error
import fetmcp.validate.Issue.Companion.warning

/**
 * Server side checks, run before every generation and by the validate tool.
 * Everything FET checks beyond this list is caught by the precheck run of fet-cl.
 */
public object Validator {
    // Hard limits from timetable_defs.h.
    private const val MAX_DAYS = 1000
    private const val MAX_HOURS = 1440
    private const val MAX_ACTIVITIES = 500000
    private const val MAX_ROOMS = 30000
    private const val MAX_BUILDINGS = 30000
    private const val MAX_SUBGROUPS = 30000

    private val maxHoursDailyTypes = setOf(
        "ConstraintTeacherMaxHoursDaily", "ConstraintTeachersMaxHoursDaily",
        "ConstraintStudentsMaxHoursDaily", "ConstraintStudentsSetMaxHoursDaily",
        "ConstraintTeacherMaxHoursDailyRealDays", "ConstraintTeachersMaxHoursDailyRealDays",
        "ConstraintStudentsMaxHoursDailyRealDays", "ConstraintStudentsSetMaxHoursDailyRealDays",
    )

    public fun validate(doc: FetDocument): List<Issue> {
        val out = ArrayList<Issue>()
        structure(doc, out)
        names(doc, out)
        activities(doc, out)
        rooms(doc, out)
        constraints(doc, out)
        generationOptions(doc, out)
        warnings(doc, out)
        return out
    }

    public fun errors(doc: FetDocument): List<Issue> = validate(doc).filter { it.severity == Severity.ERROR }

    // ---- week and mode ----

    private fun structure(doc: FetDocument, out: MutableList<Issue>) {
        if (doc.days.isEmpty()) out += error("NO_DAYS", "The week has no days")
        if (doc.hours.isEmpty()) out += error("NO_HOURS", "The day has no hours")
        if (doc.days.size > MAX_DAYS) out += error("LIMIT_EXCEEDED", "FET allows at most $MAX_DAYS days", "kind" to "days")
        if (doc.hours.size > MAX_HOURS) out += error("LIMIT_EXCEEDED", "FET allows at most $MAX_HOURS hours per day", "kind" to "hours")
        if (doc.mode == Mode.MORNINGS_AFTERNOONS && doc.days.size % 2 != 0) {
            out += error("MA_ODD_DAYS", "Mornings-Afternoons mode needs an even number of FET days (two per real day), found ${doc.days.size}")
        }
        if (doc.mode == Mode.TERMS) {
            val t = doc.terms
            if (t == null) out += error("TERMS_MISMATCH", "Terms mode needs the number of terms and days per term")
            else if (t.terms * t.daysPerTerm != doc.days.size) {
                out += error("TERMS_MISMATCH", "Terms mode needs days = terms x days per term, but ${t.terms} x ${t.daysPerTerm} is not ${doc.days.size}")
            }
        }
        if (doc.activities.none { it.active }) out += error("NO_ACTIVE_ACTIVITY", "There is no active activity to schedule")
        if (doc.activities.count { it.active } > MAX_ACTIVITIES) out += error("LIMIT_EXCEEDED", "FET allows at most $MAX_ACTIVITIES active activities", "kind" to "activities")
        if (doc.rooms.size > MAX_ROOMS) out += error("LIMIT_EXCEEDED", "FET allows at most $MAX_ROOMS rooms", "kind" to "rooms")
        if (doc.buildings.size > MAX_BUILDINGS) out += error("LIMIT_EXCEEDED", "FET allows at most $MAX_BUILDINGS buildings", "kind" to "buildings")
        val subgroups = doc.years.sumOf { y -> y.groups.sumOf { it.subgroups.size } }
        if (subgroups > MAX_SUBGROUPS) out += error("LIMIT_EXCEEDED", "FET allows at most $MAX_SUBGROUPS subgroups", "kind" to "subgroups")
    }

    // ---- names ----

    private fun names(doc: FetDocument, out: MutableList<Issue>) {
        fun check(kind: String, names: List<String>) {
            names.filter { it.isBlank() }.forEach { _ -> out += error("EMPTY_NAME", "A $kind has an empty name", "kind" to kind) }
            names.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { out += error("DUPLICATE_NAME", "The $kind name '$it' is used more than once", "kind" to kind, "name" to it) }
        }
        check("day", doc.days.map { it.name })
        check("hour", doc.hours.map { it.name })
        check("subject", doc.subjects.map { it.name })
        check("activity tag", doc.activityTags.map { it.name })
        check("teacher", doc.teachers.map { it.name })
        check("building", doc.buildings.map { it.name })
        check("room", doc.rooms.map { it.name })
        check("year", doc.years.map { it.name })
        for (year in doc.years) {
            check("group in year ${year.name}", year.groups.map { it.name })
            for (group in year.groups) check("subgroup in group ${group.name}", group.subgroups.map { it.name })
        }
        // One namespace for students sets: a name may not live on two levels.
        val levels = HashMap<String, MutableSet<String>>()
        for (year in doc.years) {
            levels.getOrPut(year.name) { HashSet() } += "year"
            for (group in year.groups) {
                levels.getOrPut(group.name) { HashSet() } += "group"
                for (sub in group.subgroups) levels.getOrPut(sub.name) { HashSet() } += "subgroup"
            }
        }
        for ((name, kinds) in levels) if (kinds.size > 1) {
            out += error("DUPLICATE_NAME", "The students set name '$name' is used as ${kinds.sorted().joinToString(" and ")}. Years, groups and subgroups share one namespace", "kind" to "students", "name" to name)
        }
        for (t in doc.teachers) for (s in t.qualifiedSubjects) {
            if (doc.subject(s) == null) out += error("REFERENCE_MISSING", "Teacher ${t.name} is qualified for subject '$s' which does not exist", "teacher" to t.name, "subject" to s)
        }
    }

    // ---- activities ----

    private fun activities(doc: FetDocument, out: MutableList<Issue>) {
        val ids = HashSet<Int>()
        for (a in doc.activities) {
            val where = "activity" to a.id.toString()
            if (a.id <= 0) out += error("INVALID_ACTIVITY_ID", "Activity id ${a.id} must be positive", where)
            if (!ids.add(a.id)) out += error("DUPLICATE_ACTIVITY_ID", "Activity id ${a.id} is used more than once", where)
            for (t in a.teachers) if (doc.teacher(t) == null) out += error("REFERENCE_MISSING", "Activity ${a.id} names teacher '$t' which does not exist", where, "teacher" to t)
            if (a.subject.isEmpty() || doc.subject(a.subject) == null) out += error("REFERENCE_MISSING", "Activity ${a.id} names subject '${a.subject}' which does not exist", where, "subject" to a.subject)
            for (t in a.tags) if (doc.activityTag(t) == null) out += error("REFERENCE_MISSING", "Activity ${a.id} names activity tag '$t' which does not exist", where, "activity_tag" to t)
            for (s in a.students) if (doc.studentsSet(s) == null) out += error("REFERENCE_MISSING", "Activity ${a.id} names students set '$s' which does not exist", where, "students" to s)
            if (a.duration < 1) out += error("DURATION_TOO_LONG", "Activity ${a.id} has duration ${a.duration}, it must be at least 1", where)
            if (doc.hours.isNotEmpty() && a.duration > doc.hours.size) out += error("DURATION_TOO_LONG", "Activity ${a.id} lasts ${a.duration} hours but a day has only ${doc.hours.size}", where)
            if (a.teachers.toSet().size != a.teachers.size) out += error("DUPLICATE_NAME", "Activity ${a.id} lists the same teacher twice", where)
            if (a.students.toSet().size != a.students.size) out += error("DUPLICATE_NAME", "Activity ${a.id} lists the same students set twice", where)
        }
        for (a in doc.activities.filter { !it.isSplit }) {
            if (a.totalDuration != a.duration) out += error("TOTAL_DURATION_MISMATCH", "Activity ${a.id} is not split, so its total duration must equal its duration", "activity" to a.id.toString())
        }
        for ((groupId, members) in doc.activities.filter { it.isSplit }.groupBy { it.groupId }) {
            val where = "activity_group" to groupId.toString()
            if (members.none { it.id == groupId }) out += error("GROUP_ID_INVALID", "Activity group $groupId must be the id of its first component", where)
            val sum = members.sumOf { it.duration }
            for (m in members) if (m.totalDuration != sum) {
                out += error("TOTAL_DURATION_MISMATCH", "Activity ${m.id} says total duration ${m.totalDuration} but its group adds up to $sum", where, "activity" to m.id.toString())
            }
        }
    }

    // ---- rooms ----

    private fun rooms(doc: FetDocument, out: MutableList<Issue>) {
        for (r in doc.rooms) {
            val where = "room" to r.name
            if (r.building.isNotEmpty() && doc.building(r.building) == null) out += error("REFERENCE_MISSING", "Room ${r.name} is in building '${r.building}' which does not exist", where, "building" to r.building)
            if (r.capacity < 1) out += error("INVALID_VALUE", "Room ${r.name} has capacity ${r.capacity}", where)
            if (!r.virtual) continue
            if (r.realRoomSets.size < 2) out += error("VIRTUAL_ROOM_INVALID", "Virtual room ${r.name} needs at least two sets of real rooms to choose from", where)
            r.realRoomSets.forEachIndexed { i, set ->
                if (set.isEmpty()) out += error("VIRTUAL_ROOM_INVALID", "Virtual room ${r.name}, set ${i + 1}, has no real rooms", where)
                if (set.toSet().size != set.size) out += error("VIRTUAL_ROOM_INVALID", "Virtual room ${r.name}, set ${i + 1}, lists a room twice", where)
                for (name in set) {
                    val real = doc.room(name)
                    if (real == null) out += error("REFERENCE_MISSING", "Virtual room ${r.name} lists real room '$name' which does not exist", where, "real_room" to name)
                    else if (real.virtual) out += error("VIRTUAL_ROOM_INVALID", "Virtual room ${r.name} lists '$name', which is itself virtual", where)
                }
            }
        }
    }

    // ---- constraints ----

    private fun constraints(doc: FetDocument, out: MutableList<Issue>) {
        fun basic(family: Family, type: String) {
            val found = doc.constraintsOf(family).filter { it.type == type }
            if (found.none { it.weight >= 100.0 && it.active }) {
                out += error("BASIC_CONSTRAINT_MISSING", "FET needs one active $type at 100 percent", "type" to type)
            }
            if (found.size > 1) out += error("DUPLICATE_CONSTRAINT", "There is more than one $type", "type" to type)
        }
        basic(Family.TIME, "ConstraintBasicCompulsoryTime")
        basic(Family.SPACE, "ConstraintBasicCompulsorySpace")

        val seen = HashSet<String>()
        for (family in Family.entries) for (c in doc.constraintsOf(family)) {
            val where = "constraint" to c.ref
            // FET allows two identical constraints, so this is only worth pointing out, not blocking.
            if (!seen.add(c.ref)) out += warning("DUPLICATE_CONSTRAINT", "Constraint ${c.ref} (${c.type}) appears twice. FET allows it, but one of them does nothing.", where, "type" to c.type)
            if (c.weight < 0.0 || c.weight > 100.0) out += error("INVALID_WEIGHT", "Constraint ${c.ref} has weight ${c.weight}, allowed is 0 to 100", where)
            val type = ConstraintSchema.get(c.type)
            if (type == null) { out += error("UNKNOWN_CONSTRAINT_TYPE", "Constraint type ${c.type} is not known to FET ${ConstraintSchema.fetVersion}", where, "type" to c.type); continue }
            if (type.family != family) out += error("FAMILY_MISMATCH", "${c.type} is a ${type.family.name.lowercase()} constraint but sits in the ${family.name.lowercase()} list", where)
            if (doc.mode !in type.modes) {
                out += error("MODE_FORBIDS", "${c.type} is not allowed in mode ${doc.mode.xml}. Allowed in: ${type.modes.joinToString { it.xml }}", where, "type" to c.type)
            }
            checkFields(doc, c, type.fields, c.fields, out)
        }
    }

    private fun checkFields(doc: FetDocument, c: Constraint, specs: List<FieldSpec>, fields: Map<String, FieldValue>, out: MutableList<Issue>) {
        val where = "constraint" to c.ref
        val known = specs.map { it.name }.toSet()
        for (name in fields.keys) if (name !in known) out += warning("FIELD_UNKNOWN", "Constraint ${c.ref} (${c.type}) has a field '$name' FET does not write", where, "field" to name)
        for (spec in specs) {
            val value = fields[spec.name]
            if (value == null) {
                if (!spec.optional && spec.kind != FieldKind.ARRAY) out += error("FIELD_MISSING", "Constraint ${c.ref} (${c.type}) is missing the field '${spec.name}'", where, "field" to spec.name)
                continue
            }
            when (spec.kind) {
                FieldKind.SCALAR -> checkScalar(doc, c, spec, value, out)
                FieldKind.OBJECT -> if (value is FieldValue.Obj) checkFields(doc, c, spec.fields, value.fields, out) else out += error("INVALID_VALUE", "Constraint ${c.ref}: field '${spec.name}' must be an object", where, "field" to spec.name)
                FieldKind.ARRAY -> {
                    val items = (value as? FieldValue.Items)?.items ?: listOf(value)
                    val itemSpec = spec.item ?: continue
                    for (item in items) {
                        if (itemSpec.kind == FieldKind.OBJECT) {
                            if (item is FieldValue.Obj) checkFields(doc, c, itemSpec.fields, item.fields, out)
                            else out += error("INVALID_VALUE", "Constraint ${c.ref}: entries of '${spec.name}' must be objects", where, "field" to spec.name)
                        } else checkScalar(doc, c, itemSpec, item, out)
                    }
                }
            }
        }
    }

    private fun checkScalar(doc: FetDocument, c: Constraint, spec: FieldSpec, value: FieldValue, out: MutableList<Issue>) {
        val where = "constraint" to c.ref
        val text = (value as? FieldValue.Scalar)?.text
        if (text == null) { out += error("INVALID_VALUE", "Constraint ${c.ref}: field '${spec.name}' must be a single value", where, "field" to spec.name); return }
        when (spec.value) {
            ValueType.NUMBER -> if (text.toDoubleOrNull() == null && !(text.isEmpty() && spec.optional)) {
                out += error("INVALID_VALUE", "Constraint ${c.ref}: field '${spec.name}' must be a number, got '$text'", where, "field" to spec.name)
            }
            ValueType.BOOL -> if (text != "true" && text != "false") {
                out += error("INVALID_VALUE", "Constraint ${c.ref}: field '${spec.name}' must be true or false, got '$text'", where, "field" to spec.name)
            }
            else -> {}
        }
        val ref = spec.ref ?: return
        if (text.isEmpty()) return // an empty reference means "any" in FET
        val exists = when (ref) {
            RefKind.TEACHER -> doc.teacher(text) != null
            RefKind.STUDENTS -> doc.studentsSet(text) != null
            RefKind.SUBJECT -> doc.subject(text) != null
            RefKind.ACTIVITY_TAG -> doc.activityTag(text) != null
            RefKind.ROOM -> doc.room(text) != null
            RefKind.BUILDING -> doc.building(text) != null
            RefKind.DAY -> doc.day(text) != null
            RefKind.HOUR -> doc.hour(text) != null
            RefKind.ACTIVITY -> text.toIntOrNull()?.let { doc.activity(it) } != null
        }
        if (!exists) {
            val kind = ref.name.lowercase()
            out += error("REFERENCE_MISSING", "Constraint ${c.ref} (${c.type}) names $kind '$text' which does not exist", where, kind to text)
        }
    }

    private fun generationOptions(doc: FetDocument, out: MutableList<Issue>) {
        doc.generationOptions.forEachIndexed { i, g ->
            if (g.activityIds.size < 2) out += error("INVALID_VALUE", "Generation option ${i + 1} must group at least two activities", "generation_option" to (i + 1).toString())
            if (g.activityIds.toSet().size != g.activityIds.size) out += error("INVALID_VALUE", "Generation option ${i + 1} lists an activity twice", "generation_option" to (i + 1).toString())
            for (id in g.activityIds) if (doc.activity(id) == null) out += error("REFERENCE_MISSING", "Generation option ${i + 1} names activity $id which does not exist", "activity" to id.toString())
        }
    }

    // ---- warnings ----

    private fun warnings(doc: FetDocument, out: MutableList<Issue>) {
        val dayCount = if (doc.mode == Mode.MORNINGS_AFTERNOONS) doc.days.size / 2 else doc.days.size
        val dayWord = if (doc.mode == Mode.MORNINGS_AFTERNOONS) "real days" else "days"
        for ((groupId, members) in doc.activities.filter { it.isSplit && it.active }.groupBy { it.groupId }) {
            if (dayCount in 1 until members.size) {
                out += warning("SPLIT_EXCEEDS_DAYS", "Activity group $groupId has ${members.size} components but the week has only $dayCount $dayWord, so min days between them cannot all be respected", "activity_group" to groupId.toString())
            }
        }
        for (c in doc.constraints()) {
            if (c.type in maxHoursDailyTypes && c.active && c.weight < 100.0) {
                out += warning("SOFT_MAX_HOURS_DAILY", "${c.type} at ${c.weight} percent is slow to compute. FET recommends 100 percent", "constraint" to c.ref)
            }
        }
        val pinnedTime = doc.timeConstraints.filter { it.type == "ConstraintActivityPreferredStartingTime" && it.weight >= 100.0 && it.active }.mapNotNull { it.scalar("Activity_Id")?.toIntOrNull() }.toSet()
        for (c in doc.spaceConstraints) {
            if (c.type != "ConstraintActivityPreferredRoom" || c.weight < 100.0 || !c.active) continue
            val id = c.scalar("Activity_Id")?.toIntOrNull() ?: continue
            val room = c.scalar("Room")?.let { doc.room(it) } ?: continue
            if (room.virtual && c.items("Real_Room").isNotEmpty() && id !in pinnedTime) {
                out += warning("VIRTUAL_ROOM_NO_FIXED_TIME", "Activity $id has fixed real rooms in virtual room ${room.name} but no fixed time. FET warns about this", "activity" to id.toString())
            }
        }
        teacherLoad(doc, out)
        identicalSubgroups(doc, out)
    }

    private fun teacherLoad(doc: FetDocument, out: MutableList<Issue>) {
        val hoursPerDay = doc.hours.size
        if (hoursPerDay == 0 || doc.days.isEmpty()) return
        for (t in doc.teachers) {
            val hours = doc.activities.filter { it.active && t.name in it.teachers }.sumOf { it.duration }
            if (hours > doc.days.size * hoursPerDay) {
                out += error("TEACHER_OVERLOAD", "Teacher ${t.name} has $hours hours of activities but the week has only ${doc.days.size * hoursPerDay} slots", "teacher" to t.name)
                continue
            }
            if (doc.mode == Mode.MORNINGS_AFTERNOONS) {
                val realDays = doc.days.size / 2
                val halfDays = when (val b = t.morningsAfternoonsBehavior ?: MaBehavior.UNRESTRICTED) {
                    MaBehavior.UNRESTRICTED -> doc.days.size
                    else -> realDays + b.exceptionDays
                }
                if (hours > halfDays * hoursPerDay) {
                    out += error("MA_TEACHER_OVERLOAD", "Teacher ${t.name} has $hours hours but can work only $halfDays half days x $hoursPerDay hours with behaviour '${(t.morningsAfternoonsBehavior ?: MaBehavior.UNRESTRICTED).xml}'", "teacher" to t.name)
                }
            }
            if (t.targetHours > 0 && t.targetHours != hours) {
                out += warning("TARGET_HOURS_MISMATCH", "Teacher ${t.name} has a target of ${t.targetHours} hours but ${hours} hours of activities", "teacher" to t.name)
            }
        }
    }

    /** Two subgroups with the same activities slow FET down. FET warns; merging them is the fix. */
    private fun identicalSubgroups(doc: FetDocument, out: MutableList<Issue>) {
        val membership = HashMap<String, MutableSet<Int>>()
        for (year in doc.years) for (group in year.groups) for (sub in group.subgroups) {
            val set = membership.getOrPut(sub.name) { HashSet() }
            for (a in doc.activities) if (a.active && (year.name in a.students || group.name in a.students || sub.name in a.students)) set += a.id
        }
        val byActivities = membership.entries.filter { it.value.isNotEmpty() }.groupBy({ it.value }, { it.key })
        for ((_, names) in byActivities) if (names.size > 1) {
            out += warning("IDENTICAL_SUBGROUPS", "Subgroups ${names.sorted().joinToString()} have exactly the same activities. Merging them makes generation faster", "subgroups" to names.sorted().joinToString())
        }
    }
}
