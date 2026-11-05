package kyrielie.blockbard.library

import java.io.File
import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Representation of a scanned song file within the library.
 *
 * @param id Relative path from the library root, normalized with forward slashes (e.g. "song.mid" or "classical/bach.mid")
 * @param file Concrete [File] on disk
 * @param isTopLevel True if the file resides directly in the root folder
 */
data class ScannedSong(
    val id: String,
    val file: File,
    val isTopLevel: Boolean
)

/**
 * Result of a library scan operation.
 *
 * @param songs Discovered songs sorted deterministically (lowercase ID, then ID)
 * @param warnings Any non-fatal issues encountered (symlink loops, broken links, duplicate targets, truncation)
 */
data class LibraryScan(
    val songs: List<ScannedSong>,
    val subfolders: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val files: List<File> get() = songs.map { it.file }
    val songsById: Map<String, ScannedSong> by lazy { songs.associateBy { it.id } }

    fun findById(id: String): ScannedSong? = songsById[id]

    /**
     * All discovered playlists, starting with "" ("All"), followed by sorted subfolder relative paths.
     */
    val playlists: List<String> by lazy {
        val folderSet = mutableSetOf<String>()
        folderSet.addAll(subfolders)
        for (song in songs) {
            if (!song.isTopLevel) {
                var folder = song.id.substringBeforeLast('/', "")
                while (folder.isNotEmpty()) {
                    folderSet.add(folder)
                    folder = folder.substringBeforeLast('/', "")
                }
            }
        }
        listOf("") + folderSet.sorted()
    }

    /**
     * Filters songs belonging to the given [playlistId] ("" for All).
     * Matches songs directly in the playlist directory or any of its subfolders.
     */
    fun songsInPlaylist(playlistId: String): List<ScannedSong> {
        if (playlistId.isEmpty()) return songs
        val prefix = "$playlistId/"
        return songs.filter { it.id.startsWith(prefix) }
    }
}

/**
 * Pure JDK-only file scanner for discovering supported music files (.mid, .midi, .nbs).
 * Handles symlinks, directory recursion with depth bounding, loop detection, and file caps.
 */
object LibraryScanner {

    val SUPPORTED_EXTENSIONS: Set<String> = setOf("mid", "midi", "nbs")

    const val DEFAULT_MAX_FOLDER_DEPTH: Int = 3
    const val DEFAULT_MAX_FILES: Int = 5000

    /**
     * Scans [root] for supported song files.
     *
     * @param root Root directory to scan from
     * @param maxFolderDepth Maximum subfolder traversal depth (0 = top-level only)
     * @param maxFiles Maximum total number of songs to index before truncating
     */
    fun scan(
        root: Path,
        maxFolderDepth: Int = DEFAULT_MAX_FOLDER_DEPTH,
        maxFiles: Int = DEFAULT_MAX_FILES
    ): LibraryScan {
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return LibraryScan(emptyList())
        }

        val songs = mutableListOf<ScannedSong>()
        val subfolders = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val seenRealPaths = mutableSetOf<Path>()

        val options = EnumSet.of(FileVisitOption.FOLLOW_LINKS)
        val maxTreeDepth = 1 + maxFolderDepth.coerceAtLeast(0)

        try {
            Files.walkFileTree(root, options, maxTreeDepth, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root) {
                        val name = dir.fileName?.toString() ?: ""
                        if (name.startsWith(".") || name.equals("__MACOSX", ignoreCase = true)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        val relDir = try {
                            root.relativize(dir).toString().replace('\\', '/')
                        } catch (e: Exception) {
                            name
                        }
                        subfolders.add(relDir)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (songs.size >= maxFiles) {
                        if (!warnings.any { it.contains("truncated") }) {
                            warnings.add("Library truncated at $maxFiles files")
                        }
                        return FileVisitResult.TERMINATE
                    }

                    val fileName = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (fileName.startsWith(".") || fileName.equals("__MACOSX", ignoreCase = true)) {
                        return FileVisitResult.CONTINUE
                    }

                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    if (ext !in SUPPORTED_EXTENSIONS) {
                        return FileVisitResult.CONTINUE
                    }

                    if (!attrs.isRegularFile) {
                        return FileVisitResult.CONTINUE
                    }

                    val relPath = try {
                        root.relativize(file).toString().replace('\\', '/')
                    } catch (e: Exception) {
                        fileName
                    }

                    // Check for duplicate real paths (e.g. symlinks pointing to an already-indexed file)
                    val realPath = try {
                        file.toRealPath()
                    } catch (e: Exception) {
                        null
                    }

                    if (realPath != null && !seenRealPaths.add(realPath)) {
                        warnings.add("Duplicate target skipped for: $relPath")
                        return FileVisitResult.CONTINUE
                    }

                    val isTopLevel = (file.parent == root)
                    songs.add(ScannedSong(relPath, file.toFile(), isTopLevel))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    if (exc is FileSystemLoopException) {
                        warnings.add("Symlink cycle detected at: ${file.fileName}")
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    warnings.add("Could not read file ${file.fileName}: ${exc.message}")
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            warnings.add("Scan error: ${e.message}")
        }

        // Deterministic ordering: lowercase ID first, then exact ID
        val sortedSongs = songs.sortedWith(compareBy({ it.id.lowercase() }, { it.id }))
        return LibraryScan(sortedSongs, subfolders.sorted(), warnings)
    }
}
