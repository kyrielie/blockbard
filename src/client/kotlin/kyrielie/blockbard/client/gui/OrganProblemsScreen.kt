package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.organ.OrganProblem
import kyrielie.blockbard.client.organ.OrganProblemCollector
import kyrielie.blockbard.client.organ.ProblemSeverity
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.client.render.ManualTuningHighlighter
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class OrganProblemsScreen(private val parent: Screen) : Screen(Component.literal("Organ Problem Inspector")) {

    private lateinit var listWidget: ProblemListWidget
    private lateinit var toggleHighlightBtn: Button

    override fun init() {
        super.init()

        val topBtnY = 16
        var btnX = 10

        // In-world highlighter toggle
        toggleHighlightBtn = addRenderableWidget(
            Button.builder(Component.literal(highlightButtonText())) { btn ->
                ManualTuningHighlighter.isEnabled = !ManualTuningHighlighter.isEnabled
                btn.message = Component.literal(highlightButtonText())
            }.pos(btnX, topBtnY).size(130, 20).build()
        )
        btnX += 135

        // Clear manual highlights button
        addRenderableWidget(
            Button.builder(Component.literal("Clear Highlights")) {
                ManualTuningHighlighter.clear()
                refreshProblems()
            }.pos(btnX, topBtnY).size(110, 20).build()
        )
        btnX += 115

        // Center Organ button
        addRenderableWidget(
            Button.builder(Component.literal("Center Organ")) {
                PlayerController.centerOnOrgan()
                refreshProblems()
            }.pos(btnX, topBtnY).size(95, 20).build()
        )

        // Back button on top right
        addRenderableWidget(
            Button.builder(Component.literal("Back")) {
                onClose()
            }.pos(width - 70, topBtnY).size(60, 20).build()
        )

        val listTop = 44
        val listBottom = height - 12
        listWidget = ProblemListWidget(
            minecraft = minecraft,
            width = width - 20,
            height = listBottom - listTop,
            y = listTop,
            itemHeight = 36
        ) { problem ->
            if (problem.pos != null) {
                val coords = "${problem.pos.x} ${problem.pos.y} ${problem.pos.z}"
                minecraft.keyboardHandler.clipboard = coords
                val player = minecraft.player
                player?.sendSystemMessage(
                    Component.literal("§b[BlockBard] §7Copied coordinates §e$coords§7 to clipboard.")
                )
            }
        }
        addRenderableWidget(listWidget)
        refreshProblems()
    }

    private fun highlightButtonText(): String =
        if (ManualTuningHighlighter.isEnabled) "Highlights: ON" else "Highlights: OFF"

    fun refreshProblems() {
        val problems = OrganProblemCollector.collect()
        listWidget.setProblems(problems)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.fill(0, 0, width, height, 0xD0101014.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val count = listWidget.problemCount
        val header = "Organ Problems ($count detected)"
        graphics.text(font, header, 10, 4, 0x88AAFF or (0xFF shl 24))

        if (count == 0) {
            val emptyMsg = "No problems detected! All blocks are reachable, unoccluded, and in tune."
            val msgW = font.width(emptyMsg)
            graphics.text(font, emptyMsg, (width - msgW) / 2, height / 2 - 10, 0x55FF55 or (0xFF shl 24))
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key == 256) { // GLFW_KEY_ESCAPE
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    // ── Problem List Widget ───────────────────────────────────────────────────

    class ProblemListWidget(
        minecraft: Minecraft,
        width: Int,
        height: Int,
        y: Int,
        itemHeight: Int = 36,
        private val onSelect: ((OrganProblem) -> Unit)? = null
    ) : ObjectSelectionList<ProblemListWidget.ProblemItem>(minecraft, width, height, y, itemHeight) {

        var problemCount: Int = 0
            private set

        fun setProblems(problems: List<OrganProblem>) {
            problemCount = problems.size
            clearEntries()
            for (p in problems) {
                addEntry(ProblemItem(p))
            }
        }

        override fun getRowWidth(): Int = (width - 24).coerceAtLeast(100)

        inner class ProblemItem(val problem: OrganProblem) : ObjectSelectionList.Entry<ProblemItem>() {

            override fun getNarration(): Component = Component.literal("${problem.title}: ${problem.details}")

            override fun extractContent(
                graphics: GuiGraphicsExtractor,
                mouseX: Int,
                mouseY: Int,
                hovered: Boolean,
                a: Float
            ) {
                val isSelected = this@ProblemListWidget.selected == this
                val font = minecraft.font
                val x = getContentX()
                val y = getContentY()
                val w = getContentWidth()
                val h = getContentHeight()

                // Background highlight
                if (isSelected) {
                    graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, 0xFF224466.toInt())
                } else if (hovered) {
                    graphics.fill(x - 2, y - 1, x + w + 2, y + h + 1, 0xFF222222.toInt())
                }

                val (badgeText, badgeColor) = when (problem.severity) {
                    ProblemSeverity.ERROR   -> Pair("[ERROR]", 0xFFFF5555.toInt())
                    ProblemSeverity.WARNING -> Pair("[WARN] ", 0xFFFFAA00.toInt())
                    ProblemSeverity.INFO    -> Pair("[INFO] ", 0xFF55FFFF.toInt())
                }

                // Line 1: Badge + Title
                graphics.text(font, badgeText, x, y + 2, badgeColor)
                val badgeW = font.width(badgeText) + 4
                graphics.text(font, problem.title, x + badgeW, y + 2, 0xFFFFFFFF.toInt())

                // Line 2: Details
                graphics.text(font, problem.details, x + 6, y + 13, 0xFFAAAAAA.toInt())

                // Line 3: Suggestion / Fix hint
                val hint = "💡 ${problem.suggestion}"
                graphics.text(font, hint, x + 6, y + 24, 0xFF55FF88.toInt())
            }

            override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
                this@ProblemListWidget.setSelected(this)
                onSelect?.invoke(problem)
                return true
            }
        }
    }
}
