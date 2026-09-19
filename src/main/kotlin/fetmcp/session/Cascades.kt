package fetmcp.session

import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.model.NamedItem
import fetmcp.model.StudentsLevel
import fetmcp.schema.ConstraintSchema
import fetmcp.schema.FieldKind
import fetmcp.schema.FieldSpec
import fetmcp.schema.RefKind

public enum class EntityKind(public val ref: RefKind?) {
    SUBJECT(RefKind.SUBJECT),
    ACTIVITY_TAG(RefKind.ACTIVITY_TAG),
    TEACHER(RefKind.TEACHER),
    BUILDING(RefKind.BUILDING),
    ROOM(RefKind.ROOM),
    YEAR(RefKind.STUDENTS),
    GROUP(RefKind.STUDENTS),
    SUBGROUP(RefKind.STUDENTS),
    STUDENTS(RefKind.STUDENTS),
    DAY(RefKind.DAY),
    HOUR(RefKind.HOUR);

    public companion object {
        public fun parse(text: String): EntityKind? = entries.firstOrNull { it.name.equals(text.replace(' ', '_'), ignoreCase = true) }
    }
}

public data class CascadeReport(
    val removedActivities: List<Int> = emptyList(),
    val changedActivities: List<Int> = emptyList(),
    val removedConstraints: List<String> = emptyList(),
    val changedConstraints: List<String> = emptyList(),
) {
    public operator fun plus(other: CascadeReport): CascadeReport = CascadeReport(
        (removedActivities + other.removedActivities).distinct().sorted(),
        (changedActivities + other.changedActivities).distinct().sorted(),
        (removedConstraints + other.removedConstraints).distinct(),
        (changedConstraints + other.changedConstraints).distinct(),
    )
}

/**
 * Rename and remove with the same side effects as `Rules::modify*` and `Rules::remove*` in `rules.cpp`.
 * Constraints are updated through the schema: every field that refers to the entity kind is visited,
 * whatever the constraint type.
 */
public object Cascades {
    // ---- rename ----

    public fun rename(doc: FetDocument, kind: EntityKind, old: String, new: String): CascadeReport {
        require(old != new) { "old and new name are the same" }
        val changedActivities = ArrayList<Int>()
        when (kind) {
            EntityKind.TEACHER -> {
                doc.teachers.replaceAll { if (it.name == old) it.copy(name = new) else it }
                doc.activities.replaceAll { a -> if (old in a.teachers) { changedActivities += a.id; a.copy(teachers = a.teachers.map { if (it == old) new else it }) } else a }
            }
            EntityKind.SUBJECT -> {
                doc.subjects.replaceAll { if (it.name == old) it.copy(name = new) else it }
                doc.teachers.replaceAll { t -> if (old in t.qualifiedSubjects) t.copy(qualifiedSubjects = t.qualifiedSubjects.map { if (it == old) new else it }) else t }
                doc.activities.replaceAll { a -> if (a.subject == old) { changedActivities += a.id; a.copy(subject = new) } else a }
            }
            EntityKind.ACTIVITY_TAG -> {
                doc.activityTags.replaceAll { if (it.name == old) it.copy(name = new) else it }
                doc.activities.replaceAll { a -> if (old in a.tags) { changedActivities += a.id; a.copy(tags = a.tags.map { if (it == old) new else it }) } else a }
            }
            EntityKind.BUILDING -> {
                doc.buildings.replaceAll { if (it.name == old) it.copy(name = new) else it }
                doc.rooms.replaceAll { if (it.building == old) it.copy(building = new) else it }
            }
            EntityKind.ROOM -> {
                doc.rooms.replaceAll { r ->
                    val renamed = if (r.name == old) r.copy(name = new) else r
                    if (renamed.virtual) renamed.copy(realRoomSets = renamed.realRoomSets.map { set -> set.map { if (it == old) new else it } }) else renamed
                }
            }
            EntityKind.YEAR, EntityKind.GROUP, EntityKind.SUBGROUP, EntityKind.STUDENTS -> {
                doc.years.replaceAll { y ->
                    val year = if (y.name == old) y.copy(name = new) else y
                    year.copy(groups = year.groups.map { g ->
                        val group = if (g.name == old) g.copy(name = new) else g
                        group.copy(subgroups = group.subgroups.map { if (it.name == old) it.copy(name = new) else it })
                    })
                }
                doc.activities.replaceAll { a -> if (old in a.students) { changedActivities += a.id; a.copy(students = a.students.map { if (it == old) new else it }) } else a }
            }
            EntityKind.DAY -> doc.days.replaceAll { if (it.name == old) NamedItem(new, it.longName) else it }
            EntityKind.HOUR -> doc.hours.replaceAll { if (it.name == old) NamedItem(new, it.longName) else it }
        }
        val constraints = mapConstraints(doc, kind.ref!!) { if (it == old) new else it }
        return CascadeReport(changedActivities = changedActivities.sorted()) + constraints
    }

