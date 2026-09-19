package fetmcp.runner

import kotlinx.serialization.Serializable
import java.io.File
import kotlin.random.Random

/** The six random seed parts FET takes. Same input, same seeds, same FET version: same timetable. */
@Serializable
public data class Seed(val s10: Long, val s11: Long, val s12: Long, val s20: Long, val s21: Long, val s22: Long) {
    init {
        for (v in listOf(s10, s11, s12)) require(v in 0..MAX_S1) { "seed parts s10..s12 must be between 0 and $MAX_S1" }
        for (v in listOf(s20, s21, s22)) require(v in 0..MAX_S2) { "seed parts s20..s22 must be between 0 and $MAX_S2" }
        require(!(s10 == 0L && s11 == 0L && s12 == 0L)) { "seed parts s10, s11, s12 must not all be zero" }
        require(!(s20 == 0L && s21 == 0L && s22 == 0L)) { "seed parts s20, s21, s22 must not all be zero" }
    }

    public fun flags(): List<String> = listOf(
        "--randomseeds10=$s10", "--randomseeds11=$s11", "--randomseeds12=$s12",
        "--randomseeds20=$s20", "--randomseeds21=$s21", "--randomseeds22=$s22",
    )

    public companion object {
        public const val MAX_S1: Long = 4294967086L
        public const val MAX_S2: Long = 4294944442L

        public fun random(random: Random = Random.Default): Seed = Seed(
            random.nextLong(1, MAX_S1), random.nextLong(1, MAX_S1), random.nextLong(1, MAX_S1),
            random.nextLong(1, MAX_S2), random.nextLong(1, MAX_S2), random.nextLong(1, MAX_S2),
        )
    }
}

/** HTML views fet-cl can write. The activities XML is always written; it is how results are read back. */
public enum class View(public val flag: String) {
    DAYS_HORIZONTAL("writetimetablesdayshorizontal"),
    DAYS_VERTICAL("writetimetablesdaysvertical"),
    TIME_HORIZONTAL("writetimetablestimehorizontal"),
    TIME_VERTICAL("writetimetablestimevertical"),
    SUBGROUPS("writetimetablessubgroups"),
    GROUPS("writetimetablesgroups"),
    YEARS("writetimetablesyears"),
    TEACHERS("writetimetablesteachers"),
    TEACHERS_FREE_PERIODS("writetimetablesteachersfreeperiods"),
    BUILDINGS("writetimetablesbuildings"),
    ROOMS("writetimetablesrooms"),
    SUBJECTS("writetimetablessubjects"),
    ACTIVITY_TAGS("writetimetablesactivitytags"),
    STATISTICS("writetimetablesstatistics");

    public companion object {
        public fun parse(text: String): View? = entries.firstOrNull { it.name.equals(text.replace(' ', '_'), ignoreCase = true) }
    }
}

public data class GenerateOptions(
    val timeLimitSeconds: Int,
    val seed: Seed? = null,
    val htmlLevel: Int = 2,
    val views: Set<View> = View.entries.toSet(),
    val language: String = "en_US",
) {
    init {
        require(timeLimitSeconds >= 1) { "time limit must be at least 1 second" }
        require(htmlLevel in 0..7) { "html level must be 0 to 7" }
    }
}

public object FetCommand {
    public fun build(fetCl: File, input: File, outputDir: File, options: GenerateOptions): List<String> = buildList {
        add(fetCl.path)
        add("--inputfile=${input.path}")
        add("--outputdir=${outputDir.path}")
        add("--timelimitseconds=${options.timeLimitSeconds}")
        add("--htmllevel=${options.htmlLevel}")
        add("--language=${options.language}")
        add("--writetimetablesxml=true")
        add("--writetimetableconflicts=true")
        add("--writetimetablesactivities=true")
        for (view in View.entries) add("--${view.flag}=${view in options.views}")
        options.seed?.let { addAll(it.flags()) }
    }
}
