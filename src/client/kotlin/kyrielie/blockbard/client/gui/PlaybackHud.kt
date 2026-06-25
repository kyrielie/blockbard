package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.playback.MidiFilePlayer
import kyrielie.blockbard.organ.NoteBlockRegistry
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

object PlaybackHud {

    var isVisible: Boolean = true
    var lastShiftMessage: String = ""

    fun register() {
        HudRenderCallback.EVENT.register { guiGraphics, _ ->
            if (!isVisible) return@register
            render(guiGraphics)
        }
    }

    private fun render(guiGraphics: GuiGraphics) {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) return  // Don't show HUD when GUI is open
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
        var y = mc.window.guiScaledHeight - 48

        guiGraphics.fill(x - 2, y - 2, x + 180, y + 40, 0x88000000.toInt())
        guiGraphics.drawString(font, status, x, y, 0xAAFFAA); y += 10
        guiGraphics.drawString(font, "Tempo: $tempo  |  $coverage", x, y, 0xCCCCCC); y += 10
        if (lastShiftMessage.isNotEmpty()) {
            guiGraphics.drawString(font, lastShiftMessage, x, y, 0xFFCC44)
        }
    }
}
