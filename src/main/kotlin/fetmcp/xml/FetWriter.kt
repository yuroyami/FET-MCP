package fetmcp.xml

import fetmcp.model.Activity
import fetmcp.model.Constraint
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.model.Mode
import fetmcp.model.NamedItem
import fetmcp.model.Room
import fetmcp.model.Teacher
import fetmcp.model.Year
import fetmcp.schema.ConstraintSchema
import fetmcp.schema.ConstraintType
import fetmcp.schema.FieldKind
import fetmcp.schema.FieldSpec
import fetmcp.xml.XmlText.indent
import fetmcp.xml.XmlText.number
import fetmcp.xml.XmlText.protect
import fetmcp.xml.XmlText.trueFalse

/** Writes a [FetDocument] the way `Rules::write()` does, section by section, same indentation, same order. */
public object FetWriter {
    public const val DEFAULT_FET_VERSION: String = "7.10.4"

    /** The UTF-8 byte order mark FET puts at the start of every file it saves. */
    public val BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    public fun write(doc: FetDocument, fetVersion: String = DEFAULT_FET_VERSION): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<fet version=\"").append(fetVersion).append("\">\n")
        line(1, "Mode", doc.mode.xml)
        line(1, "Institution_Name", doc.institution)
        line(1, "Comments", doc.comments)
        if (doc.mode == Mode.TERMS) {
            val terms = doc.terms ?: fetmcp.model.Terms(5, 5)
            line(1, "Number_of_Terms", terms.terms.toString())
            line(1, "Number_of_Days_Per_Term", terms.daysPerTerm.toString())
        }
        namedList("Days_List", "Number_of_Days", "Day", doc.days)
        // The real day and hour names come back exactly as the file had them, or derived when it had none.
        if (doc.mode == Mode.MORNINGS_AFTERNOONS) namedList("Real_Days_List", "Number_of_Real_Days", "Real_Day", doc.realDays())
        namedList("Hours_List", "Number_of_Hours", "Hour", doc.hours)
        if (doc.mode == Mode.MORNINGS_AFTERNOONS) namedList("Real_Hours_List", "Number_of_Real_Hours", "Real_Hour", doc.realHours())

        open(1, "Subjects_List")
        for (s in doc.subjects) {
            open(2, "Subject")
            line(3, "Name", s.name); line(3, "Long_Name", s.longName); line(3, "Code", s.code); line(3, "Comments", s.comments)
            close(2, "Subject")
        }
        close(1, "Subjects_List")

        open(1, "Activity_Tags_List")
        for (t in doc.activityTags) {
            open(2, "Activity_Tag")
            line(3, "Name", t.name); line(3, "Long_Name", t.longName); line(3, "Code", t.code)
            line(3, "Printable", trueFalse(t.printable)); line(3, "Comments", t.comments)
            close(2, "Activity_Tag")
        }
        close(1, "Activity_Tags_List")

        open(1, "Teachers_List")
        for (t in doc.teachers) teacher(t, doc.mode)
        close(1, "Teachers_List")

        open(1, "Students_List")
        for (y in doc.years) year(y)
        close(1, "Students_List")

        open(1, "Activities_List")
        for (a in doc.activities) activity(a)
        close(1, "Activities_List")

        open(1, "Buildings_List")
        for (b in doc.buildings) {
            open(2, "Building")
            line(3, "Name", b.name); line(3, "Long_Name", b.longName); line(3, "Code", b.code); line(3, "Comments", b.comments)
            close(2, "Building")
        }
        close(1, "Buildings_List")

        open(1, "Rooms_List")
        for (r in doc.rooms) room(r)
        close(1, "Rooms_List")

        open(1, "Time_Constraints_List")
        for (c in doc.timeConstraints) constraint(c)
        close(1, "Time_Constraints_List")

        open(1, "Space_Constraints_List")
        for (c in doc.spaceConstraints) constraint(c)
        close(1, "Space_Constraints_List")

        open(1, "Timetable_Generation_Options_List")
        for (g in doc.generationOptions) {
            open(2, "GroupActivitiesInInitialOrder")
            line(3, "Number_of_Activities", g.activityIds.size.toString())
            for (id in g.activityIds) line(3, "Activity_Id", id.toString())
            line(3, "Active", trueFalse(g.active))
            line(3, "Comments", g.comments)
            close(2, "GroupActivitiesInInitialOrder")
        }
        close(1, "Timetable_Generation_Options_List")
        append("</fet>\n")
    }

    /** One constraint element, indented like inside the list. Used for refs and for the file. */
    public fun constraintXml(c: Constraint): String = buildString { constraint(c) }

    // ---- sections ----

    private fun StringBuilder.namedList(listTag: String, countTag: String, itemTag: String, items: List<NamedItem>) {
        open(1, listTag)
        line(2, countTag, items.size.toString())
        for (item in items) {
            open(2, itemTag)
            line(3, "Name", item.name)
            line(3, "Long_Name", item.longName)
            close(2, itemTag)
        }
        close(1, listTag)
    }

    private fun StringBuilder.teacher(t: Teacher, mode: Mode) {
        open(2, "Teacher")
        line(3, "Name", t.name); line(3, "Long_Name", t.longName); line(3, "Code", t.code)
        if (mode == Mode.MORNINGS_AFTERNOONS) {
            line(3, "Mornings_Afternoons_Behavior", (t.morningsAfternoonsBehavior ?: fetmcp.model.MaBehavior.UNRESTRICTED).xml, escape = false)
        }
        line(3, "Target_Number_of_Hours", t.targetHours.toString())
        open(3, "Qualified_Subjects")
        for (s in t.qualifiedSubjects) line(4, "Qualified_Subject", s)
        close(3, "Qualified_Subjects")
        line(3, "Comments", t.comments)
        close(2, "Teacher")
    }

    private fun StringBuilder.year(y: Year) {
        open(2, "Year")
        line(3, "Name", y.name); line(3, "Long_Name", y.longName); line(3, "Code", y.code)
        line(3, "Number_of_Students", y.numberOfStudents.toString()); line(3, "Comments", y.comments)
        append(indent(3)).append("<!-- The information regarding categories, divisions of each category, and separator is only used in the divide year automatically by categories dialog. -->\n")
        line(3, "Number_of_Categories", y.categories.size.toString())
        for (divisions in y.categories) {
            open(3, "Category")
            line(4, "Number_of_Divisions", divisions.size.toString())
            for (d in divisions) line(4, "Division", d)
            close(3, "Category")
        }
        line(3, "First_Category_Is_Permanent", trueFalse(y.firstCategoryPermanent))
        line(3, "Separator", y.separator)
        for (g in y.groups) {
            open(3, "Group")
            line(4, "Name", g.name); line(4, "Long_Name", g.longName); line(4, "Code", g.code)
            line(4, "Number_of_Students", g.numberOfStudents.toString()); line(4, "Comments", g.comments)
            for (s in g.subgroups) {
                open(4, "Subgroup")
                line(5, "Name", s.name); line(5, "Long_Name", s.longName); line(5, "Code", s.code)
                line(5, "Number_of_Students", s.numberOfStudents.toString()); line(5, "Comments", s.comments)
                close(4, "Subgroup")
            }
            close(3, "Group")
        }
        close(2, "Year")
    }

    private fun StringBuilder.activity(a: Activity) {
        open(2, "Activity")
        for (t in a.teachers) line(3, "Teacher", t)
        line(3, "Subject", a.subject)
        for (t in a.tags) line(3, "Activity_Tag", t)
        for (s in a.students) line(3, "Students", s)
        line(3, "Duration", a.duration.toString())
        line(3, "Total_Duration", a.totalDuration.toString())
        line(3, "Id", a.id.toString())
        line(3, "Activity_Group_Id", a.groupId.toString())
        a.numberOfStudents?.let { line(3, "Number_of_Students", it.toString()) }
        line(3, "Active", trueFalse(a.active))
        line(3, "Comments", a.comments)
        close(2, "Activity")
    }

    private fun StringBuilder.room(r: Room) {
        open(2, "Room")
        line(3, "Name", r.name); line(3, "Long_Name", r.longName); line(3, "Code", r.code)
        line(3, "Building", r.building)
        line(3, "Capacity", r.capacity.toString())
        line(3, "Virtual", trueFalse(r.virtual))
        if (r.virtual) {
            line(3, "Number_of_Sets_of_Real_Rooms", r.realRoomSets.size.toString())
            for (set in r.realRoomSets) {
                open(3, "Set_of_Real_Rooms")
                line(4, "Number_of_Real_Rooms", set.size.toString())
                for (name in set) line(4, "Real_Room", name)
                close(3, "Set_of_Real_Rooms")
            }
        }
        line(3, "Comments", r.comments)
        close(2, "Room")
    }

    // ---- constraints ----

    private fun StringBuilder.constraint(c: Constraint) {
        open(2, c.type)
        line(3, "Weight_Percentage", number(c.weight))
        val type = ConstraintSchema.get(c.type)
        val written = HashSet<String>()
        if (type != null) {
            for (spec in type.fields) {
                field(3, spec, c.fields[spec.name])
                written += spec.name
            }
        }
        // Fields the schema does not know (unknown type, or a newer FET) are kept, in the order they came.
        for ((name, value) in c.fields) if (name !in written) genericField(3, name, value)
        line(3, "Active", trueFalse(c.active))
        line(3, "Comments", c.comments)
        close(2, c.type)
    }

    private fun StringBuilder.field(level: Int, spec: FieldSpec, value: FieldValue?) {
        when (spec.kind) {
            FieldKind.SCALAR -> {
                // An absent scalar is left out. FET's reader falls back to its default for the field.
                (value as? FieldValue.Scalar)?.let { line(level, spec.name, it.text) }
            }
            FieldKind.OBJECT -> {
                val fields = (value as? FieldValue.Obj)?.fields
                if (fields == null) { if (!spec.optional) { open(level, spec.name); close(level, spec.name) }; return }
                open(level, spec.name)
                val known = spec.fields.map { it.name }.toSet()
                for (child in spec.fields) field(level + 1, child, fields[child.name])
                for ((n, v) in fields) if (n !in known) genericField(level + 1, n, v)
                close(level, spec.name)
            }
            FieldKind.ARRAY -> {
                val items = when (value) {
                    null -> emptyList()
                    is FieldValue.Items -> value.items
                    else -> listOf(value)
                }
                if (items.isEmpty() && spec.optional) return
                spec.countTag?.let { line(level, it, items.size.toString()) }
                val item = spec.item ?: return
                for (entry in items) field(level, item.copy(name = spec.name, optional = false), entry)
            }
        }
    }

    /** No schema: scalars as they are, lists with a guessed count tag, objects recursively. */
    private fun StringBuilder.genericField(level: Int, name: String, value: FieldValue) {
        when (value) {
            is FieldValue.Scalar -> line(level, name, value.text)
            is FieldValue.Obj -> {
                open(level, name)
                for ((n, v) in value.fields) genericField(level + 1, n, v)
                close(level, name)
            }
            is FieldValue.Items -> {
                line(level, guessCountTag(name), value.items.size.toString())
                for (item in value.items) genericField(level, name, item)
            }
        }
    }

    private fun guessCountTag(listName: String): String = "Number_of_" + when {
        listName == "Activity_Id" -> "Activities"
        listName.endsWith("y") -> listName.dropLast(1) + "ies"
        else -> listName + "s"
    }

    // ---- primitives ----

    private fun StringBuilder.open(level: Int, tag: String) { append(indent(level)).append('<').append(tag).append(">\n") }
    private fun StringBuilder.close(level: Int, tag: String) { append(indent(level)).append("</").append(tag).append(">\n") }
    private fun StringBuilder.line(level: Int, tag: String, text: String, escape: Boolean = true) {
        append(indent(level)).append('<').append(tag).append('>')
        append(if (escape) protect(text) else text)
        append("</").append(tag).append(">\n")
    }
}
