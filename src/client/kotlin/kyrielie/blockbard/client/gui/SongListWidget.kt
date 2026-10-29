package kyrielie.blockbard.client.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.io.File

class SongListWidget(
    minecraft: Minecraft,
    width: Int,
    height: Int,
    y: Int,
    itemHeight: Int,
    private val onSelect: (File) -> Unit,
    private val onDoubleClick: ((File) -> Unit)? = null
) : ObjectSelectionList<SongListWidget.SongEntry>(minecraft, width, height, y, itemHeight) {

    fun setFiles(files: List<File>, currentSelected: File?) {
        clearEntries()
        var selectedEntry: SongEntry? = null
        for (file in files) {
            val entry = SongEntry(file)
            addEntry(entry)
            if (file == currentSelected || file.name == currentSelected?.name) {
                selectedEntry = entry
            }
        }
        setSelected(selectedEntry)
    }

    fun getSelectedFile(): File? = selected?.file

    override fun getRowWidth(): Int = width - 24

    inner class SongEntry(val file: File) : ObjectSelectionList.Entry<SongEntry>() {

        override fun getNarration(): Component = Component.literal(file.name)

        override fun extractContent(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            a: Float
        ) {
            val isSelected = this@SongListWidget.selected == this
            val font = minecraft.font
            val x = getContentX()
            val y = getContentY()
            val w = getContentWidth()
            val h = getContentHeight()

            // Highlight background
            if (isSelected) {
                graphics.fill(x, y, x + w, y + h, 0x5522AA44)
            } else if (hovered) {
                graphics.fill(x, y, x + w, y + h, 0x22FFFFFF)
            }

            // Format badge
            val isNbs = file.extension.equals("nbs", true)
            val badge = if (isNbs) "[NBS]" else "[MID]"
            val badgeColor = if (isNbs) opaque(0xFFCC44) else opaque(0x55FFFF)
            graphics.text(font, badge, x + 4, y + (h - 8) / 2, badgeColor)

            // File name
            val nameColor = if (isSelected) opaque(0xAAFFAA) else if (hovered) opaque(0xFFFFFF) else opaque(0xDDDDDD)
            val name = file.nameWithoutExtension.take(40)
            graphics.text(font, name, x + 38, y + (h - 8) / 2, nameColor)

            // File size
            val kb = file.length() / 1024
            val sizeStr = "${kb}KB"
            graphics.text(font, sizeStr, x + w - font.width(sizeStr) - 6, y + (h - 8) / 2, opaque(0x888888))
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            if (event.button() == 0) {
                this@SongListWidget.setSelected(this)
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
