package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ArpeggioSchedulerUnassignTest {

    @BeforeEach
    fun setUp() {
        ArpeggioScheduler.clear()
    }

    @Test
    fun unassignRemovesFailedPositionsFromAssignment() {
        val pitch1 = NotePitch(60, null)
        val pitch2 = NotePitch(62, null)
        val pos1 = BlockPos(10, 64, 10)
        val pos2 = BlockPos(20, 64, 20)

        ArpeggioScheduler.assignment = mapOf(pitch1 to pos1, pitch2 to pos2)

        ArpeggioScheduler.unassign(setOf(pos1))

        assertEquals(mapOf(pitch2 to pos2), ArpeggioScheduler.assignment)
        assertFalse(ArpeggioScheduler.assignment.containsKey(pitch1))
        assertTrue(ArpeggioScheduler.assignment.containsKey(pitch2))
    }

    @Test
    fun unassignRemovesFailedPositionsFromVoicePools() {
        val pitch = NotePitch(64, null)
        val posGood = BlockPos(1, 64, 1)
        val posBad = BlockPos(2, 64, 2)

        ArpeggioScheduler.voicePools = mapOf(pitch to listOf(posGood, posBad))

        ArpeggioScheduler.unassign(setOf(posBad))

        assertEquals(listOf(posGood), ArpeggioScheduler.voicePools[pitch])
    }

    @Test
    fun unassignDropsPoolWhenAllPositionsFail() {
        val pitch = NotePitch(65, null)
        val posBad = BlockPos(5, 64, 5)

        ArpeggioScheduler.voicePools = mapOf(pitch to listOf(posBad))

        ArpeggioScheduler.unassign(setOf(posBad))

        assertFalse(ArpeggioScheduler.voicePools.containsKey(pitch))
    }
}
