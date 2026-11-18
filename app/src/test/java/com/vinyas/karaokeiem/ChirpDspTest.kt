package com.vinyas.karaokeiem

import com.vinyas.karaokeiem.audio.ChirpDsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChirpDspTest {

    @Test
    fun chirpMatchedFilterRecoversSimulatedBluetoothLagWithinHalfMs() {
        val sampleRate = 48_000
        val upChirp = ChirpDsp.generateLinearChirpPcm16(sampleRate, 30.0, 1000.0, 3600.0)
        val downChirp = ChirpDsp.generateLinearChirpPcm16(sampleRate, 30.0, 4600.0, 2000.0)

        val upFloat = ChirpDsp.pcm16ToNormalizedFloat(upChirp)
        val downFloat = ChirpDsp.pcm16ToNormalizedFloat(downChirp)

        val wiredScheduledSample = (sampleRate * 0.250).toInt()
        val btScheduledSample = (sampleRate * 0.800).toInt()

        val wiredArrival = wiredScheduledSample + ((16.0 + 12.0) * 48.0).toInt()
        val btArrival = btScheduledSample + ((203.5 + 12.0) * 48.0).toInt()

        val micCapture = FloatArray(sampleRate * 2)
        for (i in upFloat.indices) {
            micCapture[wiredArrival + i] += 0.70f * upFloat[i]
            micCapture[wiredArrival + 240 + i] += 0.18f * upFloat[i]
        }
        for (i in downFloat.indices) {
            micCapture[btArrival + i] -= 0.65f * downFloat[i]
        }

        val wiredEnv = ChirpDsp.analyticMatchedFilterEnvelope(micCapture, upFloat)
        val btEnv = ChirpDsp.analyticMatchedFilterEnvelope(micCapture, downFloat)

        val wPeak = ChirpDsp.findPeakWithSubsampleInterpolation(wiredEnv, 8_000, 30_000)
        val bPeak = ChirpDsp.findPeakWithSubsampleInterpolation(btEnv, 36_000, 80_000)

        assertTrue(wPeak.snrRatio > 8.0)
        assertTrue(bPeak.snrRatio > 8.0)

        val measuredDeltaMs =
            ChirpDsp.samplesToMillis(bPeak.fractionalIndex - wPeak.fractionalIndex, sampleRate) - 550.0
        assertTrue(abs(measuredDeltaMs - 187.5) < 0.5)
    }

    @Test
    fun pllDriftControllerSeeksOnLargeJumpAndTrimsSmoothlyOnSmallDrift() {
        val jump = ChirpDsp.computePllPlaybackSpeed(10_000L, 10_000L, 185, 240_000L)
        assertTrue(jump.shouldHardSeek)
        assertEquals(10_185L, jump.seekTargetMs)

        val slightLag = ChirpDsp.computePllPlaybackSpeed(10_000L, 10_165L, 185, 240_000L)
        assertFalse(slightLag.shouldHardSeek)
        assertTrue(slightLag.playbackSpeed > 1.0f && slightLag.playbackSpeed <= 1.045f)

        val locked = ChirpDsp.computePllPlaybackSpeed(10_000L, 10_184L, 185, 240_000L)
        assertFalse(locked.shouldHardSeek)
        assertEquals(1.0f, locked.playbackSpeed, 1e-5f)
    }
}
