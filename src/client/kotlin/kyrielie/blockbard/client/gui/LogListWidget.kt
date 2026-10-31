package kyrielie.blockbard.client.gui

import kyrielie.blockbard.util.BlockBardLogBuffer
import kyrielie.blockbard.util.LogEntry
import kyrielie.blockbard.util.LogLevel
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class LogListWidget(
    minecraft: Minecraft,
    width: Int,
    height: Int,
    y: Int,
    itemHeight: Int = 14,
    private val onSelect: ((LogEntry) -> Unit)? = null
) : ObjectSelectionList<LogListWidget.LogItem>(minecraft, width, height, y, itemHeight) {

    private var filterLevel: LogLevel? = null

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

    fun refreshLogs(minLevel: LogLevel? = null) {
        filterLevel = minLevel
        val all = BlockBardLogBuffer.getAll()
        val filtered = when (minLevel) {
            LogLevel.WARN  -> all.filter { it.level == LogLevel.WARN || it.level == LogLevel.ERROR }
            LogLevel.ERROR -> all.filter { it.level == LogLevel.ERROR }
            else           -> all
        }
        clearEntries()
        for (e in filtered) {
            addEntry(LogItem(e))
        }
        setScrollAmount(maxScrollAmount().toDouble())
    }

    override fun getRowWidth(): Int = (width - 24).coerceAtLeast(100)

    inner class LogItem(val entry: LogEntry) : ObjectSelectionList.Entry<LogItem>() {

        override fun getNarration(): Component = Component.literal(entry.formatted())

        override fun extractContent(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            a: Float
        ) {
            val isSelected = this@LogListWidget.selected == this
            val font = minecraft.font
            val x = getContentX()
            val y = getContentY()
            val w = getContentWidth()
            val h = getContentHeight()

            if (isSelected) {
                graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, opaque(0x224466))
            } else if (hovered) {
                graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, opaque(0x222222))
            }

            val (badgeText, badgeColor) = when (entry.level) {
                LogLevel.INFO  -> Pair("INFO ", opaque(0x55FF55))
                LogLevel.WARN  -> Pair("WARN ", opaque(0xFFAA00))
                LogLevel.ERROR -> Pair("ERROR", opaque(0xFF5555))
                LogLevel.DEBUG -> Pair("DEBUG", opaque(0x88AAFF))
            }

            graphics.text(font, "[${entry.time}]", x, y + 1, opaque(0x777777))
            graphics.text(font, badgeText, x + 72, y + 1, badgeColor)
            graphics.text(font, "[${entry.tag}]", x + 108, y + 1, opaque(0xAAAAAA))

            val msgColor = when (entry.level) {
                LogLevel.INFO  -> opaque(0xDDDDDD)
                LogLevel.WARN  -> opaque(0xFFEE88)
                LogLevel.ERROR -> opaque(0xFF8888)
                LogLevel.DEBUG -> opaque(0x88AAFF)
            }
            val maxMsgChars = ((w - 180) / 6).coerceAtLeast(20)
            val truncatedMsg = if (entry.message.length > maxMsgChars) entry.message.take(maxMsgChars) + "..." else entry.message
            graphics.text(font, truncatedMsg, x + 180, y + 1, msgColor)
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            if (event.button() == 0) {
                this@LogListWidget.setSelected(this)
                onSelect?.invoke(entry)
                return true
            }
            return super.mouseClicked(event, doubleClick)
        }
    }
}
