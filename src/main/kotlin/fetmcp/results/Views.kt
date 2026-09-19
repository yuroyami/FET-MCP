package fetmcp.results

import fetmcp.model.FetDocument
import fetmcp.model.Mode

public enum class ViewKind {
    TEACHER, STUDENTS, ROOM;

    public companion object {
        public fun parse(text: String): ViewKind? = entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}

public data class CellActivity(
    val id: Int,
    val subject: String,
    val teachers: List<String>,
    val students: List<String>,
    val room: String?,
    val tags: List<String>,
    /** True for the second and later hours of an activity longer than one hour. */
    val continuation: Boolean,
)

/** A timetable for one teacher, students set or room. Columns are days, rows are hours. */
public data class Grid(
    val kind: ViewKind,
    val name: String,
    val columns: List<String>,
    val rows: List<String>,
    val cells: List<List<List<CellActivity>>>,
) {
    public fun cell(column: Int, row: Int): List<CellActivity> = cells[column][row]

    public fun allActivities(): List<CellActivity> = cells.flatten().flatten().filter { !it.continuation }

    public fun toMarkdown(): String = buildString {
        append("| Hour | ").append(columns.joinToString(" | ")).append(" |\n")
        append("| --- | ").append(columns.joinToString(" | ") { "---" }).append(" |\n")
        for (row in rows.indices) {
            append("| ").append(rows[row]).append(" | ")
            append(columns.indices.joinToString(" | ") { col -> cellText(cells[col][row]) })
            append(" |\n")
        }
    }

    private fun cellText(activities: List<CellActivity>): String =
        if (activities.isEmpty()) "" else activities.joinToString("<br>") { a ->
            val who = when (kind) {
                ViewKind.TEACHER -> a.students.joinToString(", ")
                ViewKind.STUDENTS -> a.teachers.joinToString(", ")
                ViewKind.ROOM -> (a.teachers + a.students).joinToString(", ")
            }
            val room = if (kind != ViewKind.ROOM && a.room != null) " [${a.room}]" else ""
            (if (a.continuation) "(cont.) " else "") + a.subject + (if (who.isEmpty()) "" else " ($who)") + room
        }
}

public object Views {
    public fun grid(doc: FetDocument, placements: List<Placement>, kind: ViewKind, name: String): Grid {
        val ma = doc.mode == Mode.MORNINGS_AFTERNOONS
        val columns = if (ma) doc.realDays().map { it.name } else doc.days.map { it.name }
        val rows = if (ma) doc.realHours().map { it.name } else doc.hours.map { it.name }
        val hoursPerDay = doc.hours.size
        val cells = List(columns.size) { List(rows.size) { ArrayList<CellActivity>() } }
        val family = if (kind == ViewKind.STUDENTS) studentsFamily(doc, name) else emptySet()
        for (p in placements) {
            if (!p.placed || p.dayIndex == null || p.hourIndex == null) continue
            val hit = when (kind) {
                ViewKind.TEACHER -> name in p.teachers
                ViewKind.ROOM -> p.room == name || name in p.realRooms
                ViewKind.STUDENTS -> p.students.any { it in family }
            }
            if (!hit) continue
            val column = if (ma) p.dayIndex / 2 else p.dayIndex
            val rowStart = if (ma) (p.dayIndex % 2) * hoursPerDay + p.hourIndex else p.hourIndex
            val rowEnd = if (ma) (p.dayIndex % 2) * hoursPerDay + hoursPerDay - 1 else hoursPerDay - 1
            for (offset in 0 until p.duration) {
                val row = rowStart + offset
                if (row > rowEnd || column !in columns.indices) break
                cells[column][row] += CellActivity(p.id, p.subject, p.teachers, p.students, p.room, p.tags, continuation = offset > 0)
            }
        }
        return Grid(kind, name, columns, rows, cells)
    }

    /** The set itself, everything above it and everything below it in the students tree. */
    private fun studentsFamily(doc: FetDocument, name: String): Set<String> {
        val family = HashSet<String>()
        family += name
        for (year in doc.years) {
            val yearHit = year.name == name
            for (group in year.groups) {
                val groupHit = group.name == name
                for (sub in group.subgroups) {
                    if (sub.name == name) { family += year.name; family += group.name }
                    if (yearHit || groupHit) family += sub.name
                }
                if (groupHit) family += year.name
                if (yearHit) family += group.name
            }
        }
        return family
    }
}
