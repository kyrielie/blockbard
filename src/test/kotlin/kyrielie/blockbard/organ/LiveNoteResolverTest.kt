package kyrielie.blockbard.organ

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LiveNoteResolverTest {

    private data class Blk(val inst: String, val pitch: Int, val dist: Double = 1.0, val pitched: Boolean = true)

    private fun resolve(
        note: Int, blocks: List<Blk>, octave: Boolean = true, max: Int = 2, preferred: String? = "HARP"
    ) = LiveNoteResolver.resolve(note, preferred, blocks, octave, max, { it.inst }, { it.pitch }, { it.pitched }, { it.dist })

    /** Chromatic organ: 25 harp (54..78), 25 flute (66..90), 25 guitar (42..66). */
    private val organ: List<Blk> =
        (42..66).map { Blk("GUITAR", it) } + (54..78).map { Blk("HARP", it) } + (66..90).map { Blk("FLUTE", it) }

    @Test
    fun harpWinsWhereRangesOverlap() {
        for (n in 54..78) assertEquals("HARP", resolve(n, organ)!!.block.inst, "note $n")
    }

    @Test
    fun guitarBelowAndFluteAbove() {
        for (n in 42..53) assertEquals("GUITAR", resolve(n, organ)!!.block.inst, "note $n")
        for (n in 79..90) assertEquals("FLUTE", resolve(n, organ)!!.block.inst, "note $n")
    }

    @Test
    fun fullPianoWithTwoOctavesOfShift() {
        val missing = (21..108).filter { resolve(it, organ, max = 2) == null }
        assertEquals(emptyList<Int>(), missing)
    }

    @Test
    fun oneOctaveOfShiftCovers73Keys() {
        assertEquals(73, (21..108).count { resolve(it, organ, max = 1) != null })
    }

    @Test
    fun noShiftCovers49Keys() {
        assertEquals(49, (21..108).count { resolve(it, organ, octave = false) != null })
    }

    @Test
    fun lowNoteFoldsUpAndHighNoteFoldsDown() {
        assertEquals(12, resolve(35, organ)!!.shift)
        assertEquals("GUITAR", resolve(35, organ)!!.block.inst)
        assertEquals(-12, resolve(100, organ)!!.shift)
        assertEquals(-24, resolve(106, organ)!!.shift)
        assertEquals(24, resolve(25, organ)!!.shift)
    }

    @Test
    fun nearestBlockWinsWithinTheSameInstrument() {
        val blocks = listOf(Blk("HARP", 60, 4.0), Blk("HARP", 60, 2.0), Blk("GUITAR", 60, 1.0))
        assertEquals(2.0, resolve(60, blocks)!!.block.dist)
    }

    @Test
    fun onlyAlreadyTunedPitchesQualify() {
        assertNull(resolve(60, listOf(Blk("HARP", 61)), octave = false))
    }

    @Test
    fun unpitchedBlocksNeverQualify() {
        assertNull(resolve(60, listOf(Blk("SNARE", 60, pitched = false)), octave = false))
    }

    @Test
    fun noPreferredInstrumentUsesNearest() {
        val blocks = listOf(Blk("HARP", 60, 5.0), Blk("GUITAR", 60, 1.0))
        assertEquals("GUITAR", resolve(60, blocks, preferred = null)!!.block.inst)
    }
}
