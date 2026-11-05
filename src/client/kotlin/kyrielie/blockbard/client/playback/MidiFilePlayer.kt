package kyrielie.blockbard.client.playback

import kyrielie.blockbard.client.midi.MidiChannelResolver
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NotePitch
import kyrielie.blockbard.organ.NoteRequest
import kyrielie.blockbard.util.isPitched
import kyrielie.blockbard.util.midiBase
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
    val noteUsageCounts: Map<NotePitch, Int>,
    val tempoMap: MidiTempoMap = MidiTempoMap.build(emptyList(), ticksPerBeat),
    val title: String? = null,
    val author: String? = null
)

/** Duration of the sequence in milliseconds at 1x tempo. */
val LoadedMidi.durationMs: Long get() {
    if (events.isEmpty()) return 0L
    val lastTick = events.last().tick
    return tempoMap.tickToMs(lastTick)
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

    // Written by the GUI/command thread, read by the playback thread each loop.
    @Volatile
    var tempoMultiplier: Float = 1.0f

    @Volatile
    var isPaused: Boolean = false
        private set

    @Volatile
    private var isPlaying: Boolean = false
    private var playbackThread: Thread? = null

    /**
     * Guards the lifecycle fields (isPlaying, isPaused, playbackThread, currentTick,
     * pendingResumeTick) and the enqueue of each event. Lock order is always
     * this lock, then ArpeggioScheduler's lock; the scheduler never calls back into
     * the player. onFinished and onTickUpdate are invoked outside the lock.
     */
    private val lock = Any()

    /**
     * Identity of the current playback run. stop() bumps it, so a thread whose captured
     * id no longer matches is stale: it must exit without touching isPlaying,
     * currentTick, the scheduler or onFinished. This is what stops an interrupted old
     * thread from clearing isPlaying for the run that replaced it.
     */
    @Volatile
    private var runId: Int = 0

    private fun isCurrent(run: Int): Boolean = runId == run

    @Volatile
    private var currentTick: Long = 0L

    /**
     * Set when seekToTick() is called while paused. There is no live thread then, so
     * resume() starts a fresh one from this tick instead of just clearing isPaused.
     */
    @Volatile
    private var pendingResumeTick: Long = -1L

    var onFinished: (() -> Unit)? = null
    var onTickUpdate: ((currentTick: Long, totalTicks: Long) -> Unit)? = null

    fun parse(file: File): LoadedMidi {
        val sequence = MidiSystem.getSequence(file)
        var tempoUs = 500000  // default 120 BPM
        val tempoChanges = mutableListOf<Pair<Long, Int>>()
        var midiTitle: String? = null
        var midiAuthor: String? = null

        // Tempo (MetaMessage 0x51) is not a channel-voice message, so MidiChannelResolver
        // (which only collects ShortMessages) doesn't see it — read it directly here.
        // Also extract sequence name (0x03), copyright (0x02), and text/credits (0x01).
        sequence.tracks.forEach { track ->
            for (i in 0 until track.size()) {
                val event = track.get(i)
                val msg = event.message
                if (msg is MetaMessage) {
                    when (msg.type) {
                        0x51 -> {
                            val d = msg.data
                            if (d.size >= 3) {
                                val us = ((d[0].toInt() and 0xFF) shl 16) or
                                        ((d[1].toInt() and 0xFF) shl 8) or
                                        (d[2].toInt() and 0xFF)
                                tempoUs = us
                                tempoChanges.add(event.tick to us)
                            }
                        }
                        0x01 -> { // Text event
                            if (midiAuthor == null) {
                                val text = parseMetaString(msg.data)
                                if (text.isNotBlank() && isLikelyAuthor(text)) {
                                    midiAuthor = cleanAuthorString(text)
                                }
                            }
                        }
                        0x02 -> { // Copyright notice
                            if (midiAuthor == null) {
                                val text = parseMetaString(msg.data)
                                if (text.isNotBlank()) {
                                    midiAuthor = cleanAuthorString(text)
                                }
                            }
                        }
                        0x03 -> { // Sequence / track name
                            if (midiTitle == null) {
                                val text = parseMetaString(msg.data)
                                if (text.isNotBlank() && !text.startsWith("Track", ignoreCase = true)) {
                                    midiTitle = text
                                }
                            }
                        }
                    }
                }
            }
        }
        val tempoMap = MidiTempoMap.build(tempoChanges, sequence.resolution)

        // MidiChannelResolver resolves each NOTE_ON to the instrument the minecraft3.sf2
        // soundfont would use for that channel's GM program (or percussion for channel 10),
        // tracking PROGRAM_CHANGE per channel as it scans. See its kdoc for details.
        val resolvedEvents = MidiChannelResolver.resolve(sequence, file.name)
        val sortedEvents = resolvedEvents
            .map { TimedNoteEvent(it.tick, it.midiNote, it.instrument) }
            .sortedBy { it.tick }

        // Unpitched instruments (SNARE, HAT, BASEDRUM) produce the same sound regardless
        // of noteIndex, so all MIDI notes for them are equivalent. Collapse them to the
        // instrument's midiBase before counting so the mapper requests exactly one block
        // per unpitched instrument instead of one per distinct drum MIDI note.
        val normalizedForCounting = sortedEvents.map { event ->
            if (!event.instrument.isPitched)
                event.copy(midiNote = event.instrument.midiBase)
            else event
        }
        val usageCounts = normalizedForCounting
            .groupBy { NotePitch(it.midiNote, it.instrument) }
            .mapValues { it.value.size }

        return LoadedMidi(
            file = file,
            sequence = sequence,
            ticksPerBeat = sequence.resolution,
            baseTempoUsPerBeat = tempoUs,
            events = sortedEvents,
            distinctNotes = sortedEvents.map { it.midiNote }.toSet(),
            noteUsageCounts = usageCounts,
            tempoMap = tempoMap,
            title = midiTitle,
            author = midiAuthor
        )
    }

    private fun parseMetaString(data: ByteArray): String =
        try {
            val s = String(data, Charsets.UTF_8).trim()
            if (s.any { it.isLetterOrDigit() }) s else String(data, Charsets.ISO_8859_1).trim()
        } catch (_: Exception) {
            String(data, Charsets.ISO_8859_1).trim()
        }

    private fun isLikelyAuthor(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("by ") || lower.contains("author") || lower.contains("composer") ||
               lower.contains("arrang") || lower.contains("sequenc") || lower.contains("transcrib") ||
               lower.contains("credit")
    }

    private fun cleanAuthorString(text: String): String =
        text.removePrefix("©").removePrefix("(c)").removePrefix("(C)")
            .replace(Regex("(?i)^(by|author|composer|sequenced by|arranged by|transcribed by)[:\\s]+"), "")
            .trim()

    fun load(file: File): LoadedMidi {
        val midi = parse(file)
        loadedMidi = midi
        currentTick = 0L
        return midi
    }

    fun play(startFromTick: Long = 0L) {
        val midi = loadedMidi ?: return
        synchronized(lock) {
            stop()
            val run = runId  // bumped by stop(), so unique to this run
            isPlaying = true
            isPaused = false
            pendingResumeTick = -1L
            currentTick = startFromTick

            val eventsToPlay = midi.events.filter { it.tick >= startFromTick }
            val totalTicks = midi.events.lastOrNull()?.tick ?: 0L
            val clock = PlaybackClock(midi.tempoMap.tickToMs(startFromTick).toDouble(), tempoMultiplier)

            val thread = Thread({
                try {
                    for (event in eventsToPlay) {
                        if (!isCurrent(run)) return@Thread
                        val eventBaseMs = midi.tempoMap.tickToMs(event.tick).toDouble()

                        // Wait until the target time. The target is recomputed each pass so a
                        // tempo change during a long wait takes effect immediately, and pause
                        // time is folded into the clock by this thread only.
                        while (true) {
                            if (!isCurrent(run)) return@Thread
                            if (isPaused) {
                                val pauseStart = System.currentTimeMillis()
                                while (isPaused) {
                                    Thread.sleep(50)
                                    if (!isCurrent(run)) return@Thread
                                }
                                clock.shift(System.currentTimeMillis() - pauseStart)
                            }
                            val targetWall = clock.wallTimeFor(eventBaseMs, tempoMultiplier)
                            if (System.currentTimeMillis() >= targetWall) break
                            Thread.sleep(5)
                        }

                        // Unpitched instruments (SNARE, HAT, BASEDRUM) collapse to midiBase so the
                        // lookup in ArpeggioScheduler matches the single canonical assignment entry.
                        val noteToEnqueue = if (!event.instrument.isPitched) event.instrument.midiBase else event.midiNote

                        // Check-and-enqueue is atomic with stop(), so a stale thread can never
                        // enqueue a note after stop() cleared the scheduler.
                        synchronized(lock) {
                            if (!isCurrent(run)) return@Thread
                            currentTick = event.tick
                            ArpeggioScheduler.enqueue(NoteRequest(noteToEnqueue, instrument = event.instrument))
                        }
                        onTickUpdate?.invoke(event.tick, totalTicks)
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
            }, "BlockBard-Playback")
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
            if (resumeTick >= 0L) {
                // Paused seek: no thread is running, so start one at the sought tick.
                pendingResumeTick = -1L
                isPaused = false
                play(resumeTick)
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

    fun seekToTick(tick: Long) {
        synchronized(lock) {
            val total = getTotalTicks()
            val clamped = tick.coerceIn(0L, total.coerceAtLeast(0L))
            val wasPlaying = isPlaying && !isPaused
            val wasPaused = isPaused
            stop()
            currentTick = clamped
            if (wasPlaying) {
                play(clamped)
            } else if (wasPaused) {
                // Stay paused at the new position; resume() starts playback from here.
                pendingResumeTick = clamped
                isPaused = true
            }
        }
    }

    fun getDurationSeconds(): Double {
        val midi = loadedMidi ?: return 0.0
        val total = getTotalTicks()
        return midi.tempoMap.tickToMs(total) / 1000.0
    }

    fun getElapsedSeconds(): Double {
        val midi = loadedMidi ?: return 0.0
        return midi.tempoMap.tickToMs(currentTick) / 1000.0
    }

    fun isActive(): Boolean = isPlaying
    fun getCurrentTick(): Long = currentTick
    fun getTotalTicks(): Long = loadedMidi?.events?.lastOrNull()?.tick ?: 0L
}