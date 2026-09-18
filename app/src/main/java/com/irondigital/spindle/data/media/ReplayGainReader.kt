package com.irondigital.spindle.data.media

import com.irondigital.spindle.data.media.TagBytes.be32
import com.irondigital.spindle.data.media.TagBytes.le32
import com.irondigital.spindle.data.media.TagBytes.readFullyOrNull
import com.irondigital.spindle.data.media.TagBytes.skipFully
import com.irondigital.spindle.data.media.TagBytes.syncSafe
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * ReplayGain values as written into the file by whatever tagged it.
 *
 * Gains are decibels relative to the reference level. Peaks are linear sample
 * values where 1.0 is full scale, and they exist so a player can decline to
 * apply a positive gain that would clip.
 */
data class ReplayGainValues(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean
        get() = trackGainDb == null && albumGainDb == null

    companion object {
        val NONE = ReplayGainValues()
    }
}

/**
 * Reads ReplayGain tags without decoding any audio.
 *
 * Scanning loudness ourselves would mean decoding every track end to end, which
 * is minutes of CPU per album and a flat battery. Practically every tagger —
 * foobar2000, MusicBrainz Picard, beets, rsgain — writes these tags already, so
 * reading them is both instant and what the user's library almost certainly
 * already contains.
 *
 * Supported: ID3v2 `TXXX` frames (MP3) and Vorbis comments (FLAC). MP4/M4A
 * stores the same values in `----` freeform atoms, which is not handled yet —
 * those files simply read as untagged and play at unity gain.
 */
object ReplayGainReader {

    private const val TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
    private const val ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
    private const val TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
    private const val ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK"

    /**
     * The container is sniffed from the first four bytes rather than trusted from
     * a MIME type, so this needs nothing but the stream — which matters because
     * the player knows a track's URI but not always its declared type.
     */
    fun read(input: InputStream): ReplayGainValues =
        runCatching {
            BufferedInputStream(input, 32 * 1024).use { stream ->
                stream.mark(4)
                val magic = stream.readFullyOrNull(4) ?: return@use ReplayGainValues.NONE
                stream.reset()
                when (String(magic, Charsets.ISO_8859_1)) {
                    "fLaC" -> readFlac(stream)
                    else -> readId3(stream)
                }
            }
        }.getOrDefault(ReplayGainValues.NONE)

    /**
     * Parses a ReplayGain gain string: `-7.50 dB`, `+2.3 dB`, or a bare number.
     *
     * Kept separate and public because this is where the format actually varies
     * between taggers, and it is the part worth pinning down with tests.
     */
    fun parseGainDb(raw: String?): Float? {
        val text = raw?.trim()?.removeSuffix("dB")?.removeSuffix("DB")?.trim() ?: return null
        val value = text.removePrefix("+").toFloatOrNull() ?: return null
        // A gain outside this range is a broken tag, not a quiet record. Applying
        // one would be far worse than ignoring it.
        return if (value.isFinite() && value in -60f..60f) value else null
    }

    /** Peaks are linear, 1.0 being full scale. Some taggers write values slightly over. */
    fun parsePeak(raw: String?): Float? {
        val value = raw?.trim()?.toFloatOrNull() ?: return null
        return if (value.isFinite() && value > 0f && value <= 4f) value else null
    }

    /** Folds one key/value pair into the accumulating result. */
    private fun ReplayGainValues.withTag(key: String, value: String): ReplayGainValues =
        when (key.uppercase()) {
            TRACK_GAIN -> copy(trackGainDb = parseGainDb(value) ?: trackGainDb)
            ALBUM_GAIN -> copy(albumGainDb = parseGainDb(value) ?: albumGainDb)
            TRACK_PEAK -> copy(trackPeak = parsePeak(value) ?: trackPeak)
            ALBUM_PEAK -> copy(albumPeak = parsePeak(value) ?: albumPeak)
            else -> this
        }

    // ---------------------------------------------------------------- ID3v2

    private fun readId3(stream: InputStream): ReplayGainValues {
        val header = stream.readFullyOrNull(10) ?: return ReplayGainValues.NONE
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) {
            return ReplayGainValues.NONE
        }
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = syncSafe(header, 6)
        if (tagSize <= 0 || tagSize > TagBytes.MAX_TAG_BYTES) return ReplayGainValues.NONE

        val body = stream.readFullyOrNull(tagSize) ?: return ReplayGainValues.NONE
        var pos = 0

