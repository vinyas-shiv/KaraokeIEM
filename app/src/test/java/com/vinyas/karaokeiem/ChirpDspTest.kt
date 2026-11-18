package com.vinyas.karaokeiem

import com.vinyas.karaokeiem.audio.ChirpDsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChirpDspTest {

    @Test
    fun `orthogonal chirp matched filter recovers simulated 187_5ms Bluetooth lag within 0_5ms`() {
        val sampleRate = 48_000
        val upChirp = ChirpDsp.generateLinearChirpPcm16(
            sampleRate = sampleRate,
            durationMs = 30.0,
            fStartHz = 1000.0,
            fEndHz = 3600.0
        )
        val downChirp = ChirpDsp.generateLinearChirpPcm16(
            sampleRate = sampleRate,
            durationMs = 30.0,
            fStartHz = 4600.0,
            fEndHz = 2000.0
        )

        val upFloat = ChirpDsp.pcm16ToNormalizedFloat(upChirp)
        val downFloat = ChirpDsp.pcm16ToNormalizedFloat(downChirp)

        val wiredScheduledSample = (sampleRate * 0.250).toInt()
        val btScheduledSample = (sampleRate * 0.800).toInt()

        val simulatedWiredLatencyMs = 16.0
        val simulatedBtLatencyMs = 203.5
        val micLatencyMs = 12.0

        val wiredArrivalSample = wiredScheduledSample + ((simulatedWiredLatencyMs + micLatencyMs) * 48.0).toInt()
        val btArrivalSample = btScheduledSample + ((simulatedBtLatencyMs + micLatencyMs) * 48.0).toInt()

        val micCapture = FloatArray(sampleRate * 2)
        for (i in upFloat.indices) {
            micCapture[wiredArrivalSample + i] += 0.70f * upFloat[i]
            micCapture[wiredArrivalSample + 240 + i] += 0.18f * upFloat[i]
        }
        for (i in downFloat.indices) {
            micCapture[btArrivalSample + i] -= 0.65f * downFloat[i]
        }

        val wiredEnv = ChirpDsp.analyticMatchedFilterEnvelope(micCapture, upFloat)
        val btEnv = ChirpDsp.analyticMatchedFilterEnvelope(micCapture, downFloat)

        val wPeak = ChirpDsp.findPeakWithSubsampleInterpolation(wiredEnv, 8_000, 30_000)
        val bPeak = ChirpDsp.findPeakWithSubsampleInterpolation(btEnv, 36_000, 80_000)

        assertTrue("Wired SNR should be high", wPeak.snrRatio > 8.0)
        assertTrue("BT SNR should be high even with inverted phase", bPeak.snrRatio > 8.0)

        val measuredDeltaMs =
            ChirpDsp.samplesToMillis(bPeak.fractionalIndex - wPeak.fractionalIndex, sampleRate) - 550.0

        assertTrue(
            "Expected ~187.5 ms delta, got $measuredDeltaMs ms",
            abs(measuredDeltaMs - 187.5) < 0.5
        )
    }

    @Test
    fun `PLL drift controller seeks on large jump, stays 1_0x inside jitter deadband, and gently trims moderate drift`() {
        val jump = ChirpDsp.computePllPlaybackSpeed(
            wiredPosMs = 10_000L,
            btPosMs = 10_000L,
            targetAdvanceMs = 220,
            durationMs = 240_000L
        )
        assertTrue(jump.shouldHardSeek)
        assertEquals(10_220L, jump.seekTargetMs)

        val moderateLag = ChirpDsp.computePllPlaybackSpeed(
            wiredPosMs = 10_000L,
            btPosMs = 10_140L,
            targetAdvanceMs = 185,
            durationMs = 240_000L
        )
        assertFalse(moderateLag.shouldHardSeek)
        assertEquals(1.02f, moderateLag.playbackSpeed, 1e-5f)

        val moderateAhead = ChirpDsp.computePllPlaybackSpeed(
            wiredPosMs = 10_000L,
            btPosMs = 10_230L,
            targetAdvanceMs = 185,
            durationMs = 240_000L
        )
        assertFalse(moderateAhead.shouldHardSeek)
        assertEquals(0.98f, moderateAhead.playbackSpeed, 1e-5f)

        val locked = ChirpDsp.computePllPlaybackSpeed(
            wiredPosMs = 10_000L,
            btPosMs = 10_170L,
            targetAdvanceMs = 185,
            durationMs = 240_000L
        )
        assertFalse(locked.shouldHardSeek)
        assertEquals(1.0f, locked.playbackSpeed, 1e-5f)
    }
}
