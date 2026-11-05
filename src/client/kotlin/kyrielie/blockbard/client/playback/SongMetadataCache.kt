package kyrielie.blockbard.client.playback

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class SongMetadata(
    val durationSeconds: Int,
    val bpm: Int,
    val distinctNotes: Int,
    val isNbs: Boolean,
    val title: String? = null,
    val author: String? = null,
    val instruments: List<NoteBlockInstrument> = emptyList()
) {
    val durationFormatted: String
        get() {
            val mins = durationSeconds / 60
            val secs = durationSeconds % 60
            return "%02d:%02d".format(mins, secs)
        }

    val summaryString: String
        get() {
            val authorPart = if (!author.isNullOrBlank()) "by $author  ·  " else ""
            return "$authorPart$durationFormatted  ·  $bpm BPM  ·  $distinctNotes notes"
        }
}

object SongMetadataCache {
    private val logger = LoggerFactory.getLogger("BlockBard/MetadataCache")
    private val cache = ConcurrentHashMap<String, SongMetadata>()
    private val threadPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "BlockBard-MetadataLoader").apply { isDaemon = true }
    }

    private fun cacheKey(file: File): String = "${file.absolutePath}:${file.lastModified()}:${file.length()}"

    fun get(file: File): SongMetadata? {
        val key = cacheKey(file)
        val existing = cache[key]
        if (existing != null) return existing

        // Queue background extraction if not cached
        threadPool.submit {
            try {
                val meta = extract(file)
                if (meta != null) {
                    cache[key] = meta
                }
            } catch (e: Exception) {
                logger.debug("Failed to extract metadata for ${file.name}: ${e.message}")
            }
        }
        return null
    }

    private fun extract(file: File): SongMetadata? {
        val ext = file.extension.lowercase()
        return when (ext) {
            "nbs" -> {
                val nbs = NbsFileLoader.load(file)
                val lastTick = nbs.notes.maxOfOrNull { it.tick } ?: 0
                val tempo = if (nbs.tempo > 0f) nbs.tempo else 10f
                val durationSecs = (lastTick / tempo).toInt()
                val bpm = (tempo * 6f).toInt() // approx BPM (20 tps = 120 bpm)
                val distinct = nbs.notes.map { it.key to it.instrument }.distinct().size
                val author = when {
                    nbs.author.isNotBlank() && nbs.originalAuthor.isNotBlank() && !nbs.author.equals(nbs.originalAuthor, ignoreCase = true) ->
                        "${nbs.author} (orig: ${nbs.originalAuthor})"
                    nbs.author.isNotBlank() -> nbs.author
                    nbs.originalAuthor.isNotBlank() -> nbs.originalAuthor
                    else -> null
                }
                val instruments = nbs.notes
                    .mapNotNull { nbsInstrumentToBlock(it.instrument) }
                    .distinct()
                    .sortedBy { it.ordinal }

                SongMetadata(
                    durationSeconds = durationSecs,
                    bpm = bpm,
                    distinctNotes = distinct,
                    isNbs = true,
                    title = nbs.title.ifBlank { null },
                    author = author,
                    instruments = instruments
                )
            }
            "mid", "midi" -> {
                val midi = MidiFilePlayer.parse(file)
                val lastTick = midi.events.lastOrNull()?.tick ?: 0L
                val durationMs = midi.tempoMap.tickToMs(lastTick)
                val durationSecs = (durationMs / 1000).toInt()
                val usPerBeat = midi.tempoMap.events.firstOrNull()?.usPerBeat ?: 500000
                val bpm = (60_000_000.0 / usPerBeat.coerceAtLeast(1)).toInt()
                val distinct = midi.distinctNotes.size
                val instruments = midi.events
                    .map { it.instrument }
                    .distinct()
                    .sortedBy { it.ordinal }

                SongMetadata(
                    durationSeconds = durationSecs,
                    bpm = bpm,
                    distinctNotes = distinct,
                    isNbs = false,
                    title = midi.title,
                    author = midi.author,
                    instruments = instruments
                )
            }
            else -> null
        }
    }

    fun clear() {
        cache.clear()
    }
}
