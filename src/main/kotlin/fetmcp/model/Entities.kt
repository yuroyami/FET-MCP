package fetmcp.model

/** A day or an hour. */
public data class NamedItem(val name: String, val longName: String = "")

public data class Subject(
    val name: String,
    val longName: String = "",
    val code: String = "",
    val comments: String = "",
)

public data class ActivityTag(
    val name: String,
    val longName: String = "",
    val code: String = "",
    val printable: Boolean = true,
    val comments: String = "",
)

public data class Teacher(
    val name: String,
    val longName: String = "",
    val code: String = "",
    val targetHours: Int = 0,
    val qualifiedSubjects: List<String> = emptyList(),
    /** Only meaningful in Mornings-Afternoons mode. Null elsewhere. */
    val morningsAfternoonsBehavior: MaBehavior? = null,
    val comments: String = "",
)

public data class Building(
    val name: String,
    val longName: String = "",
    val code: String = "",
    val comments: String = "",
)

public data class Room(
    val name: String,
    val longName: String = "",
    val code: String = "",
    val building: String = "",
    val capacity: Int = DEFAULT_ROOM_CAPACITY,
    val virtual: Boolean = false,
    /** For virtual rooms: each entry is one set of real room names FET may pick. */
    val realRoomSets: List<List<String>> = emptyList(),
    val comments: String = "",
) {
    public companion object {
        /** FET's own default, meaning "no limit". */
        public const val DEFAULT_ROOM_CAPACITY: Int = 30000
    }
}

public data class Subgroup(
    val name: String,
    val longName: String = "",
    val code: String = "",
    val numberOfStudents: Int = 0,
    val comments: String = "",
)

public data class Group(
    val name: String,
    val longName: String = "",
    val code: String = "",
    val numberOfStudents: Int = 0,
    val comments: String = "",
    val subgroups: List<Subgroup> = emptyList(),
)

public data class Year(
    val name: String,
    val longName: String = "",
    val code: String = "",
    val numberOfStudents: Int = 0,
    val comments: String = "",
    /** Categories and their divisions, only used by the GUI "divide year" dialog. */
    val categories: List<List<String>> = emptyList(),
    val firstCategoryPermanent: Boolean = false,
    val separator: String = " ",
    val groups: List<Group> = emptyList(),
)

public enum class StudentsLevel { YEAR, GROUP, SUBGROUP }

/** A students set found by name, whichever level it lives on. */
public data class StudentsSet(val level: StudentsLevel, val name: String, val numberOfStudents: Int)

public data class Activity(
    val id: Int,
    /** 0 for a single activity, the first component's id for a split activity. */
    val groupId: Int,
    val teachers: List<String>,
    val subject: String,
    val tags: List<String>,
    val students: List<String>,
    val duration: Int,
    val totalDuration: Int,
    val active: Boolean = true,
    /** Set only when the user overrides the computed student count. */
    val numberOfStudents: Int? = null,
    val comments: String = "",
) {
    val isSplit: Boolean get() = groupId != 0
}

/** Terms mode only. */
public data class Terms(val terms: Int, val daysPerTerm: Int)

/** One `GroupActivitiesInInitialOrder` item from `Timetable_Generation_Options_List`. */
public data class GroupInInitialOrder(
    val activityIds: List<Int>,
    val active: Boolean = true,
    val comments: String = "",
)
