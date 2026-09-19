package fetmcp.schema

import fetmcp.model.Family
import fetmcp.model.Mode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public enum class FieldKind { SCALAR, ARRAY, OBJECT }

public enum class ValueType { STRING, NUMBER, BOOL }

/** What a string field points at, so the validator can check the name exists. */
public enum class RefKind { TEACHER, STUDENTS, SUBJECT, ACTIVITY_TAG, ROOM, BUILDING, DAY, HOUR, ACTIVITY }

/**
 * One XML field of a constraint, in write order.
 * SCALAR: `value` and maybe `ref`. ARRAY: `item` describes one entry and `countTag` the `Number_of_*` tag FET writes first.
 * OBJECT: `fields`. `optional` means FET only writes the tag in some cases.
 */
@Serializable
public data class FieldSpec(
    val name: String,
    val kind: FieldKind,
    val value: ValueType? = null,
    val ref: RefKind? = null,
    val optional: Boolean = false,
    val countTag: String? = null,
    val item: FieldSpec? = null,
    val fields: List<FieldSpec> = emptyList(),
)

@Serializable
public data class ConstraintType(
    val name: String,
    val family: Family,
    val modes: Set<Mode>,
    val description: String,
    val fields: List<FieldSpec>,
    /**
     * How many activities this type needs to stay meaningful. FET deletes the constraint when its activity list
     * falls below this. Null when the type has no activity list. Taken from `Rules::updateConstraintsAfterRemoval`.
     */
    val minActivities: Int? = null,
    /**
     * True for the few types FET only deletes once *every* activity set has emptied, written with `&&` in
     * `Rules::updateConstraintsAfterRemoval`. False means one empty set is enough, which is the usual rule.
     */
    val keepWhileAnyActivityList: Boolean = false,
) {
    /** Name split at camel case, for search: "Constraint Teacher Max Gaps Per Week". */
    val words: String by lazy { name.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ") }
}

@Serializable
public data class SchemaFile(val fetVersion: String, val types: Map<String, ConstraintType>)

/** The generated constraint schema, loaded once from `fet/constraints.schema.json`. */
public object ConstraintSchema {
    private val json = Json { ignoreUnknownKeys = true }

    private val file: SchemaFile by lazy {
        val text = ConstraintSchema::class.java.getResourceAsStream("/fet/constraints.schema.json")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("Missing resource fet/constraints.schema.json. Run: ./gradlew schemagen")
        json.decodeFromString(SchemaFile.serializer(), text)
    }

    public val fetVersion: String get() = file.fetVersion
    public val types: Map<String, ConstraintType> get() = file.types

    public fun get(name: String): ConstraintType? = types[name]

    public fun allowedIn(mode: Mode): List<ConstraintType> = types.values.filter { mode in it.modes }

    /**
     * Every query word must appear in the name, the name split at camel case, or the description.
     * A whole type name pasted in works because the unsplit name is searched too. `mode` and `family` narrow the result.
     */
    public fun search(query: String, mode: Mode? = null, family: Family? = null): List<ConstraintType> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return types.values.filter { t ->
            (mode == null || mode in t.modes) &&
                (family == null || t.family == family) &&
                words.all { w -> w in t.name.lowercase() || w in t.words.lowercase() || w in t.description.lowercase() }
        }
    }

    public fun help(mode: Mode): String =
        ConstraintSchema::class.java.getResourceAsStream("/fet/help/${mode.xml}.md")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: "No help text for mode ${mode.xml}."
}
