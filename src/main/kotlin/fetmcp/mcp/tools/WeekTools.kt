package fetmcp.mcp

import fetmcp.model.Mode
import fetmcp.model.NamedItem
import fetmcp.model.Terms
import fetmcp.session.Cascades
import fetmcp.session.EntityKind

/** fet_set_week, fet_set_terms. */
public object WeekTools {
    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_set_week",
            description = "Name the days and hours of the week. In Mornings-Afternoons mode pass real_days instead of days and each one becomes a morning and an afternoon. " +
                "Renaming keeps constraints pointing at the right slot; dropping a day or hour that a constraint uses is refused.",
            mutating = true,
            inputSchema = schema {
                arr("days", "Day names in order, for example Monday..Friday", itemsAny())
                arr("real_days", "Mornings-Afternoons only: real day names. Each becomes two FET days.", itemsAny())
                arr("hours", "Hour names in order, for example 08:00..14:00. In Mornings-Afternoons these are the hours of one half day.", itemsAny())
                arr("halves", "Mornings-Afternoons only: the two half day names used to build the FET day names (default Morning, Afternoon)", itemsStr())
            },
        ) { args ->
            val doc = args.session.doc
            val ma = doc.mode == Mode.MORNINGS_AFTERNOONS
            if (ma && "days" in args && "real_days" !in args) {
                throw ToolFailure("MODE_FORBIDS", "In Mornings-Afternoons mode pass real_days, not days. Each real day becomes a morning and an afternoon FET day.")
            }
            if (!ma && "real_days" in args) {
                throw ToolFailure("MODE_FORBIDS", "real_days only exists in Mornings-Afternoons mode. Pass days instead.")
            }
            val halves = args.stringsOrEmpty("halves").ifEmpty { listOf("Morning", "Afternoon") }
            if (halves.size != 2) throw ToolFailure("INVALID_ARGUMENT", "halves must name exactly two half days", mapOf("argument" to "halves"))
            val newDays = when {
                ma && "real_days" in args -> namedItems(args, "real_days").flatMap { d -> halves.map { NamedItem("${d.name} $it", if (d.longName.isEmpty()) "" else "${d.longName} $it") } }
                "days" in args -> namedItems(args, "days")
                else -> doc.days.toList()
            }
            val newHours = if ("hours" in args) namedItems(args, "hours") else doc.hours.toList()
            if (newDays.isEmpty() || newHours.isEmpty()) throw ToolFailure("INVALID_ARGUMENT", "The week needs at least one day and one hour")
            if (ma && newDays.size % 2 != 0) throw ToolFailure("MA_ODD_DAYS", "Mornings-Afternoons mode needs an even number of FET days, got ${newDays.size}")

            // Same count means a rename, position by position. Fewer means the extra slots are dropped.
            val dayRenames = renames(doc.days.map { it.name }, newDays.map { it.name })
            val hourRenames = renames(doc.hours.map { it.name }, newHours.map { it.name })
            for ((kind, dropped) in listOf(EntityKind.DAY to dropped(doc.days.map { it.name }, newDays.map { it.name }, dayRenames), EntityKind.HOUR to dropped(doc.hours.map { it.name }, newHours.map { it.name }, hourRenames))) {
                for (name in dropped) {
                    val refs = Cascades.referencesTo(doc, kind, name)
                    if (refs.isNotEmpty()) {
                        throw ToolFailure(
                            if (kind == EntityKind.DAY) "DAY_IN_USE" else "HOUR_IN_USE",
                            "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} '$name' would be dropped but ${refs.size} constraint(s) still use it: ${refs.take(5).joinToString()}. Remove them first.",
                            mapOf("name" to name, "constraints" to refs.take(5).joinToString()),
                        )
                    }
                }
            }
            args.session.mutate("set week") { d ->
                for ((old, new) in dayRenames) Cascades.rename(d, EntityKind.DAY, old, new)
                for ((old, new) in hourRenames) Cascades.rename(d, EntityKind.HOUR, old, new)
                d.days.clear(); d.days += newDays
                d.hours.clear(); d.hours += newHours
                if (d.mode == Mode.TERMS) d.terms?.let { t -> if (t.terms * t.daysPerTerm != d.days.size) d.terms = Terms(1, d.days.size) }
            }
            ToolResult(jsonOf("week" to StateTools.week(args.session.doc), "renamed_days" to dayRenames.size, "renamed_hours" to hourRenames.size))
        }

        tools.tool(
            name = "fet_set_terms",
            description = "Terms mode only. Set how many terms the year has and how many days each term lasts. FET needs days = terms x days per term. " +
                "Term constraints with values that no longer fit are clamped, the same way the FET dialog does it.",
            mutating = true,
            inputSchema = schema {
                int("terms", "Number of terms", required = true, minimum = 1)
                int("days_per_term", "Days in one term", required = true, minimum = 1)
            },
        ) { args ->
            val doc = args.session.doc
            if (doc.mode != Mode.TERMS) throw ToolFailure("MODE_FORBIDS", "Terms only exist in Terms mode. The document is in ${doc.mode.xml}.")
            val terms = args.int("terms")
            val perTerm = args.int("days_per_term")
            if (terms * perTerm != doc.days.size) {
                throw ToolFailure(
                    "TERMS_MISMATCH",
                    "The week has ${doc.days.size} days but $terms terms x $perTerm days is ${terms * perTerm}. Set the days first with fet_set_week.",
                    mapOf("days" to doc.days.size.toString()),
                )
            }
            val clamped = args.session.mutate("set terms") { d ->
                d.terms = Terms(terms, perTerm)
                clampTermConstraints(d, terms, perTerm)
            }
            ToolResult(jsonOf("terms" to terms, "days_per_term" to perTerm, "clamped_constraints" to stringsOf(clamped)))
        }
    }

    /** Same rule as `Rules::setTerms()`: values that cannot hold in the new term shape are pulled down. */
    private fun clampTermConstraints(doc: fetmcp.model.FetDocument, terms: Int, daysPerTerm: Int): List<String> {
        val maxHours = daysPerTerm * doc.hours.size
        val clamped = ArrayList<String>()
        doc.timeConstraints.replaceAll { c ->
            val limit = when (c.type) {
                "ConstraintMaxTermsBetweenActivities" -> "MaxTerms" to terms - 1
                "ConstraintActivitiesOccupyMaxTerms" -> "Max_Number_of_Occupied_Terms" to terms
                "ConstraintTeacherMaxHoursPerTerm", "ConstraintTeachersMaxHoursPerTerm" -> "Max_Hours_Per_Term" to maxHours
                else -> null
            } ?: return@replaceAll c
            val (field, cap) = limit
            val value = c.scalar(field)?.toIntOrNull() ?: return@replaceAll c
            if (value <= cap) c else {
                clamped += c.ref
                c.copy(fields = c.fields + (field to fetmcp.model.FieldValue.of(cap)))
            }
        }
        return clamped
    }

    private fun namedItems(args: Args, name: String): List<NamedItem> = args.array(name).map { element ->
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                val a = Args(element, args.context)
                NamedItem(a.str("name"), a.str("long_name", ""))
            }
            else -> NamedItem(element.toString().trim('"'))
        }
    }

    /** Old to new name for positions that exist in both lists and changed name. */
    private fun renames(old: List<String>, new: List<String>): List<Pair<String, String>> =
        old.indices.mapNotNull { i -> new.getOrNull(i)?.takeIf { it != old[i] }?.let { old[i] to it } }

    /** Names that will no longer exist once the renames are applied. */
    private fun dropped(old: List<String>, new: List<String>, renames: List<Pair<String, String>>): List<String> {
        val afterRename = old.mapIndexed { i, name -> renames.firstOrNull { it.first == name && old.indexOf(name) == i }?.second ?: name }
        return afterRename.filterIndexed { i, _ -> i >= new.size }.filter { it !in new }
    }
}
