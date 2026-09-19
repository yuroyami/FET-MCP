package fetmcp.model

/** In-memory mirror of one `.fet` file. Mutable on purpose: the session wraps every change in a snapshot. */
public class FetDocument(public var mode: Mode = Mode.OFFICIAL) {
    public var institution: String = "Default institution"
    public var comments: String = "Default comments"

    /** Terms mode only. */
    public var terms: Terms? = null

    public val days: MutableList<NamedItem> = mutableListOf()
    public val hours: MutableList<NamedItem> = mutableListOf()

    /**
     * Mornings-Afternoons only. FET stores these names in the file and a school may have edited them, so keep what
     * was read and only fall back to deriving when they are missing or no longer match the week.
     */
    public val storedRealDays: MutableList<NamedItem> = mutableListOf()
    public val storedRealHours: MutableList<NamedItem> = mutableListOf()
    public val subjects: MutableList<Subject> = mutableListOf()
    public val activityTags: MutableList<ActivityTag> = mutableListOf()
    public val teachers: MutableList<Teacher> = mutableListOf()
    public val years: MutableList<Year> = mutableListOf()
    public val activities: MutableList<Activity> = mutableListOf()
    public val buildings: MutableList<Building> = mutableListOf()
    public val rooms: MutableList<Room> = mutableListOf()
    public val timeConstraints: MutableList<Constraint> = mutableListOf()
    public val spaceConstraints: MutableList<Constraint> = mutableListOf()
    public val generationOptions: MutableList<GroupInInitialOrder> = mutableListOf()

    public fun teacher(name: String): Teacher? = teachers.firstOrNull { it.name == name }
    public fun subject(name: String): Subject? = subjects.firstOrNull { it.name == name }
    public fun activityTag(name: String): ActivityTag? = activityTags.firstOrNull { it.name == name }
    public fun building(name: String): Building? = buildings.firstOrNull { it.name == name }
    public fun room(name: String): Room? = rooms.firstOrNull { it.name == name }
    public fun year(name: String): Year? = years.firstOrNull { it.name == name }
    public fun activity(id: Int): Activity? = activities.firstOrNull { it.id == id }
    public fun day(name: String): NamedItem? = days.firstOrNull { it.name == name }
    public fun hour(name: String): NamedItem? = hours.firstOrNull { it.name == name }

    /** Years, groups and subgroups share one namespace in FET. First match wins, years first. */
    public fun studentsSet(name: String): StudentsSet? {
        years.firstOrNull { it.name == name }?.let { return StudentsSet(StudentsLevel.YEAR, it.name, it.numberOfStudents) }
        for (year in years) {
            year.groups.firstOrNull { it.name == name }?.let { return StudentsSet(StudentsLevel.GROUP, it.name, it.numberOfStudents) }
        }
        for (year in years) for (group in year.groups) {
            group.subgroups.firstOrNull { it.name == name }?.let { return StudentsSet(StudentsLevel.SUBGROUP, it.name, it.numberOfStudents) }
        }
        return null
    }

    public fun allStudentsSets(): List<StudentsSet> = buildList {
        val seen = HashSet<String>()
        for (year in years) {
            if (seen.add(year.name)) add(StudentsSet(StudentsLevel.YEAR, year.name, year.numberOfStudents))
            for (group in year.groups) {
                if (seen.add(group.name)) add(StudentsSet(StudentsLevel.GROUP, group.name, group.numberOfStudents))
                for (sub in group.subgroups) {
                    if (seen.add(sub.name)) add(StudentsSet(StudentsLevel.SUBGROUP, sub.name, sub.numberOfStudents))
                }
            }
        }
    }

    public fun constraints(): Sequence<Constraint> = timeConstraints.asSequence() + spaceConstraints.asSequence()
    public fun constraint(ref: String): Constraint? = constraints().firstOrNull { it.ref == ref }
    public fun constraintsOf(family: Family): MutableList<Constraint> =
        if (family == Family.TIME) timeConstraints else spaceConstraints

    public fun nextActivityId(): Int = (activities.maxOfOrNull { it.id } ?: 0) + 1

    /** Mornings-Afternoons: real day i is FET days 2i (morning) and 2i+1 (afternoon). Same rule as `Rules::setMode()`. */
    public fun realDays(): List<NamedItem> {
        if (mode != Mode.MORNINGS_AFTERNOONS) return emptyList()
        val expected = days.size / 2
        if (storedRealDays.size == expected && expected > 0) return storedRealDays.toList()
        return (0 until expected).map { days[2 * it] }
    }

    /** Mornings-Afternoons: 2n real hours named H1..H2n, long names carry AM then PM. */
    public fun realHours(): List<NamedItem> {
        if (mode != Mode.MORNINGS_AFTERNOONS) return emptyList()
        val n = hours.size
        if (storedRealHours.size == 2 * n && n > 0) return storedRealHours.toList()
        return (0 until 2 * n).map { i ->
            val base = hours[i % n]
            NamedItem("H${i + 1}", base.longName + if (i < n) " AM" else " PM")
        }
    }

    /** Forget the stored real names so the next read derives them, after the week or the mode changed. */
    public fun clearStoredRealNames() {
        storedRealDays.clear()
        storedRealHours.clear()
    }

    /**
     * Index of the FET day for a real day and half, or null when the pair is not valid.
     * FET names a real day after its morning FET day, so "Sunday Morning" is the real day. A plain "Sunday" is
     * accepted too, because that is how a person names it.
     */
    public fun fetDayIndex(realDay: String, afternoon: Boolean): Int? {
        val days = realDays()
        val real = days.indexOfFirst { it.name == realDay }
            .takeIf { it >= 0 }
            ?: days.indexOfFirst { it.name.startsWith("$realDay ") }.takeIf { it >= 0 }
            ?: return null
        return 2 * real + if (afternoon) 1 else 0
    }

    /** Deep enough copy: the entities are immutable data classes, only the lists need copying. */
    public fun copy(): FetDocument {
        val out = FetDocument(mode)
        out.institution = institution
        out.comments = comments
        out.terms = terms
        out.days += days
        out.hours += hours
        out.storedRealDays += storedRealDays
        out.storedRealHours += storedRealHours
        out.subjects += subjects
        out.activityTags += activityTags
        out.teachers += teachers
        out.years += years
        out.activities += activities
        out.buildings += buildings
        out.rooms += rooms
        out.timeConstraints += timeConstraints
        out.spaceConstraints += spaceConstraints
        out.generationOptions += generationOptions
        return out
    }
}