        if (flags and 0x40 != 0) {
            if (body.size < 4) return ReplayGainValues.NONE
            val extSize = if (major >= 4) syncSafe(body, 0) else be32(body, 0) + 4
            if (extSize !in 0..body.size) return ReplayGainValues.NONE
            pos = extSize
        }

        val idLength = if (major <= 2) 3 else 4
        val sizeLength = if (major <= 2) 3 else 4
        val flagLength = if (major <= 2) 0 else 2

        var values = ReplayGainValues.NONE

        while (pos + idLength + sizeLength + flagLength <= body.size) {
            val frameId = String(body, pos, idLength, Charsets.ISO_8859_1)
            if (frameId[0] == '\u0000') break
            pos += idLength

            val frameSize = when {
                major >= 4 -> syncSafe(body, pos)
                major == 3 -> be32(body, pos)
                else -> ((body[pos].toInt() and 0xFF) shl 16) or
                    ((body[pos + 1].toInt() and 0xFF) shl 8) or (body[pos + 2].toInt() and 0xFF)
            }
            pos += sizeLength + flagLength
            if (frameSize <= 0 || pos + frameSize > body.size) break

            if (frameId == "TXXX" || frameId == "TXX") {
                parseTxxx(body, pos, frameSize)?.let { (key, value) ->
                    values = values.withTag(key, value)
                }
            }
            pos += frameSize
        }
        return values
    }

    /** TXXX: encoding(1) description(terminated) value. */
    private fun parseTxxx(body: ByteArray, offset: Int, size: Int): Pair<String, String>? {
        if (size < 2) return null
        val charset = id3Charset(body[offset].toInt() and 0xFF)
        val width = if (charset == Charsets.ISO_8859_1 || charset == Charsets.UTF_8) 1 else 2
        val end = offset + size

        var p = offset + 1
        val descriptionStart = p
        var descriptionEnd = -1
        while (p + width <= end) {
            val terminator = if (width == 1) {
                body[p] == 0.toByte()
            } else {
                body[p] == 0.toByte() && body[p + 1] == 0.toByte()
            }
            if (terminator) {
                descriptionEnd = p
                break
            }
            p += width
        }
        if (descriptionEnd < 0) return null

        val description = String(body, descriptionStart, descriptionEnd - descriptionStart, charset)
        val valueStart = descriptionEnd + width
        if (valueStart >= end) return null
        val value = String(body, valueStart, end - valueStart, charset).trim('\u0000', ' ')
        return description.trim() to value
    }

    private fun id3Charset(code: Int): Charset = when (code) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    // ------------------------------------------------------- FLAC / Vorbis

    private fun readFlac(stream: InputStream): ReplayGainValues {
        val magic = stream.readFullyOrNull(4) ?: return ReplayGainValues.NONE
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return ReplayGainValues.NONE

        while (true) {
            val header = stream.readFullyOrNull(4) ?: return ReplayGainValues.NONE
            val isLast = (header[0].toInt() and 0x80) != 0
            val blockType = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (length < 0 || length > TagBytes.MAX_TAG_BYTES) return ReplayGainValues.NONE

            if (blockType == 4) { // VORBIS_COMMENT
                val block = stream.readFullyOrNull(length) ?: return ReplayGainValues.NONE
                return parseVorbisComments(block)
            }
            if (isLast) return ReplayGainValues.NONE
            if (stream.skipFully(length.toLong()) < length.toLong()) return ReplayGainValues.NONE
        }
    }

    private fun parseVorbisComments(block: ByteArray): ReplayGainValues {
        var p = 0
        fun next32(): Int {
            if (p + 4 > block.size) return -1
            val v = le32(block, p)
            p += 4
            return v
        }

        val vendorLength = next32()
        if (vendorLength < 0 || p + vendorLength > block.size) return ReplayGainValues.NONE
        p += vendorLength

        val count = next32()
        if (count < 0 || count > 10_000) return ReplayGainValues.NONE

        var values = ReplayGainValues.NONE
        repeat(count) {
            val length = next32()
            if (length < 0 || p + length > block.size) return@repeat
            val comment = String(block, p, length, Charsets.UTF_8)
            p += length
            val separator = comment.indexOf('=')
            if (separator > 0) {
                values = values.withTag(
                    comment.substring(0, separator),
                    comment.substring(separator + 1),
                )
            }
        }
        return values
    }
}
