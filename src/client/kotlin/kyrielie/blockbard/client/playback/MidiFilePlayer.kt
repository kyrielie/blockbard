package kyrielie.blockbard.client.playback

import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import java.io.File

data class TimedNoteEvent(val tick: Long, val midiNote: Int)

data class LoadedMidi(
    val file: File,
    val sequence: Sequence,
    val ticksPerBeat: Int,
    val baseTempoUsPerBeat: Int,
    val events: List<TimedNoteEvent>,
    val distinctNotes: Set<Int>,
    /** Maps note usage counts for MidiToOrganMapper priority. */
    val noteUsageCounts: Map<Int, Int>
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
        val events = mutableListOf<TimedNoteEvent>()
        var tempoUs = 500000  // default 120 BPM

        sequence.tracks.forEach { track ->
            for (i in 0 until track.size()) {
                val event = track.get(i)
                val msg = event.message
                when {
                    msg is MetaMessage && msg.type == 0x51 -> {
                        // Tempo change: 3 bytes big-endian microseconds per beat
                        val d = msg.data
                        if (d.size >= 3) {
                            tempoUs = ((d[0].toInt() and 0xFF) shl 16) or
                                    ((d[1].toInt() and 0xFF) shl 8) or
                                    (d[2].toInt() and 0xFF)
                        }
                    }
                    msg is ShortMessage && msg.command == ShortMessage.NOTE_ON && msg.data2 > 0 -> {
                        events.add(TimedNoteEvent(event.tick, msg.data1))
                    }
                }
            }
        }

        val sortedEvents = events.sortedBy { it.tick }
        val usageCounts = sortedEvents.groupBy { it.midiNote }.mapValues { it.value.size }

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
                    ArpeggioScheduler.enqueue(NoteRequest(event.midiNote))
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
