package kyrielie.blockbard.util

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

/**
 * Human-readable name of the block that must be placed below a noteblock to produce
 * this instrument. Used in coverage and readiness messages so the player knows exactly
 * what to build, not just which instrument name is missing.
 *
 * Verified against the NoteBlockInstrument enum in the decompiled 26.x source and the
 * Minecraft wiki note block instrument table.
 */
val NoteBlockInstrument.blockBelowHint: String
    get() = when (this) {
        NoteBlockInstrument.HARP             -> "dirt/grass"
        NoteBlockInstrument.BASS             -> "any wood plank/log"
        NoteBlockInstrument.BASEDRUM         -> "stone/concrete/netherrack"
        NoteBlockInstrument.SNARE            -> "sand/gravel/soul sand"
        NoteBlockInstrument.HAT              -> "glass/sea lantern"
        NoteBlockInstrument.GUITAR           -> "any wool"
        NoteBlockInstrument.FLUTE            -> "clay"
        NoteBlockInstrument.BELL             -> "gold block"
        NoteBlockInstrument.CHIME            -> "packed ice"
        NoteBlockInstrument.XYLOPHONE        -> "bone block"
        NoteBlockInstrument.IRON_XYLOPHONE   -> "iron block"
        NoteBlockInstrument.COW_BELL         -> "soul sand"
        NoteBlockInstrument.DIDGERIDOO       -> "pumpkin"
        NoteBlockInstrument.BIT              -> "emerald block"
        NoteBlockInstrument.BANJO            -> "hay bale"
        NoteBlockInstrument.PLING            -> "glowstone"
        NoteBlockInstrument.TRUMPET          -> "copper block"
        NoteBlockInstrument.TRUMPET_EXPOSED  -> "exposed copper"
        NoteBlockInstrument.TRUMPET_WEATHERED -> "weathered copper"
        NoteBlockInstrument.TRUMPET_OXIDIZED -> "oxidized copper"
        else                                 -> "unknown block"
    }
