package kyrielie.blockbard.playback

import kyrielie.blockbard.client.playback.NbsFileLoader
import kyrielie.blockbard.client.playback.SongMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Path

class SongMetadataTest {

    @Test
    fun summaryString_withAuthor() {
        val meta = SongMetadata(
            durationSeconds = 215, // 03:35
            bpm = 120,
            distinctNotes = 48,
            isNbs = false,
            title = "Sweden",
            author = "C418"
        )
        assertEquals("03:35", meta.durationFormatted)
        assertEquals("by C418  ·  03:35  ·  120 BPM  ·  48 notes", meta.summaryString)
    }

    @Test
    fun summaryString_withoutAuthor() {
        val meta = SongMetadata(
            durationSeconds = 65, // 01:05
            bpm = 140,
            distinctNotes = 25,
            isNbs = true,
            title = "Test",
            author = null
        )
        assertEquals("01:05", meta.durationFormatted)
        assertEquals("01:05  ·  140 BPM  ·  25 notes", meta.summaryString)
    }

    @Test
    fun nbsFileLoader_extractsTitleAndAuthor(@TempDir tempDir: Path) {
        // Construct a minimal valid classic NBS file format (v0)
        val file = tempDir.resolve("test_song.nbs").toFile()
        val bout = ByteArrayOutputStream()
        val out = DataOutputStream(bout)

        // Classic NBS:
        // songLength (LE short) = 1
        out.write(1); out.write(0)
        // layerCount (LE short) = 1
        out.write(1); out.write(0)
        // title (LE int length + bytes)
        writeNBSString(out, "Test Title")
        // author (LE int length + bytes)
        writeNBSString(out, "Test Author")
        // original author (LE int length + bytes)
        writeNBSString(out, "Original Composer")
        // description
        writeNBSString(out, "Description")
        // tempo (LE short in 100ths of ticks/sec, e.g. 1000 = 10.0 TPS)
        out.write(0xE8.toByte().toInt()); out.write(0x03) // 1000
        // auto-save (byte)
        out.writeByte(0)
        // auto-save duration (byte)
        out.writeByte(0)
        // time signature (byte)
        out.writeByte(4)
        // minutes spent (LE int)
        out.writeInt(0)
        // left clicks (LE int)
        out.writeInt(0)
        // right clicks (LE int)
        out.writeInt(0)
        // note blocks added (LE int)
        out.writeInt(0)
        // note blocks removed (LE int)
        out.writeInt(0)
        // midi/schematic name
        writeNBSString(out, "")
        // loop (byte)
        out.writeByte(0)
        // max loop count (byte)
        out.writeByte(0)
        // loop start tick (LE short)
        out.writeShort(0)

        // Notes: tick jumps (LE short 0 terminates)
        out.writeShort(0) // 0 tick jump terminates note reading

        file.writeBytes(bout.toByteArray())

        val nbs = NbsFileLoader.load(file)
        assertEquals("Test Title", nbs.title)
        assertEquals("Test Author", nbs.author)
        assertEquals("Original Composer", nbs.originalAuthor)
        assertEquals(20.0f, nbs.tempo) // Resampled to 20 TPS
    }

    private fun writeNBSString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        // Little-endian int length
        out.writeByte(len and 0xFF)
        out.writeByte((len shr 8) and 0xFF)
        out.writeByte((len shr 16) and 0xFF)
        out.writeByte((len shr 24) and 0xFF)
        out.write(bytes)
    }
}
