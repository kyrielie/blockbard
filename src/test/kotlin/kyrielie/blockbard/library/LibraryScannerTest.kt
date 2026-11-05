package kyrielie.blockbard.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LibraryScannerTest {

    @Test
    fun scansSupportedExtensionsOnly(@TempDir tempDir: Path) {
        Files.createFile(tempDir.resolve("song1.mid"))
        Files.createFile(tempDir.resolve("song2.MIDI"))
        Files.createFile(tempDir.resolve("song3.nbs"))
        Files.createFile(tempDir.resolve("readme.txt"))
        Files.createFile(tempDir.resolve("audio.mp3"))

        val scan = LibraryScanner.scan(tempDir)
        assertEquals(3, scan.songs.size)
        assertEquals(listOf("song1.mid", "song2.MIDI", "song3.nbs"), scan.songs.map { it.id })
    }

    @Test
    fun ignoresHiddenFilesAndMacOsArtifacts(@TempDir tempDir: Path) {
        Files.createFile(tempDir.resolve("valid.mid"))
        Files.createFile(tempDir.resolve(".hidden.mid"))

        val macDir = Files.createDirectory(tempDir.resolve("__MACOSX"))
        Files.createFile(macDir.resolve("shadow.mid"))

        val dotDir = Files.createDirectory(tempDir.resolve(".sub"))
        Files.createFile(dotDir.resolve("inner.mid"))

        val scan = LibraryScanner.scan(tempDir)
        assertEquals(1, scan.songs.size)
        assertEquals("valid.mid", scan.songs[0].id)
    }

    @Test
    fun sortsDeterministicallyCaseInsensitive(@TempDir tempDir: Path) {
        Files.createFile(tempDir.resolve("b_track.mid"))
        Files.createFile(tempDir.resolve("A_track.mid"))
        Files.createFile(tempDir.resolve("c_track.mid"))
        Files.createFile(tempDir.resolve("1_intro.mid"))

        val scan = LibraryScanner.scan(tempDir)
        assertEquals(listOf("1_intro.mid", "A_track.mid", "b_track.mid", "c_track.mid"), scan.songs.map { it.id })
    }

    @Test
    fun traversesSubfoldersWithRelativeIds(@TempDir tempDir: Path) {
        Files.createFile(tempDir.resolve("root.mid"))
        val sub1 = Files.createDirectory(tempDir.resolve("sub1"))
        Files.createFile(sub1.resolve("inner.mid"))
        val sub2 = Files.createDirectory(sub1.resolve("sub2"))
        Files.createFile(sub2.resolve("deep.nbs"))

        val scan = LibraryScanner.scan(tempDir, maxFolderDepth = 3)
        assertEquals(3, scan.songs.size)

        val rootSong = scan.findById("root.mid")
        assertNotNull(rootSong)
        assertTrue(rootSong!!.isTopLevel)

        val innerSong = scan.findById("sub1/inner.mid")
        assertNotNull(innerSong)
        assertFalse(innerSong!!.isTopLevel)

        val deepSong = scan.findById("sub1/sub2/deep.nbs")
        assertNotNull(deepSong)
        assertFalse(deepSong!!.isTopLevel)
    }

    @Test
    fun respectsMaxFilesCapAndWarns(@TempDir tempDir: Path) {
        for (i in 1..10) {
            Files.createFile(tempDir.resolve("song$i.mid"))
        }

        val scan = LibraryScanner.scan(tempDir, maxFiles = 4)
        assertEquals(4, scan.songs.size)
        assertTrue(scan.warnings.any { it.contains("Library truncated at 4 files") })
    }

    @Test
    fun nonExistentRootReturnsEmptyScanSafely(@TempDir tempDir: Path) {
        val nonExistent = tempDir.resolve("does_not_exist")
        val scan = LibraryScanner.scan(nonExistent)
        assertTrue(scan.songs.isEmpty())
        assertTrue(scan.warnings.isEmpty())
    }

    @Test
    fun skipsDuplicateRealPathsAcrossSymlinks(@TempDir tempDir: Path) {
        val original = Files.createFile(tempDir.resolve("original.mid"))
        val linkPath = tempDir.resolve("link.mid")

        val symlinkCreated = try {
            Files.createSymbolicLink(linkPath, original)
            true
        } catch (e: Exception) {
            false
        }
        Assumptions.assumeTrue(symlinkCreated, "Symlinks must be supported by the filesystem for this test")

        val scan = LibraryScanner.scan(tempDir)
        // One of them is indexed, the duplicate is skipped with a warning
        assertEquals(1, scan.songs.size)
        assertTrue(scan.warnings.any { it.contains("Duplicate target skipped") })
    }

    @Test
    fun handlesSymlinkCycleSafely(@TempDir tempDir: Path) {
        val sub = Files.createDirectory(tempDir.resolve("sub"))
        Files.createFile(sub.resolve("song.mid"))
        val loopLink = sub.resolve("loop_to_parent")

        val symlinkCreated = try {
            Files.createSymbolicLink(loopLink, tempDir)
            true
        } catch (e: Exception) {
            false
        }
        Assumptions.assumeTrue(symlinkCreated, "Symlinks must be supported by the filesystem for this test")

        val scan = LibraryScanner.scan(tempDir, maxFolderDepth = 4)
        // Should discover song.mid and terminate the loop without stack overflow
        assertTrue(scan.songs.any { it.id == "sub/song.mid" })
    }

    @Test
    fun discoversPlaylistsAndFiltersSongsByFolder(@TempDir tempDir: Path) {
        Files.createFile(tempDir.resolve("intro.mid"))

        val classical = Files.createDirectory(tempDir.resolve("classical"))
        Files.createFile(classical.resolve("bach.mid"))

        val beethoven = Files.createDirectory(classical.resolve("beethoven"))
        Files.createFile(beethoven.resolve("ode.mid"))

        val gaming = Files.createDirectory(tempDir.resolve("gaming"))
        Files.createFile(gaming.resolve("mario.nbs"))

        Files.createDirectory(tempDir.resolve("custom_empty"))

        val scan = LibraryScanner.scan(tempDir)

        // Playlists: "" ("All") followed by sorted unique relative subfolders
        assertEquals(
            listOf("", "classical", "classical/beethoven", "custom_empty", "gaming"),
            scan.playlists
        )

        // "All" ("") contains all songs
        val allSongs = scan.songsInPlaylist("")
        assertEquals(4, allSongs.size)
        assertEquals(
            listOf("classical/bach.mid", "classical/beethoven/ode.mid", "gaming/mario.nbs", "intro.mid"),
            allSongs.map { it.id }
        )

        // "classical" contains both bach.mid and beethoven/ode.mid
        val classicalSongs = scan.songsInPlaylist("classical")
        assertEquals(
            listOf("classical/bach.mid", "classical/beethoven/ode.mid"),
            classicalSongs.map { it.id }
        )

        // "classical/beethoven" contains only beethoven/ode.mid
        val beethovenSongs = scan.songsInPlaylist("classical/beethoven")
        assertEquals(
            listOf("classical/beethoven/ode.mid"),
            beethovenSongs.map { it.id }
        )

        // "custom_empty" contains no songs
        assertTrue(scan.songsInPlaylist("custom_empty").isEmpty())
    }
}
