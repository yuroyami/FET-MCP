package fetmcp.results

import fetmcp.model.FetDocument
import fetmcp.model.Mode
import fetmcp.runner.JobState
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** One activity's place in a generated timetable, joined with the activity data it was generated from. */
public data class Placement(
    val id: Int,
    val day: String?,
    val hour: String?,
    val room: String?,
    val realRooms: List<String>,
    val dayIndex: Int?,
    val hourIndex: Int?,
    /** Mornings-Afternoons only. */
    val realDay: String?,
    /** "morning" or "afternoon" in Mornings-Afternoons mode. */
    val half: String?,
    val teachers: List<String>,
    val subject: String,
    val students: List<String>,
    val tags: List<String>,
    val duration: Int,
) {
    val placed: Boolean get() = day != null && hour != null

    public companion object {
        public fun of(doc: FetDocument, id: Int, day: String?, hour: String?, room: String?, realRooms: List<String>): Placement {
            val activity = doc.activity(id)
            val dayIndex = day?.let { d -> doc.days.indexOfFirst { it.name == d }.takeIf { it >= 0 } }
            val hourIndex = hour?.let { h -> doc.hours.indexOfFirst { it.name == h }.takeIf { it >= 0 } }
            val ma = doc.mode == Mode.MORNINGS_AFTERNOONS && dayIndex != null
            return Placement(
                id = id, day = day, hour = hour, room = room, realRooms = realRooms,
                dayIndex = dayIndex, hourIndex = hourIndex,
                realDay = if (ma) doc.realDays().getOrNull(dayIndex!! / 2)?.name else null,
                half = if (ma) (if (dayIndex!! % 2 == 0) "morning" else "afternoon") else null,
                teachers = activity?.teachers ?: emptyList(),
                subject = activity?.subject ?: "",
                students = activity?.students ?: emptyList(),
                tags = activity?.tags ?: emptyList(),
                duration = activity?.duration ?: 1,
            )
        }
    }
}

public data class Conflict(val weight: Double?, val text: String, val activityIds: List<Int>)

public data class DifficultActivity(val order: Int, val id: Int, val description: String)

public data class LogMessage(val title: String, val message: String)

/** Reads what fet-cl leaves behind: placements, soft conflicts, difficult activities, log messages, file lists. */
public object ResultReader {
    /** The timetable folder for a job state: the full result, or the best partial one FET reached. */
    public fun timetableDir(jobDir: File, stem: String, state: JobState): File? = when (state) {
        JobState.SUCCEEDED -> File(jobDir, "timetables/$stem").takeIf { it.isDirectory }
        JobState.IMPOSSIBLE, JobState.TIME_EXCEEDED, JobState.INTERRUPTED ->
            File(jobDir, "timetables/$stem-highest").takeIf { it.isDirectory } ?: File(jobDir, "timetables/$stem-current").takeIf { it.isDirectory }
        else -> null
    }

