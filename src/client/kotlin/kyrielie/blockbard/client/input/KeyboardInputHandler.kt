package kyrielie.blockbard.client.input

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

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
        logger.info("KeyboardInputHandler: registered 9 note keys (1–9)")

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            onTick()
        }
    }

    private fun onTick() {
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() != null) return

        val mappings = ConfigManager.config.keyMappings
        keyBindings.forEachIndexed { i, kb ->
            while (kb.consumeClick()) {
                val midiNote = mappings.getOrElse(i) { 54 + i }
                logger.info("KeyboardInputHandler: key ${i + 1} pressed → MIDI note $midiNote")
                mc.player?.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("§b[BlockBard] §fKey ${i + 1} → MIDI $midiNote")
                )
                ArpeggioScheduler.enqueue(NoteRequest(midiNote))
            }
        }
    }
}
