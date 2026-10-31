package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NoteRequest
import kyrielie.blockbard.util.BlockBardLogBuffer
import kyrielie.blockbard.util.midiBase
import kyrielie.blockbard.util.midiNoteToName
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

class VirtualKeyboardWidget(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    var instrument: NoteBlockInstrument = NoteBlockInstrument.HARP,
    private val onPlay: ((noteIndex: Int, midiNote: Int, instrument: NoteBlockInstrument) -> Unit)? = null
) : AbstractWidget(x, y, width, height, Component.literal("Virtual Organ Keyboard")) {

    private val keyPressTimes = IntArray(25) { 0 }
    private var lastPlayedNote: Int? = null

    // 14 White keys mapping: whiteIndex -> noteIndex (0..24)
    private val whiteKeyNotes = listOf(1, 3, 5, 6, 8, 10, 11, 13, 15, 17, 18, 20, 22, 23)

    // Key shortcut hints
    private val keyHints = mapOf(
        0 to "A", 1 to "Z", 2 to "S", 3 to "X", 4 to "D", 5 to "C", 6 to "V",
        7 to "G", 8 to "B", 9 to "H", 10 to "N", 11 to "M", 12 to "K", 13 to ",",
        14 to "L", 15 to ".", 16 to ";", 17 to "/", 18 to "Q", 19 to "2", 20 to "W",
        21 to "3", 22 to "E", 23 to "R", 24 to "5"
    )

    fun flashKey(noteIndex: Int) {
        if (noteIndex in 0..24) {
            keyPressTimes[noteIndex] = (System.currentTimeMillis() % 100000).toInt()
        }
    }

    private fun isPressed(noteIndex: Int): Boolean {
        val t = keyPressTimes[noteIndex]
        val now = (System.currentTimeMillis() % 100000).toInt()
        val diff = if (now >= t) now - t else now + 100000 - t
        return diff in 0..180
    }

    fun isNoteAvailable(noteIndex: Int): Boolean {
        val map = PlayerController.organMap
        val entries = NoteBlockRegistry.allPlayable()
        return entries.any { entry ->
            entry.instrument == instrument &&
                entry.noteIndex == noteIndex &&
                (map == null || map.isReachable(entry.pos))
        }
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val totalWhite = whiteKeyNotes.size // 14
        val whiteW = width.toFloat() / totalWhite
        val blackW = (whiteW * 0.65f).coerceAtLeast(6f)
        val blackH = (height * 0.60f).toInt()

        // 1. Render White Keys
        for (i in 0 until totalWhite) {
            val noteIdx = whiteKeyNotes[i]
            val kx1 = (x + i * whiteW).toInt()
            val kx2 = (x + (i + 1) * whiteW - 1).toInt()
            val available = isNoteAvailable(noteIdx)
            val pressed = isPressed(noteIdx)
            val hovered = mouseX in kx1..kx2 && mouseY in y..(y + height) && !isOverAnyBlackKey(mouseX, mouseY, whiteW, blackW, blackH)

            val fillColor = when {
                pressed -> opaque(0x55FF77)
                hovered && available -> opaque(0xFFFFFF)
                available -> opaque(0xDDDDDD)
                hovered -> opaque(0x555555)
                else -> opaque(0x404040)
            }

            // Fill key body
            graphics.fill(kx1, y, kx2, y + height, fillColor)
            // Border outline
            graphics.fill(kx1, y + height - 1, kx2, y + height, opaque(0x222222))
            graphics.fill(kx2, y, kx2 + 1, y + height, opaque(0x222222))

            // Key label at bottom
            val hint = keyHints[noteIdx] ?: ""
            val midiNote = instrument.midiBase + noteIdx
            val noteName = midiNoteToName(midiNote)
            val textY = y + height - 12
            val textColor = if (available) opaque(0x111111) else opaque(0x888888)
            graphics.text(font, hint, kx1 + 3, textY, textColor)
        }

        // 2. Render Black Keys (drawn on top)
        for (noteIdx in listOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24)) {
            val centerWhiteIdx = getBlackKeyWhiteIndex(noteIdx)
            val bx = (x + centerWhiteIdx * whiteW - blackW / 2).toInt()
            val by = y
            val bw = blackW.toInt()
            val bh = blackH
            val available = isNoteAvailable(noteIdx)
            val pressed = isPressed(noteIdx)
            val hovered = mouseX in bx..(bx + bw) && mouseY in by..(by + bh)

            val fillColor = when {
                pressed -> opaque(0x22DDFF)
                hovered && available -> opaque(0x444444)
                available -> opaque(0x1C1C1C)
                hovered -> opaque(0x383838)
                else -> opaque(0x282828)
            }

            graphics.fill(bx, by, bx + bw, by + bh, fillColor)
            graphics.fill(bx, by + bh - 1, bx + bw, by + bh, opaque(0x0A0A0A))
            graphics.fill(bx + bw - 1, by, bx + bw, by + bh, opaque(0x0A0A0A))

            // Label
            val hint = keyHints[noteIdx] ?: ""
            val textColor = if (available) opaque(0xCCCCCC) else opaque(0x666666)
            graphics.text(font, hint, bx + 2, by + bh - 11, textColor)
        }

        // 3. Hover tooltip text
        val hoveredNote = getNoteAt(mouseX, mouseY, whiteW, blackW, blackH)
        if (hoveredNote != null) {
            val midi = instrument.midiBase + hoveredNote
            val name = midiNoteToName(midi)
            val avail = isNoteAvailable(hoveredNote)
            val statusStr = if (avail) "§aAvailable" else "§cUnavailable"
            val info = "§f$name §7(note $hoveredNote) $statusStr"
            graphics.text(font, info, x + 4, y - 12, opaque(0xFFFFFF))
        }
    }

    private fun getBlackKeyWhiteIndex(noteIndex: Int): Float = when (noteIndex) {
        0 -> 0.0f
        2 -> 1.0f
        4 -> 2.0f
        7 -> 4.0f
        9 -> 5.0f
        12 -> 7.0f
        14 -> 8.0f
        16 -> 9.0f
        19 -> 11.0f
        21 -> 12.0f
        24 -> 14.0f
        else -> 0.0f
    }

    private fun isOverAnyBlackKey(mx: Int, my: Int, whiteW: Float, blackW: Float, blackH: Int): Boolean {
        if (my !in y..(y + blackH)) return false
        for (noteIdx in listOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24)) {
            val centerWhiteIdx = getBlackKeyWhiteIndex(noteIdx)
            val bx = (x + centerWhiteIdx * whiteW - blackW / 2).toInt()
            if (mx in bx..(bx + blackW.toInt())) return true
        }
        return false
    }

    private fun getNoteAt(mx: Int, my: Int, whiteW: Float, blackW: Float, blackH: Int): Int? {
        if (mx !in x..(x + width) || my !in y..(y + height)) return null

        // Check black keys first (they overlap)
        if (my in y..(y + blackH)) {
            for (noteIdx in listOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24)) {
                val centerWhiteIdx = getBlackKeyWhiteIndex(noteIdx)
                val bx = (x + centerWhiteIdx * whiteW - blackW / 2).toInt()
                if (mx in bx..(bx + blackW.toInt())) {
                    return noteIdx
                }
            }
        }

        // Check white keys
        val whiteIdx = ((mx - x) / whiteW).toInt().coerceIn(0, whiteKeyNotes.size - 1)
        return whiteKeyNotes.getOrNull(whiteIdx)
    }

    override fun onClick(mouseEvent: MouseButtonEvent, doubleClick: Boolean) {
        val totalWhite = whiteKeyNotes.size
        val whiteW = width.toFloat() / totalWhite
        val blackW = (whiteW * 0.65f).coerceAtLeast(6f)
        val blackH = (height * 0.60f).toInt()

        val noteIdx = getNoteAt(mouseEvent.x().toInt(), mouseEvent.y().toInt(), whiteW, blackW, blackH) ?: return
        val midiNote = instrument.midiBase + noteIdx
        flashKey(noteIdx)

        if (isNoteAvailable(noteIdx)) {
            ArpeggioScheduler.enqueue(NoteRequest(midiNote, instrument = instrument))
            onPlay?.invoke(noteIdx, midiNote, instrument)
        } else {
            val msg = "${instrument.name} has no reachable noteblock tuned to ${midiNoteToName(midiNote)}."
            BlockBardLogBuffer.warn("VirtualKeyboard", msg)
            if (ConfigManager.config.enableChatFeedback) {
                val mc = Minecraft.getInstance()
                mc.player?.sendSystemMessage(
                    Component.literal("§b[BlockBard] §c${instrument.name} §7has no reachable noteblock tuned to §e${midiNoteToName(midiNote)}§7.")
                )
            }
        }
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {}
}
