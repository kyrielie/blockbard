package kyrielie.blockbard.client.player

import kyrielie.blockbard.util.midiNoteToName
import kyrielie.blockbard.util.midiToNoteIndex
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEventListener
import net.minecraft.client.sounds.WeighedSoundEvents
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Ground-truth playback verification.
 *
 * BlockBard's own logs only ever confirm that a use-item packet was *sent*
 * (ArpeggioScheduler "dispatch" lines, PlayerController "interactWith" lines) — none
 * of them confirm what sound, if any, the client actually ends up playing. A wrong
 * instrument assignment, a stale NoteBlockRegistry entry, a desynced noteblock, or a
 * dropped packet would all look identical from those logs: "dispatch ... result=true".
 *
 * SoundEventListener is a public, mixin-free Minecraft API
 * (net.minecraft.client.sounds.SoundEventListener, registered via
 * Minecraft.getInstance().getSoundManager().addListener(...) — see
 * BlockBardClient.onInitializeClient()) that fires once for every sound the client
 * actually queues to play, including ones triggered by a server-side
 * Level.playSeededSound() call that round-tripped through the (possibly
 * same-process, in singleplayer) server. This is the real signal: if a noteblock
 * click does nothing audible, or plays the wrong instrument/pitch, this is where
 * that becomes visible, decoded from the actual SoundInstance rather than predicted
 * from our own assignment/registry state.
 *
 * How a noteblock's sound encodes (instrument, note) — verified directly against
 * NoteBlock.java / NoteBlockInstrument.java in the decompiled 26.x source:
 *   - The SOUND IDENTIFIER (e.g. "minecraft:block.note_block.harp") encodes the
 *     INSTRUMENT. NoteBlockInstrument's enum constants are registered with exactly
 *     this id pattern, "block.note_block.<lowercase enum name>" — see
 *     NoteBlockInstrument(name, soundEvent, type) in the vanilla source.
 *   - The PITCH float encodes the NOTE INDEX (0-24), via
 *     NoteBlock.getPitchFromNote(note) = 2^((note-12)/12). This runs server-side in
 *     NoteBlock.triggerEvent() and is inverted below in pitchToNoteIndex().
 *
 * Usage: PlayerController.playNoteAt(pos, request) calls [expectNote] immediately
 * before firing the interaction (not after — the resulting sound can arrive before
 * playNoteAt() returns, especially in singleplayer where client and server share a
 * thread/tick). [onPlaySound] then matches the next note_block sound against the
 * oldest pending expectation within [matchWindowMs], logs one line, and updates the
 * rolling tally that [logSummary] reports every [summaryEvery] notes — see kdoc on
 * those for why this design keeps output compact for long songs (the Nocturne test
 * file is 1328 notes; one line per note plus a per-note rollup would be unreadable).
 */
object SoundVerifier : SoundEventListener {

    private val logger = LoggerFactory.getLogger("BlockBard/Verify")

    private const val SOUND_ID_PREFIX = "minecraft:block.note_block."

    /** How long an expectation waits for a matching sound before being declared a miss. */
    var matchWindowMs: Long = 250L

    /** Print a hit/miss rollup every N resolved expectations (hit, miss, or timeout). */
    var summaryEvery: Int = 50

    private data class Expectation(
        val pos: BlockPos,
        val midiNote: Int,
        val instrument: NoteBlockInstrument?,
        val atMs: Long
    )

    // FIFO of outstanding expectations. A real deque, not just a single slot — ArpeggioScheduler
    // dispatches at most one note per tick, but the resulting sound can lag behind by more than
    // one tick under load, so a second expectation can be registered before the first resolves.
    private val pending = ArrayDeque<Expectation>()
    private val lock = Any()

    private var hits = 0
    private var misses = 0
    private var sinceLastSummary = 0

    /**
     * Registers an expectation: "the next note_block sound near [pos] should be
     * [instrument] (or any, if null) playing MIDI note [midiNote]." Called by
     * PlayerController.playNoteAt(pos, request) immediately before the attack interaction.
     */
    fun expectNote(pos: BlockPos, midiNote: Int, instrument: NoteBlockInstrument?) = synchronized(lock) {
        pruneExpired(System.currentTimeMillis())
        pending.addLast(Expectation(pos, midiNote, instrument, System.currentTimeMillis()))
    }

