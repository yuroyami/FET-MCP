package fetmcp.session

import java.time.Instant

public data class HistoryEntry(val index: Int, val description: String, val applied: Boolean, val at: String)

/**
 * The document's states, one per mutation, with a cursor for undo and redo.
 * State 0 is the file as opened or created. Each commit appends the state after a mutation.
 */
public class Snapshots(private val limit: Int) {
    private class State(val description: String, val xml: String, val at: Instant)

    private val states = ArrayList<State>()
    private var cursor = -1

    public val size: Int get() = states.size

    public fun reset(description: String, xml: String) {
        states.clear()
        states += State(description, xml, Instant.now())
        cursor = 0
    }

    /** Records the state after a mutation. Anything undone before this point is dropped. */
    public fun commit(description: String, xml: String) {
        while (states.size > cursor + 1) states.removeAt(states.size - 1)
        states += State(description, xml, Instant.now())
        cursor = states.size - 1
        while (states.size > limit + 1) { states.removeAt(0); cursor-- }
    }

    public fun canUndo(steps: Int): Boolean = steps in 1..cursor
    public fun canRedo(steps: Int): Boolean = steps >= 1 && cursor + steps <= states.size - 1

    /** What an undo would give, without moving. Call [undo] once the restore succeeded. */
    public fun peekUndo(steps: Int): Pair<String, String> {
        require(canUndo(steps)) { "cannot undo $steps step(s)" }
        return states[cursor - steps + 1].description to states[cursor - steps].xml
    }

    /** What a redo would give, without moving. Call [redo] once the restore succeeded. */
    public fun peekRedo(steps: Int): Pair<String, String> {
        require(canRedo(steps)) { "cannot redo $steps step(s)" }
        return states[cursor + steps].description to states[cursor + steps].xml
    }

    /** Moves back. Returns the description of the last mutation undone and the xml to restore. */
    public fun undo(steps: Int): Pair<String, String> {
        val result = peekUndo(steps)
        cursor -= steps
        return result
    }

    /** Moves forward. Returns the description of the last mutation redone and the xml to restore. */
    public fun redo(steps: Int): Pair<String, String> {
        val result = peekRedo(steps)
        cursor += steps
        return result
    }

    public fun history(): List<HistoryEntry> = states.drop(1).mapIndexed { i, s ->
        HistoryEntry(index = i + 1, description = s.description, applied = i + 1 <= cursor, at = s.at.toString())
    }

    public fun currentXml(): String? = states.getOrNull(cursor)?.xml
}
