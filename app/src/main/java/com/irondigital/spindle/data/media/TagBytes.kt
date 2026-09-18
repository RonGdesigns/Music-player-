package com.irondigital.spindle.data.media

import java.io.EOFException
import java.io.InputStream

/**
 * The low-level reads every tag format needs: big-endian integers, ID3's
 * syncsafe encoding, and stream helpers that do not lie about short reads.
 *
 * Shared by the lyrics reader and the ReplayGain reader, which walk the same
 * three containers looking for different frames.
 */
internal object TagBytes {

    const val MAX_TAG_BYTES = 8 * 1024 * 1024

    /**
     * ID3 sizes are stored seven bits per byte so the size can never contain a
     * byte that looks like an MPEG sync word.
     */
    fun syncSafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    fun be64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Reads exactly [count] bytes, or returns null. A short read is never silently accepted. */
    fun InputStream.readFullyOrNull(count: Int): ByteArray? {
        if (count <= 0) return null
        val buffer = ByteArray(count)
        var read = 0
        try {
            while (read < count) {
                val n = read(buffer, read, count - read)
                if (n < 0) return null
                read += n
            }
        } catch (_: EOFException) {
            return null
        }
        return buffer
    }

    /** skip() is allowed to skip fewer bytes than asked, so this insists. */
    fun InputStream.skipFully(count: Long): Long {
        var remaining = count
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val n = read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
        return count - remaining
    }
}
