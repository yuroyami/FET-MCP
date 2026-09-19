package fetmcp.mcp

import fetmcp.Config
import fetmcp.runner.JobException
import fetmcp.runner.JobRunner
import fetmcp.session.DocumentSession
import fetmcp.session.SessionException
import fetmcp.validate.Issue
import fetmcp.xml.FetXmlException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal val json: Json = Json { encodeDefaults = true }
internal val prettyJson: Json = Json { prettyPrint = true; encodeDefaults = true }

/** What every tool handler gets: the config, the open document, and the job runner when fet-cl is available. */
public class ToolContext(
    public val config: Config,
    public val session: DocumentSession,
    public val runner: JobRunner?,
) {
    public fun requireRunner(): JobRunner = runner
        ?: throw ToolFailure("NO_FET_CL", "FET_CL_PATH is not set, so timetables cannot be generated. Build fet-cl and point FET_CL_PATH at it.")
}

public data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: InputSchema,
    val mutating: Boolean,
    val handler: (Args) -> ToolResult,
)

/** Typed access to a tool's arguments. Every miss is a clear INVALID_ARGUMENT failure. */
public class Args(private val raw: JsonObject, public val context: ToolContext) {
    public val session: DocumentSession get() = context.session
    public val keys: Set<String> get() = raw.keys

    public operator fun contains(name: String): Boolean = raw[name] != null && raw[name] !is JsonNull

    private fun element(name: String): JsonElement? = raw[name]?.takeIf { it !is JsonNull }

    private fun fail(name: String, expected: String): Nothing =
        throw ToolFailure("INVALID_ARGUMENT", "Argument '$name' is missing or is not $expected", mapOf("argument" to name))

    public fun str(name: String): String = element(name)?.jsonPrimitive?.contentOrNullSafe() ?: fail(name, "a string")
    public fun strOrNull(name: String): String? = element(name)?.jsonPrimitive?.contentOrNullSafe()
    public fun str(name: String, default: String): String = strOrNull(name) ?: default

    public fun int(name: String): Int = element(name)?.jsonPrimitive?.intOrNull ?: fail(name, "a whole number")
    public fun intOrNull(name: String): Int? = element(name)?.jsonPrimitive?.intOrNull
    public fun int(name: String, default: Int): Int = intOrNull(name) ?: default

    public fun num(name: String): Double = element(name)?.jsonPrimitive?.doubleOrNull ?: fail(name, "a number")
    public fun numOrNull(name: String): Double? = element(name)?.jsonPrimitive?.doubleOrNull
    public fun num(name: String, default: Double): Double = numOrNull(name) ?: default

    public fun bool(name: String, default: Boolean): Boolean = element(name)?.jsonPrimitive?.booleanOrNull ?: default
    public fun boolOrNull(name: String): Boolean? = element(name)?.jsonPrimitive?.booleanOrNull

    public fun array(name: String): JsonArray = element(name) as? JsonArray ?: fail(name, "a list")
    public fun arrayOrEmpty(name: String): JsonArray = element(name) as? JsonArray ?: JsonArray(emptyList())
    public fun strings(name: String): List<String> = array(name).map { it.jsonPrimitive.contentOrNullSafe() ?: fail(name, "a list of strings") }
    public fun stringsOrEmpty(name: String): List<String> = arrayOrEmpty(name).map { it.jsonPrimitive.contentOrNullSafe() ?: fail(name, "a list of strings") }
    public fun ints(name: String): List<Int> = array(name).map { it.jsonPrimitive.intOrNull ?: fail(name, "a list of whole numbers") }
    public fun intsOrEmpty(name: String): List<Int> = arrayOrEmpty(name).map { it.jsonPrimitive.intOrNull ?: fail(name, "a list of whole numbers") }
    public fun longsOrEmpty(name: String): List<Long> = arrayOrEmpty(name).map { it.jsonPrimitive.longOrNull ?: fail(name, "a list of whole numbers") }
    public fun objects(name: String): List<Args> = array(name).map { Args(it as? JsonObject ?: fail(name, "a list of objects"), context) }
    public fun objectsOrEmpty(name: String): List<Args> = arrayOrEmpty(name).map { Args(it as? JsonObject ?: fail(name, "a list of objects"), context) }
    public fun obj(name: String): Args = Args(element(name) as? JsonObject ?: fail(name, "an object"), context)
    public fun objOrNull(name: String): Args? = (element(name) as? JsonObject)?.let { Args(it, context) }
    public fun rawElement(name: String): JsonElement? = element(name)

    /** An enum value by name, accepting spaces for underscores and any letter case. */
    public fun <T : Enum<T>> enum(name: String, values: Array<T>, default: T? = null): T {
        val text = strOrNull(name) ?: return default ?: fail(name, "one of ${values.joinToString { it.name }}")
        return values.firstOrNull { it.name.equals(text.replace(' ', '_'), ignoreCase = true) }
            ?: throw ToolFailure("INVALID_ARGUMENT", "Argument '$name' must be one of ${values.joinToString { it.name }}, got '$text'", mapOf("argument" to name))
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
}

/** Holds the tool table and runs calls, turning any failure into the standard envelope. */
public class ToolRegistry(public val context: ToolContext) {
    private val map = LinkedHashMap<String, ToolSpec>()

    public val specs: List<ToolSpec> get() = map.values.toList()

    public fun register(spec: ToolSpec) {
        require(map.put(spec.name, spec) == null) { "tool ${spec.name} is registered twice" }
    }

    public fun tool(
        name: String,
        description: String,
        mutating: Boolean = false,
        inputSchema: InputSchema = InputSchema(JsonObject(emptyMap()), emptyList()),
        handler: (Args) -> ToolResult,
    ) {
        register(ToolSpec(name, description, inputSchema, mutating, handler))
    }

    public fun call(name: String, arguments: JsonObject): ToolResponse {
        val spec = map[name] ?: return failure(ToolError("UNKNOWN_TOOL", "There is no tool called '$name'"))
        return try {
            val result = spec.handler(Args(arguments, context))
            ToolResponse(ok = true, data = result.data, warnings = result.warnings, errors = emptyList())
        } catch (e: ToolFailure) {
            failure(ToolError(e.code, e.message ?: e.code, e.where))
        } catch (e: SessionException) {
            ToolResponse(false, JsonObject(emptyMap()), emptyList(), listOf(ToolError(e.code, e.message ?: e.code)) + e.issues.map { it.asError() })
        } catch (e: JobException) {
            failure(ToolError(e.code, e.message ?: e.code))
        } catch (e: FetXmlException) {
            failure(ToolError("FILE_UNREADABLE", e.message ?: "The file could not be read"))
        } catch (e: IllegalArgumentException) {
            failure(ToolError("INVALID_ARGUMENT", e.message ?: "Invalid argument"))
        } catch (e: Exception) {
            failure(ToolError("INTERNAL_ERROR", "${e::class.simpleName}: ${e.message}"))
        }
    }

    private fun failure(error: ToolError) = ToolResponse(false, JsonObject(emptyMap()), emptyList(), listOf(error))

    private fun Issue.asError() = ToolError(code, message, where)
}
