package com.vinyas.karaokeiem.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class OsProbeResult(
    val deltaMs: Int,
    val wiredLatencyMs: Int,
    val btLatencyMs: Int,
    val usedHardwareTimestamps: Boolean
)

sealed class ChirpCalibrationResult {
    data class Success(
        val deltaMs: Int,
        val wiredPeakMs: Double,
        val btPeakMs: Double,
        val wiredSnr: Double,
        val btSnr: Double
    ) : ChirpCalibrationResult()

    data class Failure(val reason: String) : ChirpCalibrationResult()
}

class LatencyAutoCalibrator(context: Context) {

    private val prefs = context.getSharedPreferences("karaoke_iem_bt_profiles", Context.MODE_PRIVATE)

    fun getSavedOffsetForDevice(btDeviceName: String?): Int? {
        if (btDeviceName.isNullOrBlank()) return null
        val key = profileKey(btDeviceName)
        return if (prefs.contains(key)) prefs.getInt(key, DEFAULT_BT_OFFSET_MS) else null
    }

    fun saveOffsetForDevice(btDeviceName: String?, offsetMs: Int) {
        if (btDeviceName.isNullOrBlank()) return
        prefs.edit().putInt(profileKey(btDeviceName), offsetMs.coerceIn(-250, 600)).apply()
    }

    private fun profileKey(name: String): String =
        "bt_offset_" + name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_")

    suspend fun probeOsLatencyDeltaMs(
        wiredDevice: AudioDeviceInfo?,
        btDevice: AudioDeviceInfo?
    ): OsProbeResult = withContext(Dispatchers.IO) {
        if (btDevice == null) {
            return@withContext OsProbeResult(0, 15, 15, false)
        }

        val wiredLat = measureSingleSinkTimestampLatencyMs(wiredDevice)
        val btLat = measureSingleSinkTimestampLatencyMs(btDevice)

        if (wiredLat != null && btLat != null && btLat > 25.0) {
            val delta = (btLat - wiredLat).toInt().coerceIn(0, 500)
            OsProbeResult(
                deltaMs = delta,
                wiredLatencyMs = wiredLat.toInt(),
                btLatencyMs = btLat.toInt(),
                usedHardwareTimestamps = true
            )
        } else {
            OsProbeResult(
                deltaMs = DEFAULT_BT_OFFSET_MS,
                wiredLatencyMs = wiredLat?.toInt() ?: 18,
                btLatencyMs = btLat?.toInt() ?: (18 + DEFAULT_BT_OFFSET_MS),
                usedHardwareTimestamps = false
            )
        }
    }

    private suspend fun measureSingleSinkTimestampLatencyMs(device: AudioDeviceInfo?): Double? {
        val sampleRate = SAMPLE_RATE_HZ
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return null
        }

        try {
            if (device != null) {
                track.setPreferredDevice(device)
            }
            val chunk = ShortArray(sampleRate / 20)
            var framesWritten = 0L
            track.play()

            val ts = AudioTimestamp()
            var measuredMs: Double? = null

            repeat(8) { step ->
                val written = track.write(chunk, 0, chunk.size)
                if (written > 0) {
                    framesWritten += written.toLong()
                }
                delay(40L)
                if (step >= 4 && track.getTimestamp(ts) && ts.framePosition > 0L) {
                    val nowNs = System.nanoTime()
                    val frameDelta = framesWritten - ts.framePosition
                    val frameTimeDeltaNs = (frameDelta * 1_000_000_000L) / sampleRate.toLong()
                    val presentationNs = ts.nanoTime + frameTimeDeltaNs
                    val latMs = (presentationNs - nowNs) / 1_000_000.0
                    if (latMs in 2.0..800.0) {
                        measuredMs = latMs
                    }
                }
            }
            return measuredMs
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun measureAcousticChirpDeltaMs(
        wiredDevice: AudioDeviceInfo?,
        btDevice: AudioDeviceInfo?
    ): ChirpCalibrationResult = withContext(Dispatchers.IO) {
        if (btDevice == null) {
            return@withContext ChirpCalibrationResult.Failure(
                "Connect your Bluetooth earphones first so both outputs can be acoustically calibrated."
            )
        }

        val sampleRate = SAMPLE_RATE_HZ
        val upChirp = ChirpDsp.generateLinearChirpPcm16(sampleRate, 30.0, 1000.0, 3600.0, 0.82)
        val downChirp = ChirpDsp.generateLinearChirpPcm16(sampleRate, 30.0, 4600.0, 2000.0, 0.82)

        val wiredStartSample = (sampleRate * 0.250).toInt()
        val btStartSample = (sampleRate * 0.800).toInt()
        val totalPlaySamples = (sampleRate * 1.55).toInt()
        val totalRecordSamples = (sampleRate * 1.85).toInt()

        val wiredStreamPcm = ShortArray(totalPlaySamples)
        upChirp.copyInto(wiredStreamPcm, destinationOffset = wiredStartSample)

        val btStreamPcm = ShortArray(totalPlaySamples)
        downChirp.copyInto(btStreamPcm, destinationOffset = btStartSample)

        val minRecBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecBuf * 4
            )
        } catch (e: Exception) {
            return@withContext ChirpCalibrationResult.Failure("Microphone init failed: ${e.message}")
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext ChirpCalibrationResult.Failure("Microphone could not be initialized.")
        }

