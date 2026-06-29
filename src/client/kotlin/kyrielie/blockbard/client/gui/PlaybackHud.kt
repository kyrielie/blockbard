package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.playback.MidiFilePlayer
import kyrielie.blockbard.client.playback.NbsPlayer
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

    var isVisible: Boolean = true
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
        if (!isVisible) return
        val mc = Minecraft.getInstance()
        // Screen access in 26.x: mc.gui.screen(), not mc.screen
        if (mc.gui.screen() != null) return
        val font = mc.font

        val playable = NoteBlockRegistry.allPlayable().size
        // Checks both players, the same way MainScreen's in-GUI status bar does —
        // either MidiFilePlayer or NbsPlayer may be the one actively playing depending
        // on the loaded file type, and this HUD previously only ever checked
        // MidiFilePlayer, so NBS playback always fell through to the idle branch here.
        val status = when {
            MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused -> {
                val currentTick = MidiFilePlayer.getCurrentTick()
                val totalTicks = MidiFilePlayer.getTotalTicks()
                "▶ PLAYING  tick $currentTick/$totalTicks"
            }
            NbsPlayer.isPlaying && !NbsPlayer.isPaused -> {
                val currentTick = NbsPlayer.getCurrentTick()
                val totalTicks = NbsPlayer.getTotalTicks()
                "▶ PLAYING  tick $currentTick/$totalTicks"
            }
            MidiFilePlayer.isPaused || NbsPlayer.isPaused -> "⏸ PAUSED"
            else -> "♪ BlockBard  [B to open]"
        }

        val tempo = "${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x"
        val coverage = "Organ: $playable blocks"

        val x = 4
        // guiHeight() in 26.x (was mc.window.guiScaledHeight)
        var y = graphics.guiHeight() - 48

        // fill() signature unchanged — 0x88000000 already has a non-zero alpha byte
        // (0x88), so this one call was unaffected by the bug fixed below.
        graphics.fill(x - 2, y - 2, x + 180, y + 40, 0x88000000.toInt())
        // drawString() → text() in GuiGraphicsExtractor. text() silently drops the draw
        // entirely when the color's alpha byte is 0 — see opaque() in MainScreen.kt
        // (same package, no import needed) for why a bare 0xRRGGBB literal does that.
        graphics.text(font, status, x, y, opaque(0xAAFFAA)); y += 10
        graphics.text(font, "Tempo: $tempo  |  $coverage", x, y, opaque(0xCCCCCC)); y += 10
        if (lastShiftMessage.isNotEmpty()) {
            graphics.text(font, lastShiftMessage, x, y, opaque(0xFFCC44))
        }
    }
}