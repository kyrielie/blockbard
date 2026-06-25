package kyrielie.blockbard.util

import java.io.DataInputStream

/** Reads a 2-byte little-endian signed short. */
fun DataInputStream.readLEShort(): Short {
    val b0 = read()
    val b1 = read()
    return ((b1 shl 8) or b0).toShort()
}

/** Reads a 4-byte little-endian signed int. */
fun DataInputStream.readLEInt(): Int {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

/** Reads a NBS-style length-prefixed string (4-byte LE length, then UTF-8 bytes). */
fun DataInputStream.readNBSString(): String {
    val len = readLEInt()
    if (len <= 0) return ""
    val bytes = ByteArray(len)
    readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}
