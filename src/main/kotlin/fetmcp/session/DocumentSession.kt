package fetmcp.session

import fetmcp.Config
import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.Mode
import fetmcp.validate.Issue
import fetmcp.validate.Validator
import fetmcp.xml.FetReader
import fetmcp.xml.FetWriter
import fetmcp.xml.FetXmlException
import fetmcp.xml.UnknownTag
import java.io.File

/** A failure the session can explain: a stable code plus a plain message, sometimes with validation issues. */
public class SessionException(
    public val code: String,
    message: String,
    public val issues: List<Issue> = emptyList(),
) : RuntimeException(message)

public data class OpenReport(
    val path: String,
    val fileVersion: String,
    val mode: Mode,
    val unknownTags: List<UnknownTag>,
)

/**
 * The one open document. Disk is the source of truth: every successful mutation is written to the file.
 * Every mutation is snapshotted first so it can be undone.
 */
public class DocumentSession(public val config: Config) {
    private var current: FetDocument? = null
    private var currentFile: File? = null
    private var lastWriteSize: Long = -1
    private var lastWriteTime: Long = -1
    private val snapshots = Snapshots(config.snapshotLimit)

    public var fileVersion: String = FetWriter.DEFAULT_FET_VERSION
        private set

    public val isOpen: Boolean get() = current != null

    public val doc: FetDocument
        get() = current ?: throw SessionException("NO_DOCUMENT", "No document is open. Call fet_open or fet_new first.")

    public val file: File?
        get() = currentFile

    /** The file path shown to the AI: relative to the workspace. */
    public val relativePath: String?
        get() = currentFile?.let { config.workspace.toPath().relativize(it.toPath()).toString() }

    /** Turns a workspace relative path into a file, refusing anything that escapes the workspace. */
    public fun resolve(relative: String): File {
        val candidate = File(relative).let { if (it.isAbsolute) it else File(config.workspace, relative) }.absoluteFile.normalize()
        val root = config.workspace.absoluteFile.normalize()
        if (!candidate.toPath().startsWith(root.toPath())) {
            throw SessionException("OUT_OF_WORKSPACE", "Path '$relative' is outside the workspace ${root.path}")
        }
        return candidate
    }

    public fun new(relative: String, mode: Mode, institution: String, comments: String): File {
        val target = resolve(relative)
        if (target.exists()) throw SessionException("FILE_EXISTS", "File '$relative' already exists. Use fet_open, or pick another name.")
        val doc = FetDocument(mode)
        doc.institution = institution
        doc.comments = comments
        doc.timeConstraints += Constraint(Family.TIME, "ConstraintBasicCompulsoryTime")
        doc.spaceConstraints += Constraint(Family.SPACE, "ConstraintBasicCompulsorySpace")
        if (mode == Mode.TERMS) doc.terms = fetmcp.model.Terms(5, 5)
        target.parentFile?.mkdirs()
        install(doc, target, FetWriter.DEFAULT_FET_VERSION, "created")
        write()
        return target
    }

    public fun open(relative: String): OpenReport {
        val target = resolve(relative)
        if (!target.isFile) throw SessionException("FILE_NOT_FOUND", "No file at '$relative' in the workspace")
        val result = try {
            FetReader.read(target)
        } catch (e: FetXmlException) {
            throw SessionException("FILE_UNREADABLE", "Cannot read '$relative': ${e.message}")
        }
        install(result.document, target, result.fileVersion, "opened")
        remember(target)
        return OpenReport(relative, result.fileVersion, result.document.mode, result.unknownTags)
    }

    /** Writes the document. With a path, writes a copy there and keeps working on it from then on. */
    public fun save(relative: String? = null): File {
        val doc = this.doc
        if (relative != null) {
            val target = resolve(relative)
            target.parentFile?.mkdirs()
            currentFile = target
        } else {
            requireNotStale()
        }
        write()
        return currentFile!!
    }

    /** Every path that overwrites the open file goes through this first, so a GUI edit is never lost. */
    private fun requireNotStale() {
        if (isStale()) {
            throw SessionException("STALE_FILE", "The file on disk changed since the last write (edited in the FET GUI?). Call fet_open again before changing it.")
        }
    }

    public fun currentXml(): String = FetWriter.write(doc)

    /** Forgets the open document without touching the file. Used when an open is rejected after reading. */
    public fun close() {
        current = null
        currentFile = null
        lastWriteSize = -1
        lastWriteTime = -1
    }