    // ---- remove ----

    public fun remove(doc: FetDocument, kind: EntityKind, name: String): CascadeReport = when (kind) {
        EntityKind.TEACHER -> {
            doc.teachers.removeAll { it.name == name }
            val emptied = ArrayList<Int>()
            val changed = ArrayList<Int>()
            doc.activities.replaceAll { a ->
                if (name !in a.teachers) a else {
                    val rest = a.teachers.filter { it != name }
                    if (rest.isEmpty()) emptied += a.id else changed += a.id
                    a.copy(teachers = rest)
                }
            }
            val removed = removeActivities(doc, emptied)
            CascadeReport(changedActivities = changed.filter { it !in removed.removedActivities }) + removed + mapConstraints(doc, RefKind.TEACHER) { if (it == name) null else it }
        }
        EntityKind.SUBJECT -> {
            doc.subjects.removeAll { it.name == name }
            doc.teachers.replaceAll { t -> if (name in t.qualifiedSubjects) t.copy(qualifiedSubjects = t.qualifiedSubjects.filter { it != name }) else t }
            val ids = doc.activities.filter { it.subject == name }.map { it.id }
            removeActivities(doc, ids) + mapConstraints(doc, RefKind.SUBJECT) { if (it == name) null else it }
        }
        EntityKind.ACTIVITY_TAG -> {
            doc.activityTags.removeAll { it.name == name }
            val changed = ArrayList<Int>()
            doc.activities.replaceAll { a -> if (name in a.tags) { changed += a.id; a.copy(tags = a.tags.filter { it != name }) } else a }
            CascadeReport(changedActivities = changed) + mapConstraints(doc, RefKind.ACTIVITY_TAG) { if (it == name) null else it }
        }
        EntityKind.BUILDING -> {
            doc.buildings.removeAll { it.name == name }
            doc.rooms.replaceAll { if (it.building == name) it.copy(building = "") else it }
            mapConstraints(doc, RefKind.BUILDING) { if (it == name) null else it }
        }
        EntityKind.ROOM -> {
            doc.rooms.removeAll { it.name == name }
            // Like FET: strip the name and keep the set, even when it ends up empty. The validator reports it.
            doc.rooms.replaceAll { r -> if (r.virtual) r.copy(realRoomSets = r.realRoomSets.map { set -> set.filter { it != name } }) else r }
            mapConstraints(doc, RefKind.ROOM) { if (it == name) null else it }
        }
        EntityKind.YEAR, EntityKind.GROUP, EntityKind.SUBGROUP, EntityKind.STUDENTS -> {
            val set = doc.studentsSet(name) ?: return CascadeReport()
            when (set.level) {
                StudentsLevel.YEAR -> removeStudents(doc, name, null, null)
                StudentsLevel.GROUP -> {
                    // Remove the group from every year that has it.
                    var report = CascadeReport()
                    for (year in doc.years.map { it.name }) if (doc.year(year)?.groups?.any { it.name == name } == true) report += removeStudents(doc, year, name, null)
                    report
                }
                StudentsLevel.SUBGROUP -> {
                    var report = CascadeReport()
                    for (year in doc.years.toList()) for (group in year.groups) if (group.subgroups.any { it.name == name }) report += removeStudents(doc, year.name, group.name, name)
                    report
                }
            }
        }
        EntityKind.DAY, EntityKind.HOUR -> throw IllegalArgumentException("Days and hours are removed through the week tools")
    }

