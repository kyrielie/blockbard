package kyrielie.blockbard.client.playback

import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
import kyrielie.blockbard.util.isPitched
import kyrielie.blockbard.util.midiBase
import kyrielie.blockbard.util.readByteChecked
import kyrielie.blockbard.util.readLEInt
import kyrielie.blockbard.util.readLEShort
import kyrielie.blockbard.util.readLEUShort
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
            val firstShort = stream.readLEShort("format marker")
            val version: Byte
            val instrumentCount: Int
            val songLength: Int

            if (firstShort.toInt() == 0) {
                // New NBS format (version >= 1): the leading 0 is a literal format
                // marker with no data of its own, distinct from the classic format
                // below where the very first short read IS the song length value.
                version = stream.readByteChecked("version byte").toByte()
                instrumentCount = stream.readByteChecked("instrument count byte")
                songLength = stream.readLEUShort("song length")
            } else {
                // Classic NBS format: there is no separate format-marker short — the
                // value already consumed into firstShort above is itself songLength.
                version = 0
                instrumentCount = 0
                songLength = firstShort.toInt() and 0xFFFF
            }

            val layerCount = stream.readLEUShort("layer count")
            val title = stream.readNBSString("title")
            stream.readNBSString("author")
            stream.readNBSString("original author")
            stream.readNBSString("description")
            val tempoRaw = stream.readLEShort("tempo").toInt() // ticks per second × 100
            val tempo = if (tempoRaw > 0) tempoRaw / 100f else 20.0f

            // Skip remaining header fields
            stream.readByteChecked("auto-save enabled flag")
            stream.readByteChecked("auto-save duration")
            stream.readByteChecked("time signature")
            stream.readLEInt("minutes spent")
            stream.readLEInt("left-clicks")
            stream.readLEInt("right-clicks")
            stream.readLEInt("blocks added")
            stream.readLEInt("blocks removed")
            stream.readNBSString("MIDI/schematic filename")
            if (version >= 4) {
                stream.readByteChecked("loop on/off flag")
                stream.readByteChecked("max loop count")
                stream.readLEShort("loop start tick")
            }

            // Parse notes using jump encoding
            val notes = mutableListOf<NbsNote>()
            var currentTick = -1
            while (true) {
                val tickJump = stream.readLEUShort("tick jump")
                if (tickJump == 0) break
                currentTick += tickJump
                var currentLayer = -1
                while (true) {
                    val layerJump = stream.readLEUShort("layer jump")
                    if (layerJump == 0) break
                    currentLayer += layerJump
                    val instrument = stream.readByteChecked("instrument byte")
                    val key = stream.readByteChecked("key byte")      // 0–87
                    val velocity = if (version >= 4) stream.readByteChecked("velocity byte").toByte() else 100
                    val panning = if (version >= 4) stream.readByteChecked("panning byte") else 100
                    val pitch = if (version >= 4) stream.readLEShort("fine pitch") else 0
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

    // Mirrors MidiFilePlayer.getCurrentTick()/getTotalTicks() — updated inside the
    // playback loop each time a tick's notes are dispatched, plus public getters
    // so PlaybackHud/MainScreen can show NBS progress the same way they show MIDI
    // progress. totalTick is computed once per play() call from notes.last().tick —
    // safe because NbsFileLoader.load() appends notes in non-decreasing tick order
    // (currentTick only increases during jump-decoding for a well-formed file); a
    // malformed file could violate that, but that case is guarded separately by
    // LittleEndianReader's EOF handling rather than re-sorting here on every play().
    private var currentTick: Long = 0L
    private var totalTick: Long = 0L
    var currentNbs: NbsFile? = null
        private set

    var onFinished: (() -> Unit)? = null

    /**
     * Starts NBS playback. Tempo scaling reads MidiFilePlayer.tempoMultiplier live
     * each tick (see the loop below) rather than taking a tempoMultiplier parameter —
     * there is only one tempo control in the GUI (MainScreen's +/- buttons mutate
     * MidiFilePlayer.tempoMultiplier directly) and it should apply identically to
     * whichever format is currently playing.
     */
    fun play(nbs: NbsFile, startFromTick: Long = 0L) {
        stop()
        isPlaying = true
        isPaused = false
        currentNbs = nbs
        currentTick = startFromTick
        totalTick = nbs.notes.lastOrNull()?.tick?.toLong() ?: 0L

        val initialRealMs = (startFromTick / nbs.tempo * 1000.0).toLong()
        val initialScaledMs = (initialRealMs / MidiFilePlayer.tempoMultiplier).toLong()
        startWallMs = System.currentTimeMillis() - initialScaledMs

        playbackThread = Thread({
            try {
                val notesByTick = nbs.notes.groupBy { it.tick }
                val sortedTicks = notesByTick.keys.sorted()

                for (tick in sortedTicks) {
                    if (!isPlaying) break
                    if (tick < startFromTick) continue

                    // Recompute the scaled target time fresh from the live tempo
                    // multiplier each tick, the same way MidiFilePlayer.play() calls
                    // tickToScaledMs(..., tempoMultiplier) inside its loop, rather than
                    // baking a fixed tickDurationMs once at play() start — that older
                    // approach meant the tempo slider had no live effect on NBS playback
                    // once started, unlike MIDI. MidiFilePlayer.tempoMultiplier is the
                    // single shared value MainScreen's tempo +/- buttons mutate; reading
                    // it here each tick (rather than the captured tempoMultiplier
                    // parameter) means an in-progress NBS song picks up tempo changes
                    // immediately, matching MIDI's behavior.
                    val realMs = (tick / nbs.tempo * 1000.0).toLong()
                    val scaledMs = (realMs / MidiFilePlayer.tempoMultiplier).toLong()
                    val targetMs = startWallMs + scaledMs

                    while (System.currentTimeMillis() < targetMs) {
                        if (!isPlaying) return@Thread
                        while (isPaused) {
                            Thread.sleep(20)
                            if (!isPlaying) return@Thread
                        }
                        Thread.sleep(5)
                    }
                    if (!isPlaying) return@Thread
                    currentTick = tick.toLong()
                    notesByTick[tick]?.forEach { note ->
                        val rawInstrument = nbsInstrumentToBlock(note.instrument)
                        val instrument = rawInstrument?.let { kyrielie.blockbard.client.midi.InstrumentRemapper.remap(it) }
                        if (rawInstrument != null && instrument == null) return@forEach // Muted by InstrumentRemapper

                        // For vanilla instruments, keys 33..57 represent noteblock click indices 0..24.
                        // The actual sounding Minecraft pitch is instrument.midiBase + (note.key - 33).
                        // Unpitched instruments (SNARE, HAT, BASEDRUM) collapse to midiBase.
                        val midiNote = if (instrument != null && !instrument.isPitched) {
                            instrument.midiBase
                        } else if (instrument != null) {
                            instrument.midiBase + (note.key - 33)
                        } else {
                            note.key + 21
                        }
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
        currentTick = 0L
    }

    fun getCurrentTick(): Long = currentTick
    fun getTotalTicks(): Long = totalTick

    fun seekToTick(tick: Long) {
        val nbs = currentNbs ?: return
        val clamped = tick.coerceIn(0L, totalTick.coerceAtLeast(0L))
        val wasPlaying = isPlaying && !isPaused
        val wasPaused = isPaused
        stop()
        currentNbs = nbs
        currentTick = clamped
        if (wasPlaying) {
            play(nbs, clamped)
        } else if (wasPaused) {
            isPaused = true
        }
    }

    fun getDurationSeconds(): Double {
        val nbs = currentNbs ?: return 0.0
        val tempo = if (nbs.tempo > 0f) nbs.tempo else 10f
        return totalTick.toDouble() / tempo
    }

    fun getElapsedSeconds(): Double {
        val nbs = currentNbs ?: return 0.0
        val tempo = if (nbs.tempo > 0f) nbs.tempo else 10f
        return currentTick.toDouble() / tempo
    }
}