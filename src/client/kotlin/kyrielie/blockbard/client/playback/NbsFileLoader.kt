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
    val title: String,
    val author: String = "",
    val originalAuthor: String = ""
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
            val author = stream.readNBSString("author")
            val originalAuthor = stream.readNBSString("original author")
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

            val rawNbs = NbsFile(file, version, tempo, notes, title, author, originalAuthor)
            return SongResampler.resample(rawNbs)
        }
    }
}

object NbsPlayer {
    private var playbackThread: Thread? = null

    /**
     * Guards the lifecycle fields (isPlaying, isPaused, playbackThread, currentTick,
     * totalTick, pendingResumeTick) and the enqueue of each tick's notes. Lock order is
     * always this lock, then ArpeggioScheduler's lock; the scheduler never calls back
     * into the player. onFinished is invoked outside the lock.
     */
    private val lock = Any()

    /**
     * Identity of the current playback run. stop() bumps it, so a thread whose captured
     * id no longer matches is stale: it must exit without touching isPlaying,
     * currentTick, the scheduler or onFinished. See MidiFilePlayer.runId.
     */
    @Volatile
    private var runId: Int = 0

    private fun isCurrent(run: Int): Boolean = runId == run
    @Volatile
    var isPlaying: Boolean = false
        private set
    @Volatile
    var isPaused: Boolean = false
        private set

    /** Set by a paused seekToTick(); resume() restarts playback from this tick. */
    @Volatile
    private var pendingResumeTick: Long = -1L

    // Mirrors MidiFilePlayer.getCurrentTick()/getTotalTicks() — updated inside the
    // playback loop each time a tick's notes are dispatched, plus public getters
    // so PlaybackHud/MainScreen can show NBS progress the same way they show MIDI
    // progress. totalTick is computed once per play() call from notes.last().tick —
    // safe because NbsFileLoader.load() appends notes in non-decreasing tick order
    // (currentTick only increases during jump-decoding for a well-formed file); a
    // malformed file could violate that, but that case is guarded separately by
    // LittleEndianReader's EOF handling rather than re-sorting here on every play().
    @Volatile
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
        synchronized(lock) {
            stop()
            val run = runId  // bumped by stop(), so unique to this run
            isPlaying = true
            isPaused = false
            pendingResumeTick = -1L
            currentNbs = nbs
            currentTick = startFromTick
            totalTick = nbs.notes.lastOrNull()?.tick?.toLong() ?: 0L

            val clock = PlaybackClock(startFromTick / nbs.tempo * 1000.0, MidiFilePlayer.tempoMultiplier)

            val thread = Thread({
                try {
                    val notesByTick = nbs.notes.groupBy { it.tick }
                    val sortedTicks = notesByTick.keys.sorted()

                    for (tick in sortedTicks) {
                        if (!isCurrent(run)) return@Thread
                        if (tick < startFromTick) continue

                        // Song position at 1x tempo. PlaybackClock converts it to wall time
                        // using the live MidiFilePlayer.tempoMultiplier (the single tempo
                        // control shared by both formats) and re-anchors when it changes, so
                        // a mid-song tempo change does not shift already-elapsed time.
                        val tickBaseMs = tick / nbs.tempo * 1000.0

                        while (true) {
                            if (!isCurrent(run)) return@Thread
                            if (isPaused) {
                                val pauseStart = System.currentTimeMillis()
                                while (isPaused) {
                                    Thread.sleep(20)
                                    if (!isCurrent(run)) return@Thread
                                }
                                clock.shift(System.currentTimeMillis() - pauseStart)
                            }
                            val targetMs = clock.wallTimeFor(tickBaseMs, MidiFilePlayer.tempoMultiplier)
                            if (System.currentTimeMillis() >= targetMs) break
                            Thread.sleep(5)
                        }

                        val requests = notesByTick[tick].orEmpty().mapNotNull { note ->
                            val rawInstrument = nbsInstrumentToBlock(note.instrument)
                            val instrument = rawInstrument?.let { kyrielie.blockbard.client.midi.InstrumentRemapper.remap(it) }
                            if (rawInstrument != null && instrument == null) return@mapNotNull null // Muted by InstrumentRemapper

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
                            NoteRequest(midiNote, instrument = instrument)
                        }

                        // Check-and-enqueue is atomic with stop(), so a stale thread can never
                        // enqueue notes after stop() cleared the scheduler.
                        synchronized(lock) {
                            if (!isCurrent(run)) return@Thread
                            currentTick = tick.toLong()
                            requests.forEach { ArpeggioScheduler.enqueue(it) }
                        }
                    }
                    // Only a run that is still current may report completion. A stopped or
                    // replaced run must not touch isPlaying or fire onFinished.
                    val finished = synchronized(lock) {
                        if (isCurrent(run)) { isPlaying = false; true } else false
                    }
                    if (finished) onFinished?.invoke()
                } catch (_: InterruptedException) {
                    synchronized(lock) { if (isCurrent(run)) isPlaying = false }
                }
            }, "BlockBard-NBS-Playback")
            thread.isDaemon = true
            playbackThread = thread
            thread.start()
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!isPlaying || isPaused) return
            isPaused = true
        }
    }

    fun resume() {
        synchronized(lock) {
            if (!isPaused) return
            val resumeTick = pendingResumeTick
            val nbs = currentNbs
            if (resumeTick >= 0L && nbs != null) {
                // Paused seek: no thread is running, so start one at the sought tick.
                pendingResumeTick = -1L
                isPaused = false
                play(nbs, resumeTick)
                return
            }
            isPaused = false
        }
    }

    fun stop() {
        synchronized(lock) {
            runId++  // invalidates the running thread before it is interrupted
            isPlaying = false
            isPaused = false
            pendingResumeTick = -1L
            playbackThread?.interrupt()
            playbackThread = null
            ArpeggioScheduler.clear()
            currentTick = 0L
        }
    }

    fun getCurrentTick(): Long = currentTick
    fun getTotalTicks(): Long = totalTick

    fun seekToTick(tick: Long) {
        synchronized(lock) {
            val nbs = currentNbs ?: return
            val clamped = tick.coerceIn(0L, totalTick.coerceAtLeast(0L))
            val wasPlaying = isPlaying && !isPaused
            val wasPaused = isPaused
            stop()
            currentNbs = nbs
            currentTick = clamped
            val tempo = if (nbs.tempo > 0f) nbs.tempo else 10f
            val seekMs = (clamped.toDouble() / tempo * 1000.0).toLong()
            DynamicRetuneManager.onSeek(seekMs)
            if (wasPlaying) {
                play(nbs, clamped)
            } else if (wasPaused) {
                // Stay paused at the new position; resume() starts playback from here.
                pendingResumeTick = clamped
                isPaused = true
            }
        }
    }

    fun currentPositionMs(): Long {
        val nbs = currentNbs ?: return -1L
        if (!isPlaying && !isPaused) return -1L
        val tempo = if (nbs.tempo > 0f) nbs.tempo else 10f
        return (currentTick.toDouble() / tempo * 1000.0).toLong()
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