    /**
     * Removes one node of the students tree, like `Rules::removeYear/removeGroup/removeSubgroup`.
     * A set that still exists elsewhere in the tree is not purged from activities.
     */
    public fun removeStudents(doc: FetDocument, yearName: String, groupName: String?, subgroupName: String?): CascadeReport {
        val yearIndex = doc.years.indexOfFirst { it.name == yearName }
        if (yearIndex < 0) return CascadeReport()
        val year = doc.years[yearIndex]
        val candidates = HashSet<String>()
        when {
            groupName == null -> {
                candidates += year.name
                for (g in year.groups) { candidates += g.name; for (s in g.subgroups) candidates += s.name }
                doc.years.removeAt(yearIndex)
            }
            subgroupName == null -> {
                val group = year.groups.firstOrNull { it.name == groupName } ?: return CascadeReport()
                candidates += group.name
                for (s in group.subgroups) candidates += s.name
                doc.years[yearIndex] = year.copy(groups = year.groups.filter { it.name != groupName })
            }
            else -> {
                candidates += subgroupName
                doc.years[yearIndex] = year.copy(groups = year.groups.map { g ->
                    if (g.name == groupName) g.copy(subgroups = g.subgroups.filter { it.name != subgroupName }) else g
                })
            }
        }
        val purged = candidates.filter { doc.studentsSet(it) == null }.toSet()
        if (purged.isEmpty()) return CascadeReport()
        val emptied = ArrayList<Int>()
        val changed = ArrayList<Int>()
        doc.activities.replaceAll { a ->
            if (a.students.none { it in purged }) a else {
                val rest = a.students.filter { it !in purged }
                if (rest.isEmpty()) emptied += a.id else changed += a.id
                a.copy(students = rest)
            }
        }
        val removed = removeActivities(doc, emptied)
        return CascadeReport(changedActivities = changed.filter { it !in removed.removedActivities }) + removed +
            mapConstraints(doc, RefKind.STUDENTS) { if (it in purged) null else it }
    }

    /**
     * Removes activities. Removing one component of a split activity removes all of them, the same as
     * `Rules::removeActivities`, which expands the id set by activity group before it deletes anything.
     * Leaving a sibling behind would orphan it, because the group id is the first component's id.
     */
    public fun removeActivities(doc: FetDocument, ids: List<Int>): CascadeReport {
        if (ids.isEmpty()) return CascadeReport()
        val toRemove = HashSet(ids)
        val groups = doc.activities.filter { it.isSplit && it.id in toRemove }.map { it.groupId }.toSet()
        doc.activities.filter { it.isSplit && it.groupId in groups }.forEach { toRemove += it.id }
        val removed = doc.activities.filter { it.id in toRemove }.map { it.id }
        if (removed.isEmpty()) return CascadeReport()
        doc.activities.removeAll { it.id in toRemove }
        doc.generationOptions.replaceAll { g -> g.copy(activityIds = g.activityIds.filter { it !in toRemove }) }
        doc.generationOptions.removeAll { it.activityIds.size < 2 }
        return CascadeReport(removedActivities = removed.sorted()) +
            mapConstraints(doc, RefKind.ACTIVITY) { if (it.toIntOrNull() in toRemove) null else it }
    }

    /** Refs of constraints that name this entity. Used to refuse deleting a day or hour that is in use. */
    public fun referencesTo(doc: FetDocument, kind: EntityKind, name: String): List<String> {
        val ref = kind.ref ?: return emptyList()
        return doc.constraints().filter { c ->
            val type = ConstraintSchema.get(c.type) ?: return@filter false
            var found = false
            mapFields(type.fields, c.fields, ref) { if (it == name) found = true; it }
            found
        }.map { it.ref }.toList()
    }

    // ---- constraint walking ----

    /**
     * Applies `f` to every field of kind `ref` in every constraint. `f` returning null means "this target is gone":
     * a list entry is dropped, a single target deletes the constraint. Lists that fall below their minimum delete it too.
     */
    private fun mapConstraints(doc: FetDocument, ref: RefKind, f: (String) -> String?): CascadeReport {
        val removed = ArrayList<String>()
        val changed = ArrayList<String>()
        for (family in Family.entries) {
            val list = doc.constraintsOf(family)
            val out = ArrayList<Constraint>(list.size)
            val seen = HashSet<String>()
            for (c in list) {
                val type = ConstraintSchema.get(c.type)
                if (type == null) { out += c; seen += c.ref; continue }
                val result = mapFields(type.fields, c.fields, ref, type.keepWhileAnyActivityList, f)
                val kept = when {
                    result == null -> { removed += c.ref; null }
                    ref == RefKind.ACTIVITY && belowMinimum(c.type, type.fields, result) -> { removed += c.ref; null }
                    result == c.fields -> c
                    else -> { changed += c.ref; c.copy(fields = result) }
                } ?: continue
                // Shrinking two constraints can make them identical. Keep one, the way a person would.
                if (!seen.add(kept.ref)) { removed += c.ref; changed.remove(c.ref); continue }
                out += kept
            }
            list.clear(); list += out
        }
        return CascadeReport(removedConstraints = removed, changedConstraints = changed)
    }

