package kyrielie.blockbard.client.input

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
import kyrielie.blockbard.util.midiBase
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

/**
 * Handles 1–9 key presses for direct noteblock playback.
 *
 * Key mapping (default, configurable via ConfigManager.config.keyMappings):
 *   Key 1 → MIDI 54 (F#3, noteIndex 0 on HARP)
 *   Key 2 → MIDI 55 (G3,  noteIndex 1)
 *   Key 3 → MIDI 56 (G#3, noteIndex 2)
 *   Key 4 → MIDI 57 (A3,  noteIndex 3)
 *   Key 5 → MIDI 58 (A#3, noteIndex 4)
 *   Key 6 → MIDI 59 (B3,  noteIndex 5)
 *   Key 7 → MIDI 60 (C4,  noteIndex 6)
 *   Key 8 → MIDI 61 (C#4, noteIndex 7)
 *   Key 9 → MIDI 62 (D4,  noteIndex 8)
 *
 * IMPORTANT: Keys only fire while the BlockBard GUI (MainScreen) is open. The
 * KeyMapping objects registered in [register] exist so the bindings are visible
 * and rebindable in the vanilla Controls menu (and so ConfigManager.config.keyMappings
 * stays meaningful) — but dispatch itself happens via [tryDispatch], called directly
 * from MainScreen.keyPressed(). Polling via KeyMapping.consumeClick() on END_CLIENT_TICK
 * (the previous approach) never fires while a Screen owns input focus, since raw key
 * events are consumed by Screen's input pipeline before they reach the KeyMapping
 * click-counter — see MainScreen.keyPressed() for where this is actually wired.
 */
object KeyboardInputHandler {

    private val logger = LoggerFactory.getLogger("BlockBard/Keyboard")
    private val keyBindings: MutableList<KeyMapping> = mutableListOf()

    fun register() {
        for (i in 1..9) {
            val key = KeyMappingHelper.registerKeyMapping(
                KeyMapping(
                    "key.blockbard.note$i",
                    GLFW.GLFW_KEY_0 + i,
                    KeyMapping.Category.MISC
                )
            )
            keyBindings.add(key)
        }
        logger.info("KeyboardInputHandler: registered 9 note keys (1–9), active only when BlockBard GUI is open")
    }

    /**
     * Map of GLFW key codes to noteIndex (0..24).
     */
    private val keyToNoteIndex: Map<Int, Int> = mapOf(
        // Lower Octave White Keys
        GLFW.GLFW_KEY_Z to 1,         // G
        GLFW.GLFW_KEY_X to 3,         // A
        GLFW.GLFW_KEY_C to 5,         // B
        GLFW.GLFW_KEY_V to 6,         // C
        GLFW.GLFW_KEY_B to 8,         // D
        GLFW.GLFW_KEY_N to 10,        // E
        GLFW.GLFW_KEY_M to 11,        // F
        GLFW.GLFW_KEY_COMMA to 13,    // G
        GLFW.GLFW_KEY_PERIOD to 15,   // A
        GLFW.GLFW_KEY_SLASH to 17,    // B

        // Lower Octave Black Keys
        GLFW.GLFW_KEY_A to 0,         // F#3
        GLFW.GLFW_KEY_S to 2,         // G#
        GLFW.GLFW_KEY_D to 4,         // A#
        GLFW.GLFW_KEY_G to 7,         // C#
        GLFW.GLFW_KEY_H to 9,         // D#
        GLFW.GLFW_KEY_K to 12,        // F#4
        GLFW.GLFW_KEY_L to 14,        // G#
        GLFW.GLFW_KEY_SEMICOLON to 16,// A#

        // Upper Octave
        GLFW.GLFW_KEY_Q to 18,        // C5
        GLFW.GLFW_KEY_W to 20,        // D5
        GLFW.GLFW_KEY_E to 22,        // E5
        GLFW.GLFW_KEY_R to 23,        // F5
        GLFW.GLFW_KEY_2 to 19,        // C#5
        GLFW.GLFW_KEY_3 to 21,        // D#5
        GLFW.GLFW_KEY_5 to 24         // F#5
    )

    /**
     * Maps a GLFW key code to a noteblock noteIndex (0..24), or null if not mapped.
     */
    fun getNoteIndex(keyCode: Int): Int? {
        return keyToNoteIndex[keyCode] ?: run {
            val legacy = keyCode - GLFW.GLFW_KEY_1
            if (legacy in 0..8) legacy else null
        }
    }

    /**
     * Attempts to dispatch [keyCode] as a note-key press. Returns true if [keyCode]
     * matched a mapped note key and the note was enqueued.
     */
    fun tryDispatch(keyCode: Int, instrument: net.minecraft.world.level.block.state.properties.NoteBlockInstrument? = null): Boolean {
        val noteIdx = getNoteIndex(keyCode) ?: return false

        val inst = instrument ?: net.minecraft.world.level.block.state.properties.NoteBlockInstrument.HARP
        val midiNote = inst.midiBase + noteIdx
        logger.debug("KeyboardInputHandler: key {} -> noteIndex {}, MIDI {} ({})", keyCode, noteIdx, midiNote, inst.name)
        val mc = Minecraft.getInstance()
        if (ConfigManager.config.enableChatFeedback) {
            mc.player?.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§b[BlockBard] §fPlay §e${kyrielie.blockbard.util.midiNoteToName(midiNote)}§f (${inst.name})")
            )
        }
        ArpeggioScheduler.enqueue(NoteRequest(midiNote, instrument = inst))
        return true
    }
}