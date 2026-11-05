package kyrielie.blockbard.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class SongIdMigrationTest {

    private fun song(id: String, isTopLevel: Boolean): ScannedSong {
        val file = File(id)
        return ScannedSong(id = id, file = file, isTopLevel = isTopLevel)
    }

    @Test
    fun migratesBareFilenamesToSubfolderRelativePaths() {
        val library = listOf(
            song("theme.mid", isTopLevel = true),
            song("classical/beethoven/ode.mid", isTopLevel = false),
            song("gaming/nintendo/mario.nbs", isTopLevel = false)
        )

        val legacyFavorites = listOf("ode.mid", "mario.nbs", "theme.mid")
        val migrated = SongIdMigration.migrateIds(legacyFavorites, library)

        assertEquals(
            listOf("classical/beethoven/ode.mid", "gaming/nintendo/mario.nbs", "theme.mid"),
            migrated
        )
    }

    @Test
    fun preservesAlreadyRelativeIds() {
        val library = listOf(
            song("classical/bach.mid", isTopLevel = false),
            song("pop/rock/queen.mid", isTopLevel = false)
        )

        val favorites = listOf("classical/bach.mid", "pop/rock/queen.mid")
        val migrated = SongIdMigration.migrateIds(favorites, library)

        assertEquals(favorites, migrated)
    }

    @Test
    fun preservesBareIdsNotInLibrary() {
        val library = listOf(
            song("existing.mid", isTopLevel = true)
        )

        // Missing file shouldn't be discarded or modified
        val legacy = listOf("missing_track.mid", "existing.mid")
        val migrated = SongIdMigration.migrateIds(legacy, library)

        assertEquals(listOf("missing_track.mid", "existing.mid"), migrated)
    }

    @Test
    fun matchesFilenamesCaseInsensitively() {
        val library = listOf(
            song("Anime/Ghibli/Mononoke.mid", isTopLevel = false)
        )

        val legacy = listOf("mononoke.mid")
        val migrated = SongIdMigration.migrateIds(legacy, library)

        assertEquals(listOf("Anime/Ghibli/Mononoke.mid"), migrated)
    }

    @Test
    fun prefersTopLevelOverSubfolderIfBothMatch() {
        val library = listOf(
            song("theme.mid", isTopLevel = true),
            song("backup/theme.mid", isTopLevel = false)
        )

        val legacy = listOf("theme.mid")
        val migrated = SongIdMigration.migrateIds(legacy, library)

        assertEquals(listOf("theme.mid"), migrated)
    }

    @Test
    fun migrationIsIdempotent() {
        val library = listOf(
            song("intro.mid", isTopLevel = true),
            song("classical/chopin/nocturne.mid", isTopLevel = false),
            song("retro/arcade/pacman.mid", isTopLevel = false)
        )

        val input = listOf("nocturne.mid", "pacman.mid", "intro.mid", "missing.mid")
        val firstPass = SongIdMigration.migrateIds(input, library)
        val secondPass = SongIdMigration.migrateIds(firstPass, library)
        val thirdPass = SongIdMigration.migrateIds(secondPass, library)

        assertEquals(firstPass, secondPass)
        assertEquals(secondPass, thirdPass)
    }

    @Test
    fun migratesSingleLastPlayedId() {
        val library = listOf(
            song("orchestral/holst/jupiter.mid", isTopLevel = false)
        )

        assertEquals(
            "orchestral/holst/jupiter.mid",
            SongIdMigration.migrateId("jupiter.mid", library)
        )
        assertEquals(
            "orchestral/holst/jupiter.mid",
            SongIdMigration.migrateId("orchestral/holst/jupiter.mid", library)
        )
        assertNull(SongIdMigration.migrateId(null, library))
        assertEquals("unknown.mid", SongIdMigration.migrateId("unknown.mid", library))
    }
}
