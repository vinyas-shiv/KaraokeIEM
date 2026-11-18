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
    fun `baseline drift controller cancels OS latency offset and glides smoothly without chopping`() {
        val steady = ChirpDsp.computeBaselineDriftSpeed(
            currentPosDeltaMs = 30.0,
            targetBaselineDeltaMs = 45.0,
            currentlyGliding = false
        )
        assertFalse(steady.shouldRecueBoth)
        assertEquals(1.0f, steady.playbackSpeed, 1e-5f)

        val liveNudge = ChirpDsp.computeBaselineDriftSpeed(
            currentPosDeltaMs = 45.0,
            targetBaselineDeltaMs = 95.0,
            currentlyGliding = false
        )
        assertFalse(liveNudge.shouldRecueBoth)
        assertEquals(1.03f, liveNudge.playbackSpeed, 1e-5f)

        val settled = ChirpDsp.computeBaselineDriftSpeed(
            currentPosDeltaMs = 89.0,
            targetBaselineDeltaMs = 95.0,
            currentlyGliding = true
        )
        assertFalse(settled.shouldRecueBoth)
        assertEquals(1.0f, settled.playbackSpeed, 1e-5f)

        val disconnectJump = ChirpDsp.computeBaselineDriftSpeed(
            currentPosDeltaMs = -500.0,
            targetBaselineDeltaMs = 45.0,
            currentlyGliding = false
        )
        assertTrue(disconnectJump.shouldRecueBoth)
        assertEquals(60.0, ChirpDsp.medianOfThree(120.0, 40.0, 60.0), 1e-6)
    }
}
