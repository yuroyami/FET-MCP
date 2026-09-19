package fetmcp.schemagen

import fetmcp.model.Family
import fetmcp.model.Mode
import fetmcp.schema.ConstraintType
import fetmcp.schema.FieldKind
import fetmcp.schema.FieldSpec
import fetmcp.schema.RefKind
import fetmcp.schema.SchemaFile
import fetmcp.schema.ValueType
import kotlinx.serialization.json.Json
import java.io.File

/** Everything the generator learned from the FET sources. */
public data class Generated(
    val fetVersion: String,
    val types: Map<String, ConstraintType>,
    /** Mode xml name to help text. */
    val help: Map<String, String>,
)

/**
 * Reads the FET C++ sources and derives the constraint schema and the mode help texts.
 * Run once per FET release: `./gradlew schemagen -PfetSrc=_upstream/fet-7.10.4`.
 */
public object SchemaGen {
    public fun generate(fetSrc: File): Generated {
        val version = File(fetSrc, "VERSION").readText().trim()
        val engine = File(fetSrc, "src/engine")
        val timeSource = File(engine, "timeconstraint.cpp").readText()
        val spaceSource = File(engine, "spaceconstraint.cpp").readText()
        val rulesSource = File(engine, "rules.cpp").readText()
        val enumToClass = constructorTypeMap(timeSource) + constructorTypeMap(spaceSource)
        val minima = minimumActivities(rulesSource, enumToClass)
        val andForms = andFormTypes(rulesSource, enumToClass)
        val types = LinkedHashMap<String, ConstraintType>()
        types += parseFamily(timeSource, "TimeConstraint", Family.TIME, minima, andForms)
        types += parseFamily(spaceSource, "SpaceConstraint", Family.SPACE, minima, andForms)
        return Generated(version, types, HelpTexts.extract(File(fetSrc, "src/interface")))
    }

    /**
     * How many activities each constraint type needs, read from `Rules::updateConstraintsAfterRemoval`.
     * That function pairs `case CONSTRAINT_X:` with `if(c->n_activities<N)` or `if(c->activitiesIds.count()<N)`,
     * and deletes the constraint when it falls below N.
     */
    private fun minimumActivities(rulesSource: String, enumToClass: Map<String, String>): Map<String, Int> {
        val start = rulesSource.indexOf("void Rules::updateConstraintsAfterRemoval(")
        require(start >= 0) { "Missing Rules::updateConstraintsAfterRemoval in rules.cpp" }
        val body = functionBody(rulesSource, rulesSource.indexOf('{', start) + 1)
        val out = LinkedHashMap<String, Int>()
        var current: String? = null
        val case = Regex("case (CONSTRAINT_\\w+):")
        val test = Regex("if\\(\\s*(?:c->)?(?:n_activities|activitiesIds\\.count\\(\\)|ids\\.count\\(\\))\\s*<\\s*(\\d+)\\s*\\)")
        for (line in body.lineSequence()) {
            case.find(line)?.let { current = it.groupValues[1] }
            test.find(line)?.let { m ->
                val className = current?.let { enumToClass[it] } ?: return@let
                out.putIfAbsent(className, m.groupValues[1].toInt())
            }
        }
        return out
    }

    public fun write(generated: Generated, outDir: File) {
        val json = Json { prettyPrint = true; encodeDefaults = false }
        outDir.mkdirs()
        File(outDir, "constraints.schema.json").writeText(json.encodeToString(SchemaFile.serializer(), SchemaFile(generated.fetVersion, generated.types)))
        val helpDir = File(outDir, "help").also { it.mkdirs() }
        for ((mode, text) in generated.help) File(helpDir, "$mode.md").writeText(text)
    }

