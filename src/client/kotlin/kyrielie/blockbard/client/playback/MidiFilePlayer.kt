package kyrielie.blockbard.client.playback

import kyrielie.blockbard.midi.MidiChannelResolver
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NotePitch
import kyrielie.blockbard.organ.NoteRequest
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import java.io.File

data class TimedNoteEvent(val tick: Long, val midiNote: Int, val instrument: NoteBlockInstrument)

data class LoadedMidi(
    val file: File,
    val sequence: Sequence,
    val ticksPerBeat: Int,
    val baseTempoUsPerBeat: Int,
    val events: List<TimedNoteEvent>,
    val distinctNotes: Set<Int>,
    /**
     * Usage counts per (midiNote, instrument) pair, for MidiToOrganMapper priority —
     * the most-used notes get first pick of available blocks. Instrument is resolved
     * per MIDI channel via MidiChannelResolver (GM program → NoteBlockInstrument),
     * so two channels playing the same pitch on different instruments are counted
     * and later assigned separately rather than collapsed into one pitch bucket.
     */
    val noteUsageCounts: Map<NotePitch, Int>
)

/** Duration of the sequence in milliseconds at 1x tempo. */
val LoadedMidi.durationMs: Long get() {
    if (events.isEmpty()) return 0L
    val lastTick = events.last().tick
    return tickToMs(lastTick, ticksPerBeat, baseTempoUsPerBeat)
}

fun tickToMs(tick: Long, ticksPerBeat: Int, usPerBeat: Int): Long {
    return ((tick.toDouble() / ticksPerBeat) * (usPerBeat / 1000.0)).toLong()
}

fun tickToScaledMs(tick: Long, ticksPerBeat: Int, usPerBeat: Int, tempoMultiplier: Float): Long {
    val realMs = tickToMs(tick, ticksPerBeat, usPerBeat)
    return (realMs / tempoMultiplier).toLong()
}

object MidiFilePlayer {

    var loadedMidi: LoadedMidi? = null
        private set

    var tempoMultiplier: Float = 1.0f

    var isPaused: Boolean = false
        private set

    private var isPlaying: Boolean = false
    private var playbackThread: Thread? = null
    private var pausedAtMs: Long = 0L
    private var startWallMs: Long = 0L
    private var startTick: Long = 0L
    private var currentTick: Long = 0L

    var onFinished: (() -> Unit)? = null
    var onTickUpdate: ((currentTick: Long, totalTicks: Long) -> Unit)? = null

    fun load(file: File): LoadedMidi {
        val sequence = MidiSystem.getSequence(file)
        var tempoUs = 500000  // default 120 BPM

        // Tempo (MetaMessage 0x51) is not a channel-voice message, so MidiChannelResolver
        // (which only collects ShortMessages) doesn't see it — read it directly here.
        sequence.tracks.forEach { track ->
            for (i in 0 until track.size()) {
                val msg = track.get(i).message
                if (msg is MetaMessage && msg.type == 0x51) {
                    val d = msg.data
                    if (d.size >= 3) {
                        tempoUs = ((d[0].toInt() and 0xFF) shl 16) or
                                ((d[1].toInt() and 0xFF) shl 8) or
                                (d[2].toInt() and 0xFF)
                    }
                }
            }
        }

        // MidiChannelResolver resolves each NOTE_ON to the instrument the minecraft3.sf2
        // soundfont would use for that channel's GM program (or percussion for channel 10),
        // tracking PROGRAM_CHANGE per channel as it scans. See its kdoc for details.
        // Note: this re-parses the file independently via its own MidiSystem.getSequence
        // call rather than reusing `sequence` above — a small duplicate parse, traded for
        // keeping MidiChannelResolver's resolve(File) signature self-contained and usable
        // by other callers (e.g. OrganReadinessChecker) without needing a Sequence handle.
        val resolvedEvents = MidiChannelResolver.resolve(file)
        val sortedEvents = resolvedEvents
            .map { TimedNoteEvent(it.tick, it.midiNote, it.instrument) }
            .sortedBy { it.tick }
        val usageCounts = sortedEvents
            .groupBy { NotePitch(it.midiNote, it.instrument) }
            .mapValues { it.value.size }

        val midi = LoadedMidi(
            file = file,
            sequence = sequence,
            ticksPerBeat = sequence.resolution,
            baseTempoUsPerBeat = tempoUs,
            events = sortedEvents,
            distinctNotes = sortedEvents.map { it.midiNote }.toSet(),
            noteUsageCounts = usageCounts
        )
        loadedMidi = midi
        currentTick = 0L
        return midi
    }

    fun play(startFromTick: Long = 0L) {
        val midi = loadedMidi ?: return
        stop()
        isPlaying = true
        isPaused = false
        startTick = startFromTick
        startWallMs = System.currentTimeMillis()
        currentTick = startFromTick

        val eventsToPlay = midi.events.filter { it.tick >= startFromTick }

        playbackThread = Thread({
            try {
                for (event in eventsToPlay) {
                    if (!isPlaying) break
                    val scaledMs = tickToScaledMs(
                        event.tick - startTick,
                        midi.ticksPerBeat,
                        midi.baseTempoUsPerBeat,
                        tempoMultiplier
                    )
                    val targetWall = startWallMs + scaledMs

                    // Wait until target time, checking for pause
                    while (System.currentTimeMillis() < targetWall) {
                        if (!isPlaying) return@Thread
                        while (isPaused) {
                            Thread.sleep(50)
                            if (!isPlaying) return@Thread
                        }
                        Thread.sleep(5)
                    }
                    if (!isPlaying) return@Thread

                    currentTick = event.tick
                    onTickUpdate?.invoke(currentTick, midi.events.lastOrNull()?.tick ?: 0L)
                    // instrument is passed through so ArpeggioScheduler.resolvePos can
                    // look up the (midiNote, instrument) assignment built at tuning time
                    // (see MainScreen.startTuning) instead of falling back to a
                    // pitch-only live lookup that ignores which instrument this note
                    // was authored for.
                    ArpeggioScheduler.enqueue(NoteRequest(event.midiNote, instrument = event.instrument))
                }
                isPlaying = false
                onFinished?.invoke()
            } catch (_: InterruptedException) {
                isPlaying = false
            }
        }, "BlockBard-Playback")
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
        // Adjust startWallMs to account for paused time
        val pausedDuration = System.currentTimeMillis() - pausedAtMs
        startWallMs += pausedDuration
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

    fun seekToTick(tick: Long) {
        val wasPlaying = isPlaying && !isPaused
        stop()
        if (wasPlaying) play(tick)
    }

    fun isActive(): Boolean = isPlaying
    fun getCurrentTick(): Long = currentTick
    fun getTotalTicks(): Long = loadedMidi?.events?.lastOrNull()?.tick ?: 0L
}
