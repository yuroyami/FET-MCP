package fetmcp.mcp

import fetmcp.model.Mode
import kotlinx.serialization.json.buildJsonArray

/** fet_new, fet_open, fet_save. */
public object FileTools {
    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_new",
            description = "Create a new empty .fet file in the workspace and open it. Adds the two basic compulsory constraints FET needs.",
            mutating = true,
            inputSchema = schema {
                str("path", "File path inside the workspace, for example school.fet", required = true)
                str("mode", "Timetabling mode", required = true, enum = Mode.entries.map { it.xml })
                str("institution", "Name of the school")
                str("comments", "Free notes stored in the file")
            },
        ) { args ->
            val mode = Mode.fromXml(args.str("mode"))
                ?: throw ToolFailure("INVALID_ARGUMENT", "mode must be one of ${Mode.entries.joinToString { it.xml }}", mapOf("argument" to "mode"))
            val path = args.str("path")
            args.session.new(path, mode, args.str("institution", "Default institution"), args.str("comments", ""))
            ToolResult(
                jsonOf(
                    "file" to args.session.relativePath,
                    "mode" to mode.xml,
                    "next_steps" to stringsOf(
                        listOf(
                            "fet_set_week to name the days and hours" + if (mode == Mode.MORNINGS_AFTERNOONS) " (pass real_days and halves in this mode)" else "",
                            "fet_add_subjects, fet_add_teachers, fet_add_years, fet_add_rooms",
                            "fet_add_activities, then fet_validate",
                        ),
                    ),
                ),
            )
        }

        tools.tool(
            name = "fet_open",
            description = "Open an existing .fet file from the workspace. Reports the FET version it was written with and any tags this server does not know.",
            mutating = true,
            inputSchema = schema {
                str("path", "File path inside the workspace", required = true)
                bool("allow_unknown", "Open even when the file has tags this server does not know (default false)")
            },
        ) { args ->
            val report = args.session.open(args.str("path"))
            if (report.unknownTags.isNotEmpty() && !args.bool("allow_unknown", false)) {
                val list = report.unknownTags.take(10).joinToString("; ") { "${it.tag} at line ${it.line}" }
                args.session.close()
                throw ToolFailure(
                    "UNKNOWN_TAGS",
                    "The file has ${report.unknownTags.size} tag(s) this server does not know: $list. " +
                        "Saving would drop them. Pass allow_unknown true to open anyway.",
                    mapOf("count" to report.unknownTags.size.toString()),
                )
            }
            val doc = args.session.doc
            ToolResult(
                jsonOf(
                    "file" to report.path,
                    "file_version" to report.fileVersion,
                    "mode" to report.mode.xml,
                    "institution" to doc.institution,
                    "counts" to StateTools.counts(doc),
                    "unknown_tags" to buildJsonArray {
                        for (t in report.unknownTags) add(jsonOf("tag" to t.tag, "path" to t.path, "line" to t.line, "column" to t.column))
                    },
                ),
            )
        }

        tools.tool(
            name = "fet_save",
            description = "Write the open document to disk. Every change is saved automatically, so use this only to checkpoint or to save a copy under another name.",
            mutating = true,
            inputSchema = schema { str("path", "Save a copy here and keep working on the copy. Leave empty to write the current file.") },
        ) { args ->
            val file = args.session.save(args.strOrNull("path"))
            ToolResult(jsonOf("file" to args.session.relativePath, "bytes" to file.length()))
        }
    }
}
