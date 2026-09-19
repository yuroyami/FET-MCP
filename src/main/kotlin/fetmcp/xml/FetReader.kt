package fetmcp.xml

import fetmcp.model.Activity
import fetmcp.model.ActivityTag
import fetmcp.model.Building
import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.model.Group
import fetmcp.model.GroupInInitialOrder
import fetmcp.model.MaBehavior
import fetmcp.model.Mode
import fetmcp.model.NamedItem
import fetmcp.model.Room
import fetmcp.model.Subgroup
import fetmcp.model.Subject
import fetmcp.model.Teacher
import fetmcp.model.Terms
import fetmcp.model.Year
import fetmcp.schema.ConstraintNormalizer
import fetmcp.schema.ConstraintSchema
import fetmcp.schema.FieldKind
import fetmcp.schema.FieldSpec
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

public class FetXmlException(message: String, public val line: Int, public val column: Int) :
    RuntimeException("$message (line $line, column $column)")

/** A tag the reader did not know. FET skips these with a warning; we report them so the caller can decide. */
public data class UnknownTag(val path: String, val tag: String, val line: Int, val column: Int)

public data class ReadResult(
    val document: FetDocument,
    val fileVersion: String,
    val unknownTags: List<UnknownTag>,
)

/** Reads a `.fet` file into a [FetDocument]. Mirrors the tag handling of `Rules::read()` in `rules.cpp`. */
public object FetReader {
    public fun read(file: File): ReadResult = read(file.readBytes())

    public fun read(text: String): ReadResult = read(text.removePrefix("﻿").toByteArray(Charsets.UTF_8))

