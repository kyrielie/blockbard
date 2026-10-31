package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.playback.MidiFilePlayer
import kyrielie.blockbard.client.playback.NbsPlayer
import kyrielie.blockbard.client.player.SoundVerifier
import kyrielie.blockbard.organ.NoteBlockRegistry
// HudRenderCallback is gone in 26.x. Use the new HudElement / HudElementRegistry API.
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
// GuiGraphics is now GuiGraphicsExtractor in 26.x
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object PlaybackHud {

    private val logger = LoggerFactory.getLogger("BlockBard/PlaybackHud")

    var isVisible: Boolean = false
    var lastShiftMessage: String = ""

    // Identifier.of() was removed — use fromNamespaceAndPath(namespace, path)
    private val HUD_ID = Identifier.fromNamespaceAndPath("blockbard", "playback_hud")

    fun register() {
        // Attach after BOSS_BAR so we render after the main HUD but before chat/subtitles.
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.BOSS_BAR,
            HUD_ID,
            HudElement { graphics, _ -> render(graphics) }
        )
        logger.info("PlaybackHud: registered")
    }

    private fun render(graphics: GuiGraphicsExtractor) {
        if (!isVisible || !kyrielie.blockbard.client.config.ConfigManager.config.hudEnabled) return
        val mc = Minecraft.getInstance()
        // Screen access in 26.x: mc.gui.screen(), not mc.screen
        if (mc.gui.screen() != null) return

        // Only render when actively playing or paused -- never render an intrusive box when idle
        val isPlayingOrPaused = MidiFilePlayer.isActive() || NbsPlayer.isPlaying || MidiFilePlayer.isPaused || NbsPlayer.isPaused
        if (!isPlayingOrPaused) return
        val font = mc.font

        val playable = NoteBlockRegistry.allPlayable().size
        // Checks both players, the same way MainScreen's in-GUI status bar does —
        // either MidiFilePlayer or NbsPlayer may be the one actively playing depending
        // on the loaded file type, and this HUD previously only ever checked
        // MidiFilePlayer, so NBS playback always fell through to the idle branch here.
        val (status, progressFraction) = when {
            MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused -> {
                val currentTick = MidiFilePlayer.getCurrentTick()
                val totalTicks = MidiFilePlayer.getTotalTicks()
                val frac = if (totalTicks > 0) currentTick.toFloat() / totalTicks else 0f
                Pair("▶ PLAYING  tick $currentTick/$totalTicks", frac)
            }
            NbsPlayer.isPlaying && !NbsPlayer.isPaused -> {
                val currentTick = NbsPlayer.getCurrentTick()
                val totalTicks = NbsPlayer.getTotalTicks()
                val frac = if (totalTicks > 0) currentTick.toFloat() / totalTicks else 0f
                Pair("▶ PLAYING  tick $currentTick/$totalTicks", frac)
            }
            MidiFilePlayer.isPaused || NbsPlayer.isPaused -> Pair("⏸ PAUSED", 0f)
            else -> Pair("♪ BlockBard  [B to open]", 0f)
        }

        val tempo = "${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x"
        val coverage = "Organ: $playable blocks"

        val x = 4
        // guiHeight() in 26.x (was mc.window.guiScaledHeight)
        var y = graphics.guiHeight() - 52
        val hudWidth = 180

        graphics.fill(x - 2, y - 2, x + hudWidth, y + 46, 0x88000000.toInt())
        graphics.text(font, status, x, y, opaque(0xAAFFAA)); y += 10

        // Progress bar
        if (progressFraction > 0f) {
            val barW = hudWidth - 4
            val filledW = (barW * progressFraction.coerceIn(0f, 1f)).toInt()
            graphics.fill(x, y + 1, x + barW, y + 4, 0x55333333.toInt())
            graphics.fill(x, y + 1, x + filledW, y + 4, opaque(0x55FF55))
            y += 6
        }

        graphics.text(font, "Tempo: $tempo  |  $coverage", x, y, opaque(0xCCCCCC)); y += 10
        if (lastShiftMessage.isNotEmpty()) {
            graphics.text(font, lastShiftMessage, x, y, opaque(0xFFCC44))
        }
    }
}