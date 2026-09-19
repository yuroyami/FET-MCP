package fetmcp

import java.io.File

/** Server settings. Read from environment variables at startup, built directly in tests. */
public data class Config(
    /** The one directory the server may read and write. */
    val workspace: File,
    /** Absolute path to the fet-cl binary. Null means generation tools are unavailable. */
    val fetCl: File?,
    val language: String = "en_US",
    val defaultTimeLimitSeconds: Int = 600,
    val allowVersionMismatch: Boolean = false,
    val snapshotLimit: Int = 200,
) {
    public companion object {
        public fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val workspace = env["FET_WORKSPACE"]?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: error("FET_WORKSPACE is not set. It must point at the directory holding the .fet files.")
            return Config(
                workspace = workspace.absoluteFile.normalize(),
                fetCl = env["FET_CL_PATH"]?.takeIf { it.isNotBlank() }?.let { File(it) },
                language = env["FET_LANGUAGE"]?.takeIf { it.isNotBlank() } ?: "en_US",
                defaultTimeLimitSeconds = env["FET_DEFAULT_TIME_LIMIT"]?.toIntOrNull() ?: 600,
                allowVersionMismatch = env["FET_ALLOW_VERSION_MISMATCH"] == "true",
            )
        }
    }
}
