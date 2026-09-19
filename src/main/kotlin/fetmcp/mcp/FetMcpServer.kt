package fetmcp.mcp

import fetmcp.Config
import fetmcp.runner.JobRunner
import fetmcp.schema.ConstraintSchema
import fetmcp.session.DocumentSession
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

/**
 * Puts the whole tool surface behind one MCP server.
 * Everything is reachable without the SDK too, which is how the tests drive it.
 */
public class FetMcpServer(
    public val config: Config,
    public val session: DocumentSession = DocumentSession(config),
) {
    public val context: ToolContext = ToolContext(config, session, config.fetCl?.let { JobRunner(config) })

    public val tools: ToolRegistry = ToolRegistry(context).also {
        FileTools.register(it)
        StateTools.register(it)
        WeekTools.register(it)
        EntityTools.register(it)
        ActivityTools.register(it)
        ConstraintTools.register(it)
        HelperTools.register(it)
        ValidationTools.register(it)
        GenerationTools.register(it)
        ResultTools.register(it)
        LockTools.register(it)
        SnapshotTools.register(it)
    }

    public val resources: List<ResourceSpec> = Resources.all()
    public val prompts: List<PromptSpec> = Prompts.all()

    public fun callTool(name: String, arguments: JsonObject): String = tools.call(name, arguments).toText()

    public fun readResource(uri: String): String = Resources.read(uri, tools)

    public fun renderPrompt(name: String, arguments: Map<String, String>): String {
        val prompt = prompts.firstOrNull { it.name == name } ?: return "There is no prompt called '$name'."
        val missing = prompt.arguments.filter { it.required && arguments[it.name].isNullOrBlank() }
        if (missing.isNotEmpty()) return "The prompt '$name' needs: ${missing.joinToString { it.name }}."
        return prompt.render(arguments)
    }

    /** Builds the SDK server object with every tool, resource and prompt attached. */
    public fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "fet-mcp", version = VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
            ),
            instructions = instructions(),
        )
        for (spec in tools.specs) {
            server.addTool(
                name = spec.name,
                description = spec.description,
                inputSchema = ToolSchema(properties = spec.inputSchema.properties, required = spec.inputSchema.required),
                toolAnnotations = ToolAnnotations(readOnlyHint = !spec.mutating, destructiveHint = spec.mutating, openWorldHint = false),
            ) { request ->
                val response = tools.call(spec.name, request.arguments ?: JsonObject(emptyMap()))
                CallToolResult(content = listOf(TextContent(response.toText())), isError = !response.ok)
            }
        }
        for (resource in resources) {
            server.addResource(uri = resource.uri, name = resource.name, description = resource.description, mimeType = resource.mimeType) { request ->
                ReadResourceResult(contents = listOf(TextResourceContents(text = readResource(request.uri), uri = request.uri, mimeType = resource.mimeType)))
            }
        }
        for (template in Resources.templates) {
            server.addResourceTemplate(uriTemplate = template.uri, name = template.name, description = template.description, mimeType = template.mimeType) { request, _ ->
                ReadResourceResult(contents = listOf(TextResourceContents(text = readResource(request.uri), uri = request.uri, mimeType = template.mimeType)))
            }
        }
        for (prompt in prompts) {
            server.addPrompt(
                name = prompt.name,
                description = prompt.description,
                arguments = prompt.arguments.map { PromptArgument(name = it.name, description = it.description, required = it.required) },
            ) { request ->
                GetPromptResult(
                    description = prompt.description,
                    messages = listOf(PromptMessage(role = Role.User, content = TextContent(renderPrompt(prompt.name, request.arguments ?: emptyMap())))),
                )
            }
        }
        return server
    }

    private fun instructions(): String = """
        These tools drive FET, the free timetabling program, through its command line binary.

        How to work with them:
        - Call fet_state first. It tells you what is open, which mode it is in and what is missing.
        - Never write .fet XML yourself. Every change goes through a tool, and every change can be undone with fet_undo.
        - Before adding a constraint, find its exact type and fields with fet_constraint_types. FET has ${ConstraintSchema.types.size} types and
          guessing a name will fail. Count fields such as Number_of_Activities are written for you.
        - Weight 100 means a rule can never be broken. Lower weights are preferences. Too many rules at 100 make a timetable impossible.
        - Modes matter. In Mornings-Afternoons mode every real day is two FET days, a morning and an afternoon, and about a
          third of the constraint types exist only there. Read fet_explain_mode before working in a mode other than Official.
        - Generation runs in the background. Start it with fet_generate_start, watch it with fet_generate_status, read it with fet_results_summary.
          When it fails, fet_results_summary names the lesson that broke it. That lesson is where to look, nowhere else.

        The workspace is ${config.workspace.path}. FET version ${ConstraintSchema.fetVersion}.
    """.trimIndent()

    public companion object {
        public const val VERSION: String = "0.1.0"
    }
}
