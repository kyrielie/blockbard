package kyrielie.blockbard.util

import java.io.DataInputStream
import java.io.EOFException

/**
 * Reads a single byte and throws EOFException (rather than silently returning -1)
 * if the stream is exhausted. [field] describes what was being read (e.g. "tick byte",
 * "instrument byte") so a truncated/corrupted file produces a clear, specific error
 * instead of -1 silently flowing into downstream bit-shift or lookup logic.
 */
fun DataInputStream.readByteChecked(field: String): Int {
    val b = read()
    if (b == -1) throw EOFException("Unexpected end of NBS file while reading $field")
    return b
}

/** Reads a 2-byte little-endian signed short. [field] describes what is being read, for EOFException messages. */
fun DataInputStream.readLEShort(field: String): Short {
    val b0 = readByteChecked("$field (byte 0)")
    val b1 = readByteChecked("$field (byte 1)")
    return ((b1 shl 8) or b0).toShort()
}

/** Reads a 4-byte little-endian signed int. [field] describes what is being read, for EOFException messages. */
fun DataInputStream.readLEInt(field: String): Int {
    val b0 = readByteChecked("$field (byte 0)")
    val b1 = readByteChecked("$field (byte 1)")
    val b2 = readByteChecked("$field (byte 2)")
    val b3 = readByteChecked("$field (byte 3)")
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

/** Reads a NBS-style length-prefixed string (4-byte LE length, then UTF-8 bytes). [field] describes what is being read, for EOFException messages. */
fun DataInputStream.readNBSString(field: String): String {
    val len = readLEInt("$field length")
    if (len <= 0) return ""
    val bytes = ByteArray(len)
    try {
        readFully(bytes, 0, len)
    } catch (e: EOFException) {
        throw EOFException("Unexpected end of NBS file while reading $field ($len bytes expected)")
    }
    return String(bytes, Charsets.UTF_8)
}