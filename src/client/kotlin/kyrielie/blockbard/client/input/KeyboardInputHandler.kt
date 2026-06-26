package kyrielie.blockbard.client.input

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteRequest
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
// KeyBindingHelper → KeyMappingHelper (fabric-key-mapping-api-v1)
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

object KeyboardInputHandler {

    private val keyBindings: MutableList<KeyMapping> = mutableListOf()

    fun register() {
        for (i in 1..9) {
            // KeyMapping constructor now takes KeyMapping.Category instead of a raw String
            val key = KeyMappingHelper.registerKeyMapping(
                KeyMapping(
                    "key.blockbard.note$i",
                    GLFW.GLFW_KEY_0 + i,
                    KeyMapping.Category.MISC
                )
            )
            keyBindings.add(key)
        }

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            onTick()
        }
    }

    private fun onTick() {
        val mc = Minecraft.getInstance()
        // Screen access: mc.gui.screen()
        if (mc.gui.screen() != null) return  // Don't trigger during GUI

        val mappings = ConfigManager.config.keyMappings
        keyBindings.forEachIndexed { i, kb ->
            while (kb.consumeClick()) {
                val midiNote = mappings.getOrElse(i) { 54 + i }
                ArpeggioScheduler.enqueue(NoteRequest(midiNote))
            }
        }
    }
}