    // SoundEventListener — see kdoc above for why this is the right hook (public API,
    // no mixin, fires for every sound with a subtitle, which includes every vanilla
    // note_block instrument sound).
    override fun onPlaySound(sound: SoundInstance, soundEvent: WeighedSoundEvents, range: Float) {
        val id = runCatching { sound.identifier.toString() }.getOrNull() ?: return
        if (!id.startsWith(SOUND_ID_PREFIX)) return  // not a noteblock sound — ignore silently

        val instrumentName = id.removePrefix(SOUND_ID_PREFIX)
        val actualInstrument = runCatching { NoteBlockInstrument.valueOf(instrumentName.uppercase()) }.getOrNull()
        val actualNoteIndex = pitchToNoteIndex(sound.pitch)

        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneExpired(now)

            val expectation = pending.firstOrNull()
            if (expectation == null) {
                // A note_block sound played with nothing pending — e.g. redstone-triggered,
                // or this is the scale test's own rapid-fire pattern outracing registration.
                // Not necessarily a bug; log at debug rather than spam warn for every one.
                logger.debug("unmatched note_block sound: $instrumentName idx=${actualNoteIndex ?: "?"} (no pending expectation)")
                return
            }

            pending.removeFirst()
            val expectedNoteIndex = expectation.instrument?.let { midiToNoteIndex(expectation.midiNote, it) }
            val instrumentMatches = expectation.instrument == null || expectation.instrument == actualInstrument
            val noteMatches = actualNoteIndex != null && expectedNoteIndex != null && actualNoteIndex == expectedNoteIndex
            val midiLabel = "${expectation.midiNote} (${midiNoteToName(expectation.midiNote)})"

            if (instrumentMatches && (expectedNoteIndex == null || noteMatches)) {
                hits++
                logger.info(
                    "OK   MIDI $midiLabel -> ${actualInstrument?.name ?: instrumentName} idx${actualNoteIndex ?: "?"} (${expectation.pos})"
                )
            } else {
                misses++
                val expectedDesc = expectation.instrument?.name ?: "any"
                logger.warn(
                    "MISS MIDI $midiLabel -> expected $expectedDesc" +
                        (expectedNoteIndex?.let { " idx$it" } ?: "") +
                        ", got ${actualInstrument?.name ?: instrumentName} idx${actualNoteIndex ?: "?"} (${expectation.pos})"
                )
            }
            sinceLastSummary++
            if (sinceLastSummary >= summaryEvery) {
                logSummaryLocked()
            }
        }
    }

    // Drops expectations older than matchWindowMs as timeouts (counted as misses) — a
    // dropped packet, a silenced block, or a desynced noteblock can mean no sound ever
    // arrives for a given click; without this, pending would grow unboundedly and every
    // later sound would incorrectly match against a stale expectation instead of being
    // correctly reported as unmatched.
    private fun pruneExpired(nowMs: Long) {
        while (pending.isNotEmpty() && nowMs - pending.first().atMs > matchWindowMs) {
            val timedOut = pending.removeFirst()
            misses++
            sinceLastSummary++
            logger.warn(
                "MISS MIDI ${timedOut.midiNote} (${midiNoteToName(timedOut.midiNote)}) -> no note_block sound within ${matchWindowMs}ms (${timedOut.pos})"
            )
            if (sinceLastSummary >= summaryEvery) {
                logSummaryLocked()
            }
        }
    }

    /** Inverse of NoteBlock.getPitchFromNote(note) = 2^((note-12)/12); rounds to the nearest int. */
    private fun pitchToNoteIndex(pitch: Float): Int? {
        if (pitch <= 0f) return null
        val note = 12.0 + 12.0 * (ln(pitch.toDouble()) / ln(2.0))
        val rounded = note.roundToInt()
        return rounded.takeIf { it in 0..24 }
    }

    /** Compact rollup instead of per-note spam — see class kdoc. Safe to call from outside an existing lock (e.g. a future debug command). */
    fun logSummary() = synchronized(lock) { logSummaryLocked() }

    private fun logSummaryLocked() {
        val total = hits + misses
        if (total == 0) return
        logger.info("--- verify summary: $hits/$total OK (${"%.1f".format(100.0 * hits / total)}%) since last summary ---")
        sinceLastSummary = 0
    }

    /** Resets all counters and clears pending expectations — call before starting a new test run. */
    fun reset() = synchronized(lock) {
        pending.clear()
        hits = 0
        misses = 0
        sinceLastSummary = 0
        logger.info("verify counters reset")
    }
}