    /**
     * Types whose removal test joins two emptiness checks with `&&`, meaning FET keeps the constraint while any
     * of its activity sets still has entries. Everything else drops as soon as one set empties.
     */
    private fun andFormTypes(rulesSource: String, enumToClass: Map<String, String>): Set<String> {
        val start = rulesSource.indexOf("void Rules::updateConstraintsAfterRemoval(")
        require(start >= 0) { "Missing Rules::updateConstraintsAfterRemoval in rules.cpp" }
        val body = functionBody(rulesSource, rulesSource.indexOf('{', start) + 1)
        val out = LinkedHashSet<String>()
        var current: String? = null
        val case = Regex("case (CONSTRAINT_\\w+):")
        val andTest = Regex("if\\(.*(?:count\\(\\)\\s*<\\s*\\d+|isEmpty\\(\\)).*&&.*(?:count\\(\\)\\s*<\\s*\\d+|isEmpty\\(\\)).*\\)")
        for (line in body.lineSequence()) {
            case.find(line)?.let { current = it.groupValues[1] }
            if (andTest.containsMatchIn(line)) current?.let { enumToClass[it] }?.let { out += it }
        }
        return out
    }

    private fun parseFamily(source: String, baseClass: String, family: Family, minima: Map<String, Int>, andForms: Set<String>): Map<String, ConstraintType> {
        val enumToClass = constructorTypeMap(source)
        val modes = Mode.entries.associateWith { mode -> allowedEnums(source, baseClass, mode).mapNotNull { enumToClass[it] }.toSet() }
        val out = LinkedHashMap<String, ConstraintType>()
        for (m in Regex("^QString (Constraint\\w+)::getXmlDescription\\([^)]*\\)\\s*\\{", RegexOption.MULTILINE).findAll(source)) {
            val name = m.groupValues[1]
            val body = functionBody(source, m.range.last + 1)
            val fields = XmlBodyParser(body).fields()
            val allowed = Mode.entries.filter { name in modes.getValue(it) }.toSet()
            val hasActivityList = fields.any { hasActivityList(it) }
            out[name] = ConstraintType(
                name, family, allowed, description(source, name), fields,
                minActivities = if (hasActivityList) minima[name] ?: 1 else null,
                keepWhileAnyActivityList = name in andForms,
            )
        }
        return out
    }

    /** `ConstraintX::ConstraintX(...)` constructors assign `this->type=CONSTRAINT_Y;`. Map Y to X. */
    private fun constructorTypeMap(source: String): Map<String, String> {
        val map = HashMap<String, String>()
        var current: String? = null
        for (line in source.lineSequence()) {
            Regex("^(Constraint\\w+)::\\1\\(").find(line)?.let { current = it.groupValues[1] }
            // Seen as `this->type=X;`, `type=X;` and `type = X;` across the two files.
            Regex("\\btype\\s*=\\s*(CONSTRAINT_\\w+)\\s*;").find(line)?.let { m -> current?.let { map[m.groupValues[1]] = it } }
        }
        return map
    }

    /** The `case CONSTRAINT_X:` labels before `t=true;` in `canBeUsedIn<Mode>Mode()`. */
    private fun allowedEnums(source: String, baseClass: String, mode: Mode): List<String> {
        val fnName = "canBeUsedIn" + when (mode) {
            Mode.OFFICIAL -> "Official"
            Mode.MORNINGS_AFTERNOONS -> "MorningsAfternoons"
            Mode.BLOCK_PLANNING -> "BlockPlanning"
            Mode.TERMS -> "Terms"
        } + "Mode"
        val start = source.indexOf("bool $baseClass::$fnName()")
        require(start >= 0) { "Missing $baseClass::$fnName in source" }
        val body = functionBody(source, source.indexOf('{', start) + 1)
        val allowedPart = body.substringBefore("t=true;")
        return Regex("case (CONSTRAINT_\\w+):").findAll(allowedPart).map { it.groupValues[1] }.toList()
    }

