package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.playback.SongMetadataCache
import kyrielie.blockbard.playback.QueueRow
import kyrielie.blockbard.playback.QueueSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.io.File

/**
 * One displayed queue row. [index] is the row's position in the full "up next" list (user rows
 * first, then context rows) and is what the action callbacks receive, so it stays correct even
 * when a file is missing and only a placeholder [file] is shown.
 */
data class QueueItem(val index: Int, val row: QueueRow, val file: File)

/**
 * The "up next" list. A single click only highlights a row; nothing is selected into the
 * player and the list is never recomputed by clicking. Double-click means "play now".
 * User-queued rows have move-to-top, up, down and remove. Context rows (the playlist order)
 * cannot be removed or reordered, but "+Q" copies one into the user queue.
 */
class QueueListWidget(
    minecraft: Minecraft,
    width: Int,
    height: Int,
    y: Int,
    itemHeight: Int = 26,
    private val onPlayNow: ((QueueItem) -> Unit)? = null,
    private val onRemove: ((QueueItem) -> Unit)? = null,
    private val onMoveUp: ((QueueItem) -> Unit)? = null,
    private val onMoveDown: ((QueueItem) -> Unit)? = null,
    private val onMoveToTop: ((QueueItem) -> Unit)? = null,
    private val onPromote: ((QueueItem) -> Unit)? = null
) : ObjectSelectionList<QueueListWidget.QueueEntry>(minecraft, width, height, y, itemHeight) {

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

    /**
     * Rebuilds the rows. The highlighted row stays highlighted when the same song is still at
     * the same index, so a refresh caused by another action does not lose the highlight.
     */
    fun setQueue(items: List<QueueItem>) {
        val previous = selected?.item
        clearEntries()
        var restored: QueueEntry? = null
        for (item in items) {
            val entry = QueueEntry(item)
            addEntry(entry)
            if (previous != null && previous.index == item.index && previous.row.id == item.row.id) {
                restored = entry
            }
        }
        if (restored != null) super.setSelected(restored)
    }

    override fun getRowWidth(): Int = (width - 24).coerceAtLeast(100)

    inner class QueueEntry(val item: QueueItem) : ObjectSelectionList.Entry<QueueEntry>() {

        private val file: File get() = item.file
        private val queuePos: Int get() = item.index + 1
        private val isUserQueued: Boolean get() = item.row.source == QueueSource.USER

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
            if (isSelected) {
                graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, opaque(0x3A3A4A))
                graphics.fill(x - 1, y, x + w + 1, y + h, opaque(0x4A4A5E))
            } else if (hovered) {
                graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, opaque(0x26262E))
            }

            // Queue number badge: cyan for user-queued rows, gold for the context
            val numColor = if (isUserQueued) opaque(0x55FFFF) else opaque(0xFFD700)
            graphics.text(font, "#$queuePos", x + 4, y + 3, numColor)

            // Format badge: [MID] or [NBS]
            val isNbs = file.extension.equals("nbs", true)
            val badge = if (isNbs) "[NBS]" else "[MID]"
            val badgeColor = if (isNbs) opaque(0xFFCC44) else opaque(0x55FFFF)
            graphics.text(font, badge, x + 24, y + 3, badgeColor)

            // Track name
            val nameColor = if (hovered) opaque(0xFFFFFF) else opaque(0xDDDDDD)
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
            val rightOffset = if (isUserQueued) 70 else 30
            graphics.text(font, durationText, x + w - durW - rightOffset, y + 3, opaque(0xAAAAAA))

            val rowHovered = mouseY >= y + 2 && mouseY <= y + 12
            var hint: String? = null
            if (!isUserQueued) {
                val promoteX = x + w - 22
                val promoteHovered = rowHovered && mouseX >= promoteX - 2 && mouseX <= x + w
                graphics.text(font, "+Q", promoteX, y + 3, if (promoteHovered) opaque(0x55FF55) else opaque(0x77AA77))
                if (promoteHovered) hint = "Copy to your queue so it plays before the playlist"
            }
            if (isUserQueued) {
                // Move to top
                val topX = x + w - 64
                val topHovered = rowHovered && mouseX >= topX - 2 && mouseX <= topX + 18
                graphics.text(font, "Top", topX, y + 3, if (topHovered) opaque(0xFFFF55) else opaque(0x888888))
                if (topHovered) hint = "Move to the top of your queue"

                // Reorder controls: up and down
                val upX = x + w - 38
                val upHovered = mouseX >= upX - 2 && mouseX <= upX + 8 && mouseY >= y + 2 && mouseY <= y + 12
                val upColor = if (upHovered) opaque(0xFFFF55) else opaque(0x888888)
                graphics.text(font, "▲", upX, y + 3, upColor)

                val downX = x + w - 26
                val downHovered = mouseX >= downX - 2 && mouseX <= downX + 8 && mouseY >= y + 2 && mouseY <= y + 12
                val downColor = if (downHovered) opaque(0xFFFF55) else opaque(0x888888)
                graphics.text(font, "▼", downX, y + 3, downColor)

                // Remove button. Context rows have none: they come from the playlist order.
                val removeX = x + w - 14
                val removeHovered = mouseX >= removeX && mouseX <= removeX + 10 && mouseY >= y + 2 && mouseY <= y + 12
                val removeColor = if (removeHovered) opaque(0xFF5555) else opaque(0x666666)
                graphics.text(font, "✕", removeX, y + 3, removeColor)
            }

            // Subtext: summary (notes / channels / tempo)
            val sub = if (isUserQueued) {
                "Custom queue: #$queuePos • ${meta?.summaryString ?: "Queued track"}"
            } else {
                meta?.summaryString ?: "Queued track"
            }
            val subColor = when {
                hint != null -> opaque(0x88DD88)
                isUserQueued -> opaque(0x88CCFF)
                else -> opaque(0x777777)
            }
            graphics.text(font, hint ?: sub, x + 58, y + 14, subColor)
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            if (event.button() == 0) {
                val entryX = getContentX()
                val entryW = getContentWidth()
                if (!isUserQueued && event.x() >= entryX + entryW - 24) {
                    onPromote?.invoke(item)
                    return true
                }
                if (isUserQueued) {
                    val topX = entryX + entryW - 64
                    if (event.x() >= topX - 2 && event.x() <= topX + 18) {
                        onMoveToTop?.invoke(item)
                        return true
                    }
                    val upX = entryX + entryW - 38
                    if (event.x() >= upX - 2 && event.x() <= upX + 9) {
                        onMoveUp?.invoke(item)
                        return true
                    }
                    val downX = entryX + entryW - 26
                    if (event.x() >= downX - 2 && event.x() <= downX + 9) {
                        onMoveDown?.invoke(item)
                        return true
                    }
                    val removeX = entryX + entryW - 14
                    if (event.x() >= removeX - 2 && event.x() <= removeX + 12) {
                        onRemove?.invoke(item)
                        return true
                    }
                }
                // Single click only highlights. Double-click plays this row now.
                this@QueueListWidget.setSelected(this)
                if (doubleClick) {
                    onPlayNow?.invoke(item)
                }
                return true
            }
            return super.mouseClicked(event, doubleClick)
        }
    }
}
