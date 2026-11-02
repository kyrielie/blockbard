package kyrielie.blockbard.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * P9 spike. Records what the `test` classpath can see under Loom's split source sets, so later
 * passes know which code is unit-testable. The probes print their findings (build.gradle sets
 * showStandardStreams) and never fail the build on a negative result; the Minecraft-dependent
 * assertion is skipped, not failed, when the class cannot be loaded.
 */
class TestClasspathProbeTest {

    private fun probe(className: String): String =
        try {
            Class.forName(className)
            "LOADS"
        } catch (e: Throwable) {
            // Include the cause chain: a failed static initializer only reveals its root cause
            // on the first attempt, or as the cause of the later NoClassDefFoundError.
            val chain = generateSequence<Throwable>(e) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            "FAILS ($chain)"
        }

    @Test
    fun reportClasspathVisibility() {
        val targets = listOf(
            "main source set, pure Kotlin" to "kyrielie.blockbard.util.NoteUtilsKt",
            "main source set, Minecraft enum" to "net.minecraft.world.level.block.state.properties.NoteBlockInstrument",
            "main source set, organ package" to "kyrielie.blockbard.organ.NoteBlockEntry",
            "client source set (PlaybackClock)" to "kyrielie.blockbard.client.playback.PlaybackClock",
            "client source set (MidiTempoMap)" to "kyrielie.blockbard.client.playback.MidiTempoMap",
        )
        println("=== BlockBard test classpath probe ===")
        for ((label, cls) in targets) println("PROBE $label -> ${probe(cls)}")
        println("=== end probe ===")
    }

    @Test
    fun minecraftInstrumentExtensionsWork_whenMinecraftClassesLoad() {
        val loads = probe("net.minecraft.world.level.block.state.properties.NoteBlockInstrument") == "LOADS"
        assumeTrue(loads, "Minecraft classes are not loadable on the test classpath")
        val harp = net.minecraft.world.level.block.state.properties.NoteBlockInstrument.HARP
        assertEquals(54, harp.midiBase)
        assertEquals(true, harp.coversNatively(60))
    }
}