        val wiredTrack = buildCalibrationTrack(sampleRate, wiredDevice)
        val btTrack = buildCalibrationTrack(sampleRate, btDevice)

        if (wiredTrack == null || btTrack == null) {
            recorder.release()
            wiredTrack?.release()
            btTrack?.release()
            return@withContext ChirpCalibrationResult.Failure("Could not open dual calibration audio tracks.")
        }

        val capturedPcm = ShortArray(totalRecordSamples)
        val wiredFirstWriteNs = AtomicLong(0L)
        val btFirstWriteNs = AtomicLong(0L)
        val startGate = CountDownLatch(1)
        val readyGate = CountDownLatch(2)

        try {
            recorder.startRecording()
            delay(60L)

            coroutineScope {
                val wiredJob = async(Dispatchers.IO) {
                    wiredTrack.play()
                    readyGate.countDown()
                    startGate.await(1, TimeUnit.SECONDS)
                    streamBufferWithTimestamp(wiredTrack, wiredStreamPcm, wiredFirstWriteNs)
                }

                val btJob = async(Dispatchers.IO) {
                    btTrack.play()
                    readyGate.countDown()
                    startGate.await(1, TimeUnit.SECONDS)
                    streamBufferWithTimestamp(btTrack, btStreamPcm, btFirstWriteNs)
                }

                val recJob = async(Dispatchers.IO) {
                    readyGate.await(1, TimeUnit.SECONDS)
                    startGate.countDown()
                    var offset = 0
                    while (offset < capturedPcm.size) {
                        val toRead = minOf(2048, capturedPcm.size - offset)
                        val read = recorder.read(capturedPcm, offset, toRead)
                        if (read <= 0) break
                        offset += read
                    }
                }

                wiredJob.await()
                btJob.await()
                recJob.await()
            }
        } catch (e: Exception) {
            return@withContext ChirpCalibrationResult.Failure("Acoustic capture interrupted: ${e.message}")
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { wiredTrack.stop() }
            runCatching { wiredTrack.release() }
            runCatching { btTrack.stop() }
            runCatching { btTrack.release() }
        }

        val recFloat = ChirpDsp.pcm16ToNormalizedFloat(capturedPcm)
        val upTemplate = ChirpDsp.pcm16ToNormalizedFloat(upChirp)
        val downTemplate = ChirpDsp.pcm16ToNormalizedFloat(downChirp)

        val wiredEnv = ChirpDsp.analyticMatchedFilterEnvelope(recFloat, upTemplate)
        val btEnv = ChirpDsp.analyticMatchedFilterEnvelope(recFloat, downTemplate)

        val wiredPeak = ChirpDsp.findPeakWithSubsampleInterpolation(
            envelope = wiredEnv,
            minIndex = (sampleRate * 0.18).toInt(),
            maxIndex = (sampleRate * 0.65).toInt()
        )
        val btPeak = ChirpDsp.findPeakWithSubsampleInterpolation(
            envelope = btEnv,
            minIndex = (sampleRate * 0.75).toInt(),
            maxIndex = minOf(btEnv.lastIndex, (sampleRate * 1.75).toInt())
        )

        if (wiredPeak.snrRatio < MIN_CHIRP_SNR || btPeak.snrRatio < MIN_CHIRP_SNR) {
            return@withContext ChirpCalibrationResult.Failure(
                "Chirp signal too quiet (Wired SNR ${"%.1f".format(wiredPeak.snrRatio)}, " +
                    "BT SNR ${"%.1f".format(btPeak.snrRatio)}). Raise volume above 65% and hold both earpieces right next to the phone mic."
            )
        }

        val wiredArrivalMs = ChirpDsp.samplesToMillis(wiredPeak.fractionalIndex, sampleRate)
        val btArrivalMs = ChirpDsp.samplesToMillis(btPeak.fractionalIndex, sampleRate)
        val scheduledGapMs = ChirpDsp.samplesToMillis((btStartSample - wiredStartSample).toDouble(), sampleRate)
        val triggerSkewMs = (btFirstWriteNs.get() - wiredFirstWriteNs.get()) / 1_000_000.0

        val measuredDeltaMs = (btArrivalMs - wiredArrivalMs) - scheduledGapMs - triggerSkewMs
        val clampedDelta = ChirpDsp.roundMillis(measuredDeltaMs).coerceIn(-150, 550)

        saveOffsetForDevice(btDevice.productName?.toString(), clampedDelta)

        ChirpCalibrationResult.Success(
            deltaMs = clampedDelta,
            wiredPeakMs = wiredArrivalMs,
            btPeakMs = btArrivalMs,
            wiredSnr = wiredPeak.snrRatio,
            btSnr = btPeak.snrRatio
        )
    }

    private fun buildCalibrationTrack(sampleRate: Int, device: AudioDeviceInfo?): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { track ->
                    if (device != null) {
                        track.setPreferredDevice(device)
                    }
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun streamBufferWithTimestamp(
        track: AudioTrack,
        pcm: ShortArray,
        firstWriteTimestampNs: AtomicLong
    ) {
        val chunkSize = 1920
        var offset = 0
        while (offset < pcm.size) {
            if (offset == 0) {
                firstWriteTimestampNs.set(System.nanoTime())
            }
            val count = minOf(chunkSize, pcm.size - offset)
            val written = track.write(pcm, offset, count)
            if (written <= 0) break
            offset += written
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_BT_OFFSET_MS = 180
        private const val MIN_CHIRP_SNR = 4.2
    }
}
