package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.library.MidiLibrary
import kyrielie.blockbard.client.playback.SongMetadataCache
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
    itemHeight: Int = 26,
    private val onSelect: (File) -> Unit,
    private val onDoubleClick: ((File) -> Unit)? = null,
    private val onToggleFavorite: ((File) -> Unit)? = null,
    private val onQueueNext: ((File) -> Unit)? = null,
    private val onQueueLast: ((File) -> Unit)? = null
) : ObjectSelectionList<SongListWidget.SongEntry>(minecraft, width, height, y, itemHeight) {

    private var isRefreshingFiles = false

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

    fun setFiles(files: List<File>, currentSelected: File?) {
        isRefreshingFiles = true
        try {
            clearEntries()
            var selectedEntry: SongEntry? = null
            for (file in files) {
                val entry = SongEntry(file)
                addEntry(entry)
                if (file == currentSelected || (currentSelected != null && MidiLibrary.idForFile(file) == MidiLibrary.idForFile(currentSelected))) {
                    selectedEntry = entry
                }
            }
            setSelected(selectedEntry)
            if (selectedEntry != null) {
                centerScrollOn(selectedEntry)
            }
        } finally {
            isRefreshingFiles = false
        }
    }

    override fun setSelected(entry: SongEntry?) {
        super.setSelected(entry)
        if (!isRefreshingFiles && entry != null) {
            onSelect(entry.file)
        }
    }

    fun getSelectedFile(): File? = selected?.file

    override fun getRowWidth(): Int = (width - 24).coerceAtLeast(100)

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
                graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, opaque(0x2E5B35))
                graphics.fill(x - 1, y, x + w + 1, y + h, opaque(0x3B7A45))
            } else if (hovered) {
                graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, opaque(0x333333))
            }

            // Star icon
            val isFav = ConfigManager.config.isFavorite(MidiLibrary.idForFile(file))
            val star = if (isFav) "★" else "☆"
            val starColor = if (isFav) opaque(0xFFFF55) else opaque(0x777777)
            graphics.text(font, star, x + 4, y + 2, starColor)

            // Format badge
            val isNbs = file.extension.equals("nbs", true)
            val badge = if (isNbs) "[NBS]" else "[MID]"
            val badgeColor = if (isNbs) opaque(0xFFCC44) else opaque(0x55FFFF)
            graphics.text(font, badge, x + 16, y + 2, badgeColor)

            // File name
            val nameColor = if (isSelected) opaque(0xAAFFAA) else if (hovered) opaque(0xFFFFFF) else opaque(0xDDDDDD)
            val maxChars = ((w - 90) / 6).coerceIn(15, 60)
            val name = file.nameWithoutExtension.take(maxChars)
            graphics.text(font, name, x + 46, y + 2, nameColor)

            // Queue buttons: "Next" plays after the current song, "Last" goes to the end of the queue
            val lastX = x + w - 26
            val nextX = x + w - 54
            val rowHovered = mouseY >= y + 1 && mouseY <= y + 13
            val lastHovered = rowHovered && mouseX >= lastX - 2 && mouseX <= x + w
            val nextHovered = rowHovered && mouseX >= nextX - 2 && mouseX < lastX - 2
            graphics.text(font, "Next", nextX, y + 2, if (nextHovered) opaque(0x55FF55) else opaque(0x77AA77))
            graphics.text(font, "Last", lastX, y + 2, if (lastHovered) opaque(0x55FF55) else opaque(0x77AA77))

            // File size
            val kb = file.length() / 1024
            val sizeStr = "${kb}KB"
            graphics.text(font, sizeStr, nextX - font.width(sizeStr) - 6, y + 2, opaque(0x888888))

            // Metadata subtext; replaced by a hint while a queue button is hovered
            val meta = SongMetadataCache.get(file)
            val hint = when {
                nextHovered -> "Next: play this right after the current song"
                lastHovered -> "Last: add this to the end of your queue"
                else -> null
            }
            val relFolder = MidiLibrary.idForFile(file).substringBeforeLast('/', "")
            val folderPrefix = if (relFolder.isNotEmpty()) "📁 $relFolder  ·  " else ""
            val subtext = hint ?: (folderPrefix + (meta?.summaryString ?: "Analyzing..."))
            val subtextColor = when {
                hint != null -> opaque(0x88DD88)
                meta != null -> opaque(0xAAAAAA)
                else -> opaque(0x666666)
            }
            graphics.text(font, subtext, x + 46, y + 14, subtextColor)
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            if (event.button() == 0) {
                val entryX = getContentX()
                val entryW = getContentWidth()
                if (event.x() >= entryX && event.x() <= entryX + 22) {
                    onToggleFavorite?.invoke(file)
                    return true
                }
                if (event.x() >= entryX + entryW - 28) {
                    onQueueLast?.invoke(file)
                    return true
                }
                if (event.x() >= entryX + entryW - 56) {
                    onQueueNext?.invoke(file)
                    return true
                }
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
