package fetmcp.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A tool's input schema in the two parts the MCP SDK wants. */
public data class InputSchema(val properties: JsonObject, val required: List<String>) {
    public fun asObject(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}

/** Small JSON schema builder. Enough for tool inputs, no more. */
public class SchemaBuilder {
    private val props = LinkedHashMap<String, JsonObject>()
    private val required = ArrayList<String>()

    private fun add(name: String, required: Boolean, obj: JsonObject) {
        props[name] = obj
        if (required) this.required += name
    }

    public fun str(name: String, description: String, required: Boolean = false, enum: List<String>? = null) {
        add(name, required, buildJsonObject {
            put("type", "string"); put("description", description)
            if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
        })
    }

    public fun int(name: String, description: String, required: Boolean = false, minimum: Int? = null, maximum: Int? = null) {
        add(name, required, buildJsonObject {
            put("type", "integer"); put("description", description)
            if (minimum != null) put("minimum", minimum)
            if (maximum != null) put("maximum", maximum)
        })
    }

    public fun num(name: String, description: String, required: Boolean = false) {
        add(name, required, buildJsonObject { put("type", "number"); put("description", description) })
    }

    public fun bool(name: String, description: String, required: Boolean = false) {
        add(name, required, buildJsonObject { put("type", "boolean"); put("description", description) })
    }

    public fun arr(name: String, description: String, items: JsonObject, required: Boolean = false) {
        add(name, required, buildJsonObject { put("type", "array"); put("description", description); put("items", items) })
    }

    public fun obj(name: String, description: String, required: Boolean = false, block: SchemaBuilder.() -> Unit) {
        val inner = SchemaBuilder().apply(block).build()
        add(name, required, buildJsonObject {
            put("type", "object"); put("description", description)
            put("properties", inner.properties)
            if (inner.required.isNotEmpty()) put("required", buildJsonArray { inner.required.forEach { add(JsonPrimitive(it)) } })
        })
    }

    /** Any JSON value. Used where the shape depends on data the schema cannot know, such as constraint fields. */
    public fun any(name: String, description: String, required: Boolean = false) {
        add(name, required, buildJsonObject { put("description", description) })
    }

    public fun build(): InputSchema = InputSchema(JsonObject(props), required.toList())
}

public fun schema(block: SchemaBuilder.() -> Unit): InputSchema = SchemaBuilder().apply(block).build()

public fun itemsStr(description: String = ""): JsonObject = buildJsonObject { put("type", "string"); if (description.isNotEmpty()) put("description", description) }
public fun itemsInt(description: String = ""): JsonObject = buildJsonObject { put("type", "integer"); if (description.isNotEmpty()) put("description", description) }
public fun itemsObj(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build().asObject()
public fun itemsAny(): JsonObject = JsonObject(emptyMap())

internal fun JsonArray.strings(): List<String> = map { it.toString().trim('"') }
