package fetmcp.mcp

import fetmcp.model.Mode
import fetmcp.schema.ConstraintSchema
import fetmcp.session.SessionException
import kotlinx.serialization.json.JsonObject

public data class ResourceSpec(val uri: String, val name: String, val description: String, val mimeType: String)

/** Read-only views a host can pin into context without a tool call. */
public object Resources {
    public fun all(): List<ResourceSpec> = listOf(
        ResourceSpec("fet://document", "Open .fet file", "The open document as FET writes it", "application/xml"),
        ResourceSpec("fet://state", "Document state", "Mode, week, counts, validation summary and the latest job", "application/json"),
        ResourceSpec("fet://schema/constraints", "Constraint schema", "Every FET constraint type with its fields and the modes it works in", "application/json"),
        ResourceSpec("fet://results/latest", "Latest result", "Summary of the last generation", "application/json"),
    ) + Mode.entries.map {
        ResourceSpec("fet://help/modes/${it.xml}", "Help: ${it.xml}", "What the ${it.xml} mode means", "text/markdown")
    }

    public val templates: List<ResourceSpec> = listOf(
        ResourceSpec("fet://schema/constraints/{type}", "One constraint type", "The fields of one constraint type", "application/json"),
        ResourceSpec("fet://help/modes/{mode}", "Mode help", "What one mode means", "text/markdown"),
    )

    public fun read(uri: String, tools: ToolRegistry): String {
        val session = tools.context.session
        return when {
            uri == "fet://document" -> if (session.isOpen) session.currentXml() else "No document is open. Call fet_open or fet_new."
            uri == "fet://state" -> tools.call("fet_state", JsonObject(emptyMap())).toText()
            uri == "fet://schema/constraints" -> prettyJson.encodeToString(
                JsonObject.serializer(),
                jsonOf(
                    "fet_version" to ConstraintSchema.fetVersion,
                    "count" to ConstraintSchema.types.size,
                    "types" to ConstraintSchema.types.values.map { t ->
                        jsonOf("name" to t.name, "family" to t.family.name.lowercase(), "description" to t.description, "modes" to stringsOf(t.modes.map { m -> m.xml }))
                    },
                ),
            )
            uri.startsWith("fet://schema/constraints/") -> {
                val name = uri.removePrefix("fet://schema/constraints/")
                tools.call("fet_constraint_types", jsonOf("search" to name, "include_schema" to true, "limit" to 5)).toText()
            }
            uri.startsWith("fet://help/modes/") -> {
                val mode = Mode.fromXml(uri.removePrefix("fet://help/modes/"))
                    ?: return "Unknown mode. The modes are: ${Mode.entries.joinToString { it.xml }}."
                ConstraintSchema.help(mode)
            }
            uri == "fet://results/latest" -> tools.call("fet_results_summary", JsonObject(emptyMap())).toText()
            else -> "Unknown resource: $uri"
        }
    }
}
