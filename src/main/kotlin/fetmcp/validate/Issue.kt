package fetmcp.validate

import kotlinx.serialization.Serializable

public enum class Severity { ERROR, WARNING }

/** One validation finding. `where` names the things involved: teacher, activity, constraint ref, and so on. */
@Serializable
public data class Issue(
    val severity: Severity,
    val code: String,
    val message: String,
    val where: Map<String, String> = emptyMap(),
) {
    public companion object {
        public fun error(code: String, message: String, vararg where: Pair<String, String>): Issue =
            Issue(Severity.ERROR, code, message, mapOf(*where))

        public fun warning(code: String, message: String, vararg where: Pair<String, String>): Issue =
            Issue(Severity.WARNING, code, message, mapOf(*where))
    }
}
