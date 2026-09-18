package com.irondigital.spindle.playback

import com.irondigital.spindle.data.media.ReplayGainReader
import com.irondigital.spindle.data.media.ReplayGainValues
import com.irondigital.spindle.data.settings.NormalizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainParsingTest {

    @Test
    fun `parses the shapes taggers actually write`() {
        assertEquals(-7.5f, ReplayGainReader.parseGainDb("-7.50 dB"))
        assertEquals(2.3f, ReplayGainReader.parseGainDb("+2.3 dB"))
        assertEquals(-7.5f, ReplayGainReader.parseGainDb("-7.5"))
        assertEquals(0f, ReplayGainReader.parseGainDb("0.00 dB"))
        assertEquals(-6.25f, ReplayGainReader.parseGainDb("  -6.25 DB  "))
    }

    @Test
    fun `rejects nonsense rather than applying it`() {
        assertNull(ReplayGainReader.parseGainDb(null))
        assertNull(ReplayGainReader.parseGainDb(""))
        assertNull(ReplayGainReader.parseGainDb("loud"))
        assertNull(ReplayGainReader.parseGainDb("dB"))
        // A tag this far out is broken; honouring it would be far worse than
        // ignoring it.
        assertNull(ReplayGainReader.parseGainDb("-140.0 dB"))
        assertNull(ReplayGainReader.parseGainDb("999 dB"))
    }

    @Test
    fun `parses peaks and rejects impossible ones`() {
        assertEquals(0.987654f, ReplayGainReader.parsePeak("0.987654"))
        assertEquals(1.0f, ReplayGainReader.parsePeak("1.000000"))
        assertNull(ReplayGainReader.parsePeak("0"))
        assertNull(ReplayGainReader.parsePeak("-0.5"))
        assertNull(ReplayGainReader.parsePeak("abc"))
        assertNull(ReplayGainReader.parsePeak(null))
    }
}

class NormalizationTest {

    private fun values(
        track: Float? = null,
        album: Float? = null,
        trackPeak: Float? = null,
        albumPeak: Float? = null,
    ) = ReplayGainValues(track, album, trackPeak, albumPeak)

    @Test
    fun `off mode never touches anything`() {
        val result = Normalization.compute(
            NormalizationMode.OFF,
            values(track = -9f, trackPeak = 0.5f),
            preampDb = 6f,
        )
        assertEquals(NormalizationResult.UNITY, result)
    }

    @Test
    fun `an untagged track plays at unity`() {
        val result = Normalization.compute(NormalizationMode.TRACK, ReplayGainValues.NONE, 0f)
        assertEquals(NormalizationResult.UNITY, result)
    }

    @Test
    fun `a loud track is attenuated with the player volume`() {
        val result = Normalization.compute(NormalizationMode.TRACK, values(track = -6f), 0f)

        // -6 dB is a linear multiplier of about 0.501.
        assertEquals(0.501f, result.playerVolume, 0.005f)
        assertEquals(0, result.boostMillibels)
    }

    @Test
    fun `a quiet track is boosted rather than attenuated`() {
        // Peak is low enough that there is room for the whole boost.
        val result = Normalization.compute(
            NormalizationMode.TRACK,
            values(track = 4f, trackPeak = 0.1f),
            0f,
        )
        assertEquals(1f, result.playerVolume, 0.0001f)
        assertEquals(400, result.boostMillibels)
    }

    @Test
    fun `the preamp shifts the result`() {
        val plain = Normalization.compute(NormalizationMode.TRACK, values(track = -6f), 0f)
        val lifted = Normalization.compute(NormalizationMode.TRACK, values(track = -6f), 3f)

        assertTrue(lifted.playerVolume > plain.playerVolume)
    }

    @Test
    fun `peak headroom caps a boost that would clip`() {
        // A file peaking at 0.95 has only ~0.45 dB of headroom, so a +6 dB gain
        // must be cut down rather than squaring off every transient.
        val result = Normalization.compute(
            NormalizationMode.TRACK,
            values(track = 6f, trackPeak = 0.95f),
            0f,
        )
        assertEquals(1f, result.playerVolume, 0.0001f)
        assertTrue(
            "expected the boost to be clamped near the 0.45 dB of headroom, was ${result.boostMillibels}",
            result.boostMillibels in 1..60,
        )
    }

    @Test
    fun `a file already at full scale gets no boost at all`() {
        val result = Normalization.compute(
            NormalizationMode.TRACK,
            values(track = 5f, trackPeak = 1.0f),
            0f,
        )
        assertEquals(NormalizationResult.UNITY, result)
    }

    @Test
    fun `peak never blocks attenuation`() {
        // Turning a track down cannot clip, so headroom is irrelevant here.
        val result = Normalization.compute(
            NormalizationMode.TRACK,
            values(track = -8f, trackPeak = 1.0f),
            0f,
        )
        assertTrue(result.playerVolume < 1f)
    }

    @Test
    fun `album mode prefers the album gain`() {
        val result = Normalization.compute(
            NormalizationMode.ALBUM,
            values(track = -12f, album = -6f),
            0f,
        )
        assertEquals(Normalization.decibelsToLinear(-6f), result.playerVolume, 0.001f)
    }

    @Test
    fun `album mode falls back to the track gain when the album was never scanned`() {
        val result = Normalization.compute(NormalizationMode.ALBUM, values(track = -12f), 0f)
        assertEquals(Normalization.decibelsToLinear(-12f), result.playerVolume, 0.001f)
    }

    @Test
    fun `track mode falls back to the album gain`() {
        val result = Normalization.compute(NormalizationMode.TRACK, values(album = -4f), 0f)
        assertEquals(Normalization.decibelsToLinear(-4f), result.playerVolume, 0.001f)
    }

    @Test
    fun `a boost is capped so the enhancer is never asked for mush`() {
        val result = Normalization.compute(
            NormalizationMode.TRACK,
            values(track = 40f, trackPeak = 0.001f),
            preampDb = 15f,
        )
        assertEquals(Normalization.MAX_BOOST_MILLIBELS, result.boostMillibels)
    }

    @Test
    fun `volume stays inside the legal range for an extreme attenuation`() {
        val result = Normalization.compute(NormalizationMode.TRACK, values(track = -60f), -15f)
        assertTrue(result.playerVolume in 0f..1f)
    }

    @Test
    fun `decibel conversion matches the reference points`() {
        assertEquals(1f, Normalization.decibelsToLinear(0f), 0.0001f)
        assertEquals(0.5f, Normalization.decibelsToLinear(-6.0206f), 0.001f)
        assertEquals(2f, Normalization.decibelsToLinear(6.0206f), 0.001f)
    }
}
