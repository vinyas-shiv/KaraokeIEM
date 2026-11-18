package com.vinyas.karaokeiem.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class ChirpPeakResult(
    val fractionalIndex: Double,
    val peakMagnitude: Double,
    val snrRatio: Double
)

data class PllDecision(
    val shouldRecueBoth: Boolean,
    val playbackSpeed: Float,
    val driftErrorMs: Long
)

object ChirpDsp {

    fun generateLinearChirpPcm16(
        sampleRate: Int,
        durationMs: Double,
        fStartHz: Double,
        fEndHz: Double,
        amplitude: Double = 0.78
    ): ShortArray {
        val numSamples = ((sampleRate * durationMs) / 1000.0).toInt().coerceAtLeast(16)
        val durationSec = numSamples.toDouble() / sampleRate.toDouble()
        val sweepRate = (fEndHz - fStartHz) / durationSec
        val out = ShortArray(numSamples)

        for (n in 0 until numSamples) {
            val t = n.toDouble() / sampleRate.toDouble()
            val hann = 0.5 * (1.0 - cos((2.0 * PI * n) / (numSamples - 1).coerceAtLeast(1)))
            val phase = 2.0 * PI * (fStartHz * t + 0.5 * sweepRate * t * t)
            val sample = amplitude * hann * sin(phase)
            out[n] = (sample * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun pcm16ToNormalizedFloat(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = pcm[i] / 32768.0f
        }
        return out
    }

    fun analyticMatchedFilterEnvelope(signal: FloatArray, template: FloatArray): FloatArray {
        val n = nextPowerOfTwo(signal.size + template.size)
        val sigRe = DoubleArray(n)
        val sigIm = DoubleArray(n)
        val tplRe = DoubleArray(n)
        val tplIm = DoubleArray(n)

        for (i in signal.indices) sigRe[i] = signal[i].toDouble()
        for (i in template.indices) tplRe[i] = template[i].toDouble()

        fftInPlace(sigRe, sigIm, invert = false)
        fftInPlace(tplRe, tplIm, invert = false)

        val prodRe = DoubleArray(n)
        val prodIm = DoubleArray(n)
        for (k in 0 until n) {
            val xr = sigRe[k]
            val xi = sigIm[k]
            val hr = tplRe[k]
            val hi = tplIm[k]
            prodRe[k] = xr * hr + xi * hi
            prodIm[k] = xi * hr - xr * hi
        }

        val half = n / 2
        for (k in 1 until half) {
            prodRe[k] *= 2.0
            prodIm[k] *= 2.0
        }
        for (k in (half + 1) until n) {
            prodRe[k] = 0.0
            prodIm[k] = 0.0
        }

        fftInPlace(prodRe, prodIm, invert = true)

        val out = FloatArray(signal.size)
        for (i in signal.indices) {
            out[i] = hypot(prodRe[i], prodIm[i]).toFloat()
        }
        return out
    }

    fun findPeakWithSubsampleInterpolation(
        envelope: FloatArray,
        minIndex: Int = 0,
        maxIndex: Int = envelope.lastIndex
    ): ChirpPeakResult {
        val start = minIndex.coerceIn(0, envelope.lastIndex)
        val end = maxIndex.coerceIn(start, envelope.lastIndex)

        var bestIdx = start
        var bestVal = -1.0
        var sumSq = 0.0
        var count = 0

        for (i in start..end) {
            val v = envelope[i].toDouble()
            if (v > bestVal) {
                bestVal = v
                bestIdx = i
            }
        }

        for (i in start..end) {
            if (abs(i - bestIdx) > 120) {
                val v = envelope[i].toDouble()
                sumSq += v * v
                count++
            }
        }
        val noiseRms = if (count > 0) sqrt(sumSq / count).coerceAtLeast(1e-9) else 1e-9
        val snr = bestVal / noiseRms

        var delta = 0.0
        if (bestIdx > start && bestIdx < end) {
            val yMinus = envelope[bestIdx - 1].toDouble()
            val yZero = envelope[bestIdx].toDouble()
            val yPlus = envelope[bestIdx + 1].toDouble()
            val denom = 2.0 * (2.0 * yZero - yMinus - yPlus)
            if (abs(denom) > 1e-12) {
                delta = ((yPlus - yMinus) / denom).coerceIn(-0.5, 0.5)
            }
        }

        return ChirpPeakResult(
            fractionalIndex = bestIdx + delta,
            peakMagnitude = bestVal,
            snrRatio = snr
        )
    }

    fun computeBaselineDriftSpeed(
        currentPosDeltaMs: Double,
        targetBaselineDeltaMs: Double,
        currentlyGliding: Boolean
    ): PllDecision {
        val errorMs = (targetBaselineDeltaMs - currentPosDeltaMs).roundToLong()
        val absErr = abs(errorMs)

        if (absErr > 450L) {
            return PllDecision(
                shouldRecueBoth = true,
                playbackSpeed = 1.0f,
                driftErrorMs = errorMs
            )
        }

        val stopThreshold = if (currentlyGliding) 8L else 35L
        if (absErr <= stopThreshold) {
            return PllDecision(
                shouldRecueBoth = false,
                playbackSpeed = 1.0f,
                driftErrorMs = errorMs
            )
        }

        val step = if (absErr <= 90L) 0.03f else 0.05f
        val speed = if (errorMs > 0L) 1.0f + step else 1.0f - step
        return PllDecision(
            shouldRecueBoth = false,
            playbackSpeed = speed,
            driftErrorMs = errorMs
        )
    }

    fun medianOfThree(a: Double, b: Double, c: Double): Double {
        return maxOf(minOf(a, b), minOf(maxOf(a, b), c))
    }

    fun nextPowerOfTwo(value: Int): Int {
        var n = 1
        while (n < value) n = n shl 1
        return n
    }

    private fun fftInPlace(re: DoubleArray, im: DoubleArray, invert: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val sign = if (invert) 1.0 else -1.0
            val ang = sign * 2.0 * PI / len.toDouble()
            val wLenRe = cos(ang)
            val wLenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                val halfLen = len shr 1
                for (k in 0 until halfLen) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + halfLen] * wRe - im[i + k + halfLen] * wIm
                    val vIm = re[i + k + halfLen] * wIm + im[i + k + halfLen] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + halfLen] = uRe - vRe
                    im[i + k + halfLen] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }

        if (invert) {
            val invN = 1.0 / n.toDouble()
            for (i in 0 until n) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }

    fun samplesToMillis(samples: Double, sampleRate: Int): Double {
        return (samples * 1000.0) / sampleRate.toDouble()
    }

    fun roundMillis(ms: Double): Int = ms.roundToLong().toInt()
}
