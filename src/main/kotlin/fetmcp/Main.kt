package fetmcp

import fetmcp.mcp.FetMcpServer
import fetmcp.schema.ConstraintSchema
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Entry point. Speaks MCP over stdin and stdout, so nothing but protocol may ever be printed to stdout.
 * Every message for a human goes to stderr.
 */
public fun main() {
    // Stdout is the MCP channel and must carry nothing but protocol. Take a private handle on the real stdout for the
    // transport, then point System.out at stderr so a stray println from any library cannot corrupt the stream.
    val protocolOut = FileOutputStream(FileDescriptor.out)
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getenv("FET_LOG_LEVEL") ?: "warn")
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")

    val config = try {
        Config.fromEnv()
    } catch (e: IllegalStateException) {
        System.err.println("fet-mcp: ${e.message}")
        System.err.println("Set FET_WORKSPACE to the directory holding your .fet files, and FET_CL_PATH to the fet-cl binary.")
        exitProcess(2)
    }
    if (!config.workspace.isDirectory) {
        System.err.println("fet-mcp: the workspace ${config.workspace.path} is not a directory")
        exitProcess(2)
    }
    checkFetCl(config)

    val app = FetMcpServer(config)
    System.err.println("fet-mcp ${FetMcpServer.VERSION}: ${app.tools.specs.size} tools, workspace ${config.workspace.path}, FET ${ConstraintSchema.fetVersion}")

    val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())
    runBlocking {
        val session = app.buildServer().createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}

/** Warns when fet-cl is missing or built from another FET version than the schema was generated from. */
private fun checkFetCl(config: Config) {
    val fetCl: File? = config.fetCl
    if (fetCl == null) {
        System.err.println("fet-mcp: FET_CL_PATH is not set. Editing works, generating does not.")
        return
    }
    if (!fetCl.canExecute()) {
        System.err.println("fet-mcp: ${fetCl.path} is not an executable file. Generating will fail.")
        return
    }
    // Read on a side thread and give up after 20 seconds. A binary that waits for input must never hang the server.
    val version = runCatching {
        val process = ProcessBuilder(fetCl.path, "--version")
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
        val text = StringBuilder()
        val reader = Thread { runCatching { text.append(process.inputStream.bufferedReader().readText()) } }
        reader.isDaemon = true
        reader.start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly()
        reader.join(2000)
        Regex("FET version ([0-9][0-9A-Za-z.\\-]*)").find(text.toString())?.groupValues?.get(1)
    }.getOrNull()
    when {
        version == null -> System.err.println("fet-mcp: could not read the version of ${fetCl.path}. Carrying on.")
        version != ConstraintSchema.fetVersion && !config.allowVersionMismatch -> {
            System.err.println("fet-mcp: fet-cl is version $version but the constraint schema was generated from ${ConstraintSchema.fetVersion}.")
            System.err.println("Regenerate it with: ./gradlew schemagen -PfetSrc=<fet sources>, or set FET_ALLOW_VERSION_MISMATCH=true.")
            exitProcess(3)
        }
        version != ConstraintSchema.fetVersion -> System.err.println("fet-mcp: version mismatch allowed: fet-cl $version, schema ${ConstraintSchema.fetVersion}.")
    }
}