    /** First `tr("...")` assigned to `s` in `getDescription`, which is FET's one line name for the constraint. */
    private fun description(source: String, name: String): String {
        val start = source.indexOf("QString $name::getDescription(")
        if (start < 0) return name
        val body = functionBody(source, source.indexOf('{', start) + 1)
        val m = Regex("QString s=tr\\(\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            ?: Regex("tr\\(\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            ?: return name
        return CppText.unescape(m.groupValues[1])
    }

    /** An activity list, at this level or nested inside a wrapper object such as `First_Activities_Ids_Set`. */
    private fun hasActivityList(spec: FieldSpec): Boolean =
        (spec.kind == FieldKind.ARRAY && spec.item?.ref == RefKind.ACTIVITY) ||
            spec.fields.any { hasActivityList(it) } ||
            (spec.item?.fields?.any { hasActivityList(it) } ?: false)

    /** Text from `start` (just after the opening brace) to the matching closing brace, braces inside strings ignored. */
    internal fun functionBody(source: String, start: Int): String {
        var depth = 1
        var i = start
        var inString = false
        while (i < source.length) {
            val c = source[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return source.substring(start, i) }
                '/' -> if (source.startsWith("//", i)) { i = source.indexOf('\n', i).let { if (it < 0) source.length else it } }
                    else if (source.startsWith("/*", i)) { i = source.indexOf("*/", i) + 1 }
            }
            i++
        }
        return source.substring(start)
    }
}

internal object CppText {
    fun unescape(literal: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c == '\\' && i + 1 < literal.length) {
                when (val n = literal[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}

/**
 * Turns one `getXmlDescription` body into field specs.
 * The body is tokenised into string literals, helper names, `for`/`if`/`else`, braces and semicolons.
 * Then a stack replays the tags in write order: an open tag starts a frame, a close tag ends it,
 * `for` marks arrays, `if` marks optional fields, `Number_of_*` tags become count tags.
 */
internal class XmlBodyParser(body: String) {
    private sealed interface Tok {
        data class Str(val text: String) : Tok
        data class Ident(val name: String) : Tok
        data object Open : Tok
        data object Close : Tok
        data object Semi : Tok
        data object Cond : Tok
    }

    private val tokens: List<Tok> = tokenize(body)
    private var pos = 0

    private class Frame(val tag: String, val loopDepth: Int) {
        val children = ArrayList<FieldSpec>()
        var number = false
        var bool = false
        var ref: RefKind? = null
        var pendingCount: String? = null
    }

    private val root = Frame("", 0)
    private val stack = ArrayDeque<Frame>().apply { addLast(root) }
    private var loopDepth = 0
    private var optionalDepth = 0

    private val common = setOf("Weight_Percentage", "Active", "Comments")

    /** The fields inside the outer `<ConstraintX>` element, minus the three every constraint has. */
    fun fields(): List<FieldSpec> {
        parseStatements(untilClose = false)
        val outer = root.children.singleOrNull()?.takeIf { it.kind == FieldKind.OBJECT }
        val inner = outer?.fields ?: root.children
        return inner.filter { it.name !in common }
    }

    private fun parseStatements(untilClose: Boolean) {
        while (pos < tokens.size) {
            when (val t = tokens[pos]) {
                is Tok.Close -> { if (untilClose) { pos++; return } else pos++ }
                is Tok.Open -> { pos++; parseStatements(untilClose = true) }
                is Tok.Ident -> when (t.name) {
                    "for", "while" -> { pos++; skipCond(); loopDepth++; parseOneStatementOrBlock(); loopDepth-- }
                    "if" -> { pos++; skipCond(); optionalDepth++; parseOneStatementOrBlock(); optionalDepth-- }
                    "else" -> { pos++; optionalDepth++; parseOneStatementOrBlock(); optionalDepth-- }
                    else -> parseSimpleStatement()
                }
                else -> parseSimpleStatement()
            }
        }
    }

    private fun skipCond() { if (pos < tokens.size && tokens[pos] is Tok.Cond) pos++ }

    private fun parseOneStatementOrBlock() {
        if (pos >= tokens.size) return
        when (val t = tokens[pos]) {
            is Tok.Open -> { pos++; parseStatements(untilClose = true) }
            is Tok.Ident -> if (t.name == "for" || t.name == "while" || t.name == "if") parseStatements1() else parseSimpleStatement()
            else -> parseSimpleStatement()
        }
    }

    /** A nested control statement as the single body of another control statement. */
    private fun parseStatements1() {
        val t = tokens[pos] as Tok.Ident
        pos++
        skipCond()
        if (t.name == "if") { optionalDepth++; parseOneStatementOrBlock(); optionalDepth-- }
        else { loopDepth++; parseOneStatementOrBlock(); loopDepth-- }
    }

    /** Tokens up to the next semicolon: emit tag events and value hints in order. */
    private fun parseSimpleStatement() {
        while (pos < tokens.size) {
            val t = tokens[pos++]
            when (t) {
                is Tok.Semi -> return
                is Tok.Str -> handleLiteral(t.text)
                is Tok.Ident -> handleIdent(t.name)
                is Tok.Open, is Tok.Close -> { pos--; return }
                is Tok.Cond -> {}
            }
        }
    }

    private fun handleIdent(name: String) {
        val frame = stack.last()
        if (frame === root) return
        when (name) {
            "number" -> frame.number = true
            "trueFalse" -> frame.bool = true
            "daysOfTheWeek", "realDaysOfTheWeek" -> frame.ref = RefKind.DAY
            "hoursOfTheDay", "realHoursOfTheDay" -> frame.ref = RefKind.HOUR
        }
    }

    private fun handleLiteral(text: String) {
        // Some booleans are written as bare "true" / "false" literals instead of trueFalse().
        if ((text == "true" || text == "false") && stack.last() !== root) stack.last().bool = true
        for (m in Regex("</?([A-Za-z_][A-Za-z0-9_]*)>").findAll(text)) {
            val tag = m.groupValues[1]
            val closing = m.value.startsWith("</")
            if (tag.startsWith("Number_of_") || tag.startsWith("Number_Of_")) {
                if (!closing) stack.last().pendingCount = tag
                continue
            }
            if (!closing) stack.addLast(Frame(tag, loopDepth))
            else closeFrame(tag)
        }
    }

    private fun closeFrame(tag: String) {
        val frame = stack.removeLast()
        check(frame.tag == tag) { "Tag mismatch: opened <${frame.tag}> but closed </$tag>" }
        val parent = stack.last()
        val optional = optionalDepth > 0
        var spec = if (frame.children.isNotEmpty()) {
            FieldSpec(name = tag, kind = FieldKind.OBJECT, optional = optional, fields = frame.children)
        } else {
            val value = when {
                frame.bool -> ValueType.BOOL
                frame.number -> ValueType.NUMBER
                else -> ValueType.STRING
            }
            FieldSpec(name = tag, kind = FieldKind.SCALAR, value = value, ref = frame.ref ?: refByName(tag, value), optional = optional)
        }
        if (frame.loopDepth > parent.loopDepth) {
            spec = FieldSpec(name = tag, kind = FieldKind.ARRAY, optional = optional, countTag = parent.pendingCount, item = spec.copy(optional = false))
            parent.pendingCount = null
        }
        addMerged(parent.children, spec)
    }

    /** Same tag written on two code paths (if/else): keep one field, mark it optional, prefer the typed variant. */
    private fun addMerged(list: MutableList<FieldSpec>, spec: FieldSpec) {
        val i = list.indexOfFirst { it.name == spec.name && it.kind == spec.kind }
        if (i < 0) { list += spec; return }
        val old = list[i]
        val value = listOfNotNull(old.value, spec.value).firstOrNull { it != ValueType.STRING } ?: old.value ?: spec.value
        list[i] = old.copy(optional = true, value = value, ref = old.ref ?: spec.ref, countTag = old.countTag ?: spec.countTag)
    }

    private fun refByName(tag: String, value: ValueType): RefKind? = when {
        value == ValueType.NUMBER && tag.endsWith("Activity_Id") -> RefKind.ACTIVITY
        value != ValueType.STRING -> null
        tag == "Teacher" || tag == "Teacher_Name" -> RefKind.TEACHER
        tag == "Students" || tag == "Students_Name" -> RefKind.STUDENTS
        tag == "Subject" || tag == "Subject_Name" -> RefKind.SUBJECT
        tag.endsWith("Activity_Tag") || tag == "Activity_Tag_Name" -> RefKind.ACTIVITY_TAG
        tag == "Room" || tag == "Preferred_Room" || tag == "Real_Room" -> RefKind.ROOM
        tag == "Building" -> RefKind.BUILDING
        tag == "Day" || tag == "Preferred_Day" || tag == "Interval_Start_Day" || tag == "Interval_End_Day" -> RefKind.DAY
        tag == "Hour" || tag == "Preferred_Hour" || tag == "Interval_Start_Hour" || tag == "Interval_End_Hour" -> RefKind.HOUR
        else -> null
    }

    private fun tokenize(body: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        var lastIdent: String? = null
        while (i < body.length) {
            val c = body[i]
            when {
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < body.length && body[i] != '"') {
                        if (body[i] == '\\' && i + 1 < body.length) { sb.append(body[i]).append(body[i + 1]); i += 2 } else { sb.append(body[i]); i++ }
                    }
                    i++
                    out += Tok.Str(CppText.unescape(sb.toString()))
                    lastIdent = null
                }
                c == '{' -> { out += Tok.Open; i++; lastIdent = null }
                c == '}' -> { out += Tok.Close; i++; lastIdent = null }
                c == ';' -> { out += Tok.Semi; i++; lastIdent = null }
                c == '(' && (lastIdent == "for" || lastIdent == "if" || lastIdent == "while") -> {
                    var depth = 0
                    while (i < body.length) {
                        if (body[i] == '(') depth++ else if (body[i] == ')') { depth--; if (depth == 0) { i++; break } }
                        i++
                    }
                    out += Tok.Cond
                    lastIdent = null
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < body.length && (body[i].isLetterOrDigit() || body[i] == '_')) i++
                    val ident = body.substring(start, i)
                    out += Tok.Ident(ident)
                    lastIdent = ident
                }
                body.startsWith("//", i) -> { i = body.indexOf('\n', i).let { if (it < 0) body.length else it } }
                body.startsWith("/*", i) -> { i = body.indexOf("*/", i).let { if (it < 0) body.length else it + 2 } }
                else -> i++
            }
        }
        return out
    }
}

/** Pulls the mode help texts out of the GUI help dialogs, resolving literal `.arg()` values. */
internal object HelpTexts {
    fun extract(interfaceDir: File): Map<String, String> {
        val chooser = statements(File(interfaceDir, "getmodefornewfileform.cpp").readText())
        fun intro(prefix: String) = chooser.firstOrNull { it.startsWith(prefix) } ?: ""
        val algeria = statements(File(interfaceDir, "helpalgeriaform.cpp").readText())
        val morocco = statements(File(interfaceDir, "helpmoroccoform.cpp").readText())
        val terms = statements(File(interfaceDir, "helptermsform.cpp").readText())
        val bp = statements(File(interfaceDir, "helpblockplanningform.cpp").readText())
        fun join(parts: List<String>) = parts.joinToString("").trim() + "\n"
        return linkedMapOf(
            Mode.OFFICIAL.xml to intro("Mode: official") + "\n",
            Mode.MORNINGS_AFTERNOONS.xml to intro("Mode: mornings-afternoons") + "\n\n## Unrestricted teachers (Algeria)\n\n" + join(algeria) + "\n## Exclusive teachers (Morocco)\n\n" + join(morocco),
            Mode.BLOCK_PLANNING.xml to intro("Mode: block-planning") + "\n\n" + join(bp),
            Mode.TERMS.xml to intro("Mode: terms") + "\n\n" + join(terms),
        )
    }