    /** Replaces the whole document, for example with a generated result file. Snapshotted like any change. */
    public fun replace(description: String, replacement: FetDocument) {
        doc
        requireNotStale()
        val xml = FetWriter.write(replacement)
        writeXml(xml)
        current = replacement
        snapshots.commit(description, xml)
    }

    /**
     * Runs a change on a copy, refuses it if it introduces structural errors, then commits, writes and snapshots.
     * Errors that were already in the file do not block the change.
     */
    public fun <T> mutate(description: String, block: (FetDocument) -> T): T {
        val before = this.doc
        requireNotStale()
        val working = before.copy()
        val result = block(working)
        val known = Validator.errors(before).map { it.key() }.toSet()
        val introduced = Validator.errors(working).filter { it.code in STRUCTURAL && it.key() !in known }
        if (introduced.isNotEmpty()) {
            throw SessionException("VALIDATION", "Change '$description' refused:\n" + introduced.joinToString("\n") { "- " + it.message }, introduced)
        }
        // Write first. If the disk write fails, the in-memory document and the history stay as they were.
        val xml = FetWriter.write(working)
        writeXml(xml)
        current = working
        snapshots.commit(description, xml)
        return result
    }

    /** Restores the state before the last N mutations. Returns the description of the last one undone. */
    public fun undo(steps: Int = 1): String {
        doc
        requireNotStale()
        if (!snapshots.canUndo(steps)) throw SessionException("NOTHING_TO_UNDO", "Cannot undo $steps step(s). ${snapshots.history().count { it.applied }} step(s) can be undone.")
        // Read and write before the cursor moves, so a failure leaves the history where it was.
        val (description, xml) = snapshots.peekUndo(steps)
        restore(xml)
        snapshots.undo(steps)
        return description
    }

    public fun redo(steps: Int = 1): String {
        doc
        requireNotStale()
        if (!snapshots.canRedo(steps)) throw SessionException("NOTHING_TO_REDO", "Cannot redo $steps step(s). ${snapshots.history().count { !it.applied }} step(s) can be redone.")
        val (description, xml) = snapshots.peekRedo(steps)
        restore(xml)
        snapshots.redo(steps)
        return description
    }

    public fun history(): List<HistoryEntry> = snapshots.history()

    /** True when someone else wrote the file since our last write. Checked by size and modification time. */
    public fun isStale(): Boolean {
        val f = currentFile ?: return false
        if (!f.exists()) return true
        return f.length() != lastWriteSize || f.lastModified() != lastWriteTime
    }

    // ---- internals ----

    private fun install(doc: FetDocument, target: File, version: String, description: String) {
        current = doc
        currentFile = target
        fileVersion = version
        snapshots.reset(description, FetWriter.write(doc))
    }

    private fun restore(xml: String) {
        val restored = FetReader.read(xml).document
        writeXml(xml)
        current = restored
    }

    private fun write() {
        writeXml(FetWriter.write(doc))
    }

    private fun writeXml(xml: String) {
        val target = currentFile ?: return
        target.writeBytes(FetWriter.BOM + xml.toByteArray(Charsets.UTF_8))
        remember(target)
    }

    private fun remember(target: File) {
        lastWriteSize = target.length()
        lastWriteTime = target.lastModified()
    }

    private fun Issue.key(): String = code + "|" + where.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private companion object {
        /** Errors a change may never introduce. Completeness errors (no activities yet, no rooms yet) are checked at generation instead. */
        val STRUCTURAL = setOf(
            "EMPTY_NAME", "DUPLICATE_NAME", "REFERENCE_MISSING", "INVALID_ACTIVITY_ID", "DUPLICATE_ACTIVITY_ID",
            "GROUP_ID_INVALID", "TOTAL_DURATION_MISMATCH", "DURATION_TOO_LONG", "INVALID_VALUE", "INVALID_WEIGHT",
            "UNKNOWN_CONSTRAINT_TYPE", "FAMILY_MISMATCH", "MODE_FORBIDS", "FIELD_MISSING",
            "MA_ODD_DAYS", "TERMS_MISMATCH", "LIMIT_EXCEEDED", "BASIC_CONSTRAINT_MISSING",
        )
        // VIRTUAL_ROOM_INVALID is left out on purpose: removing a real room can empty a set, exactly as it does in
        // FET. The removal goes through and fet_validate reports the room, rather than the edit being impossible.
    }
}
