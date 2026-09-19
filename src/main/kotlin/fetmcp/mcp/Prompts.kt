package fetmcp.mcp

/** A prompt the host can offer the user, with the arguments it fills in. */
public data class PromptSpec(
    val name: String,
    val description: String,
    val arguments: List<PromptArgumentSpec>,
    val render: (Map<String, String>) -> String,
)

public data class PromptArgumentSpec(val name: String, val description: String, val required: Boolean)

/** The three guided workflows: build a timetable, fix an impossible one, explain a finished one. */
public object Prompts {
    public fun all(): List<PromptSpec> = listOf(build(), fix(), explain())

    private fun build() = PromptSpec(
        name = "build_timetable",
        description = "Take a school described in plain words and build its timetable from start to finish.",
        arguments = listOf(
            PromptArgumentSpec("description", "The school in plain words: days, hours, teachers, classes, subjects, rooms, and any rules", required = true),
            PromptArgumentSpec("file", "File name to work in, for example school.fet", required = false),
            PromptArgumentSpec("mode", "Official, Mornings_Afternoons, Block_Planning or Terms. Leave empty to be asked.", required = false),
        ),
    ) { args ->
        val file = args["file"] ?: "school.fet"
        """
        Build a school timetable with the FET tools. Here is the school:

        ${args["description"] ?: "(no description given, ask the user)"}

        Work in this order and stop at the first thing that does not add up.

        1. Mode. ${args["mode"]?.let { "Use $it." } ?: "Decide the mode from the description and say why. If the school runs a morning shift and an afternoon shift, that is Mornings_Afternoons. Read fet_explain_mode before you use anything but Official."}
        2. Create the file with fet_new, path $file.
        3. Name the week with fet_set_week. In Mornings_Afternoons mode pass real_days, not days.
        4. Add the data, in this order because each step refers to the one before:
           fet_add_subjects, fet_add_activity_tags (only if the school uses labels like Lab or Double),
           fet_add_teachers, fet_add_years then fet_add_groups then fet_add_subgroups (or fet_divide_year to split a year at once),
           fet_add_buildings then fet_add_rooms.
        5. Add the lessons with fet_add_activities. One entry per subject and class. Use total_duration for the weekly hours
           and split for how many separate blocks. A three hour subject taught in three separate lessons is total_duration 3, split 3.
        6. Add the rules:
           fet_set_unavailable for people and rooms that are not there at certain times,
           fet_set_breaks for a whole school break such as lunch,
           fet_set_limits for the common numbers (max hours a day, max gaps, max days a week),
           fet_set_rooms_preference for home rooms and special rooms.
           For anything else, search with fet_constraint_types first, then use fet_add_constraints. Never guess a type name.
        7. Check with fet_validate, then with fet_precheck. Fix what they report before going on.
        8. Generate with fet_generate_start and a time limit of 600 seconds. Watch it with fet_generate_status, wait_seconds 60 at a time.
        9. Read the result with fet_results_summary. If it failed, follow the fix_impossible prompt.
           If it worked, show a few timetables with fet_results_view and tell the user where the HTML files are.

        Rules while you work: never write XML yourself, use the tools. Weight 100 means a rule can never be broken,
        which makes generation harder, so use it only for real constraints. Anything a person merely prefers should be below 100.
        """.trimIndent()
    }

    private fun fix() = PromptSpec(
        name = "fix_impossible",
        description = "Work out why a timetable could not be built and relax the right rule.",
        arguments = listOf(PromptArgumentSpec("rounds", "How many attempts to make before stopping and asking (default 3)", required = false)),
    ) { args ->
        val rounds = args["rounds"] ?: "3"
        """
        The last generation did not produce a full timetable. Find out why and fix it, in at most $rounds attempts.

        1. Call fet_results_summary. Read state, placed against total, and difficult_activities.
           The last entry of difficult_activities is the lesson FET could not fit. That is where to look, not anywhere else.
        2. Call fet_list_constraints with activity_id set to that lesson, and also with the teacher and students set it belongs to.
           You now have the rules that box it in.
        3. Decide which single rule to relax. In this order of preference:
           lower a weight below 100 rather than deleting anything,
           widen a number (max hours, max gaps, min days between),
           remove a not-available slot,
           only then remove a constraint outright.
           If the lesson has no free slot at all, the problem is usually a not-available rule or a room that is too small.
        4. Say in one sentence what you changed and why, then generate again with the same time limit.
        5. If it still fails after $rounds rounds, stop and report: the lesson, the rules around it, and what you tried.
           Do not keep loosening rules on your own past that point, because the result stops being the timetable the school asked for.

        If the state was TIME_EXCEEDED rather than IMPOSSIBLE, try a longer time limit once before changing any rule.
        """.trimIndent()
    }

    private fun explain() = PromptSpec(
        name = "explain_timetable",
        description = "Turn a finished timetable into a short plain summary for a person.",
        arguments = listOf(
            PromptArgumentSpec("kind", "teacher, students or room", required = false),
            PromptArgumentSpec("name", "Whose timetable to explain. Leave empty for an overview of the whole school.", required = false),
        ),
    ) { args ->
        val who = args["name"]
        """
        Explain the timetable that was just generated, for a school principal who does not use FET.

        1. Call fet_results_summary first. Say whether every lesson was placed, and how many soft rules were broken.
        ${if (who != null) "2. Call fet_results_view with kind ${args["kind"] ?: "teacher"} and name $who, and show the markdown grid." else "2. Call fet_results_view for two or three teachers and classes and show the markdown grids."}
        3. Call fet_results_conflicts. Turn the top three into plain sentences: who is affected and what is not ideal about it.
           Skip the FET wording, say things like \"Mr Ahmed has one free hour in the middle of Tuesday\".
        4. End with the file path of the HTML index, so the timetables can be opened and printed.

        Keep it short. No FET jargon, no constraint type names, no refs.
        """.trimIndent()
    }
}