    public fun read(bytes: ByteArray): ReadResult {
        val factory = XMLInputFactory.newInstance()
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes), "UTF-8")
        return try {
            Parser(reader).parse()
        } catch (e: javax.xml.stream.XMLStreamException) {
            val loc = e.location
            throw FetXmlException("Malformed XML: ${e.message?.lineSequence()?.firstOrNull()}", loc?.lineNumber ?: 0, loc?.columnNumber ?: 0)
        } finally {
            reader.close()
        }
    }

    private class Parser(private val r: XMLStreamReader) {
        private val doc = FetDocument()
        private val unknown = mutableListOf<UnknownTag>()
        private val path = ArrayDeque<String>()

        fun parse(): ReadResult {
            while (r.hasNext() && r.eventType != XMLStreamConstants.START_ELEMENT) r.next()
            if (r.eventType != XMLStreamConstants.START_ELEMENT) fail("Empty document")
            val root = r.localName
            if (root != "fet" && root != "FET") fail("Root tag must be <fet>, found <$root>")
            val version = r.getAttributeValue(null, "version") ?: ""
            doc.mode = modeFromLegacyVersion(version)
            var modeTagSeen = false
            children("fet") { name ->
                when (name) {
                    "Mode" -> {
                        val text = text()
                        doc.mode = Mode.fromXml(text) ?: fail("Incorrect mode '$text' - it has to be Official, Mornings_Afternoons, Block_Planning, or Terms")
                        modeTagSeen = true
                    }
                    "Institution_Name" -> doc.institution = text()
                    "Comments" -> doc.comments = text()
                    "Number_of_Terms" -> doc.terms = Terms(int(text(), name), doc.terms?.daysPerTerm ?: 0)
                    "Number_of_Days_Per_Term" -> doc.terms = Terms(doc.terms?.terms ?: 0, int(text(), name))
                    "Days_List" -> readNamedList(name, "Day", setOf("Number", "Number_of_Days"), doc.days)
                    "Hours_List" -> readNamedList(name, "Hour", setOf("Number", "Number_of_Hours"), doc.hours)
                    // FET keeps these names in the file and a school may have edited them, so read them back.
                    "Real_Days_List" -> readNamedList(name, "Real_Day", setOf("Number", "Number_of_Real_Days"), doc.storedRealDays)
                    "Real_Hours_List" -> readNamedList(name, "Real_Hour", setOf("Number", "Number_of_Real_Hours"), doc.storedRealHours)
                    "Subjects_List" -> children(name) { n -> if (n == "Subject") readSubject() else unknown(n) }
                    "Activity_Tags_List" -> children(name) { n -> if (n == "Activity_Tag") readActivityTag() else unknown(n) }
                    "Teachers_List" -> children(name) { n -> if (n == "Teacher") readTeacher() else unknown(n) }
                    "Students_List" -> children(name) { n -> if (n == "Year") readYear() else unknown(n) }
                    "Activities_List" -> children(name) { n -> if (n == "Activity") readActivity() else unknown(n) }
                    "Buildings_List" -> children(name) { n -> if (n == "Building") readBuilding() else unknown(n) }
                    "Rooms_List" -> children(name) { n -> if (n == "Room") readRoom() else unknown(n) }
                    "Time_Constraints_List" -> children(name) { n -> doc.timeConstraints += readConstraint(Family.TIME, n) }
                    "Space_Constraints_List" -> children(name) { n -> doc.spaceConstraints += readConstraint(Family.SPACE, n) }
                    "Timetable_Generation_Options_List" -> children(name) { n ->
                        if (n == "GroupActivitiesInInitialOrder") readGroupInInitialOrder() else unknown(n)
                    }
                    "Exception_Teachers_One_Day_List" -> readExceptionTeachers(name, 1)
                    "Exception_Teachers_Two_Days_List" -> readExceptionTeachers(name, 2)
                    "Exception_Teachers_Three_Days_List" -> readExceptionTeachers(name, 3)
                    "Exception_Teachers_Four_Days_List" -> readExceptionTeachers(name, 4)
                    "Exception_Teachers_Five_Days_List" -> readExceptionTeachers(name, 5)
                    else -> unknown(name)
                }
            }
            applyExceptionTeachers()
            if (doc.mode != Mode.TERMS) doc.terms = null
            if (doc.mode == Mode.TERMS && doc.terms == null) doc.terms = Terms(5, 5) // FET's default for Finland
            if (!modeTagSeen && doc.mode == Mode.MORNINGS_AFTERNOONS) {
                // Old files without a Mode tag: FET gives every teacher the unrestricted behaviour.
                doc.teachers.replaceAll { it.copy(morningsAfternoonsBehavior = it.morningsAfternoonsBehavior ?: MaBehavior.UNRESTRICTED) }
            }
            return ReadResult(doc, version, unknown)
        }

        // Only FET 5 files carry a mode in the version suffix. Same tests as rules.cpp, in the same order.
        private fun modeFromLegacyVersion(version: String): Mode {
            val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(.*)$").find(version) ?: return Mode.OFFICIAL
            val major = m.groupValues[1].toInt()
            if (major != 5) return Mode.OFFICIAL
            val suffix = m.groupValues[4]
            fun has(prefix: String): Boolean {
                if (suffix == prefix) return true
                if (suffix.length <= prefix.length || !suffix.startsWith(prefix)) return false
                val c = suffix[prefix.length]
                return c.isDigit() || c == '-' || c == '_'
            }
            return when {
                has("-ma") || has("-morocco") || has("-algeria") -> Mode.MORNINGS_AFTERNOONS
                has("-bp") -> Mode.BLOCK_PLANNING
                has("-mathmake") -> Mode.TERMS
                else -> Mode.OFFICIAL
            }
        }

        private fun readNamedList(listTag: String, itemTag: String, countTags: Set<String>, into: MutableList<NamedItem>) {
            var declared: Int? = null
            var countTag = countTags.first()
            children(listTag) { n ->
                when (n) {
                    in countTags -> { countTag = n; declared = int(text(), n) }
                    itemTag -> {
                        var name = ""
                        var longName = ""
                        children(n) { f ->
                            when (f) {
                                "Name" -> name = text()
                                "Long_Name" -> longName = text()
                                else -> unknown(f)
                            }
                        }
                        into += NamedItem(name, longName)
                    }
                    else -> unknown(n)
                }
            }
            checkCount(countTag, declared, into.size, itemTag)
        }

        private fun readSubject() {
            var name = ""; var longName = ""; var code = ""; var comments = ""
            children("Subject") { f ->
                when (f) {
                    "Name" -> name = text()
                    "Long_Name" -> longName = text()
                    "Code" -> code = text()
                    "Comments" -> comments = text()
                    else -> unknown(f)
                }
            }
            doc.subjects += Subject(name, longName, code, comments)
        }

        private fun readActivityTag() {
            var name = ""; var longName = ""; var code = ""; var comments = ""; var printable = true
            children("Activity_Tag") { f ->
                when (f) {
                    "Name" -> name = text()
                    "Long_Name" -> longName = text()
                    "Code" -> code = text()
                    // FET sets this true only for the exact text "true".
                    "Printable" -> printable = text() == "true"
                    "Comments" -> comments = text()
                    else -> unknown(f)
                }
            }
            doc.activityTags += ActivityTag(name, longName, code, printable, comments)
        }

        private fun readTeacher() {
            var name = ""; var longName = ""; var code = ""; var comments = ""
            var target = 0
            var behavior: MaBehavior? = null
            val qualified = mutableListOf<String>()
            children("Teacher") { f ->
                when (f) {
                    "Name" -> name = text()
                    "Long_Name" -> longName = text()
                    "Code" -> code = text()
                    "Target_Number_of_Hours" -> target = int(text(), f)
                    "Mornings_Afternoons_Behavior" -> {
                        val t = text()
                        behavior = MaBehavior.fromXml(t) ?: fail("Unknown Mornings_Afternoons_Behavior '$t' for teacher $name")
                    }
                    "Qualified_Subjects" -> children(f) { q -> if (q == "Qualified_Subject") qualified += text() else unknown(q) }
                    "Comments" -> comments = text()
                    else -> unknown(f)
                }
            }
            doc.teachers += Teacher(name, longName, code, target, qualified, behavior, comments)
        }

        private fun readBuilding() {
            var name = ""; var longName = ""; var code = ""; var comments = ""
            children("Building") { f ->
                when (f) {
                    "Name" -> name = text()
                    "Long_Name" -> longName = text()
                    "Code" -> code = text()
                    "Comments" -> comments = text()
                    else -> unknown(f)
                }
            }
            doc.buildings += Building(name, longName, code, comments)
        }

        private fun readRoom() {
            var name = ""; var longName = ""; var code = ""; var building = ""; var comments = ""
            var capacity = Room.DEFAULT_ROOM_CAPACITY
            var virtual = false
            var declaredSets: Int? = null
            val sets = mutableListOf<List<String>>()
            children("Room") { f ->
                when (f) {
                    "Name" -> name = text()
                    "Long_Name" -> longName = text()
                    "Code" -> code = text()
                    "Building" -> building = text()
                    "Capacity" -> capacity = int(text(), f)
                    // FET sets this true only for the exact text "true".
                    "Virtual" -> virtual = text() == "true"
                    "Number_of_Sets_of_Real_Rooms" -> declaredSets = int(text(), f)
                    "Set_of_Real_Rooms" -> {
                        var declaredRooms: Int? = null
                        val set = mutableListOf<String>()
                        children(f) { s ->
                            when (s) {
                                "Number_of_Real_Rooms" -> declaredRooms = int(text(), s)
                                "Real_Room" -> set += text()
                                else -> unknown(s)
                            }
                        }
                        checkCount("Number_of_Real_Rooms", declaredRooms, set.size, "Real_Room")
                        sets += set
                    }
                    "Comments" -> comments = text()
                    else -> unknown(f)
                }
            }
            checkCount("Number_of_Sets_of_Real_Rooms", declaredSets, sets.size, "Set_of_Real_Rooms")
            doc.rooms += Room(name, longName, code, building, capacity, virtual, sets, comments)
        }

        private fun readYear() {
            var name = ""; var longName = ""; var code = ""; var comments = ""; var number = 0
            var declaredCategories: Int? = null
            val categories = mutableListOf<List<String>>()
            var firstPermanent = false
            var separator = " "
            val groups = mutableListOf<Group>()
            children("Year") { f ->
                when (f) {
                    "Name" -> name = text()
                    "Long_Name" -> longName = text()
                    "Code" -> code = text()
                    "Number_of_Students" -> number = int(text(), f)
                    "Comments" -> comments = text()
                    "Number_of_Categories" -> declaredCategories = int(text(), f)
                    "Category" -> {
                        var declaredDivisions: Int? = null
                        val divisions = mutableListOf<String>()
                        children(f) { c ->
                            when (c) {
                                "Number_of_Divisions" -> declaredDivisions = int(text(), c)
                                "Division" -> divisions += text()
                                else -> unknown(c)
                            }
                        }
                        checkCount("Number_of_Divisions", declaredDivisions, divisions.size, "Division")
                        categories += divisions
                    }
                    "First_Category_Is_Permanent" -> firstPermanent = bool(text())
                    "Separator" -> separator = text()
                    "Group" -> groups += readGroup()
                    else -> unknown(f)
                }
            }
            checkCount("Number_of_Categories", declaredCategories, categories.size, "Category")
            doc.years += Year(name, longName, code, number, comments, categories, firstPermanent, separator, groups)
        }

        private fun readGroup(): Group {
            var name = ""; var longName = ""; var code = ""; var comments = ""; var number = 0
            val subgroups = mutableListOf<Subgroup>()
            children("Group") { f ->
                when (f) {
                    "Name" -> name = text()
                    "Long_Name" -> longName = text()
                    "Code" -> code = text()
                    "Number_of_Students" -> number = int(text(), f)
                    "Comments" -> comments = text()
                    "Subgroup" -> subgroups += readSubgroup()
                    else -> unknown(f)
                }
            }
            return Group(name, longName, code, number, comments, subgroups)
        }

        private fun readSubgroup(): Subgroup {
            var name = ""; var longName = ""; var code = ""; var comments = ""; var number = 0
            children("Subgroup") { f ->
                when (f) {
                    "Name" -> name = text()
                    "Long_Name" -> longName = text()
                    "Code" -> code = text()
                    "Number_of_Students" -> number = int(text(), f)
                    "Comments" -> comments = text()
                    else -> unknown(f)
                }
            }
            return Subgroup(name, longName, code, number, comments)
        }

        private fun readActivity() {
            val teachers = mutableListOf<String>()
            val tags = mutableListOf<String>()
            val students = mutableListOf<String>()
            var subject = ""; var comments = ""
            var duration = -1; var total = -1; var id = -1; var groupId = 0
            var active = true
            var numberOfStudents: Int? = null
            val line = r.location.lineNumber
            children("Activity") { f ->
                when (f) {
                    "Teacher" -> teachers += text()
                    "Subject" -> subject = text()
                    "Activity_Tag", "Subject_Tag" -> text().let { if (it.isNotEmpty()) tags += it }
                    "Students" -> students += text()
                    "Duration" -> duration = int(text(), f)
                    "Total_Duration" -> total = int(text(), f)
                    "Id" -> id = int(text(), f)
                    "Activity_Group_Id" -> groupId = int(text(), f)
                    "Number_of_Students", "Number_Of_Students" -> numberOfStudents = int(text(), f)
                    // FET accepts yes/true/1 here and treats anything else as inactive.
                    "Active" -> active = text() in setOf("yes", "true", "1")
                    "Comments" -> comments = text()
                    else -> unknown(f)
                }
            }
            if (id < 0) throw FetXmlException("Field Id not met in an activity", line, 0)
            if (duration < 0) throw FetXmlException("Field Duration not met in activity $id", line, 0)
            if (total < 0) total = duration
            if (doc.activity(id) != null) throw FetXmlException("Activity Id $id is incorrect (already existing)", line, 0)
            doc.activities += Activity(id, groupId, teachers, subject, tags, students, duration, total, active, numberOfStudents, comments)
        }

        private fun readGroupInInitialOrder() {
            var declared: Int? = null
            val ids = mutableListOf<Int>()
            var active = true; var comments = ""
            children("GroupActivitiesInInitialOrder") { f ->
                when (f) {
                    "Number_of_Activities" -> declared = int(text(), f)
                    "Activity_Id" -> ids += int(text(), f)
                    "Active" -> active = bool(text())
                    "Comments" -> comments = text()
                    else -> unknown(f)
                }
            }
            checkCount("Number_of_Activities", declared, ids.size, "Activity_Id")
            doc.generationOptions += GroupInInitialOrder(ids, active, comments)
        }

        /**
         * Generic constraint reader. Any child with children becomes an object, anything else a scalar.
         * FET always writes `<Number_of_X>n</Number_of_X>` right before the n repeated entries, so a count tag
         * marks the next child name as a list and the count is checked. Weight, Active and Comments are lifted out.
         */
        private fun readConstraint(family: Family, type: String): Constraint {
            val line = r.location.lineNumber
            var weight = 100.0
            var active = true
            var comments = ""
            val fields = readGenericFields(type, ConstraintSchema.get(type)?.fields) { name, text ->
                when (name) {
                    "Weight_Percentage" -> { weight = text.toDoubleOrNull() ?: fail("Weight_Percentage '$text' is not a number in $type"); true }
                    "Active" -> { active = bool(text); true }
                    "Comments" -> { comments = text; true }
                    else -> false
                }
            }
            if (type.startsWith("Constraint").not()) {
                unknown.add(UnknownTag(path.joinToString("/"), type, line, 0))
            }
            return ConstraintNormalizer.normalize(Constraint(family, type, weight, active, comments, fields))
        }

        /**
         * Old Morocco files list teachers with exception days in their own sections. FET turns them into
         * per-teacher behaviours: everyone becomes Exclusive, listed teachers get N exception days.
         */
        private fun readExceptionTeachers(listTag: String, days: Int) {
            val names = mutableListOf<String>()
            children(listTag) { n ->
                if (n == "Teacher") {
                    if (hasChildElements()) children(n) { f -> if (f == "Name") names += text() else unknown(f) }
                    else names += text()
                } else unknown(n)
            }
            exceptionTeachers += names.map { it to days }
        }

        private val exceptionTeachers = mutableListOf<Pair<String, Int>>()

        private fun applyExceptionTeachers() {
            if (exceptionTeachers.isEmpty()) return
            doc.mode = Mode.MORNINGS_AFTERNOONS
            doc.teachers.replaceAll { t ->
                val b = t.morningsAfternoonsBehavior
                if (b == null || b == MaBehavior.UNRESTRICTED) t.copy(morningsAfternoonsBehavior = MaBehavior.EXCLUSIVE) else t
            }
            for ((name, days) in exceptionTeachers) {
                val i = doc.teachers.indexOfFirst { it.name == name }
                if (i < 0) fail("Exception teacher $name is not in the teachers list")
                val behavior = MaBehavior.entries.first { it.exceptionDays == days && it != MaBehavior.EXCLUSIVE && it != MaBehavior.UNRESTRICTED }
                doc.teachers[i] = doc.teachers[i].copy(morningsAfternoonsBehavior = behavior)
            }
        }

        /**
         * Reads the children of the current element into an ordered field map. `lift` may claim a scalar and keep it
         * out of the map. `specs` is the schema for this element when it is known, which is what names an empty list.
         */
        private fun readGenericFields(
            owner: String,
            specs: List<FieldSpec>?,
            lift: (String, String) -> Boolean = { _, _ -> false },
        ): Map<String, FieldValue> {
            val fields = LinkedHashMap<String, FieldValue>()
            val listNames = HashMap<String, Int>() // list field name -> declared count
            var pendingCount: Pair<String, Int>? = null
            children(owner) { name ->
                if (name.startsWith("Number_of_") || name.startsWith("Number_Of_")) {
                    val countTag = name
                    val n = int(text(), countTag)
                    // An empty list has nothing after it, so close it here. Anything that follows is a later field.
                    if (n == 0) fields.putIfAbsent(listNameFor(countTag, specs), FieldValue.Items(emptyList()))
                    else pendingCount = countTag to n
                    return@children
                }
                val childSpecs = specs?.firstOrNull { it.name == name }?.let { if (it.kind == FieldKind.ARRAY) it.item?.fields else it.fields }
                val value: FieldValue = if (hasChildElements()) FieldValue.Obj(readGenericFields(name, childSpecs)) else {
                    val text = text()
                    if (pendingCount == null && lift(name, text)) return@children
                    FieldValue.Scalar(text)
                }
                pendingCount?.let { (_, n) ->
                    pendingCount = null
                    listNames[name] = n
                    fields.putIfAbsent(name, FieldValue.Items(emptyList()))
                }
                val existing = fields[name]
                fields[name] = when {
                    existing is FieldValue.Items -> FieldValue.Items(existing.items + value)
                    existing != null -> FieldValue.Items(listOf(existing, value))
                    else -> value
                }
            }
            pendingCount?.let { (countTag, n) -> checkCount(countTag, n, 0, listNameFor(countTag, specs)) }
            for ((name, declared) in listNames) {
                val actual = (fields[name] as? FieldValue.Items)?.items?.size ?: 0
                checkCount(countTagFor(name, specs), declared, actual, name)
            }
            return fields
        }

        /** The list field a count tag belongs to. The schema knows it; without one, fall back to dropping the plural. */
        private fun listNameFor(countTag: String, specs: List<FieldSpec>?): String {
            specs?.firstOrNull { it.countTag == countTag }?.let { return it.name }
            val base = countTag.removePrefix("Number_of_").removePrefix("Number_Of_")
            return when {
                base == "Activities" -> "Activity_Id"
                base.endsWith("ies") -> base.dropLast(3) + "y"
                base.endsWith("s") -> base.dropLast(1)
                else -> base
            }
        }

        private fun countTagFor(listName: String, specs: List<FieldSpec>?): String {
            specs?.firstOrNull { it.name == listName }?.countTag?.let { return it }
            return "Number_of_" + when {
                listName == "Activity_Id" -> "Activities"
                listName.endsWith("y") -> listName.dropLast(1) + "ies"
                else -> listName + "s"
            }
        }

        // ---- cursor helpers ----

        /** Iterates the child elements of the current start element. The block must consume each child fully. */
        private inline fun children(name: String, block: (String) -> Unit) {
            path.addLast(name)
            if (pendingStart) {
                // A lookahead already moved the cursor onto the first child's start tag.
                pendingStart = false
                block(r.localName)
            }
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> block(r.localName)
                    XMLStreamConstants.END_ELEMENT -> { path.removeLast(); return }
                }
            }
            fail("Unexpected end of document inside <$name>")
        }

        // StAX cannot peek, so a lookahead consumes events and leaves what it saw in these three flags.
        // children() and text() honour them before reading further.
        private var pendingStart = false
        private var pendingEnd = false
        private var pendingText: String? = null

        /** Does the current start element contain child elements? Consumes events up to the answer. */
        private fun hasChildElements(): Boolean {
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> { pendingText = null; pendingStart = true; return true }
                    XMLStreamConstants.END_ELEMENT -> { pendingEnd = true; return false }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (!r.isWhiteSpace) { pendingText = r.text; return false }
                        pendingText = (pendingText ?: "") + r.text
                    }
                }
            }
            fail("Unexpected end of document")
        }

        /** Reads the text of the current element and consumes its end tag. Works after a lookahead too. */
        private fun text(): String {
            if (pendingStart) fail("Expected text but found a child element <${r.localName}>")
            if (pendingEnd) { pendingEnd = false; val t = pendingText ?: ""; pendingText = null; return t }
            val sb = StringBuilder(pendingText ?: "")
            pendingText = null
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> sb.append(r.text)
                    XMLStreamConstants.END_ELEMENT -> return sb.toString()
                    XMLStreamConstants.START_ELEMENT -> fail("Expected text but found a child element <${r.localName}>")
                }
            }
            fail("Unexpected end of document")
        }

        /** Skips the current element entirely, including nested elements. */
        private fun skip() {
            var depth = 1
            if (pendingStart) { pendingStart = false; depth = 2 }
            if (pendingEnd) { pendingEnd = false; pendingText = null; return }
            pendingText = null
            while (r.hasNext() && depth > 0) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> depth++
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
        }

        private fun unknown(tag: String) {
            unknown.add(UnknownTag(path.joinToString("/"), tag, r.location.lineNumber, r.location.columnNumber))
            skip()
        }

        private fun checkCount(countTag: String, declared: Int?, actual: Int, itemTag: String) {
            if (declared != null && declared != actual) {
                fail("$countTag does not coincide with the number of $itemTag which were read ($declared declared, $actual found)")
            }
        }

        private fun int(text: String, tag: String): Int =
            text.trim().toIntOrNull() ?: fail("$tag is incorrect: '$text' is not a whole number")

        /** FET's rule for the `Active` flag on a constraint: only the exact text "false" turns it off. */
        private fun bool(text: String): Boolean = text != "false"

        private fun fail(message: String): Nothing =
            throw FetXmlException(message, r.location?.lineNumber ?: 0, r.location?.columnNumber ?: 0)
    }
}
