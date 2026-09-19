package fetmcp.model

import java.security.MessageDigest

public enum class Family(public val listTag: String, public val refPrefix: String) {
    TIME("Time_Constraints_List", "t"),
    SPACE("Space_Constraints_List", "s"),
}

/** A constraint field value as read from or written to XML. Text is kept as text; the schema says what it means. */
public sealed interface FieldValue {
    public data class Scalar(val text: String) : FieldValue
    public data class Items(val items: List<FieldValue>) : FieldValue
    public data class Obj(val fields: Map<String, FieldValue>) : FieldValue

    public companion object {
        public fun of(text: String): Scalar = Scalar(text)
        public fun of(number: Number): Scalar = Scalar(number.toString())
        public fun of(flag: Boolean): Scalar = Scalar(if (flag) "true" else "false")
        public fun list(vararg items: FieldValue): Items = Items(items.toList())
        public fun strings(items: Iterable<String>): Items = Items(items.map { Scalar(it) })
        public fun obj(vararg pairs: Pair<String, FieldValue>): Obj = Obj(linkedMapOf(*pairs))
    }
}

/**
 * One time or space constraint. `type` is the exact FET tag name, for example `ConstraintTeacherMaxDaysPerWeek`.
 * `fields` holds everything except the three fields every constraint has: weight, active, comments.
 */
public data class Constraint(
    val family: Family,
    val type: String,
    val weight: Double = 100.0,
    val active: Boolean = true,
    val comments: String = "",
    val fields: Map<String, FieldValue> = emptyMap(),
) {
    /** Stable id: family prefix plus 8 hex chars of SHA-1 over a canonical, order independent text form. */
    val ref: String by lazy {
        val digest = MessageDigest.getInstance("SHA-1").digest(canonical().toByteArray(Charsets.UTF_8))
        family.refPrefix + ":" + digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    private fun canonical(): String = buildString {
        append(type).append('|').append(weight).append('|').append(active).append('|').append(comments).append('|')
        appendValue(FieldValue.Obj(fields))
    }

    private fun StringBuilder.appendValue(value: FieldValue) {
        when (value) {
            is FieldValue.Scalar -> append('"').append(value.text.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            is FieldValue.Items -> {
                append('[')
                value.items.forEachIndexed { i, item -> if (i > 0) append(','); appendValue(item) }
                append(']')
            }
            is FieldValue.Obj -> {
                append('{')
                value.fields.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(',')
                    append(k).append(':'); appendValue(v)
                }
                append('}')
            }
        }
    }

    public fun scalar(name: String): String? = (fields[name] as? FieldValue.Scalar)?.text
    public fun items(name: String): List<FieldValue> = (fields[name] as? FieldValue.Items)?.items ?: emptyList()
    public fun scalars(name: String): List<String> = items(name).mapNotNull { (it as? FieldValue.Scalar)?.text }
}