    public fun placements(dir: File, stem: String, doc: FetDocument): List<Placement> {
        val file = File(dir, "${stem}_activities.xml")
        if (!file.isFile) return emptyList()
        val out = ArrayList<Placement>()
        val factory = XMLInputFactory.newInstance()
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        val r = factory.createXMLStreamReader(file.inputStream(), "UTF-8")
        try {
            var id: Int? = null
            var day: String? = null
            var hour: String? = null
            var room: String? = null
            val realRooms = ArrayList<String>()
            var field: String? = null
            val text = StringBuilder()
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        field = r.localName
                        text.setLength(0)
                        if (field == "Activity") { id = null; day = null; hour = null; room = null; realRooms.clear() }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(r.text)
                    XMLStreamConstants.END_ELEMENT -> {
                        val value = text.toString()
                        when (r.localName) {
                            "Id" -> id = value.trim().toIntOrNull()
                            "Day" -> day = value.ifEmpty { null }
                            "Hour" -> hour = value.ifEmpty { null }
                            "Room" -> room = value.ifEmpty { null }
                            "Real_Room" -> if (value.isNotEmpty()) realRooms += value
                            "Activity" -> id?.let { out += Placement.of(doc, it, day, hour, room, realRooms.toList()) }
                        }
                        field = null
                        text.setLength(0)
                    }
                }
            }
        } finally {
            r.close()
        }
        return out
    }

    public fun conflicts(dir: File, stem: String): List<Conflict> {
        val lines = conflictLines(dir, stem) ?: return emptyList()
        val start = lines.indexOfFirst { it.startsWith("Soft conflicts list") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1)
            .filter { it.isNotBlank() && it != "End of file." }
            .map { line ->
                val weight = WEIGHT.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                val ids = ID.findAll(line).map { it.groupValues[1].toInt() }.toList()
                Conflict(weight, line, ids)
            }
    }

    /** Number of broken soft constraints and the total weight, from the header of the conflicts file. */
    public fun conflictTotals(dir: File, stem: String): Pair<Int, Double> {
        val lines = conflictLines(dir, stem) ?: return 0 to 0.0
        val count = lines.firstNotNullOfOrNull { COUNT.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val total = lines.firstNotNullOfOrNull { TOTAL.find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0
        return count to total
    }

    private fun conflictLines(dir: File, stem: String): List<String>? {
        val file = File(dir, "${stem}_soft_conflicts.txt")
        return if (file.isFile) file.readLines().map { it.removePrefix("﻿") } else null
    }

    /** `logs/difficult_activities.txt`: the activities FET placed before it gave up, last one first to blame. */
    public fun difficultActivities(jobDir: File): List<DifficultActivity> {
        val file = File(jobDir, "logs/difficult_activities.txt")
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val m = DIFFICULT.find(line) ?: return@mapNotNull null
            DifficultActivity(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].trim())
        }
    }

    /** `logs/warnings.txt` or `logs/errors.txt`: the dialogs fet-cl could not show, as title and message pairs. */
    public fun logMessages(jobDir: File, fileName: String): List<LogMessage> {
        val file = File(jobDir, "logs/$fileName")
        if (!file.isFile) return emptyList()
        val out = ArrayList<LogMessage>()
        var title = ""
        val message = StringBuilder()
        fun flush() { if (title.isNotEmpty() || message.isNotEmpty()) out += LogMessage(title, message.toString().trim()); title = ""; message.setLength(0) }
        for (raw in file.readLines()) {
            val line = raw.removePrefix("﻿")
            when {
                line.startsWith("Title: ") -> { flush(); title = line.removePrefix("Title: ") }
                line.startsWith("Message: ") -> message.append(line.removePrefix("Message: ")).append('\n')
                line.isBlank() -> flush()
                else -> message.append(line).append('\n')
            }
        }
        flush()
        return out
    }

    /** Output files grouped by kind, as absolute paths. */
    public fun files(dir: File, stem: String): Map<String, List<String>> {
        val all = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (f in all) {
            val kind = when {
                f.name == "${stem}_index.html" -> "index"
                f.name.endsWith(".html") -> "html"
                f.name.endsWith(".xml") -> "xml"
                f.name.endsWith(".csv") -> "csv"
                f.name.endsWith(".fet") -> "fet"
                f.name.endsWith(".txt") -> "txt"
                f.name.endsWith(".css") -> "css"
                else -> "other"
            }
            groups.getOrPut(kind) { ArrayList() } += f.absolutePath
        }
        return groups
    }

    private val WEIGHT = Regex("conflicts total by ([0-9]+(?:\\.[0-9]+)?)")
    private val ID = Regex("\\bid=(\\d+)")
    private val COUNT = Regex("Number of broken soft constraints: (\\d+)")
    private val TOTAL = Regex("Total soft conflicts: ([0-9]+(?:\\.[0-9]+)?)")
    private val DIFFICULT = Regex("No: (\\d+)\\D+Id: (\\d+) \\((.*)\\)\\s*$")
}
