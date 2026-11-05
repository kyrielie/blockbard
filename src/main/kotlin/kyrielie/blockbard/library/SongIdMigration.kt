package kyrielie.blockbard.library

/**
 * Migration helper for converting legacy bare-filename song IDs to relative paths.
 *
 * Prior to the folder playlist pass, song IDs were bare file names (e.g. "ode.mid").
 * With subfolder support, song IDs are relative paths (e.g. "classical/beethoven/ode.mid").
 *
 * This migration maps legacy bare IDs in user configs (favorites, lastPlayedTrack)
 * to their relative path counterparts when discovered in the library.
 */
object SongIdMigration {

    /**
     * Migrates a list of song IDs (e.g. favorites).
     *
     * - IDs that already contain a slash '/' are kept as-is (already relative).
     * - Bare IDs matching a song directly in root remain as bare filenames.
     * - Bare IDs matching a song in a subfolder are migrated to that song's relative ID.
     * - Bare IDs not matching any scanned song are kept as-is to prevent data loss.
     *
     * Idempotent: repeated calls produce identical results.
     */
    fun migrateIds(ids: List<String>, availableSongs: List<ScannedSong>): List<String> {
        if (ids.isEmpty() || availableSongs.isEmpty()) return ids

        val songsByFilename = availableSongs.groupBy { it.file.name.lowercase() }
        val rootIds = availableSongs.filter { it.isTopLevel }.map { it.id.lowercase() }.toSet()

        return ids.map { id ->
            if (id.contains('/')) {
                id
            } else {
                val lower = id.lowercase()
                if (lower in rootIds) {
                    id
                } else {
                    val matches = songsByFilename[lower]
                    if (!matches.isNullOrEmpty()) {
                        matches.first().id
                    } else {
                        id
                    }
                }
            }
        }.distinct()
    }

    /**
     * Migrates a single song ID (e.g. lastPlayedTrack).
     */
    fun migrateId(id: String?, availableSongs: List<ScannedSong>): String? {
        if (id == null) return null
        return migrateIds(listOf(id), availableSongs).firstOrNull()
    }
}
