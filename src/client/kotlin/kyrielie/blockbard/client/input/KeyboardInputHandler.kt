package kyrielie.blockbard.client.input

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
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
     * Attempts to dispatch [keyCode] as a note-key press. Returns true if [keyCode]
     * matched one of the 9 note keys (1–9) and the note was enqueued — callers should
     * treat a true return as "consumed" so the key doesn't also fall through to
     * vanilla hotbar-slot switching.
     */
    fun tryDispatch(keyCode: Int): Boolean {
        val i = keyCode - GLFW.GLFW_KEY_1
        if (i < 0 || i > 8) return false

        val mc = Minecraft.getInstance()
        val mappings = ConfigManager.config.keyMappings
        val midiNote = mappings.getOrElse(i) { 54 + i }
        logger.info("KeyboardInputHandler: key ${i + 1} → MIDI $midiNote")
        mc.player?.sendSystemMessage(
            net.minecraft.network.chat.Component.literal("§b[BlockBard] §fKey ${i + 1} → MIDI $midiNote")
        )
        ArpeggioScheduler.enqueue(NoteRequest(midiNote))
        return true
    }
}