package kyrielie.blockbard.client.gui

import kyrielie.blockbard.organ.NotePitch
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

data class MaterialItem(
    val itemStack: ItemStack,
    val name: String,
    val needed: Int,
    val inInventory: Int
)

object MaterialsShoppingOverlay {

    private val logger = LoggerFactory.getLogger("BlockBard/MaterialsOverlay")
    private val HUD_ID = Identifier.fromNamespaceAndPath("blockbard", "materials_overlay")

    var isVisible: Boolean = false
    private var materials: List<MaterialItem> = emptyList()

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.BOSS_BAR,
            HUD_ID,
            HudElement { graphics, _ -> render(graphics) }
        )
        logger.info("MaterialsShoppingOverlay registered")
    }

    fun updateRequirements(requirements: Set<NotePitch>) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        val inv = player?.inventory

        fun countInInventory(itemStack: ItemStack): Int {
            if (inv == null) return 0
            var count = 0
            for (i in 0 until inv.containerSize) {
                val stack = inv.getItem(i)
                if (stack.item == itemStack.item) count += stack.count
            }
            return count
        }

        val instrumentCounts = requirements
            .mapNotNull { it.instrument }
            .groupingBy { it }
            .eachCount()

        val list = mutableListOf<MaterialItem>()

        val totalNoteBlocks = requirements.size
        if (totalNoteBlocks > 0) {
            val nbStack = ItemStack(Blocks.NOTE_BLOCK)
            list.add(MaterialItem(nbStack, "Note Block", totalNoteBlocks, countInInventory(nbStack)))
        }

        for ((instrument, count) in instrumentCounts.entries.sortedByDescending { it.value }) {
            val stack = instrumentToItemStack(instrument)
            list.add(MaterialItem(stack, instrument.name.lowercase().replace('_', ' '), count, countInInventory(stack)))
        }

        materials = list
    }

    fun clear() {
        materials = emptyList()
    }

    private fun render(graphics: GuiGraphicsExtractor) {
        if (!isVisible || materials.isEmpty()) return
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() != null) return
        val font = mc.font

        val x = graphics.guiWidth() - 120
        var y = 20
        val width = 116
        val height = (materials.size * 18) + 16

        graphics.fill(x - 2, y - 2, x + width, y + height, 0x88000000.toInt())
        graphics.text(font, "Required Materials:", x + 2, y + 2, opaque(0xAAFFAA))
        y += 14

        for (mat in materials) {
            graphics.item(mat.itemStack, x + 2, y - 2)
            val color = if (mat.inInventory >= mat.needed) opaque(0x55FF55) else opaque(0xFF8888)
            val countStr = "${mat.inInventory}/${mat.needed}"
            graphics.text(font, countStr, x + 22, y + 2, color)
            graphics.text(font, mat.name.take(12), x + 56, y + 2, opaque(0xCCCCCC))
            y += 18
        }
    }

    private fun instrumentToItemStack(instrument: NoteBlockInstrument): ItemStack =
        kyrielie.blockbard.client.util.InstrumentItemHelper.itemStackFor(instrument)
}
