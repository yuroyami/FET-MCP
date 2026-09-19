package fetmcp.mcp

import fetmcp.validate.Issue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Thrown by a tool to answer with one error. The registry turns it into the envelope. */
public class ToolFailure(
    public val code: String,
    message: String,
    public val where: Map<String, String> = emptyMap(),
) : RuntimeException(message)

@Serializable
public data class ToolError(val code: String, val message: String, val where: Map<String, String> = emptyMap())

/** What a tool handler returns on success. */
public data class ToolResult(val data: JsonElement, val warnings: List<Issue> = emptyList())

/** Every tool answers with this shape. `ok` false means nothing was changed. */
public data class ToolResponse(
    val ok: Boolean,
    val data: JsonElement,
    val warnings: List<Issue>,
    val errors: List<ToolError>,
) {
    public fun toJson(): JsonObject = buildJsonObject {
        put("ok", ok)
        put("data", data)
        put("warnings", buildJsonArray { for (w in warnings) add(json.encodeToJsonElement(Issue.serializer(), w)) })
        put("errors", buildJsonArray { for (e in errors) add(json.encodeToJsonElement(ToolError.serializer(), e)) })
    }

    public fun toText(): String = prettyJson.encodeToString(JsonObject.serializer(), toJson())
}
