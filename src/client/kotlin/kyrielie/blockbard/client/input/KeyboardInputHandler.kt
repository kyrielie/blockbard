package kyrielie.blockbard.client.input

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.gui.MainScreen
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
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
 * IMPORTANT: Keys only fire while the BlockBard GUI (MainScreen) is open.
 * They are suppressed entirely when no screen is open or a different screen
 * is showing — this prevents interference with vanilla number-key hotbar.
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

        ClientTickEvents.END_CLIENT_TICK.register { _ -> onTick() }
    }

    private fun onTick() {
        val mc = Minecraft.getInstance()

        // Only fire when BlockBard's own screen is open
        val screen = mc.gui.screen()
        if (screen !is MainScreen) {
            // Drain any queued clicks silently so they don't fire later when the screen opens
            keyBindings.forEach { kb -> while (kb.consumeClick()) { /* discard */ } }
            return
        }

        val mappings = ConfigManager.config.keyMappings
        keyBindings.forEachIndexed { i, kb ->
            while (kb.consumeClick()) {
                val midiNote = mappings.getOrElse(i) { 54 + i }
                logger.info("KeyboardInputHandler: key ${i + 1} → MIDI $midiNote")
                mc.player?.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("§b[BlockBard] §fKey ${i + 1} → MIDI $midiNote")
                )
                ArpeggioScheduler.enqueue(NoteRequest(midiNote))
            }
        }
    }
}
