package kyrielie.blockbard.organ

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * Floor-mounted mob head blocks that, when placed directly above a noteblock,
 * override its sound to the mob's ambient sound instead of a musical note.
 * Wall-mounted variants are intentionally excluded — only floor-mounted heads
 * placed at pos.above() trigger this behavior.
 */
val MOB_HEAD_BLOCKS: Set<Block> = setOf(
    Blocks.SKELETON_SKULL,
    Blocks.WITHER_SKELETON_SKULL,
    Blocks.ZOMBIE_HEAD,
    Blocks.CREEPER_HEAD,
    Blocks.PIGLIN_HEAD,
    Blocks.DRAGON_HEAD,
    Blocks.PLAYER_HEAD
)