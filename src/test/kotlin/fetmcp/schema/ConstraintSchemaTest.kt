package fetmcp.schema

import fetmcp.model.Family
import fetmcp.model.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstraintSchemaTest {
    @Test
    fun `schema resource loads every type`() {
        assertEquals(344, ConstraintSchema.types.size)
        assertNotNull(ConstraintSchema.get("ConstraintBasicCompulsoryTime"))
        assertNull(ConstraintSchema.get("ConstraintNope"))
    }

    @Test
    fun `allowed types per mode`() {
        assertEquals(207, ConstraintSchema.allowedIn(Mode.OFFICIAL).size)
        assertEquals(329, ConstraintSchema.allowedIn(Mode.MORNINGS_AFTERNOONS).size)
        assertTrue(ConstraintSchema.allowedIn(Mode.OFFICIAL).all { Mode.OFFICIAL in it.modes })
    }

    @Test
    fun `family lookup and search`() {
        assertEquals(Family.SPACE, ConstraintSchema.get("ConstraintRoomNotAvailableTimes")?.family)
        val hits = ConstraintSchema.search("gaps per week", Mode.OFFICIAL)
        assertTrue(hits.any { it.name == "ConstraintTeacherMaxGapsPerWeek" })
        assertTrue(hits.none { it.name == "ConstraintTeacherMaxGapsPerRealDay" })
    }

    @Test
    fun `help text for a mode`() {
        assertTrue("real day" in ConstraintSchema.help(Mode.MORNINGS_AFTERNOONS).lowercase())
    }
}
