package fetmcp.mcp

import fetmcp.model.ActivityTag
import fetmcp.model.Building
import fetmcp.model.FetDocument
import fetmcp.model.Group
import fetmcp.model.MaBehavior
import fetmcp.model.Mode
import fetmcp.model.Room
import fetmcp.model.StudentsLevel
import fetmcp.model.Subgroup
import fetmcp.model.Subject
import fetmcp.model.Teacher
import fetmcp.model.Year
import fetmcp.session.Cascades
import fetmcp.session.EntityKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Typed add tools plus the generic update, remove, get and list. */
public object EntityTools {
    public fun register(tools: ToolRegistry) {
        registerAdders(tools)
        registerDivideYear(tools)
        registerGeneric(tools)
    }

    private fun registerAdders(tools: ToolRegistry) {
        tools.tool(
            name = "fet_add_subjects",
            description = "Add school subjects. Names must be unique and are how activities and constraints refer to them.",
            mutating = true,
            inputSchema = schema {
                arr("subjects", "The subjects to add", itemsObj {
                    str("name", "Short name, used everywhere else", required = true)
                    str("long_name", "Full name")
                    str("code", "Free code")
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val items = args.objects("subjects")
            args.session.mutate("add ${items.size} subject(s)") { d ->
                for (a in items) {
                    val name = a.str("name")
                    requireFree(d, name, "subject")
                    d.subjects += Subject(name, a.str("long_name", ""), a.str("code", ""), a.str("comments", ""))
                }
            }
            ToolResult(jsonOf("added" to stringsOf(items.map { it.str("name") })))
        }

        tools.tool(
            name = "fet_add_activity_tags",
            description = "Add activity tags. A tag is a free label on activities, used by constraints such as 'this tag prefers this room'.",
            mutating = true,
            inputSchema = schema {
                arr("activity_tags", "The tags to add", itemsObj {
                    str("name", "Short name", required = true)
                    str("long_name", "Full name")
                    str("code", "Free code")
                    bool("printable", "Show this tag in the printed timetables (default true)")
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val items = args.objects("activity_tags")
            args.session.mutate("add ${items.size} activity tag(s)") { d ->
                for (a in items) {
                    val name = a.str("name")
                    requireFree(d, name, "activity tag")
                    d.activityTags += ActivityTag(name, a.str("long_name", ""), a.str("code", ""), a.bool("printable", true), a.str("comments", ""))
                }
            }
            ToolResult(jsonOf("added" to stringsOf(items.map { it.str("name") })))
        }

        tools.tool(
            name = "fet_add_teachers",
            description = "Add teachers. In Mornings-Afternoons mode each teacher also has a behaviour: Unrestricted (Algeria style, any half day) or Exclusive (Morocco style, morning or afternoon of a real day, never both), plus one to five exception days.",
            mutating = true,
            inputSchema = schema {
                arr("teachers", "The teachers to add", itemsObj {
                    str("name", "Short name, used by activities and constraints", required = true)
                    str("long_name", "Full name")
                    str("code", "Free code")
                    int("target_hours", "Weekly hours this teacher should reach. 0 means no target.")
                    arr("qualified_subjects", "Subjects this teacher can teach", itemsStr())
                    str("mornings_afternoons_behavior", "Mornings-Afternoons mode only", enum = MaBehavior.entries.map { it.xml })
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val items = args.objects("teachers")
            args.session.mutate("add ${items.size} teacher(s)") { d ->
                for (a in items) {
                    val name = a.str("name")
                    requireFree(d, name, "teacher")
                    val behaviorText = a.strOrNull("mornings_afternoons_behavior")
                    if (behaviorText != null && d.mode != Mode.MORNINGS_AFTERNOONS) {
                        throw ToolFailure("MODE_FORBIDS", "mornings_afternoons_behavior only exists in Mornings-Afternoons mode. The document is in ${d.mode.xml}.", mapOf("teacher" to name))
                    }
                    val behavior = behaviorText?.let {
                        MaBehavior.fromXml(it) ?: throw ToolFailure("INVALID_ARGUMENT", "mornings_afternoons_behavior must be one of ${MaBehavior.entries.joinToString { b -> b.xml }}", mapOf("teacher" to name))
                    } ?: if (d.mode == Mode.MORNINGS_AFTERNOONS) MaBehavior.UNRESTRICTED else null
                    for (s in a.stringsOrEmpty("qualified_subjects")) {
                        if (d.subject(s) == null) throw ToolFailure("REFERENCE_MISSING", "Teacher $name is qualified for subject '$s' which does not exist", mapOf("teacher" to name, "subject" to s))
                    }
                    d.teachers += Teacher(name, a.str("long_name", ""), a.str("code", ""), a.int("target_hours", 0), a.stringsOrEmpty("qualified_subjects"), behavior, a.str("comments", ""))
                }
            }
            ToolResult(jsonOf("added" to stringsOf(items.map { it.str("name") })))
        }

        tools.tool(
            name = "fet_add_buildings",
            description = "Add buildings. Rooms may sit in a building, and constraints can limit how often people move between buildings.",
            mutating = true,
            inputSchema = schema {
                arr("buildings", "The buildings to add", itemsObj {
                    str("name", "Short name", required = true)
                    str("long_name", "Full name")
                    str("code", "Free code")
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val items = args.objects("buildings")
            args.session.mutate("add ${items.size} building(s)") { d ->
                for (a in items) {
                    val name = a.str("name")
                    requireFree(d, name, "building")
                    d.buildings += Building(name, a.str("long_name", ""), a.str("code", ""), a.str("comments", ""))
                }
            }
            ToolResult(jsonOf("added" to stringsOf(items.map { it.str("name") })))
        }

        tools.tool(
            name = "fet_add_rooms",
            description = "Add rooms. A virtual room stands for a choice between sets of real rooms: give at least two sets, each with at least one real room.",
            mutating = true,
            inputSchema = schema {
                arr("rooms", "The rooms to add", itemsObj {
                    str("name", "Short name", required = true)
                    str("long_name", "Full name")
                    str("code", "Free code")
                    str("building", "Building this room is in")
                    int("capacity", "How many students fit. Leave empty for no limit.")
                    bool("virtual", "This room is a choice between sets of real rooms")
                    arr("real_room_sets", "For a virtual room: each entry is one set of real room names", itemsObj { arr("rooms", "Real room names", itemsStr(), required = true) })
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val items = args.objects("rooms")
            args.session.mutate("add ${items.size} room(s)") { d ->
                for (a in items) {
                    val name = a.str("name")
                    requireFree(d, name, "room")
                    val building = a.str("building", "")
                    if (building.isNotEmpty() && d.building(building) == null) {
                        throw ToolFailure("REFERENCE_MISSING", "Room $name is in building '$building' which does not exist", mapOf("room" to name, "building" to building))
                    }
                    val virtual = a.bool("virtual", false)
                    val sets = a.objectsOrEmpty("real_room_sets").map { it.strings("rooms") }
                    if (virtual) {
                        if (sets.size < 2) throw ToolFailure("VIRTUAL_ROOM_INVALID", "Virtual room $name needs at least two sets of real rooms", mapOf("room" to name))
                        for (set in sets) for (r in set) {
                            val real = d.room(r) ?: throw ToolFailure("REFERENCE_MISSING", "Virtual room $name lists real room '$r' which does not exist", mapOf("room" to name, "real_room" to r))
                            if (real.virtual) throw ToolFailure("VIRTUAL_ROOM_INVALID", "Virtual room $name lists '$r', which is itself virtual", mapOf("room" to name))
                        }
                    }
                    d.rooms += Room(name, a.str("long_name", ""), a.str("code", ""), building, a.int("capacity", Room.DEFAULT_ROOM_CAPACITY), virtual, sets, a.str("comments", ""))
                }
            }
            ToolResult(jsonOf("added" to stringsOf(items.map { it.str("name") })))
        }

        tools.tool(
            name = "fet_add_years",
            description = "Add year levels, the top of the students tree. A year holds groups, a group holds subgroups. Activities can target any level.",
            mutating = true,
            inputSchema = schema {
                arr("years", "The years to add", itemsObj {
                    str("name", "Short name, for example 9 or Grade 9", required = true)
                    str("long_name", "Full name")
                    str("code", "Free code")
                    int("number_of_students", "How many students in the year")
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val items = args.objects("years")
            args.session.mutate("add ${items.size} year(s)") { d ->
                for (a in items) {
                    val name = a.str("name")
                    requireFree(d, name, "students set")
                    d.years += Year(name, a.str("long_name", ""), a.str("code", ""), a.int("number_of_students", 0), a.str("comments", ""))
                }
            }
            ToolResult(jsonOf("added" to stringsOf(items.map { it.str("name") })))
        }

        tools.tool(
            name = "fet_add_groups",
            description = "Add groups (classes) to a year. A group name that already exists in another year attaches the same students, which is how FET shares a class between years.",
            mutating = true,
            inputSchema = schema {
                str("year", "The year to add the groups to", required = true)
                arr("groups", "The groups to add", itemsObj {
                    str("name", "Short name, for example 9A", required = true)
                    str("long_name", "Full name")
                    str("code", "Free code")
                    int("number_of_students", "How many students in the group")
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val yearName = args.str("year")
            val items = args.objects("groups")
            args.session.mutate("add ${items.size} group(s) to $yearName") { d ->
                val index = d.years.indexOfFirst { it.name == yearName }
                if (index < 0) throw ToolFailure("NOT_FOUND", "There is no year called '$yearName'", mapOf("year" to yearName))
                var year = d.years[index]
                for (a in items) {
                    val name = a.str("name")
                    if (year.groups.any { it.name == name }) throw ToolFailure("DUPLICATE", "Year $yearName already has a group called '$name'", mapOf("group" to name))
                    val existing = d.studentsSet(name)
                    if (existing != null && existing.level != StudentsLevel.GROUP) {
                        throw ToolFailure("DUPLICATE", "The name '$name' is already used by a ${existing.level.name.lowercase()}. Years, groups and subgroups share one namespace.", mapOf("group" to name))
                    }
                    year = year.copy(groups = year.groups + Group(name, a.str("long_name", ""), a.str("code", ""), a.int("number_of_students", 0), a.str("comments", "")))
                    d.years[index] = year
                }
            }
            ToolResult(jsonOf("year" to yearName, "added" to stringsOf(items.map { it.str("name") })))
        }

        tools.tool(
            name = "fet_add_subgroups",
            description = "Add subgroups to a group. Use these when part of a class has its own lessons, for example a language or an option group.",
            mutating = true,
            inputSchema = schema {
                str("year", "The year the group belongs to", required = true)
                str("group", "The group to add the subgroups to", required = true)
                arr("subgroups", "The subgroups to add", itemsObj {
                    str("name", "Short name", required = true)
                    str("long_name", "Full name")
                    str("code", "Free code")
                    int("number_of_students", "How many students in the subgroup")
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val yearName = args.str("year")
            val groupName = args.str("group")
            val items = args.objects("subgroups")
            args.session.mutate("add ${items.size} subgroup(s) to $groupName") { d ->
                val yearIndex = d.years.indexOfFirst { it.name == yearName }
                if (yearIndex < 0) throw ToolFailure("NOT_FOUND", "There is no year called '$yearName'", mapOf("year" to yearName))
                val year = d.years[yearIndex]
                val groupIndex = year.groups.indexOfFirst { it.name == groupName }
                if (groupIndex < 0) throw ToolFailure("NOT_FOUND", "Year $yearName has no group called '$groupName'", mapOf("group" to groupName))
                var group = year.groups[groupIndex]
                for (a in items) {
                    val name = a.str("name")
                    if (group.subgroups.any { it.name == name }) throw ToolFailure("DUPLICATE", "Group $groupName already has a subgroup called '$name'", mapOf("subgroup" to name))
                    val existing = d.studentsSet(name)
                    if (existing != null && existing.level != StudentsLevel.SUBGROUP) {
                        throw ToolFailure("DUPLICATE", "The name '$name' is already used by a ${existing.level.name.lowercase()}. Years, groups and subgroups share one namespace.", mapOf("subgroup" to name))
                    }
                    group = group.copy(subgroups = group.subgroups + Subgroup(name, a.str("long_name", ""), a.str("code", ""), a.int("number_of_students", 0), a.str("comments", "")))
                }
                d.years[yearIndex] = year.copy(groups = year.groups.toMutableList().also { it[groupIndex] = group })
            }
            ToolResult(jsonOf("year" to yearName, "group" to groupName, "added" to stringsOf(items.map { it.str("name") })))
        }
    }

    private fun registerDivideYear(tools: ToolRegistry) {
        tools.tool(
            name = "fet_divide_year",
            description = "Split a year into groups and subgroups by categories, the way the FET dialog does it. One category makes groups; a second makes subgroups inside each group. " +
                "Names are built by joining the year name and the division names with the separator.",
            mutating = true,
            inputSchema = schema {
                str("year", "The year to divide", required = true)
                arr("categories", "One list of division names per category. The first makes the groups.", itemsAny(), required = true)
                str("separator", "Text between the parts of a generated name (default a single space)")
                bool("first_category_is_permanent", "Remember the first category as permanent, like the FET dialog")
                int("number_of_students", "Students per smallest set. Leave empty to keep 0.")
            },
        ) { args ->
            val yearName = args.str("year")
            val categories = args.array("categories").map { element ->
                (element as? kotlinx.serialization.json.JsonArray)?.map { it.toString().trim('"') }
                    ?: throw ToolFailure("INVALID_ARGUMENT", "categories must be a list of lists of names", mapOf("argument" to "categories"))
            }
            if (categories.isEmpty() || categories.any { it.isEmpty() }) throw ToolFailure("INVALID_ARGUMENT", "Every category needs at least one division", mapOf("argument" to "categories"))
            val separator = args.str("separator", " ")
            val perSet = args.int("number_of_students", 0)
            val groupNames = ArrayList<String>()
            val subgroupNames = ArrayList<String>()
            args.session.mutate("divide year $yearName") { d ->
                val index = d.years.indexOfFirst { it.name == yearName }
                if (index < 0) throw ToolFailure("NOT_FOUND", "There is no year called '$yearName'", mapOf("year" to yearName))
                val year = d.years[index]
                if (year.groups.isNotEmpty()) throw ToolFailure("DUPLICATE", "Year $yearName already has groups. Remove them first, or add groups one by one.", mapOf("year" to yearName))
                val groups = categories.first().map { first ->
                    val groupName = yearName + separator + first
                    requireFree(d, groupName, "students set")
                    groupNames += groupName
                    val subgroups = combinations(categories.drop(1)).map { rest ->
                        val subName = (listOf(groupName) + rest).joinToString(separator)
                        requireFree(d, subName, "students set")
                        subgroupNames += subName
                        Subgroup(subName, numberOfStudents = perSet)
                    }
                    Group(groupName, numberOfStudents = if (subgroups.isEmpty()) perSet else subgroups.sumOf { it.numberOfStudents }, subgroups = subgroups)
                }
                d.years[index] = year.copy(
                    categories = categories,
                    firstCategoryPermanent = args.bool("first_category_is_permanent", false),
                    separator = separator,
                    groups = groups,
                    numberOfStudents = if (year.numberOfStudents > 0) year.numberOfStudents else groups.sumOf { it.numberOfStudents },
                )
            }
            ToolResult(jsonOf("year" to yearName, "groups" to stringsOf(groupNames), "subgroups" to stringsOf(subgroupNames)))
        }
    }

    private fun registerGeneric(tools: ToolRegistry) {
        tools.tool(
            name = "fet_update",
            description = "Change one entity. Renaming follows into every activity and constraint that names it.",
            mutating = true,
            inputSchema = schema {
                str("kind", "What to change", required = true, enum = kinds)
                str("name", "Its current name", required = true)
                obj("changes", "The fields to change. Pass name to rename.", required = true) {
                    str("name", "New name")
                    str("long_name", "New long name")
                    str("code", "New code")
                    str("building", "Rooms only: new building")
                    int("capacity", "Rooms only: new capacity")
                    int("number_of_students", "Students sets only")
                    int("target_hours", "Teachers only")
                    arr("qualified_subjects", "Teachers only", itemsStr())
                    str("mornings_afternoons_behavior", "Teachers in Mornings-Afternoons mode only", enum = MaBehavior.entries.map { it.xml })
                    bool("printable", "Activity tags only")
                    str("comments", "New notes")
                }
            },
        ) { args ->
            val kind = kind(args.str("kind"))
            val name = args.str("name")
            val changes = args.obj("changes")
            val report = args.session.mutate("update ${kind.name.lowercase()} $name") { d ->
                findOrFail(d, kind, name)
                val newName = changes.strOrNull("name")
                var report = fetmcp.session.CascadeReport()
                if (newName != null && newName != name) {
                    requireFree(d, newName, kind.name.lowercase())
                    report = Cascades.rename(d, kind, name, newName)
                }
                applyChanges(d, kind, newName ?: name, changes)
                report
            }
            ToolResult(
                jsonOf(
                    "kind" to kind.name.lowercase(),
                    "name" to (changes.strOrNull("name") ?: name),
                    "changed_activities" to report.changedActivities,
                    "changed_constraints" to stringsOf(report.changedConstraints),
                ),
            )
        }

        tools.tool(
            name = "fet_remove",
            description = "Remove entities. This also removes what depends on them: activities that would be left without a teacher, subject or students set, and constraints that name them. " +
                "A cascade that would delete more than ten activities needs confirm true.",
            mutating = true,
            inputSchema = schema {
                str("kind", "What to remove", required = true, enum = kinds)
                arr("names", "The names to remove", itemsStr(), required = true)
                bool("confirm", "Go ahead even when many activities would be deleted")
            },
        ) { args ->
            val kind = kind(args.str("kind"))
            if (kind == EntityKind.DAY || kind == EntityKind.HOUR) {
                throw ToolFailure("MODE_FORBIDS", "Days and hours are changed with fet_set_week, not fet_remove.")
            }
            val names = args.strings("names")
            // Try it on a copy first, so the size of the cascade is known before anything is written.
            val preview = args.session.doc.copy().let { copy ->
                names.fold(fetmcp.session.CascadeReport()) { acc, n -> acc + Cascades.remove(copy, kind, n) }
            }
            if (preview.removedActivities.size > 10 && !args.bool("confirm", false)) {
                throw ToolFailure(
                    "CASCADE_TOO_LARGE",
                    "Removing ${names.joinToString()} would also delete ${preview.removedActivities.size} activities. Pass confirm true to go ahead.",
                    mapOf("activities" to preview.removedActivities.size.toString()),
                )
            }
            val report = args.session.mutate("remove ${names.size} ${kind.name.lowercase()}(s)") { d ->
                names.fold(fetmcp.session.CascadeReport()) { acc, n ->
                    findOrFail(d, kind, n)
                    acc + Cascades.remove(d, kind, n)
                }
            }
            ToolResult(
                jsonOf(
                    "kind" to kind.name.lowercase(),
                    "removed" to stringsOf(names),
                    "removed_activities" to report.removedActivities,
                    "changed_activities" to report.changedActivities,
                    "removed_constraints" to stringsOf(report.removedConstraints),
                    "changed_constraints" to stringsOf(report.changedConstraints),
                ),
            )
        }

        tools.tool(
            name = "fet_get",
            description = "One entity in full, with every activity and constraint that mentions it.",
            inputSchema = schema {
                str("kind", "What to look up", required = true, enum = kinds)
                str("name", "Its name", required = true)
            },
        ) { args ->
            val kind = kind(args.str("kind"))
            val name = args.str("name")
            val doc = args.session.doc
            val entity = findOrFail(doc, kind, name)
            val activities = doc.activities.filter { a ->
                when (kind) {
                    EntityKind.TEACHER -> name in a.teachers
                    EntityKind.SUBJECT -> a.subject == name
                    EntityKind.ACTIVITY_TAG -> name in a.tags
                    EntityKind.YEAR, EntityKind.GROUP, EntityKind.SUBGROUP, EntityKind.STUDENTS -> name in a.students
                    else -> false
                }
            }
            val refs = Cascades.referencesTo(doc, kind, name)
            ToolResult(
                buildJsonObject {
                    put("kind", kind.name.lowercase())
                    put("entity", entity)
                    put("activities", buildJsonArray { activities.forEach { add(ActivityTools.activityJson(it)) } })
                    put("constraints", buildJsonArray { refs.mapNotNull { doc.constraint(it) }.forEach { add(it.toJson(includeFields = false)) } })
                },
            )
        }

        tools.tool(
            name = "fet_list",
            description = "List entities of one kind. Use kind 'students' to see the whole year, group and subgroup tree.",
            inputSchema = schema {
                str("kind", "What to list", required = true, enum = kinds + "students_tree")
                str("filter", "Keep only names containing this text")
            },
        ) { args ->
            val text = args.str("kind")
            val filter = args.str("filter", "")
            val doc = args.session.doc
            fun keep(name: String) = filter.isEmpty() || name.contains(filter, ignoreCase = true)
            val items: List<JsonObject> = when (text.lowercase()) {
                "students_tree" -> doc.years.filter { keep(it.name) }.map { y ->
                    buildJsonObject {
                        put("name", y.name)
                        put("number_of_students", y.numberOfStudents)
                        put("groups", buildJsonArray {
                            for (g in y.groups) add(jsonOf("name" to g.name, "number_of_students" to g.numberOfStudents, "subgroups" to stringsOf(g.subgroups.map { it.name })))
                        })
                    }
                }
                else -> when (kind(text)) {
                    EntityKind.SUBJECT -> doc.subjects.filter { keep(it.name) }.map { jsonOf("name" to it.name, "long_name" to it.longName, "code" to it.code) }
                    EntityKind.ACTIVITY_TAG -> doc.activityTags.filter { keep(it.name) }.map { jsonOf("name" to it.name, "long_name" to it.longName, "printable" to it.printable) }
                    EntityKind.TEACHER -> doc.teachers.filter { keep(it.name) }.map {
                        jsonOf(
                            "name" to it.name, "long_name" to it.longName, "target_hours" to it.targetHours,
                            "qualified_subjects" to stringsOf(it.qualifiedSubjects),
                            "mornings_afternoons_behavior" to it.morningsAfternoonsBehavior?.xml,
                            "assigned_hours" to doc.activities.filter { a -> a.active && it.name in a.teachers }.sumOf { a -> a.duration },
                        )
                    }
                    EntityKind.BUILDING -> doc.buildings.filter { keep(it.name) }.map { jsonOf("name" to it.name, "long_name" to it.longName) }
                    EntityKind.ROOM -> doc.rooms.filter { keep(it.name) }.map {
                        jsonOf("name" to it.name, "building" to it.building, "capacity" to it.capacity, "virtual" to it.virtual, "real_room_sets" to it.realRoomSets.map { s -> stringsOf(s) })
                    }
                    EntityKind.YEAR -> doc.years.filter { keep(it.name) }.map { jsonOf("name" to it.name, "number_of_students" to it.numberOfStudents, "groups" to stringsOf(it.groups.map { g -> g.name })) }
                    EntityKind.GROUP -> doc.years.flatMap { y -> y.groups.map { y.name to it } }.filter { keep(it.second.name) }
                        .map { jsonOf("name" to it.second.name, "year" to it.first, "number_of_students" to it.second.numberOfStudents, "subgroups" to stringsOf(it.second.subgroups.map { s -> s.name })) }
                    EntityKind.SUBGROUP -> doc.years.flatMap { y -> y.groups.flatMap { g -> g.subgroups.map { Triple(y.name, g.name, it) } } }.filter { keep(it.third.name) }
                        .map { jsonOf("name" to it.third.name, "year" to it.first, "group" to it.second, "number_of_students" to it.third.numberOfStudents) }
                    EntityKind.STUDENTS -> doc.allStudentsSets().filter { keep(it.name) }.map { jsonOf("name" to it.name, "level" to it.level.name.lowercase(), "number_of_students" to it.numberOfStudents) }
                    EntityKind.DAY -> doc.days.filter { keep(it.name) }.map { jsonOf("name" to it.name, "long_name" to it.longName) }
                    EntityKind.HOUR -> doc.hours.filter { keep(it.name) }.map { jsonOf("name" to it.name, "long_name" to it.longName) }
                }
            }
            ToolResult(jsonOf("kind" to text.lowercase(), "count" to items.size, "items" to items))
        }
    }

    // ---- helpers ----

    private val kinds = listOf("subject", "activity_tag", "teacher", "building", "room", "year", "group", "subgroup", "students", "day", "hour")

    private fun kind(text: String): EntityKind = EntityKind.parse(text)
        ?: throw ToolFailure("INVALID_ARGUMENT", "kind must be one of ${kinds.joinToString()}", mapOf("argument" to "kind"))

    /** Refuses a name that any entity already uses, so the FET namespaces stay clean. */
    private fun requireFree(doc: FetDocument, name: String, kindLabel: String) {
        if (name.isBlank()) throw ToolFailure("INVALID_ARGUMENT", "A $kindLabel needs a name")
        val clash = when {
            doc.subject(name) != null -> "subject"
            doc.activityTag(name) != null -> "activity tag"
            doc.teacher(name) != null -> "teacher"
            doc.building(name) != null -> "building"
            doc.room(name) != null -> "room"
            doc.studentsSet(name) != null -> "students set"
            else -> null
        }
        if (clash != null) throw ToolFailure("DUPLICATE", "The name '$name' is already used by a $clash", mapOf("name" to name, "kind" to clash))
    }

    private fun findOrFail(doc: FetDocument, kind: EntityKind, name: String): JsonObject {
        val found: JsonObject? = when (kind) {
            EntityKind.SUBJECT -> doc.subject(name)?.let { jsonOf("name" to it.name, "long_name" to it.longName, "code" to it.code, "comments" to it.comments) }
            EntityKind.ACTIVITY_TAG -> doc.activityTag(name)?.let { jsonOf("name" to it.name, "long_name" to it.longName, "code" to it.code, "printable" to it.printable, "comments" to it.comments) }
            EntityKind.TEACHER -> doc.teacher(name)?.let {
                jsonOf(
                    "name" to it.name, "long_name" to it.longName, "code" to it.code, "target_hours" to it.targetHours,
                    "qualified_subjects" to stringsOf(it.qualifiedSubjects), "mornings_afternoons_behavior" to it.morningsAfternoonsBehavior?.xml, "comments" to it.comments,
                )
            }
            EntityKind.BUILDING -> doc.building(name)?.let { jsonOf("name" to it.name, "long_name" to it.longName, "code" to it.code, "comments" to it.comments) }
            EntityKind.ROOM -> doc.room(name)?.let {
                jsonOf(
                    "name" to it.name, "long_name" to it.longName, "code" to it.code, "building" to it.building,
                    "capacity" to it.capacity, "virtual" to it.virtual, "real_room_sets" to it.realRoomSets.map { s -> stringsOf(s) }, "comments" to it.comments,
                )
            }
            EntityKind.DAY -> doc.day(name)?.let { jsonOf("name" to it.name, "long_name" to it.longName) }
            EntityKind.HOUR -> doc.hour(name)?.let { jsonOf("name" to it.name, "long_name" to it.longName) }
            EntityKind.YEAR, EntityKind.GROUP, EntityKind.SUBGROUP, EntityKind.STUDENTS ->
                doc.studentsSet(name)?.let { jsonOf("name" to it.name, "level" to it.level.name.lowercase(), "number_of_students" to it.numberOfStudents) }
        }
        return found ?: throw ToolFailure("NOT_FOUND", "There is no ${kind.name.lowercase().replace('_', ' ')} called '$name'", mapOf("name" to name))
    }

    private fun applyChanges(doc: FetDocument, kind: EntityKind, name: String, changes: Args) {
        when (kind) {
            EntityKind.SUBJECT -> doc.subjects.replaceAll { s ->
                if (s.name != name) s else s.copy(longName = changes.str("long_name", s.longName), code = changes.str("code", s.code), comments = changes.str("comments", s.comments))
            }
            EntityKind.ACTIVITY_TAG -> doc.activityTags.replaceAll { t ->
                if (t.name != name) t else t.copy(longName = changes.str("long_name", t.longName), code = changes.str("code", t.code), printable = changes.bool("printable", t.printable), comments = changes.str("comments", t.comments))
            }
            EntityKind.TEACHER -> {
                val behaviorText = changes.strOrNull("mornings_afternoons_behavior")
                if (behaviorText != null && doc.mode != Mode.MORNINGS_AFTERNOONS) {
                    throw ToolFailure("MODE_FORBIDS", "mornings_afternoons_behavior only exists in Mornings-Afternoons mode. The document is in ${doc.mode.xml}.", mapOf("teacher" to name))
                }
                val behavior = behaviorText?.let {
                    MaBehavior.fromXml(it) ?: throw ToolFailure("INVALID_ARGUMENT", "mornings_afternoons_behavior must be one of ${MaBehavior.entries.joinToString { b -> b.xml }}", mapOf("teacher" to name))
                }
                if ("qualified_subjects" in changes) {
                    for (s in changes.strings("qualified_subjects")) {
                        if (doc.subject(s) == null) throw ToolFailure("REFERENCE_MISSING", "Subject '$s' does not exist", mapOf("teacher" to name, "subject" to s))
                    }
                }
                doc.teachers.replaceAll { t ->
                    if (t.name != name) t else t.copy(
                        longName = changes.str("long_name", t.longName), code = changes.str("code", t.code),
                        targetHours = changes.int("target_hours", t.targetHours),
                        qualifiedSubjects = if ("qualified_subjects" in changes) changes.strings("qualified_subjects") else t.qualifiedSubjects,
                        morningsAfternoonsBehavior = behavior ?: t.morningsAfternoonsBehavior,
                        comments = changes.str("comments", t.comments),
                    )
                }
            }
            EntityKind.BUILDING -> doc.buildings.replaceAll { b ->
                if (b.name != name) b else b.copy(longName = changes.str("long_name", b.longName), code = changes.str("code", b.code), comments = changes.str("comments", b.comments))
            }
            EntityKind.ROOM -> {
                val building = changes.strOrNull("building")
                if (building != null && building.isNotEmpty() && doc.building(building) == null) {
                    throw ToolFailure("REFERENCE_MISSING", "Building '$building' does not exist", mapOf("room" to name, "building" to building))
                }
                doc.rooms.replaceAll { r ->
                    if (r.name != name) r else r.copy(
                        longName = changes.str("long_name", r.longName), code = changes.str("code", r.code),
                        building = building ?: r.building, capacity = changes.int("capacity", r.capacity), comments = changes.str("comments", r.comments),
                    )
                }
            }
            EntityKind.DAY, EntityKind.HOUR -> {
                val list = if (kind == EntityKind.DAY) doc.days else doc.hours
                list.replaceAll { if (it.name != name) it else fetmcp.model.NamedItem(it.name, changes.str("long_name", it.longName)) }
            }
            EntityKind.YEAR, EntityKind.GROUP, EntityKind.SUBGROUP, EntityKind.STUDENTS -> {
                val count = changes.intOrNull("number_of_students")
                val longName = changes.strOrNull("long_name")
                val code = changes.strOrNull("code")
                val comments = changes.strOrNull("comments")
                doc.years.replaceAll { y ->
                    var year = if (y.name == name) y.copy(numberOfStudents = count ?: y.numberOfStudents, longName = longName ?: y.longName, code = code ?: y.code, comments = comments ?: y.comments) else y
                    year = year.copy(groups = year.groups.map { g ->
                        var group = if (g.name == name) g.copy(numberOfStudents = count ?: g.numberOfStudents, longName = longName ?: g.longName, code = code ?: g.code, comments = comments ?: g.comments) else g
                        group = group.copy(subgroups = group.subgroups.map { s ->
                            if (s.name == name) s.copy(numberOfStudents = count ?: s.numberOfStudents, longName = longName ?: s.longName, code = code ?: s.code, comments = comments ?: s.comments) else s
                        })
                        group
                    })
                    year
                }
            }
        }
    }

    /** Every combination across the remaining categories, in order: [[A,B],[x,y]] gives Ax, Ay, Bx, By. */
    private fun combinations(categories: List<List<String>>): List<List<String>> =
        if (categories.isEmpty()) emptyList()
        else categories.fold(listOf(emptyList<String>())) { acc, options -> acc.flatMap { prefix -> options.map { prefix + it } } }
}
