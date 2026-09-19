package fetmcp.mcp

/** fet_undo and fet_redo. */
public object SnapshotTools {
    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_undo",
            description = "Take back the last change or the last few. Every tool that changes the document can be undone.",
            mutating = true,
            inputSchema = schema { int("steps", "How many changes to take back (default 1)", minimum = 1) },
        ) { args ->
            val steps = args.int("steps", 1)
            val undone = args.session.undo(steps)
            ToolResult(
                jsonOf(
                    "undone" to undone,
                    "steps" to steps,
                    "undo_available" to args.session.history().count { it.applied },
                    "redo_available" to args.session.history().count { !it.applied },
                ),
            )
        }

        tools.tool(
            name = "fet_redo",
            description = "Put back a change that was undone. A new change after an undo drops what could be redone.",
            mutating = true,
            inputSchema = schema { int("steps", "How many changes to put back (default 1)", minimum = 1) },
        ) { args ->
            val steps = args.int("steps", 1)
            val redone = args.session.redo(steps)
            ToolResult(
                jsonOf(
                    "redone" to redone,
                    "steps" to steps,
                    "undo_available" to args.session.history().count { it.applied },
                    "redo_available" to args.session.history().count { !it.applied },
                ),
            )
        }
    }
}
