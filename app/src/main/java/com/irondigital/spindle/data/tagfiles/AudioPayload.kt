package com.irondigital.spindle.data.tagfiles

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** The containers tags can be written into. Anything else is refused. */
enum class AudioContainer(val extensions: Set<String>) {
    MP3(setOf("mp3")),
    FLAC(setOf("flac")),
    M4A(setOf("m4a", "mp4", "m4b"));

    companion object {
        fun forFileName(name: String): AudioContainer? {
            val extension = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }
    }
}

/**
 * A fingerprint of the sound in a file, excluding every byte that belongs to a
 * tag.
 *
 * This is the guarantee the whole feature rests on. Rewriting tags moves bytes
 * around — a tag grows, padding changes, an MP4 index is rewritten — so the
 * file as a whole is expected to differ afterwards. The audio must not. Each
 * container keeps its audio in one identifiable region, and a digest of exactly
 * that region, taken before and after, proves the edit touched only what it
 * was meant to. If the fingerprints differ, nothing is written back.
 *
 * Returns null when the layout cannot be read with confidence, which is treated
 * as a reason to refuse — never as a reason to write anyway.
 */
object AudioPayload {

    fun digest(file: File, container: AudioContainer): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            when (container) {
                AudioContainer.MP3 -> mp3(raf)
                AudioContainer.FLAC -> flac(raf)
                AudioContainer.M4A -> m4a(raf)
            }
        }
    }.getOrNull()

    /**
     * MPEG frames sit between an ID3v2 tag at the front and optional APEv2 and
     * ID3v1 tags at the back. Measured from the first frame sync, so padding a
     * tag writer adds or removes is not mistaken for a change to the audio.
     */
    private fun mp3(raf: RandomAccessFile): ByteArray? {
        val length = raf.length()
        var start = skipId3v2(raf, 0L)

        // First frame sync after the tag: eleven set bits.
        raf.seek(start)
        val window = ByteArray(minOf(SYNC_SEARCH, (length - start).toInt()).coerceAtLeast(0))
        raf.readFully(window)
        var sync = -1
        for (i in 0 until window.size - 1) {
            if (window[i].toInt() and 0xFF == 0xFF && window[i + 1].toInt() and 0xE0 == 0xE0) {
                sync = i
                break
            }
        }
        if (sync < 0) return null
        start += sync

        var end = length
        if (end - start >= 128 && ascii(raf, end - 128, 3) == "TAG") end -= 128
        if (end - start >= 32 && ascii(raf, end - 32, 8) == "APETAGEX") {
            raf.seek(end - 32 + 12)
            val size = le32(raf).toLong()
            raf.seek(end - 32 + 20)
            val hasHeader = le32(raf) and (1 shl 31) != 0
            end -= size + if (hasHeader) 32 else 0
        }
        if (end <= start) return null
        return sha256(raf, start, end)
    }

    /** Audio frames follow the last metadata block. */
    private fun flac(raf: RandomAccessFile): ByteArray? {
        val length = raf.length()
        var pos = skipId3v2(raf, 0L)
        if (ascii(raf, pos, 4) != "fLaC") return null
        pos += 4

        while (true) {
            if (pos + 4 > length) return null
            raf.seek(pos)
            val header = raf.read()
            val blockLength = (raf.read() shl 16) or (raf.read() shl 8) or raf.read()
            pos += 4 + blockLength
            if (header and 0x80 != 0) break
        }

        var end = length
        if (end - pos >= 128 && ascii(raf, end - 128, 3) == "TAG") end -= 128
        if (end <= pos) return null
        return sha256(raf, pos, end)
    }

    /**
     * MP4 keeps its samples in `mdat` boxes. Tag writing rewrites `moov` and may
     * move `mdat` relative to it, updating the offsets that point into it — so
     * the digest covers the contents of every `mdat`, in order, wherever it sits.
     */
    private fun m4a(raf: RandomAccessFile): ByteArray? {
        val length = raf.length()
        val digest = MessageDigest.getInstance("SHA-256")
        var pos = 0L
        var found = false

        while (pos + 8 <= length) {
            raf.seek(pos)
            var size = be32(raf).toLong() and 0xFFFFFFFFL
            val type = ascii(raf, pos + 4, 4)
            var headerSize = 8L
            if (size == 1L) {
                raf.seek(pos + 8)
                size = raf.readLong()
                headerSize = 16L
            } else if (size == 0L) {
                size = length - pos
            }
            if (size < headerSize || pos + size > length) return null

            if (type == "mdat") {
                update(digest, raf, pos + headerSize, pos + size)
                found = true
            }
            pos += size
        }
        return if (found) digest.digest() else null
    }

    private fun skipId3v2(raf: RandomAccessFile, from: Long): Long {
        var pos = from
        // Some files carry more than one, and some FLACs carry one at all.
        while (pos + 10 <= raf.length() && ascii(raf, pos, 3) == "ID3") {
            raf.seek(pos + 5)
            val flags = raf.read()
            raf.seek(pos + 6)
            val bytes = ByteArray(4).also { raf.readFully(it) }
            val size = ((bytes[0].toInt() and 0x7F) shl 21) or ((bytes[1].toInt() and 0x7F) shl 14) or
                ((bytes[2].toInt() and 0x7F) shl 7) or (bytes[3].toInt() and 0x7F)
            pos += 10 + size + if (flags and 0x10 != 0) 10 else 0
        }
        return pos
    }

    private fun sha256(raf: RandomAccessFile, start: Long, end: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, raf, start, end)
        return digest.digest()
    }

    private fun update(digest: MessageDigest, raf: RandomAccessFile, start: Long, end: Long) {
        raf.seek(start)
        val buffer = ByteArray(64 * 1024)
        var remaining = end - start
        while (remaining > 0) {
            val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }

    private fun ascii(raf: RandomAccessFile, at: Long, count: Int): String {
        if (at < 0 || at + count > raf.length()) return ""
        raf.seek(at)
        val bytes = ByteArray(count).also { raf.readFully(it) }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun be32(raf: RandomAccessFile): Int = raf.readInt()

    private fun le32(raf: RandomAccessFile): Int {
        val b = ByteArray(4).also { raf.readFully(it) }
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    /** How far past the tag to look for the first frame before giving up. */
    private const val SYNC_SEARCH = 256 * 1024
}
