package kyrielie.blockbard.client.playback

import kyrielie.blockbard.util.midiToNoteIndex
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.pow

/**
 * Streams song audio client-side using Minecraft's local sound engine.
 * Allows auditioning tracks directly without placing blocks or sending packets.
 */
object SongAudioPreviewer {

    private val logger = LoggerFactory.getLogger("BlockBard/Previewer")

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var currentFile: File? = null
        private set

    private var previewThread: Thread? = null

    var onFinished: (() -> Unit)? = null

    fun startPreview(file: File, tempoMultiplier: Float = 1.0f) {
        stopPreview()
        isPlaying = true
        currentFile = file

        previewThread = Thread({
            try {
                logger.info("Preview started: ${file.name}")
                val ext = file.extension.lowercase()
                if (ext in listOf("mid", "midi")) {
                    previewMidi(file, tempoMultiplier)
                } else if (ext == "nbs") {
                    previewNbs(file, tempoMultiplier)
                }
            } catch (e: InterruptedException) {
                logger.debug("Preview interrupted")
            } catch (e: Exception) {
                logger.warn("Preview failed for ${file.name}: ${e.message}", e)
            } finally {
                isPlaying = false
                currentFile = null
                onFinished?.invoke()
                logger.info("Preview finished: ${file.name}")
            }
        }, "BlockBard-AudioPreview").apply {
            isDaemon = true
            start()
        }
    }

    fun stopPreview() {
        if (!isPlaying) return
        isPlaying = false
        currentFile = null
        previewThread?.interrupt()
        previewThread = null
    }

    private fun previewMidi(file: File, tempoMultiplier: Float) {
        val midi = MidiFilePlayer.load(file)
        val startWallMs = System.currentTimeMillis()

        for (event in midi.events) {
            if (!isPlaying) break
            val scaledMs = midi.tempoMap.tickToScaledMs(event.tick, tempoMultiplier)
            val targetWall = startWallMs + scaledMs

            while (System.currentTimeMillis() < targetWall) {
                if (!isPlaying) return
                Thread.sleep(2)
            }

            val instrument = kyrielie.blockbard.client.midi.InstrumentRemapper.remap(event.instrument) ?: continue
            val noteIndex = midiToNoteIndex(event.midiNote, instrument) ?: (event.midiNote - 54).coerceIn(0, 24)
            playLocalNote(instrument, noteIndex)
        }
    }

    private fun previewNbs(file: File, tempoMultiplier: Float) {
        val nbs = NbsFileLoader.load(file)
        if (nbs.notes.isEmpty()) return

        val tickDurationMs = ((1000.0 / nbs.tempo) / tempoMultiplier.coerceAtLeast(0.1f)).toLong()
        val startWallMs = System.currentTimeMillis()
        val notesByTick = nbs.notes.groupBy { it.tick }
        val maxTick = nbs.notes.maxOf { it.tick }

        for (tick in 0..maxTick) {
            if (!isPlaying) break
            val targetWall = startWallMs + tick * tickDurationMs

            while (System.currentTimeMillis() < targetWall) {
                if (!isPlaying) return
                Thread.sleep(2)
            }

            val notesAtTick = notesByTick[tick] ?: continue
            for (note in notesAtTick) {
                val rawInstrument = nbsInstrumentToBlock(note.instrument) ?: NoteBlockInstrument.HARP
                val instrument = kyrielie.blockbard.client.midi.InstrumentRemapper.remap(rawInstrument) ?: continue
                val midiNote = note.key + 21
                val noteIndex = midiToNoteIndex(midiNote, instrument) ?: (note.key - 33).coerceIn(0, 24)
                playLocalNote(instrument, noteIndex)
            }
        }
    }

    /** Plays a single note sound locally at the player's position. */
    fun playLocalNote(instrument: NoteBlockInstrument, noteIndex: Int) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val pitch = 2.0.pow((noteIndex - 12) / 12.0).toFloat()

        mc.execute {
            level.playLocalSound(
                player.x,
                player.y,
                player.z,
                instrument.getSoundEvent().value(),
                SoundSource.RECORDS,
                3.0f,
                pitch,
                false
            )
        }
    }
}
