package fetmcp.mcp

import fetmcp.model.Constraint
import fetmcp.model.FieldValue
import fetmcp.validate.Issue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) put(key, value.toJsonElement())
}

internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(name)
    is Map<*, *> -> buildJsonObject { for ((k, v) in this@toJsonElement) put(k.toString(), v.toJsonElement()) }
    is Iterable<*> -> buildJsonArray { for (item in this@toJsonElement) add(item.toJsonElement()) }
    is Issue -> json.encodeToJsonElement(Issue.serializer(), this)
    else -> JsonPrimitive(toString())
}

internal fun stringsOf(values: Iterable<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

/** Constraint field values as JSON: scalars stay text, lists become arrays, nested groups become objects. */
internal fun FieldValue.toJson(): JsonElement = when (this) {
    is FieldValue.Scalar -> JsonPrimitive(text)
    is FieldValue.Items -> buildJsonArray { items.forEach { add(it.toJson()) } }
    is FieldValue.Obj -> buildJsonObject { fields.forEach { (k, v) -> put(k, v.toJson()) } }
}

/** The reverse: JSON from a tool call into constraint field values. Numbers and booleans become their FET text. */
internal fun JsonElement.toFieldValue(): FieldValue = when (this) {
    is JsonArray -> FieldValue.Items(map { it.toFieldValue() })
    is JsonObject -> FieldValue.Obj(LinkedHashMap<String, FieldValue>().also { out -> forEach { (k, v) -> out[k] = v.toFieldValue() } })
    is JsonNull -> FieldValue.Scalar("")
    else -> FieldValue.Scalar(jsonPrimitive.content)
}

internal fun Constraint.toJson(includeFields: Boolean = true): JsonObject = buildJsonObject {
    put("ref", ref)
    put("type", type)
    put("family", family.name)
    put("weight", weight)
    put("active", active)
    if (comments.isNotEmpty()) put("comments", comments)
    if (includeFields) put("fields", buildJsonObject { fields.forEach { (k, v) -> put(k, v.toJson()) } })
}
