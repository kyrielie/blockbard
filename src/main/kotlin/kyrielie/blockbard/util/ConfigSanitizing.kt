package kyrielie.blockbard.util

// Pure value rules used by BlockBardConfig.sanitize(). Hand-edited or damaged config files can hold
// zero, negative, NaN or infinite values that would divide by zero, rescan every tick or silently
// disable a feature; each helper returns a safe value instead.

/** [value] when it is above zero, else [fallback]. */
fun positiveOr(value: Int, fallback: Int): Int = if (value > 0) value else fallback

/** [value] when it is above zero, else [fallback]. */
fun positiveOr(value: Long, fallback: Long): Long = if (value > 0L) value else fallback

/** [value] when it is finite and above zero (NaN and infinities fail both tests), else [fallback]. */
fun positiveFiniteOr(value: Float, fallback: Float): Float =
    if (value.isFinite() && value > 0f) value else fallback

/** Burst cap for notes per tick: 8 when unset or non-positive, at most 64. */
fun sanitizeMaxNotesPerTick(value: Int): Int = positiveOr(value, 8).coerceAtMost(64)

/** Default tempo multiplier: 1.0 when unusable, then clamped to 0.5..2.0. */
fun sanitizeTempoMultiplier(value: Float): Float = positiveFiniteOr(value, 1.0f).coerceIn(0.5f, 2.0f)
