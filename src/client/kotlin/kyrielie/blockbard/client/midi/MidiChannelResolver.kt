package kyrielie.blockbard.midi

import kyrielie.blockbard.client.playback.LoadedMidi
import kyrielie.blockbard.client.playback.TimedNoteEvent
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.ShortMessage
import java.io.File

private val logger = LoggerFactory.getLogger("BlockBard/MidiChannelResolver")

/** A MIDI event with its channel and GM program resolved to a NoteBlockInstrument. */
data class ResolvedNoteEvent(
    val tick: Long,
    val midiNote: Int,
    val instrument: NoteBlockInstrument
)

/**
 * Reads a MIDI file and resolves each NOTE_ON event to the [NoteBlockInstrument]
 * that the minecraft3.sf2 soundfont would use for that channel's program.
 *
 * Channel 10 (index 9) is always treated as percussion regardless of program.
 *
 * This is intentionally separate from [MidiFilePlayer] — that class only cares about
 * note timing; this one cares about which instrument each note belongs to.
 */
object MidiChannelResolver {

    /**
     * Reads [file] and returns every NOTE_ON event with its instrument resolved.
     * Program-change events are tracked per-channel and applied from the point
     * they appear; notes before the first program change use program 0 (piano → harp).
     */
    fun resolve(file: File): List<ResolvedNoteEvent> {
        val sequence = MidiSystem.getSequence(file)
        // channel -> current GM program (default 0)
        val channelProgram = IntArray(16) { 0 }

        val events = mutableListOf<Pair<Long, ShortMessage>>() // tick, message

        // Collect all channel-voice messages in tick order across all tracks
        sequence.tracks.forEach { track ->
            for (i in 0 until track.size()) {
                val event = track.get(i)
                val msg = event.message
                if (msg is ShortMessage) {
                    events.add(event.tick to msg)
                }
            }
        }
        events.sortBy { it.first }

        val resolved = mutableListOf<ResolvedNoteEvent>()
        for ((tick, msg) in events) {
            when (msg.command) {
                ShortMessage.PROGRAM_CHANGE -> {
                    channelProgram[msg.channel] = msg.data1
                }
                ShortMessage.NOTE_ON -> {
                    if (msg.data2 == 0) continue // velocity-0 note-on = note-off
                    val instrument = if (msg.channel == 9) {
                        MidiInstrumentMap.percussionInstrument(msg.data1)
                    } else {
                        MidiInstrumentMap.primaryFor(channelProgram[msg.channel])
                    }
                    resolved.add(ResolvedNoteEvent(tick, msg.data1, instrument))
                }
            }
        }

        logger.info("MidiChannelResolver: ${resolved.size} events from ${file.name}")
        return resolved
    }

    /**
     * Returns the distinct (instrument, midiNote) pairs that [file] requires.
     * This is the input to [OrganReadinessChecker.check].
     */
    fun resolveRequirements(file: File): Set<MidiNoteRequirement> =
        resolve(file).map { MidiNoteRequirement(it.instrument, it.midiNote) }.toSet()

    /**
     * Returns per-channel program assignments as a human-readable summary for the GUI,
     * e.g. "Ch1: piano 1 → HARP, Ch10: percussion".
     */
    fun channelSummary(file: File): List<String> {
        val sequence = MidiSystem.getSequence(file)
        val channelProgram = IntArray(16) { 0 }
        val channelsSeen = mutableSetOf<Int>()

        sequence.tracks.forEach { track ->
            for (i in 0 until track.size()) {
                val event = track.get(i)
                val msg = event.message
                if (msg is ShortMessage) {
                    when (msg.command) {
                        ShortMessage.PROGRAM_CHANGE -> channelProgram[msg.channel] = msg.data1
                        ShortMessage.NOTE_ON -> if (msg.data2 > 0) channelsSeen.add(msg.channel)
                    }
                }
            }
        }

        return channelsSeen.sorted().map { ch ->
            if (ch == 9) {
                "Ch10: percussion"
            } else {
                val prog = channelProgram[ch]
                val instr = MidiInstrumentMap.primaryFor(prog)
                "Ch${ch + 1}: prog $prog → ${instr.name}"
            }
        }
    }
}