    /** Every `s+=...;` statement rendered to text, in order. Also picks up the `tr("Mode: ...")` chooser strings. */
    private fun statements(source: String): List<String> {
        val out = ArrayList<String>()
        for (stmt in splitStatements(source)) {
            val trimmed = stmt.trim()
            val isAppend = trimmed.startsWith("s+=")
            val isModeString = trimmed.contains("tr(\"Mode: ")
            if (!isAppend && !isModeString) continue
            val text = render(trimmed) ?: continue
            if (isModeString && !isAppend) out += text else out += text
        }
        return out
    }

    private fun splitStatements(source: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inString = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < source.length) { sb.append(source[i + 1]); i++ } else if (c == '"') inString = false
            } else when {
                c == '"' -> { inString = true; sb.append(c) }
                c == ';' -> { out += sb.toString(); sb.clear() }
                source.startsWith("//", i) -> { i = source.indexOf('\n', i).let { if (it < 0) source.length else it }; continue }
                else -> sb.append(c)
            }
            i++
        }
        return out
    }

    /** Text of one statement: literals before the first `.arg(` joined, `%n` replaced by literal args. */
    private fun render(stmt: String): String? {
        val literals = literalsWithOffsets(stmt)
        if (literals.isEmpty()) return null
        val firstArg = stmt.indexOf(".arg(")
        val trStart = stmt.indexOf("tr(")
        val textLits = ArrayList<String>()
        var textEnd = if (firstArg >= 0) firstArg else stmt.length
        if (trStart >= 0 && (firstArg < 0 || trStart < firstArg)) {
            // Only the first argument of tr(...) is the text; a second literal is a translator comment.
            val close = matchingParen(stmt, stmt.indexOf('(', trStart))
            val comma = topLevelComma(stmt, stmt.indexOf('(', trStart) + 1, close)
            textEnd = minOf(textEnd, if (comma >= 0) comma else close)
        }
        for ((offset, lit) in literals) if (offset < textEnd) textLits += lit
        var text = textLits.joinToString("")
        if (text.isEmpty() && textLits.isEmpty()) return null
        var argIndex = 1
        var search = firstArg
        while (search >= 0) {
            val open = stmt.indexOf('(', search)
            val close = matchingParen(stmt, open)
            val inner = stmt.substring(open + 1, close)
            val value = literalsWithOffsets(inner).firstOrNull()?.second
            if (value != null) text = text.replace("%$argIndex", value)
            argIndex++
            search = stmt.indexOf(".arg(", close)
        }
        return text
    }

    private fun literalsWithOffsets(s: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '"') {
                val start = i
                val sb = StringBuilder()
                i++
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i]).append(s[i + 1]); i += 2 } else { sb.append(s[i]); i++ }
                }
                out += start to CppText.unescape(sb.toString())
            }
            i++
        }
        return out
    }

    private fun matchingParen(s: String, open: Int): Int {
        var depth = 0
        var i = open
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) { if (c == '\\') i++ else if (c == '"') inString = false }
            else when (c) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return s.length
    }

    private fun topLevelComma(s: String, from: Int, to: Int): Int {
        var depth = 0
        var i = from
        var inString = false
        while (i < to) {
            val c = s[i]
            if (inString) { if (c == '\\') i++ else if (c == '"') inString = false }
            else when (c) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) return i
            }
            i++
        }
        return -1
    }
}

public fun main(args: Array<String>) {
    require(args.size == 2) { "usage: schemagen <fet source dir> <output resources dir>" }
    val generated = SchemaGen.generate(File(args[0]))
    SchemaGen.write(generated, File(args[1]))
    System.err.println("schemagen: ${generated.types.size} constraint types, FET ${generated.fetVersion}, help texts for ${generated.help.keys}")
}
