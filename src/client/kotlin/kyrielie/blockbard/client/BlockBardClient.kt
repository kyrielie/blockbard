
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
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

object BlockBardClient : ClientModInitializer {

    const val MOD_ID = "blockbard"
    val logger = LoggerFactory.getLogger("BlockBard")

    private lateinit var openGuiKey: KeyMapping
    private lateinit var toggleHudKey: KeyMapping

    override fun onInitializeClient() {
        logger.info("BlockBard initializing...")
        ConfigManager.load()
        val cfg = ConfigManager.config
        logger.info("Config loaded: scanRadius=${cfg.scanRadius}, shiftMode=${cfg.shiftMode}, arpeggioStaleTimeoutMs=${cfg.arpeggioStaleTimeoutMs}")

        OrganScanner.scanRadius = cfg.scanRadius
        InstrumentShifter.mode = cfg.shiftModeEnum()
        InstrumentShifter.maxOctaveShift = cfg.maxOctaveShift
        ArpeggioScheduler.staleTimeoutMs = cfg.arpeggioStaleTimeoutMs

        ArpeggioScheduler.interactDelegate = { pos ->
            logger.debug("interactDelegate: → $pos")
            PlayerController.interactWith(pos)
        }

        openGuiKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.blockbard.open_gui", GLFW.GLFW_KEY_B, KeyMapping.Category.MISC)
        )
        toggleHudKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.blockbard.toggle_hud", GLFW.GLFW_KEY_H, KeyMapping.Category.MISC)
        )
        logger.info("Keybindings registered: B=open GUI, H=toggle HUD")

        KeyboardInputHandler.register()
        PlaybackHud.register()

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            while (openGuiKey.consumeClick()) {
                logger.info("B pressed — opening MainScreen")
                if (mc.gui.screen() == null) mc.gui.setScreen(MainScreen())
            }
            while (toggleHudKey.consumeClick()) {
                PlaybackHud.isVisible = !PlaybackHud.isVisible
                logger.info("H pressed — HUD visible=${PlaybackHud.isVisible}")
            }
            // Phase 2: aim at the next queued block BEFORE dispatching the interact.
            // This puts the rotation into the movement packet that the engine sends this
            // tick, so the server sees the updated facing before the use packet arrives
            // next tick — matching Baritone's PRE player-update rotation pattern.
            ArpeggioScheduler.peekNextPos()?.let { PlayerController.primeRotation(it) }
            ArpeggioScheduler.onTick()
        }

        MidiInputHandler.autoConnect()
        logger.info("BlockBard initialized.")
    }
}
