package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.playback.MidiFilePlayer
import kyrielie.blockbard.organ.NoteBlockRegistry
// HudRenderCallback is gone. Use the new HudElement / HudElementRegistry API.
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
// GuiGraphics is now GuiGraphicsExtractor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

object PlaybackHud {

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
    }

    private fun render(graphics: GuiGraphicsExtractor) {
        if (!isVisible) return
        val mc = Minecraft.getInstance()
        // Screen access: mc.gui.screen()
        if (mc.gui.screen() != null) return  // Don't show HUD when GUI is open
        val font = mc.font

        val playable = NoteBlockRegistry.allPlayable().size
        val status = when {
            MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused -> {
                val currentMs = MidiFilePlayer.getCurrentTick()
                val totalMs = MidiFilePlayer.getTotalTicks()
                "▶ PLAYING  tick $currentMs/$totalMs"
            }
            MidiFilePlayer.isPaused -> "⏸ PAUSED"
            else -> "♪ BlockBard  [B to open]"
        }

        val tempo = "${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x"
        val coverage = "Organ: $playable blocks"

        val x = 4
        var y = graphics.guiHeight() - 48

        // fill() signature unchanged
        graphics.fill(x - 2, y - 2, x + 180, y + 40, 0x88000000.toInt())
        // drawString() → text()  (GuiGraphicsExtractor uses text(font, string, x, y, color))
        graphics.text(font, status, x, y, 0xAAFFAA); y += 10
        graphics.text(font, "Tempo: $tempo  |  $coverage", x, y, 0xCCCCCC); y += 10
        if (lastShiftMessage.isNotEmpty()) {
            graphics.text(font, lastShiftMessage, x, y, 0xFFCC44)
        }
    }
}
