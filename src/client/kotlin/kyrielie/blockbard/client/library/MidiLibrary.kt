package kyrielie.blockbard.client.library

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.library.LibraryScan
import kyrielie.blockbard.library.LibraryScanner
import kyrielie.blockbard.library.ScannedSong
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Client-side song file manager and coordinator for the local music library.
 * Centralizes the `midisDir` root, tracks library scans, and handles system folder interactions.
 */
object MidiLibrary {

    private val logger = LoggerFactory.getLogger("BlockBard/MidiLibrary")

    val root: File by lazy {
        FabricLoader.getInstance().configDir.resolve("blockbard/midis").toFile().also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                dir.resolve("README.txt").writeText(
                    "Drop .mid or .nbs files here and click 'Refresh' in BlockBard."
                )
            }
        }
    }

    @Volatile
    var lastScan: LibraryScan = LibraryScan(emptyList())
        private set

    val songs: List<ScannedSong> get() = lastScan.songs
    val files: List<File> get() = lastScan.files
    val playlists: List<String> get() = lastScan.playlists

    /**
     * Executes a fresh scan of the library directory tree.
     */
    fun scan(): LibraryScan {
        val scan = LibraryScanner.scan(root.toPath())
        lastScan = scan
        logger.info("refreshFiles: found ${scan.songs.size} files, ${scan.playlists.size} playlists in ${root.absolutePath}")
        for (w in scan.warnings) {
            logger.warn("MidiLibrary scan: $w")
        }
        if (ConfigManager.config.migrateSongIds(scan.songs)) {
            ConfigManager.save()
            logger.info("Migrated legacy song IDs in config to relative paths")
        }
        return scan
    }

    /**
     * Songs belonging to the given [playlistId] ("" for All).
     */
    fun songsInPlaylist(playlistId: String): List<ScannedSong> =
        lastScan.songsInPlaylist(playlistId)

    /**
     * Files belonging to the given [playlistId] ("" for All).
     */
    fun filesInPlaylist(playlistId: String): List<File> =
        songsInPlaylist(playlistId).map { it.file }

    /**
     * Resolves a [SongId] (relative path or filename) to a concrete [File].
     */
    fun fileForId(id: String): File {
        lastScan.findById(id)?.let { return it.file }
        if (!id.contains('/')) {
            lastScan.songs.firstOrNull { it.file.name.equals(id, ignoreCase = true) }?.let { return it.file }
        }
        return root.resolve(id)
    }

    /**
     * Computes the normalized relative ID for a given file within the library.
     */
    fun idForFile(file: File): String {
        return try {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            val filePath = file.toPath().toAbsolutePath().normalize()
            if (filePath.startsWith(rootPath)) {
                rootPath.relativize(filePath).toString().replace('\\', '/')
            } else {
                file.name
            }
        } catch (e: Exception) {
            file.name
        }
    }

    /**
     * Searches available library files matching query [name]:
     * 1. Exact match on ID or filename (case-insensitive)
     * 2. Match without extension (case-insensitive)
     * 3. Substring contains match (case-insensitive)
     */
    fun findSongFile(name: String): File? {
        val allFiles = files.ifEmpty { scan().files }
        allFiles.firstOrNull { it.name.equals(name, ignoreCase = true) || idForFile(it).equals(name, ignoreCase = true) }?.let { return it }
        allFiles.firstOrNull { it.nameWithoutExtension.equals(name, ignoreCase = true) }?.let { return it }
        return allFiles.firstOrNull { it.name.contains(name, ignoreCase = true) || idForFile(it).contains(name, ignoreCase = true) }
    }

    /**
     * Opens the library directory in the user's operating system file manager
     * (e.g. Finder on macOS, Explorer on Windows, or default file browser on Linux).
     */
    fun openFolder() {
        if (!root.exists()) {
            root.mkdirs()
        }
        com.mojang.blaze3d.Blaze3D.openPath(root.toPath())
    }
}
