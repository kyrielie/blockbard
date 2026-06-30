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
import kyrielie.blockbard.util.DebugLog
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
        logger.info(
            "Config loaded: scanRadius=${cfg.scanRadius}, shiftMode=${cfg.shiftMode}, " +
            "arpeggioStaleTimeoutMs=${cfg.arpeggioStaleTimeoutMs}, " +
            "debugLogging=${cfg.debugLogging}, maxNotesPerTick=${cfg.maxNotesPerTick}, " +
            "loopMode=${cfg.loopMode}"
        )

        // Tier 1: apply debug logging flag to DebugLog before anything else logs
        DebugLog.enabled = cfg.debugLogging

        OrganScanner.scanRadius = cfg.scanRadius
        InstrumentShifter.mode = cfg.shiftModeEnum()
        InstrumentShifter.maxOctaveShift = cfg.maxOctaveShift
        ArpeggioScheduler.staleTimeoutMs = cfg.arpeggioStaleTimeoutMs
        ArpeggioScheduler.rotationInProgressTimeoutMs = cfg.rotationInProgressTimeoutMs
        // Tier 2: max notes per tick
        ArpeggioScheduler.maxNotesPerTick = cfg.maxNotesPerTick.coerceIn(1, 8)
        MAX_ROTATION_DEGREES_PER_TICK = cfg.maxRotationDegreesPerTick
        ROTATION_CONVERGENCE_THRESHOLD_DEGREES = cfg.rotationConvergenceThresholdDegrees

        ArpeggioScheduler.interactDelegate = { pos, request ->
            logger.debug("interactDelegate: -> $pos (MIDI ${request.midiNote})")
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

        // Prime rotation in START_CLIENT_TICK -- fires before LocalPlayer.tick() calls
        // sendPosition(), so each tick's eased rotation step is included in that tick's
        // movement packet. Drives playback rotation only; tuning does not use rotation
        // gating (see NoteBlockTuner for rationale).
        ClientTickEvents.START_CLIENT_TICK.register { _ ->
            ArpeggioScheduler.peekNextPos()?.let { PlayerController.primeRotation(it) }
        }

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            // SoundVerifier needs mc.soundManager, which is not available during
            // onInitializeClient() -- deferred to first tick via a one-shot flag.
            if (!soundVerifierRegistered) {
                mc.soundManager.addListener(SoundVerifier)
                soundVerifierRegistered = true
                logger.info("SoundVerifier registered as a SoundEventListener")
            }

            while (openGuiKey.consumeClick()) {
                logger.info("B pressed -- opening MainScreen")
                if (mc.gui.screen() == null) mc.gui.setScreen(MainScreen())
            }
            while (toggleHudKey.consumeClick()) {
                PlaybackHud.isVisible = !PlaybackHud.isVisible
                logger.info("H pressed -- HUD visible=${PlaybackHud.isVisible}")
            }
            ArpeggioScheduler.onTick()
        }

        MidiInputHandler.autoConnect()
        logger.info("BlockBard initialized.")
    }
}
