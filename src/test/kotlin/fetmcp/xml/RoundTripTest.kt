package fetmcp.xml

import fetmcp.model.FetDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoundTripTest {
    private val examples = File(System.getProperty("fet.examples"))
    private val allFiles: List<File> by lazy { examples.walkTopDown().filter { it.extension == "fet" }.sorted().toList() }

    @Test
    fun `every example file survives read write read`() {
        val problems = mutableListOf<String>()
        var checked = 0
        for (file in allFiles) {
            val first = try { FetReader.read(file) } catch (e: FetXmlException) { problems += "${file.name}: read failed: ${e.message}"; continue }
            val text = FetWriter.write(first.document)
            val second = try { FetReader.read(text) } catch (e: FetXmlException) { problems += "${file.name}: re-read failed: ${e.message}"; continue }
            if (second.unknownTags.isNotEmpty()) problems += "${file.name}: writer produced unknown tags ${second.unknownTags.take(3)}"
            val diff = describeDifference(first.document, second.document)
            if (diff != null) problems += "${file.name}: $diff"
            checked++
        }
        assertTrue(checked > 300, "only $checked files checked")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `every example file loads with no unknown tags`() {
        val withUnknown = allFiles.mapNotNull { f ->
            val r = FetReader.read(f)
            if (r.unknownTags.isEmpty()) null else "${f.name}: ${r.unknownTags.map { it.tag }.distinct()}"
        }
        assertTrue(withUnknown.isEmpty(), withUnknown.joinToString("\n"))
    }

    @Test
    fun `a file written by fet 7 comes back byte for byte`() {
        val file = File(examples, "exams/by-Benahmed-Abdelkrim/7/BEM_05-2026.fet")
        val original = file.readText().removePrefix("﻿")
        val doc = FetReader.read(file).document
        val written = FetWriter.write(doc, fetVersion = FetReader.read(file).fileVersion)
        assertEquals(original.lines(), written.lines())
    }

    @Test
    fun `a mornings afternoons file written by fet 7 comes back byte for byte`() {
        // This one carries the real day and hour names a school edited, which must survive untouched.
        val file = File(examples, "exams/by-Benahmed-Abdelkrim/1/test3_nsrt6.fet")
        val original = file.readText().removePrefix("﻿")
        val read = FetReader.read(file)
        assertEquals(original.lines(), FetWriter.write(read.document, fetVersion = read.fileVersion).lines())
    }

    @Test
    fun `every fet 7 file comes back byte for byte, apart from what fet itself changed`() {
        // Two example files were written by an older FET 7 whose output differs from 7.10.4. Both are FET's own
        // doing, not ours: it moved a field, and it normalises the old Number_Of_Students spelling on save.
        val expectedDifferences = mapOf(
            "test_Benahmed6.fet" to "Maximum_Allowed_Activity_Tags",
            "small-test.fet" to "Number_Of_Students",
        )
        val problems = mutableListOf<String>()
        var checked = 0
        var exact = 0
        for (file in allFiles) {
            val read = try { FetReader.read(file) } catch (e: FetXmlException) { continue }
            // Only files this FET series wrote. Older ones are converted on load, so they are meant to differ.
            if (!read.fileVersion.startsWith("7.")) continue
            checked++
            val original = file.readText().removePrefix("﻿").lines()
            val written = FetWriter.write(read.document, fetVersion = read.fileVersion).lines()
            if (original == written) { exact++; continue }
            val i = original.zip(written).indexOfFirst { it.first != it.second }
            val expected = expectedDifferences[file.name]
            if (expected != null && expected in (original.getOrNull(i) ?: "")) continue
            problems += "${file.name}: line ${i + 1}\n  file:    ${original.getOrNull(i)}\n  written: ${written.getOrNull(i)}"
        }
        assertTrue(checked > 50, "only $checked FET 7 files checked")
        assertEquals(checked - expectedDifferences.size, exact)
        assertTrue(problems.isEmpty(), problems.take(5).joinToString("\n"))
    }

    @Test
    fun `constraint refs are stable across a round trip`() {
        val doc = FetReader.read(File(examples, "FET-5-block-planning/small-example.fet")).document
        val again = FetReader.read(FetWriter.write(doc)).document
        assertEquals(doc.constraints().map { it.ref }.toList(), again.constraints().map { it.ref }.toList())
    }

    private fun describeDifference(a: FetDocument, b: FetDocument): String? {
        if (a.mode != b.mode) return "mode ${a.mode} vs ${b.mode}"
        if (a.institution != b.institution || a.comments != b.comments) return "header differs"
        if (a.terms != b.terms) return "terms differ"
        fun <T> cmp(name: String, x: List<T>, y: List<T>): String? {
            if (x.size != y.size) return "$name count ${x.size} vs ${y.size}"
            val i = x.indices.firstOrNull { x[it] != y[it] } ?: return null
            return "$name[$i] differs:\n  ${x[i]}\n  ${y[i]}"
        }
        return cmp("days", a.days, b.days)
            ?: cmp("hours", a.hours, b.hours)
            ?: cmp("subjects", a.subjects, b.subjects)
            ?: cmp("tags", a.activityTags, b.activityTags)
            ?: cmp("teachers", a.teachers, b.teachers)
            ?: cmp("years", a.years, b.years)
            ?: cmp("activities", a.activities, b.activities)
            ?: cmp("buildings", a.buildings, b.buildings)
            ?: cmp("rooms", a.rooms, b.rooms)
            ?: cmp("time constraints", a.timeConstraints, b.timeConstraints)
            ?: cmp("space constraints", a.spaceConstraints, b.spaceConstraints)
            ?: cmp("generation options", a.generationOptions, b.generationOptions)
    }
}
