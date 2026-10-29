package kyrielie.blockbard.client.util

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

object SongDownloader {

    private val logger = LoggerFactory.getLogger("BlockBard/Downloader")
    private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024 // 10 MB

    private val midisDir: File by lazy {
        FabricLoader.getInstance().configDir.resolve("blockbard/midis").toFile().also {
            if (!it.exists()) it.mkdirs()
        }
    }

    /**
     * Downloads a MIDI or NBS file asynchronously from [urlStr].
     * [customName] can optionally override the target file name.
     * [onComplete] is called on Minecraft's client thread with the downloaded file, or null on failure.
     */
    fun download(urlStr: String, customName: String? = null, onComplete: ((File?) -> Unit)? = null) {
        Thread({
            try {
                logger.info("Starting download: $urlStr")
                val uri = URI(urlStr.trim())
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    notifyUser("§cInvalid URL scheme. Must be http or https.", false)
                    callback(null, onComplete)
                    return@Thread
                }

                val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "BlockBard/1.0 (Minecraft Fabric)")
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    notifyUser("§cDownload failed: HTTP $responseCode (${conn.responseMessage})", false)
                    callback(null, onComplete)
                    return@Thread
                }

                val contentLength = conn.contentLengthLong
                if (contentLength > MAX_FILE_SIZE_BYTES) {
                    notifyUser("§cFile exceeds maximum allowed size (10MB)", false)
                    callback(null, onComplete)
                    return@Thread
                }

                // Read data into memory buffer first to inspect magic bytes and enforce size cap
                val buffer = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val chunk = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0
                    while (input.read(chunk).also { bytesRead = it } != -1) {
                        totalRead += bytesRead
                        if (totalRead > MAX_FILE_SIZE_BYTES) {
                            notifyUser("§cDownload cancelled: file exceeded 10MB limit", false)
                            callback(null, onComplete)
                            return@Thread
                        }
                        buffer.write(chunk, 0, bytesRead)
                    }
                }

                val data = buffer.toByteArray()
                if (data.isEmpty()) {
                    notifyUser("§cDownloaded file was empty", false)
                    callback(null, onComplete)
                    return@Thread
                }

                // Determine file name and extension
                val filename = determineFilename(urlStr, conn.getHeaderField("Content-Disposition"), customName, data)
                val targetFile = midisDir.resolve(filename)

                targetFile.writeBytes(data)
                logger.info("Downloaded ${targetFile.name} (${data.size} bytes)")
                notifyUser("§aDownloaded: §f${targetFile.name} §7(${data.size / 1024} KB)", true)

                callback(targetFile, onComplete)
            } catch (e: Exception) {
                logger.warn("Download error for $urlStr: ${e.message}", e)
                notifyUser("§cDownload error: ${e.message}", false)
                callback(null, onComplete)
            }
        }, "BlockBard-SongDownloader").apply {
            isDaemon = true
            start()
        }
    }

    private fun determineFilename(urlStr: String, contentDisposition: String?, customName: String?, data: ByteArray): String {
        // Priority 1: user-specified name
        var baseName = customName?.trim()

        // Priority 2: Content-Disposition header
        if (baseName.isNullOrBlank() && !contentDisposition.isNullOrBlank()) {
            val cdMatch = Regex("filename\\*?=['\"]?(?:UTF-8'')?([^'\";\\r\\n]+)['\"]?", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
            baseName = cdMatch?.groupValues?.get(1)?.trim()
        }

        // Priority 3: extract from URL path
        if (baseName.isNullOrBlank()) {
            val path = URI(urlStr).path
            baseName = path.substringAfterLast('/').takeIf { it.isNotBlank() }
        }

        if (baseName.isNullOrBlank()) {
            baseName = "downloaded_track"
        }

        // Sanitize
        baseName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")

        // Ensure proper extension using magic bytes
        val hasMidiExt = baseName.endsWith(".mid", ignoreCase = true) || baseName.endsWith(".midi", ignoreCase = true)
        val hasNbsExt = baseName.endsWith(".nbs", ignoreCase = true)

        if (!hasMidiExt && !hasNbsExt) {
            // Check magic bytes: MIDI starts with 'MThd' (0x4D, 0x54, 0x68, 0x64)
            val isMidi = data.size >= 4 &&
                    data[0] == 0x4D.toByte() &&
                    data[1] == 0x54.toByte() &&
                    data[2] == 0x68.toByte() &&
                    data[3] == 0x64.toByte()

            baseName += if (isMidi) ".mid" else ".nbs"
        }

        return baseName
    }

    private fun notifyUser(msg: String, success: Boolean) {
        val mc = Minecraft.getInstance()
        mc.execute {
            val player = mc.player
            if (player != null) {
                player.sendSystemMessage(Component.literal("§b[BlockBard] $msg"))
            } else {
                if (success) logger.info(msg) else logger.warn(msg)
            }
        }
    }

    private fun callback(file: File?, onComplete: ((File?) -> Unit)?) {
        if (onComplete == null) return
        Minecraft.getInstance().execute {
            onComplete(file)
        }
    }
}
