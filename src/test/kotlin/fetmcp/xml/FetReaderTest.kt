package fetmcp.xml

import fetmcp.model.Family
import fetmcp.model.FieldValue
import fetmcp.model.Mode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FetReaderTest {
    private val examples = File(System.getProperty("fet.examples"))
    private fun example(path: String): String = File(examples, path).readText()

    @Test
    fun `reads a small block planning example`() {
        val result = FetReader.read(example("FET-5-block-planning/small-example.fet"))
        val doc = result.document
        assertEquals("5.37.5-bp", result.fileVersion)
        assertEquals(Mode.BLOCK_PLANNING, doc.mode)
        assertEquals(2, doc.days.size)
        assertEquals(30, doc.hours.size)
        assertEquals("Mon-P1", doc.hours[0].name)
        assertEquals(6, doc.subjects.size)
        assertEquals(0, doc.teachers.size)
        assertEquals(1, doc.years.size)
        assertEquals(4, doc.years[0].groups.size)
        assertEquals(18, doc.years[0].groups.sumOf { it.subgroups.size })
        assertEquals(28, doc.activities.size)
        assertEquals(0, doc.rooms.size)
        assertEquals(13, doc.timeConstraints.size)
        assertEquals(1, doc.spaceConstraints.size)
        assertTrue(result.unknownTags.isEmpty(), "unknown tags: ${result.unknownTags}")
    }

    @Test
    fun `reads activity fields`() {
        val doc = FetReader.read(example("FET-5-block-planning/small-example.fet")).document
        val first = doc.activities.first()
        assertEquals(1, first.id)
        assertEquals(1, first.groupId)
        assertEquals("English", first.subject)
        assertEquals(listOf("7_a"), first.students)
        assertEquals(2, first.duration)
        assertEquals(4, first.totalDuration)
        assertTrue(first.active)
        assertTrue(first.teachers.isEmpty())
    }

    @Test
    fun `reads a constraint with a repeated field and drops the count tag`() {
        val doc = FetReader.read(example("FET-5-block-planning/small-example.fet")).document
        val c = doc.timeConstraints.first { it.type == "ConstraintActivitiesSameStartingDay" }
        assertEquals(Family.TIME, c.family)
        assertEquals(100.0, c.weight)
        assertTrue(c.active)
        assertEquals(listOf("1", "2", "3"), c.scalars("Activity_Id"))
        assertTrue("Number_of_Activities" !in c.fields)
        assertTrue("Weight_Percentage" !in c.fields)
    }

    @Test
    fun `reads nested constraint objects`() {
        val text = wrap(
            """
            <Time_Constraints_List>
            <ConstraintTeacherNotAvailableTimes>
              <Weight_Percentage>100</Weight_Percentage>
              <Teacher>T1</Teacher>
              <Number_of_Not_Available_Times>2</Number_of_Not_Available_Times>
              <Not_Available_Time><Day>D1</Day><Hour>H1</Hour></Not_Available_Time>
              <Not_Available_Time><Day>D1</Day><Hour>H2</Hour></Not_Available_Time>
              <Active>true</Active>
              <Comments>x &amp; y</Comments>
            </ConstraintTeacherNotAvailableTimes>
            </Time_Constraints_List>
            """,
        )
        val c = FetReader.read(text).document.timeConstraints.single()
        assertEquals("T1", c.scalar("Teacher"))
        assertEquals("x & y", c.comments)
        val slots = c.items("Not_Available_Time")
        assertEquals(2, slots.size)
        assertEquals(FieldValue.obj("Day" to FieldValue.of("D1"), "Hour" to FieldValue.of("H2")), slots[1])
    }

    @Test
    fun `an empty list does not swallow the fields that follow it`() {
        val text = wrap(
            """
            <Time_Constraints_List>
            <ConstraintTeacherNotAvailableTimes>
              <Weight_Percentage>100</Weight_Percentage>
              <Teacher>T1</Teacher>
              <Number_of_Not_Available_Times>0</Number_of_Not_Available_Times>
              <Active>true</Active>
              <Comments>note</Comments>
            </ConstraintTeacherNotAvailableTimes>
            </Time_Constraints_List>
            """,
        )
        val c = FetReader.read(text).document.timeConstraints.single()
        assertEquals("note", c.comments)
        assertTrue(c.active)
        assertEquals(emptyList(), c.items("Not_Available_Time"))
        assertEquals(setOf("Teacher", "Not_Available_Time"), c.fields.keys)
        // Written back, Active and Comments appear exactly once.
        val xml = FetWriter.constraintXml(c)
        assertEquals(1, Regex("<Active>").findAll(xml).count(), xml)
        assertEquals(1, Regex("<Comments>").findAll(xml).count(), xml)
    }

    @Test
    fun `an empty list keeps the name the schema gives it`() {
        val text = wrap(
            """
            <Time_Constraints_List>
            <ConstraintTeacherOccupiesMaxSetsOfTimeSlotsFromSelection>
              <Weight_Percentage>100</Weight_Percentage>
              <Teacher>T1</Teacher>
              <Maximum_Number_of_Occupied_Sets>2</Maximum_Number_of_Occupied_Sets>
              <Number_of_Selected_Sets_of_Time_Slots>0</Number_of_Selected_Sets_of_Time_Slots>
              <Active>true</Active>
              <Comments></Comments>
            </ConstraintTeacherOccupiesMaxSetsOfTimeSlotsFromSelection>
            </Time_Constraints_List>
            """,
        )
        val c = FetReader.read(text).document.timeConstraints.single()
        assertEquals(setOf("Teacher", "Maximum_Number_of_Occupied_Sets", "Selected_Set_of_Time_Slots"), c.fields.keys)
        val xml = FetWriter.constraintXml(c)
        assertTrue("<Number_of_Selected_Sets_of_Time_Slots>0</Number_of_Selected_Sets_of_Time_Slots>" in xml, xml)
    }

    @Test
    fun `count tag that does not match the entries is an error`() {
        val text = wrap(
            """
            <Time_Constraints_List>
            <ConstraintActivitiesSameStartingDay>
              <Weight_Percentage>100</Weight_Percentage>
              <Number_of_Activities>3</Number_of_Activities>
              <Activity_Id>1</Activity_Id>
              <Active>true</Active>
              <Comments></Comments>
            </ConstraintActivitiesSameStartingDay>
            </Time_Constraints_List>
            """,
        )
        val e = assertFailsWith<FetXmlException> { FetReader.read(text) }
        assertTrue("Number_of_Activities" in e.message.orEmpty())
        assertTrue(e.line > 0)
    }

    @Test
    fun `unknown tags are reported with their position`() {
        val text = wrap("<Bogus_List><Thing>1</Thing></Bogus_List>")
        val result = FetReader.read(text)
        assertEquals(1, result.unknownTags.size)
        assertEquals("Bogus_List", result.unknownTags[0].tag)
        assertTrue(result.unknownTags[0].line > 0)
    }

    @Test
    fun `mode tag wins and terms are read`() {
        val text = wrap("<Mode>Terms</Mode><Number_of_Terms>5</Number_of_Terms><Number_of_Days_Per_Term>5</Number_of_Days_Per_Term>")
        val doc = FetReader.read(text).document
        assertEquals(Mode.TERMS, doc.mode)
        assertEquals(5, doc.terms?.terms)
        assertEquals(5, doc.terms?.daysPerTerm)
    }

    @Test
    fun `teacher fields including mornings afternoons behavior`() {
        val text = wrap(
            """
            <Mode>Mornings_Afternoons</Mode>
            <Teachers_List>
            <Teacher>
              <Name>T1</Name><Long_Name>Teacher One</Long_Name><Code>t1</Code>
              <Mornings_Afternoons_Behavior>Exclusive</Mornings_Afternoons_Behavior>
              <Target_Number_of_Hours>18</Target_Number_of_Hours>
              <Qualified_Subjects><Qualified_Subject>Math</Qualified_Subject></Qualified_Subjects>
              <Comments></Comments>
            </Teacher>
            </Teachers_List>
            """,
        )
        val t = FetReader.read(text).document.teachers.single()
        assertEquals("Teacher One", t.longName)
        assertEquals(fetmcp.model.MaBehavior.EXCLUSIVE, t.morningsAfternoonsBehavior)
        assertEquals(18, t.targetHours)
        assertEquals(listOf("Math"), t.qualifiedSubjects)
    }

    @Test
    fun `virtual room sets are read`() {
        val text = wrap(
            """
            <Rooms_List>
            <Room><Name>Lab</Name><Building>B</Building><Capacity>20</Capacity><Virtual>true</Virtual>
              <Number_of_Sets_of_Real_Rooms>2</Number_of_Sets_of_Real_Rooms>
              <Set_of_Real_Rooms><Number_of_Real_Rooms>1</Number_of_Real_Rooms><Real_Room>R1</Real_Room></Set_of_Real_Rooms>
              <Set_of_Real_Rooms><Number_of_Real_Rooms>2</Number_of_Real_Rooms><Real_Room>R2</Real_Room><Real_Room>R3</Real_Room></Set_of_Real_Rooms>
              <Comments></Comments>
            </Room>
            </Rooms_List>
            """,
        )
        val r = FetReader.read(text).document.rooms.single()
        assertTrue(r.virtual)
        assertEquals(listOf(listOf("R1"), listOf("R2", "R3")), r.realRoomSets)
        assertEquals(20, r.capacity)
    }

    @Test
    fun `students tree with categories and generation options`() {
        val text = wrap(
            """
            <Students_List>
            <Year><Name>9</Name><Number_of_Students>60</Number_of_Students><Comments></Comments>
              <Number_of_Categories>1</Number_of_Categories>
              <Category><Number_of_Divisions>2</Number_of_Divisions><Division>A</Division><Division>B</Division></Category>
              <First_Category_Is_Permanent>true</First_Category_Is_Permanent>
              <Separator>-</Separator>
              <Group><Name>9-A</Name><Number_of_Students>30</Number_of_Students><Comments></Comments>
                <Subgroup><Name>9-A-x</Name><Number_of_Students>15</Number_of_Students><Comments></Comments></Subgroup>
              </Group>
            </Year>
            </Students_List>
            <Timetable_Generation_Options_List>
            <GroupActivitiesInInitialOrder><Number_of_Activities>2</Number_of_Activities><Activity_Id>1</Activity_Id><Activity_Id>2</Activity_Id><Active>true</Active><Comments></Comments></GroupActivitiesInInitialOrder>
            </Timetable_Generation_Options_List>
            """,
        )
        val doc = FetReader.read(text).document
        val y = doc.years.single()
        assertEquals(listOf(listOf("A", "B")), y.categories)
        assertTrue(y.firstCategoryPermanent)
        assertEquals("-", y.separator)
        assertEquals("9-A-x", y.groups.single().subgroups.single().name)
        assertEquals(listOf(1, 2), doc.generationOptions.single().activityIds)
    }

    @Test
    fun `the legacy Weight tag means one hundred percent, not its own value`() {
        val text = wrap(
            """
            <Time_Constraints_List>
            <ConstraintTeacherMaxDaysPerWeek>
              <Weight>0.75</Weight>
              <Teacher_Name>T1</Teacher_Name>
              <Max_Days_Per_Week>3</Max_Days_Per_Week>
              <Active>true</Active><Comments></Comments>
            </ConstraintTeacherMaxDaysPerWeek>
            </Time_Constraints_List>
            <Teachers_List><Teacher><Name>T1</Name></Teacher></Teachers_List>
            """,
        )
        val c = FetReader.read(text).document.timeConstraints.single()
        assertEquals(100.0, c.weight)
        assertTrue("Weight" !in c.fields)
    }

    @Test
    fun `booleans fet defaults to true are not invented as false`() {
        val text = wrap(
            """
            <Time_Constraints_List>
            <ConstraintMinDaysBetweenActivities>
              <Weight_Percentage>95</Weight_Percentage>
              <Number_of_Activities>2</Number_of_Activities>
              <Activity_Id>1</Activity_Id><Activity_Id>2</Activity_Id>
              <MinDays>1</MinDays>
              <Active>true</Active><Comments></Comments>
            </ConstraintMinDaysBetweenActivities>
            </Time_Constraints_List>
            """,
        )
        val c = FetReader.read(text).document.timeConstraints.single()
        // FET defaults Consecutive_If_Same_Day to true when the tag is absent, so we must not write false.
        assertTrue("Consecutive_If_Same_Day" !in c.fields)
        assertTrue("<Consecutive_If_Same_Day>" !in FetWriter.constraintXml(c))
    }

    @Test
    fun `legacy alias tags are renamed to the current names`() {
        val text = wrap(
            """
            <Time_Constraints_List>
            <ConstraintActivitiesPreferredStartingTimes>
              <Weight_Percentage>100</Weight_Percentage>
              <Teacher_Name>T1</Teacher_Name><Students_Name></Students_Name><Subject_Name></Subject_Name><Activity_Tag_Name></Activity_Tag_Name>
              <Duration></Duration>
              <Number_of_Preferred_Starting_Times>1</Number_of_Preferred_Starting_Times>
              <Preferred_Starting_Time><Preferred_Starting_Day>D1</Preferred_Starting_Day><Preferred_Starting_Hour>H1</Preferred_Starting_Hour></Preferred_Starting_Time>
              <Active>true</Active><Comments></Comments>
            </ConstraintActivitiesPreferredStartingTimes>
            </Time_Constraints_List>
            """,
        )
        val c = FetReader.read(text).document.timeConstraints.single()
        assertEquals("T1", c.scalar("Teacher"))
        assertTrue("Teacher_Name" !in c.fields)
        assertEquals(FieldValue.obj("Day" to FieldValue.of("D1"), "Hour" to FieldValue.of("H1")), c.items("Preferred_Starting_Time").single())
    }

    @Test
    fun `old morocco exception lists become teacher behaviours`() {
        val text = wrap(
            """
            <Teachers_List>
            <Teacher><Name>A</Name></Teacher><Teacher><Name>B</Name></Teacher><Teacher><Name>C</Name></Teacher>
            </Teachers_List>
            <Exception_Teachers_One_Day_List><Teacher>B</Teacher></Exception_Teachers_One_Day_List>
            <Exception_Teachers_Two_Days_List><Teacher><Name>C</Name></Teacher></Exception_Teachers_Two_Days_List>
            """,
        )
        val r = FetReader.read(text)
        assertEquals(Mode.MORNINGS_AFTERNOONS, r.document.mode)
        assertEquals(listOf(fetmcp.model.MaBehavior.EXCLUSIVE, fetmcp.model.MaBehavior.ONE_DAY_EXCEPTION, fetmcp.model.MaBehavior.TWO_DAYS_EXCEPTION), r.document.teachers.map { it.morningsAfternoonsBehavior })
        assertTrue(r.unknownTags.isEmpty())
    }

    @Test
    fun `real day and hour names are kept as the file wrote them`() {
        val text = """<?xml version="1.0" encoding="UTF-8"?>
<fet version="7.10.4">
<Mode>Mornings_Afternoons</Mode>
<Institution_Name>T</Institution_Name>
<Comments></Comments>
<Days_List><Number_of_Days>2</Number_of_Days><Day><Name>Sun AM</Name></Day><Day><Name>Sun PM</Name></Day></Days_List>
<Real_Days_List><Number_of_Real_Days>1</Number_of_Real_Days><Real_Day><Name>الأحد: 08-03-2026</Name><Long_Name>Sunday</Long_Name></Real_Day></Real_Days_List>
<Hours_List><Number_of_Hours>1</Number_of_Hours><Hour><Name>08:30</Name></Hour></Hours_List>
<Real_Hours_List><Number_of_Real_Hours>2</Number_of_Real_Hours><Real_Hour><Name>08:30 AM</Name><Long_Name></Long_Name></Real_Hour><Real_Hour><Name>08:30 PM</Name><Long_Name></Long_Name></Real_Hour></Real_Hours_List>
</fet>
"""
        val doc = FetReader.read(text).document
        assertEquals(listOf("الأحد: 08-03-2026"), doc.realDays().map { it.name })
        assertEquals("Sunday", doc.realDays().single().longName)
        assertEquals(listOf("08:30 AM", "08:30 PM"), doc.realHours().map { it.name })
        assertEquals(listOf("", ""), doc.realHours().map { it.longName })
        // And they survive a write.
        val again = FetReader.read(FetWriter.write(doc)).document
        assertEquals(doc.realDays(), again.realDays())
        assertEquals(doc.realHours(), again.realHours())
    }

    @Test
    fun `real days are derived when the file has none`() {
        val doc = FetReader.read(
            """<?xml version="1.0" encoding="UTF-8"?>
<fet version="7.10.4">
<Mode>Mornings_Afternoons</Mode>
<Institution_Name>T</Institution_Name>
<Comments></Comments>
<Days_List><Number_of_Days>2</Number_of_Days><Day><Name>Sun AM</Name><Long_Name>Sunday morning</Long_Name></Day><Day><Name>Sun PM</Name></Day></Days_List>
<Hours_List><Number_of_Hours>1</Number_of_Hours><Hour><Name>1</Name><Long_Name>08:30</Long_Name></Hour></Hours_List>
</fet>
""",
        ).document
        assertEquals(listOf("Sun AM"), doc.realDays().map { it.name })
        assertEquals(listOf("H1", "H2"), doc.realHours().map { it.name })
        assertEquals(listOf("08:30 AM", "08:30 PM"), doc.realHours().map { it.longName })
    }

    @Test
    fun `booleans follow fet's own rules`() {
        val text = wrap(
            """
            <Activity_Tags_List>
            <Activity_Tag><Name>Lab</Name><Printable>1</Printable><Comments></Comments></Activity_Tag>
            </Activity_Tags_List>
            <Activities_List>
            <Activity><Subject>S</Subject><Duration>1</Duration><Total_Duration>1</Total_Duration><Id>1</Id><Activity_Group_Id>0</Activity_Group_Id><Active>no</Active><Comments></Comments></Activity>
            <Activity><Subject>S</Subject><Duration>1</Duration><Total_Duration>1</Total_Duration><Id>2</Id><Activity_Group_Id>0</Activity_Group_Id><Active>yes</Active><Comments></Comments></Activity>
            <Activity><Subject>S</Subject><Duration>1</Duration><Total_Duration>1</Total_Duration><Id>3</Id><Activity_Group_Id>0</Activity_Group_Id><Active>0</Active><Comments></Comments></Activity>
            </Activities_List>
            """,
        )
        val doc = FetReader.read(text).document
        // FET: an activity is active for yes/true/1, inactive for no/false/0.
        assertEquals(listOf(false, true, false), doc.activities.map { it.active })
        // FET only treats the exact text "true" as printable.
        assertEquals(false, doc.activityTags.single().printable)
    }

    @Test
    fun `bad mode text is an error`() {
        assertFailsWith<FetXmlException> { FetReader.read(wrap("<Mode>Weird</Mode>")) }
    }

    private fun wrap(body: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<fet version="7.10.4">
<Institution_Name>Test</Institution_Name>
<Comments></Comments>
<Days_List><Number_of_Days>2</Number_of_Days><Day><Name>D1</Name></Day><Day><Name>D2</Name></Day></Days_List>
<Hours_List><Number_of_Hours>2</Number_of_Hours><Hour><Name>H1</Name></Hour><Hour><Name>H2</Name></Hour></Hours_List>
$body
</fet>
"""
}