    /** Null means the owner must go. */
    private fun mapFields(
        specs: List<FieldSpec>,
        fields: Map<String, FieldValue>,
        ref: RefKind,
        keepEmptyActivityLists: Boolean = false,
        f: (String) -> String?,
    ): Map<String, FieldValue>? {
        var changed = false
        val out = LinkedHashMap<String, FieldValue>()
        for ((name, value) in fields) {
            val spec = specs.firstOrNull { it.name == name }
            if (spec == null) { out[name] = value; continue }
            val mapped = mapValue(spec, value, ref, keepEmptyActivityLists, f) ?: return null
            if (mapped !== value) changed = true
            out[name] = mapped
        }
        return if (changed) out else fields
    }

    private fun mapValue(spec: FieldSpec, value: FieldValue, ref: RefKind, keepEmptyActivityLists: Boolean, f: (String) -> String?): FieldValue? = when (spec.kind) {
        FieldKind.SCALAR -> {
            val text = (value as? FieldValue.Scalar)?.text
            if (spec.ref != ref || text.isNullOrEmpty()) value
            else f(text)?.let { if (it == text) value else FieldValue.Scalar(it) }
        }
        FieldKind.OBJECT -> {
            val fields = (value as? FieldValue.Obj)?.fields
            if (fields == null) value else mapFields(spec.fields, fields, ref, keepEmptyActivityLists, f)?.let { if (it === fields) value else FieldValue.Obj(it) }
        }
        FieldKind.ARRAY -> {
            val items = (value as? FieldValue.Items)?.items ?: listOf(value)
            val itemSpec = spec.item
            if (itemSpec == null) value else {
                var changed = false
                val kept = ArrayList<FieldValue>()
                for (item in items) {
                    val mapped = mapValue(itemSpec, item, ref, keepEmptyActivityLists, f)
                    if (mapped == null) changed = true else { if (mapped !== item) changed = true; kept += mapped }
                }
                // An emptied list normally means the constraint has lost its target. Two exceptions, both from FET:
                // preferred real rooms are simply cleared, and the "and" form types survive while one set remains.
                val survivesEmpty = spec.name == "Real_Room" || (keepEmptyActivityLists && itemSpec.ref == RefKind.ACTIVITY)
                when {
                    !changed -> value
                    kept.isEmpty() && items.isNotEmpty() && !survivesEmpty -> null
                    else -> FieldValue.Items(kept)
                }
            }
        }
    }

    /** FET's own minimum, generated from `updateConstraintsAfterRemoval`. Below it, FET deletes the constraint. */
    private fun belowMinimum(type: String, specs: List<FieldSpec>, fields: Map<String, FieldValue>): Boolean {
        val schema = ConstraintSchema.get(type)
        val minimum = schema?.minActivities ?: 1
        if (schema?.keepWhileAnyActivityList == true) {
            // These live on while one set still has activities, so count everything together.
            return countActivities(specs, fields) < minimum
        }
        for (spec in specs) {
            if (spec.kind != FieldKind.ARRAY || spec.item?.ref != RefKind.ACTIVITY) continue
            val count = (fields[spec.name] as? FieldValue.Items)?.items?.size ?: 0
            if (count < minimum) return true
        }
        return false
    }

    private fun countActivities(specs: List<FieldSpec>, fields: Map<String, FieldValue>): Int = specs.sumOf { spec ->
        val value = fields[spec.name]
        when {
            spec.kind == FieldKind.ARRAY && spec.item?.ref == RefKind.ACTIVITY -> (value as? FieldValue.Items)?.items?.size ?: 0
            spec.kind == FieldKind.OBJECT -> (value as? FieldValue.Obj)?.let { countActivities(spec.fields, it.fields) } ?: 0
            else -> 0
        }
    }
}
