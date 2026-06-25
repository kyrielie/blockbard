package kyrielie.blockbard.client

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.gui.MainScreen
import kyrielie.blockbard.client.gui.PlaybackHud
import kyrielie.blockbard.client.input.KeyboardInputHandler
import kyrielie.blockbard.client.input.MidiInputHandler
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.InstrumentShifter
import kyrielie.blockbard.client.organ.OrganScanner
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

object BlockBardClient : ClientModInitializer {

    const val MOD_ID = "blockbard"

    private lateinit var openGuiKey: KeyMapping
    private lateinit var toggleHudKey: KeyMapping

    override fun onInitializeClient() {
        ConfigManager.load()
        val cfg = ConfigManager.config

        // Apply config to subsystems
        OrganScanner.scanRadius = cfg.scanRadius
        InstrumentShifter.mode = cfg.shiftModeEnum()
        InstrumentShifter.maxOctaveShift = cfg.maxOctaveShift
        ArpeggioScheduler.staleTimeoutMs = cfg.arpeggioStaleTimeoutMs

        // Wire the interact delegate so ArpeggioScheduler can call PlayerController without
        // the main sourceset depending on the client sourceset.
        ArpeggioScheduler.interactDelegate = { pos -> PlayerController.interactWith(pos) }

        // Register hotkeys
        openGuiKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.blockbard.open_gui", GLFW.GLFW_KEY_B, "category.blockbard")
        )
        toggleHudKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.blockbard.toggle_hud", GLFW.GLFW_KEY_H, "category.blockbard")
        )

        // Register live-play keys 1–9
        KeyboardInputHandler.register()

        // Register HUD
        PlaybackHud.register()

        // Main tick event
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            while (openGuiKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(MainScreen())
            }
            while (toggleHudKey.consumeClick()) {
                PlaybackHud.isVisible = !PlaybackHud.isVisible
            }
            ArpeggioScheduler.onTick()
        }

        // Auto-connect saved MIDI device
        MidiInputHandler.autoConnect()
    }
}
