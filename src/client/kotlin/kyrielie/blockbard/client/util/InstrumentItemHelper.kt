package kyrielie.blockbard.client.util

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

object InstrumentItemHelper {

    /**
     * Returns an [ItemStack] representing the block placed beneath a noteblock to produce [instrument].
     */
    fun itemStackFor(instrument: NoteBlockInstrument): ItemStack = when (instrument) {
        NoteBlockInstrument.HARP              -> ItemStack(Blocks.DIRT)
        NoteBlockInstrument.BASS              -> ItemStack(Blocks.OAK_PLANKS)
        NoteBlockInstrument.BASEDRUM          -> ItemStack(Blocks.STONE)
        NoteBlockInstrument.SNARE             -> ItemStack(Blocks.SAND)
        NoteBlockInstrument.HAT               -> ItemStack(Blocks.GLASS)
        NoteBlockInstrument.GUITAR            -> ItemStack(Blocks.WOOL.white())
        NoteBlockInstrument.FLUTE             -> ItemStack(Blocks.CLAY)
        NoteBlockInstrument.BELL              -> ItemStack(Blocks.GOLD_BLOCK)
        NoteBlockInstrument.CHIME             -> ItemStack(Blocks.PACKED_ICE)
        NoteBlockInstrument.XYLOPHONE         -> ItemStack(Blocks.BONE_BLOCK)
        NoteBlockInstrument.IRON_XYLOPHONE    -> ItemStack(Blocks.IRON_BLOCK)
        NoteBlockInstrument.COW_BELL          -> ItemStack(Blocks.SOUL_SAND)
        NoteBlockInstrument.DIDGERIDOO        -> ItemStack(Blocks.PUMPKIN)
        NoteBlockInstrument.BIT               -> ItemStack(Blocks.EMERALD_BLOCK)
        NoteBlockInstrument.BANJO             -> ItemStack(Blocks.HAY_BLOCK)
        NoteBlockInstrument.PLING             -> ItemStack(Blocks.GLOWSTONE)
        NoteBlockInstrument.TRUMPET           -> ItemStack(Blocks.COPPER_BLOCK.weathering().unaffected())
        NoteBlockInstrument.TRUMPET_EXPOSED   -> ItemStack(Blocks.COPPER_BLOCK.weathering().exposed())
        NoteBlockInstrument.TRUMPET_WEATHERED -> ItemStack(Blocks.COPPER_BLOCK.weathering().weathered())
        NoteBlockInstrument.TRUMPET_OXIDIZED  -> ItemStack(Blocks.COPPER_BLOCK.weathering().oxidized())
        NoteBlockInstrument.ZOMBIE            -> ItemStack(Items.ZOMBIE_HEAD)
        NoteBlockInstrument.SKELETON          -> ItemStack(Items.SKELETON_SKULL)
        NoteBlockInstrument.CREEPER           -> ItemStack(Items.CREEPER_HEAD)
        NoteBlockInstrument.DRAGON            -> ItemStack(Items.DRAGON_HEAD)
        NoteBlockInstrument.WITHER_SKELETON   -> ItemStack(Items.WITHER_SKELETON_SKULL)
        NoteBlockInstrument.PIGLIN            -> ItemStack(Items.PIGLIN_HEAD)
        else                                  -> ItemStack(Blocks.NOTE_BLOCK)
    }
}
