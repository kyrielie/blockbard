package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.playback.SongMetadataCache
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.io.File

class QueueListWidget(
    minecraft: Minecraft,
    width: Int,
    height: Int,
    y: Int,
    itemHeight: Int = 26,
    private val onSelect: (File) -> Unit,
    private val onDoubleClick: ((File) -> Unit)? = null,
    private val onRemove: ((Int) -> Unit)? = null,
    private val onMoveUp: ((Int) -> Unit)? = null,
    private val onMoveDown: ((Int) -> Unit)? = null
) : ObjectSelectionList<QueueListWidget.QueueEntry>(minecraft, width, height, y, itemHeight) {

    private var isRefreshing = false

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean {
        return visible && super.isMouseOver(mouseX, mouseY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (!visible) return false
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!visible) return false
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    fun setQueue(files: List<File>, currentPlaying: File?, userQueueCount: Int = 0) {
        isRefreshing = true
        try {
            clearEntries()
            var selectedEntry: QueueEntry? = null
            files.forEachIndexed { idx, file ->
                val isCurrent = (currentPlaying != null && file.name.equals(currentPlaying.name, ignoreCase = true) && idx == 0)
                val isUserQueued = idx < userQueueCount
                val entry = QueueEntry(idx + 1, file, isCurrent, isUserQueued)
                addEntry(entry)
                if (isCurrent && selectedEntry == null) {
                    selectedEntry = entry
                }
            }
            if (selectedEntry != null) {
                setSelected(selectedEntry)
            }
        } finally {
            isRefreshing = false
        }
    }

    override fun setSelected(entry: QueueEntry?) {
        super.setSelected(entry)
        if (!isRefreshing && entry != null) {
            onSelect(entry.file)
        }
    }

    override fun getRowWidth(): Int = (width - 24).coerceAtLeast(100)

    inner class QueueEntry(
        val queuePos: Int,
        val file: File,
        val isCurrent: Boolean,
        val isUserQueued: Boolean = false
    ) : ObjectSelectionList.Entry<QueueEntry>() {

        override fun getNarration(): Component = Component.literal("#$queuePos ${file.name}")

        override fun extractContent(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            a: Float
        ) {
            val isSelected = this@QueueListWidget.selected == this
            val font = minecraft.font
            val x = getContentX()
            val y = getContentY()
            val w = getContentWidth()
            val h = getContentHeight()

            // Entry backdrop
            if (isCurrent) {
                graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, opaque(0x225533))
                graphics.fill(x - 1, y, x + w + 1, y + h, opaque(0x2E6B40))
            } else if (isSelected) {
                graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, opaque(0x3A3A4A))
                graphics.fill(x - 1, y, x + w + 1, y + h, opaque(0x4A4A5E))
            } else if (hovered) {
                graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, opaque(0x26262E))
            }

            // Queue number badge: e.g. "#1" or "▶"
            val numStr = if (isCurrent) "▶" else "#$queuePos"
            val numColor = when {
                isCurrent -> opaque(0x55FF77)
                isUserQueued -> opaque(0x55FFFF)
                else -> opaque(0xFFD700)
            }
            graphics.text(font, numStr, x + 4, y + 3, numColor)

            // Format badge: [MID] or [NBS]
            val isNbs = file.extension.equals("nbs", true)
            val badge = if (isNbs) "[NBS]" else "[MID]"
            val badgeColor = if (isNbs) opaque(0xFFCC44) else opaque(0x55FFFF)
            graphics.text(font, badge, x + 24, y + 3, badgeColor)

            // Track name
            val nameColor = if (isCurrent) opaque(0xAAFFAA) else if (hovered) opaque(0xFFFFFF) else opaque(0xDDDDDD)
            val maxChars = ((w - 110) / 6).coerceIn(15, 65)
            val name = file.nameWithoutExtension.take(maxChars)
            graphics.text(font, name, x + 58, y + 3, nameColor)

            // Duration / Metadata on right
            val meta = SongMetadataCache.get(file)
            val durationText = meta?.durationSeconds?.let { s ->
                val m = s / 60
                val sec = s % 60
                "%02d:%02d".format(m, sec)
            } ?: (file.length() / 1024).let { "${it}KB" }

            val durW = font.width(durationText)
            val rightOffset = if (isUserQueued) 46 else 22
            graphics.text(font, durationText, x + w - durW - rightOffset, y + 3, opaque(0xAAAAAA))

            // Reorder controls for user-queued items: ▲ and ▼
            if (isUserQueued) {
                val upX = x + w - 38
                val upHovered = mouseX >= upX - 2 && mouseX <= upX + 8 && mouseY >= y + 2 && mouseY <= y + 12
                val upColor = if (upHovered) opaque(0xFFFF55) else opaque(0x888888)
                graphics.text(font, "▲", upX, y + 3, upColor)

                val downX = x + w - 26
                val downHovered = mouseX >= downX - 2 && mouseX <= downX + 8 && mouseY >= y + 2 && mouseY <= y + 12
                val downColor = if (downHovered) opaque(0xFFFF55) else opaque(0x888888)
                graphics.text(font, "▼", downX, y + 3, downColor)
            }

            // Remove button [✕]
            val removeX = x + w - 14
            val removeHovered = mouseX >= removeX && mouseX <= removeX + 10 && mouseY >= y + 2 && mouseY <= y + 12
            val removeColor = if (removeHovered) opaque(0xFF5555) else opaque(0x666666)
            graphics.text(font, "✕", removeX, y + 3, removeColor)

            // Subtext: summary (notes / channels / tempo)
            val sub = when {
                isCurrent -> "Currently playing"
                isUserQueued -> "Custom queue: #${queuePos} • ${meta?.summaryString ?: "Queued track"}"
                else -> meta?.summaryString ?: "Queued track"
            }
            val subColor = if (isCurrent) opaque(0x77DD99) else if (isUserQueued) opaque(0x88CCFF) else opaque(0x777777)
            graphics.text(font, sub, x + 58, y + 14, subColor)
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            if (event.button() == 0) {
                val entryX = getContentX()
                val entryW = getContentWidth()
                if (isUserQueued) {
                    val upX = entryX + entryW - 38
                    if (event.x() >= upX - 2 && event.x() <= upX + 9) {
                        onMoveUp?.invoke(queuePos - 1)
                        return true
                    }
                    val downX = entryX + entryW - 26
                    if (event.x() >= downX - 2 && event.x() <= downX + 9) {
                        onMoveDown?.invoke(queuePos - 1)
                        return true
                    }
                }
                val removeX = entryX + entryW - 14
                if (event.x() >= removeX - 2 && event.x() <= removeX + 12) {
                    onRemove?.invoke(queuePos - 1)
                    return true
                }
                this@QueueListWidget.setSelected(this)
                onSelect(file)
                if (doubleClick) {
                    onDoubleClick?.invoke(file)
                }
                return true
            }
            return super.mouseClicked(event, doubleClick)
        }
    }
}
