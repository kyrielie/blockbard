package kyrielie.blockbard.client.playback

import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
import kyrielie.blockbard.util.readLEInt
import kyrielie.blockbard.util.readLEShort
import kyrielie.blockbard.util.readNBSString
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import java.io.DataInputStream
import java.io.File

/**
 * NBS instrument byte (0-15) -> NoteBlockInstrument, per the vanilla instrument order
 * defined by Open Note Block Studio. Indices 0-9 are the original instrument set;
 * 10-15 were added as Minecraft introduced more note block instruments. Files may
 * specify instrumentCount < 16 in the header (older Minecraft versions had fewer
 * note block instruments) but the byte values for any instrument present are stable
 * across versions, so a single fixed table covers all of them.
 *
 * Instrument index >= 16 means a custom instrument (a player-imported sound, not a
 * vanilla noteblock instrument) — there's no NoteBlockInstrument to map it to, so
 * nbsInstrumentToBlock returns null and the caller falls back to pitch-only matching.
 */
private val NBS_INSTRUMENTS = arrayOf(
    NoteBlockInstrument.HARP,
    NoteBlockInstrument.BASS,
    NoteBlockInstrument.BASEDRUM,
    NoteBlockInstrument.SNARE,
    NoteBlockInstrument.HAT,
    NoteBlockInstrument.GUITAR,
    NoteBlockInstrument.FLUTE,
    NoteBlockInstrument.BELL,
    NoteBlockInstrument.CHIME,
    NoteBlockInstrument.XYLOPHONE,
    NoteBlockInstrument.IRON_XYLOPHONE,
    NoteBlockInstrument.COW_BELL,
    NoteBlockInstrument.DIDGERIDOO,
    NoteBlockInstrument.BIT,
    NoteBlockInstrument.BANJO,
    NoteBlockInstrument.PLING
)

/** Converts an NBS instrument byte to a NoteBlockInstrument, or null if it's a custom instrument (index >= 16). */
fun nbsInstrumentToBlock(nbsInstrument: Int): NoteBlockInstrument? =
    NBS_INSTRUMENTS.getOrNull(nbsInstrument)

data class NbsNote(
    val tick: Int,
    val layer: Int,
    val instrument: Int,   // NBS instrument index
    val key: Int,          // 0–87; add 21 for MIDI note number
    val velocity: Byte
)

data class NbsFile(
    val file: File,
    val version: Byte,
    val tempo: Float,       // ticks per second
    val notes: List<NbsNote>,
    val title: String
) {
    val durationMs: Long get() {
        val lastTick = notes.maxOfOrNull { it.tick } ?: 0
        return (lastTick / tempo * 1000).toLong()
    }
}

object NbsFileLoader {

    fun load(file: File): NbsFile {
        DataInputStream(file.inputStream().buffered()).use { stream ->
            // Header
            val firstShort = stream.readLEShort()
            val version: Byte
            val instrumentCount: Int

            if (firstShort.toInt() == 0) {
                // New NBS format (version >= 1)
                version = stream.read().toByte()
                instrumentCount = stream.read()
            } else {
                // Classic NBS format
                version = 0
                instrumentCount = 0
            }

            val songLength = stream.readLEShort().toInt()
            val layerCount = stream.readLEShort().toInt()
            val title = stream.readNBSString()
            stream.readNBSString() // author
            stream.readNBSString() // original author
            stream.readNBSString() // description
            val tempoRaw = stream.readLEShort().toInt() // ticks per second × 100
            val tempo = tempoRaw / 100f

            // Skip remaining header fields
            stream.read() // auto-save enabled
            stream.read() // auto-save duration
            stream.read() // time signature
            stream.readLEInt() // minutes spent
            stream.readLEInt() // left-clicks
            stream.readLEInt() // right-clicks
            stream.readLEInt() // blocks added
            stream.readLEInt() // blocks removed
            stream.readNBSString() // MIDI/schematic filename
            if (version >= 4) {
                stream.read() // loop on/off
                stream.read() // max loop count
                stream.readLEShort() // loop start tick
            }

            // Parse notes using jump encoding
            val notes = mutableListOf<NbsNote>()
            var currentTick = -1
            while (true) {
                val tickJump = stream.readLEShort().toInt()
                if (tickJump == 0) break
                currentTick += tickJump
                var currentLayer = -1
                while (true) {
                    val layerJump = stream.readLEShort().toInt()
                    if (layerJump == 0) break
                    currentLayer += layerJump
                    val instrument = stream.read()
                    val key = stream.read()      // 0–87
                    val velocity = if (version >= 4) stream.read().toByte() else 100
                    val panning = if (version >= 4) stream.read() else 100
                    val pitch = if (version >= 4) stream.readLEShort() else 0
                    notes.add(NbsNote(currentTick, currentLayer, instrument, key, velocity))
                }
            }

            return NbsFile(file, version, tempo, notes, title)
        }
    }
}

object NbsPlayer {
    private var playbackThread: Thread? = null
    var isPlaying: Boolean = false
        private set
    var isPaused: Boolean = false
        private set
    private var pausedAtMs: Long = 0L
    private var startWallMs: Long = 0L

    var onFinished: (() -> Unit)? = null

    fun play(nbs: NbsFile, tempoMultiplier: Float = 1.0f) {
        stop()
        isPlaying = true
        isPaused = false
        startWallMs = System.currentTimeMillis()

        val tickDurationMs = (1000.0 / nbs.tempo / tempoMultiplier).toLong()

        playbackThread = Thread({
            try {
                val notesByTick = nbs.notes.groupBy { it.tick }
                val sortedTicks = notesByTick.keys.sorted()

                for (tick in sortedTicks) {
                    if (!isPlaying) break
                    val targetMs = startWallMs + tick * tickDurationMs
                    while (System.currentTimeMillis() < targetMs) {
                        if (!isPlaying) return@Thread
                        while (isPaused) {
                            Thread.sleep(20)
                            if (!isPlaying) return@Thread
                        }
                        Thread.sleep(5)
                    }
                    if (!isPlaying) return@Thread
                    notesByTick[tick]?.forEach { note ->
                        val midiNote = note.key + 21  // NBS key → MIDI note
                        // Resolve the NBS instrument byte so playback targets a block
                        // tuned for the right instrument, not just any block at this
                        // pitch — see nbsInstrumentToBlock kdoc. null for custom
                        // (non-vanilla) instruments, which falls back to pitch-only
                        // matching in ArpeggioScheduler.resolvePos.
                        val instrument = nbsInstrumentToBlock(note.instrument)
                        ArpeggioScheduler.enqueue(NoteRequest(midiNote, instrument = instrument))
                    }
                }
                isPlaying = false
                onFinished?.invoke()
            } catch (_: InterruptedException) {
                isPlaying = false
            }
        }, "BlockBard-NBS-Playback")
        playbackThread?.isDaemon = true
        playbackThread?.start()
    }

    fun pause() {
        if (!isPlaying || isPaused) return
        isPaused = true
        pausedAtMs = System.currentTimeMillis()
    }

    fun resume() {
        if (!isPaused) return
        val paused = System.currentTimeMillis() - pausedAtMs
        startWallMs += paused
        isPaused = false
    }

    fun stop() {
        isPlaying = false
        isPaused = false
        playbackThread?.interrupt()
        playbackThread = null
        ArpeggioScheduler.clear()
    }
}
