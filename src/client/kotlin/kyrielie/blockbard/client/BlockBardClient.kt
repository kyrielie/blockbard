package kyrielie.blockbard.client

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.gui.MainScreen
import kyrielie.blockbard.client.gui.PlaybackHud
import kyrielie.blockbard.client.input.KeyboardInputHandler
import kyrielie.blockbard.client.input.MidiInputHandler
import kyrielie.blockbard.client.player.MAX_ROTATION_DEGREES_PER_TICK
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.client.player.ROTATION_CONVERGENCE_THRESHOLD_DEGREES
import kyrielie.blockbard.client.player.SoundVerifier
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
    private var soundVerifierRegistered = false

    override fun onInitializeClient() {
        logger.info("BlockBard initializing...")
        ConfigManager.load()
        val cfg = ConfigManager.config
        logger.info("Config loaded: scanRadius=${cfg.scanRadius}, shiftMode=${cfg.shiftMode}, arpeggioStaleTimeoutMs=${cfg.arpeggioStaleTimeoutMs}")

        OrganScanner.scanRadius = cfg.scanRadius
        InstrumentShifter.mode = cfg.shiftModeEnum()
        InstrumentShifter.maxOctaveShift = cfg.maxOctaveShift
        ArpeggioScheduler.staleTimeoutMs = cfg.arpeggioStaleTimeoutMs
        ArpeggioScheduler.rotationInProgressTimeoutMs = cfg.rotationInProgressTimeoutMs
        MAX_ROTATION_DEGREES_PER_TICK = cfg.maxRotationDegreesPerTick
        ROTATION_CONVERGENCE_THRESHOLD_DEGREES = cfg.rotationConvergenceThresholdDegrees

        ArpeggioScheduler.interactDelegate = { pos, request ->
            logger.debug("interactDelegate: → $pos (MIDI ${request.midiNote})")
            PlayerController.playNoteAt(pos, request)
        }
        ArpeggioScheduler.rotationConvergedDelegate = { pos ->
            PlayerController.rotationConverged(pos)
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

        // Prime rotation in START_CLIENT_TICK — this fires before LocalPlayer.tick(),
        // which calls sendPosition(), so each tick's eased rotation step is in that
        // tick's movement packet before END_CLIENT_TICK runs. primeRotation() now eases
        // toward the target at a capped degrees/tick rate (see PlayerController.
        // MAX_ROTATION_DEGREES_PER_TICK) rather than snapping instantly — large turns
        // take several ticks to converge. ArpeggioScheduler.onTick() (END_CLIENT_TICK,
        // below) only fires the actual interact once PlayerController.rotationConverged()
        // confirms the turn has finished, so the use packet never arrives mid-turn.
        ClientTickEvents.START_CLIENT_TICK.register { _ ->
            ArpeggioScheduler.peekNextPos()?.let { PlayerController.primeRotation(it) }
        }

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            // Minecraft.getInstance() (and therefore mc.soundManager) does not exist yet
            // during onInitializeClient() — Fabric Loader calls all ClientModInitializers
            // before the Minecraft instance is constructed (confirmed against Minecraft.java:
            // soundManager is a constructor-assigned field, and the constructor runs after
            // entrypoint dispatch). Registering here, on the first tick the callback fires,
            // is the standard deferred-init pattern: by END_CLIENT_TICK the client is fully
            // up. A one-shot flag avoids calling addListener repeatedly every tick.
            if (!soundVerifierRegistered) {
                mc.soundManager.addListener(SoundVerifier)
                soundVerifierRegistered = true
                logger.info("SoundVerifier registered as a SoundEventListener")
            }

            while (openGuiKey.consumeClick()) {
                logger.info("B pressed — opening MainScreen")
                if (mc.gui.screen() == null) mc.gui.setScreen(MainScreen())
            }
            while (toggleHudKey.consumeClick()) {
                PlaybackHud.isVisible = !PlaybackHud.isVisible
                logger.info("H pressed — HUD visible=${PlaybackHud.isVisible}")
            }
            ArpeggioScheduler.onTick()
        }

        MidiInputHandler.autoConnect()
        logger.info("BlockBard initialized.")
    }
}