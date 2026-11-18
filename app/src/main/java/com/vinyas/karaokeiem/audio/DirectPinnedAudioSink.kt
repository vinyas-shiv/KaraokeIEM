@file:OptIn(UnstableApi::class)

package com.vinyas.karaokeiem.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class DirectPinnedAudioSink(
    private val label: String
) : TeeAudioProcessor.AudioBufferSink {

    private val lock = Any()
    private var audioTrack: AudioTrack? = null

    private var sampleRateHz: Int = 48_000
    private var inputChannelCount: Int = 2
    private var outputChannelCount: Int = 2
    private var pcmEncoding: Int = C.ENCODING_PCM_16BIT

    @Volatile
    private var desiredUsage: Int = AudioAttributes.USAGE_MEDIA

    @Volatile
    private var desiredContentType: Int = AudioAttributes.CONTENT_TYPE_MUSIC

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    @Volatile
    private var gain: Float = 1.0f

    @Volatile
    private var muted: Boolean = false

    @Volatile
    private var isPlayingState: Boolean = false

    @Volatile
    private var desiredSpeed: Float = 1.0f

    private var appliedUsage: Int = -1
    private var appliedSpeed: Float = 1.0f
    private var scratchBytes = ByteArray(16_384)
    private var stereoDownmixBytes = ByteArray(16_384)

    fun configureRoute(
        usage: Int,
        contentType: Int,
        device: AudioDeviceInfo?
    ) {
        synchronized(lock) {
            val usageChanged = (usage != desiredUsage || contentType != desiredContentType)
            desiredUsage = usage
            desiredContentType = contentType
            preferredDevice = device

            val track = audioTrack
            if (track != null) {
                if (usageChanged && sampleRateHz > 0) {
                    rebuildTrackLocked()
                } else {
                    runCatching { track.setPreferredDevice(device) }
                }
            }
        }
    }

    fun setVolume(volume: Float, isMuted: Boolean = muted) {
        gain = volume.coerceIn(0f, 1f)
        muted = isMuted
        synchronized(lock) {
            val effective = if (muted) 0f else gain
            runCatching { audioTrack?.setVolume(effective) }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.85f, 1.15f)
        desiredSpeed = clamped
        synchronized(lock) {
            val track = audioTrack ?: return
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING && abs(clamped - appliedSpeed) > 0.002f) {
                runCatching {
                    track.playbackParams = PlaybackParams()
                        .allowDefaults()
                        .setSpeed(clamped)
                        .setPitch(1.0f)
                    appliedSpeed = clamped
                }
            }
        }
    }

    fun onPlay() {
        isPlayingState = true
        synchronized(lock) {
            val track = audioTrack ?: return
            runCatching {
                if (preferredDevice != null) {
                    track.setPreferredDevice(preferredDevice)
                }
                track.play()
                if (abs(desiredSpeed - 1.0f) > 0.002f) {
                    track.playbackParams = PlaybackParams()
                        .allowDefaults()
                        .setSpeed(desiredSpeed)
                        .setPitch(1.0f)
                    appliedSpeed = desiredSpeed
                }
            }
        }
    }

    fun onPause() {
        isPlayingState = false
        synchronized(lock) {
            runCatching {
                audioTrack?.pause()
                audioTrack?.flush()
            }
        }
    }

    fun onSeekFlush() {
        synchronized(lock) {
            val track = audioTrack ?: return
            runCatching {
                val wasPlaying = (track.playState == AudioTrack.PLAYSTATE_PLAYING)
                track.pause()
                track.flush()
                if (wasPlaying || isPlayingState) {
                    track.play()
                }
            }
        }
    }

    fun queryActualRoutedDevice(): AudioDeviceInfo? {
        synchronized(lock) {
            val track = audioTrack ?: return null
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return null
            return runCatching { track.routedDevice }.getOrNull()
        }
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        if (sampleRateHz <= 0 || channelCount <= 0) return
        synchronized(lock) {
            val outCh = if (channelCount == 1) 1 else 2
            val needsRebuild = audioTrack == null ||
                this.sampleRateHz != sampleRateHz ||
                this.outputChannelCount != outCh ||
                this.pcmEncoding != encoding ||
                this.appliedUsage != desiredUsage

            this.sampleRateHz = sampleRateHz
            this.inputChannelCount = channelCount
            this.outputChannelCount = outCh
            this.pcmEncoding = encoding

            if (needsRebuild) {
                rebuildTrackLocked()
            } else {
                runCatching {
                    val track = audioTrack
                    if (track != null) {
                        val wasPlaying = (track.playState == AudioTrack.PLAYSTATE_PLAYING)
                        track.pause()
                        track.flush()
                        track.setPreferredDevice(preferredDevice)
                        if (wasPlaying || isPlayingState) {
                            track.play()
                        }
                    }
                }
            }
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val remaining = buffer.remaining()
        if (remaining <= 0) return

        synchronized(lock) {
            if (audioTrack == null && sampleRateHz > 0) {
                rebuildTrackLocked()
            }
            val track = audioTrack ?: return

            if (scratchBytes.size < remaining) {
                scratchBytes = ByteArray(remaining * 2)
            }
            val pos = buffer.position()
            buffer.get(scratchBytes, 0, remaining)
            buffer.position(pos)

            if (track.playState != AudioTrack.PLAYSTATE_PLAYING && isPlayingState) {
                runCatching {
                    if (preferredDevice != null) {
                        track.setPreferredDevice(preferredDevice)
                    }
                    track.play()
                }
            }

            if (inputChannelCount <= 2 || pcmEncoding != C.ENCODING_PCM_16BIT) {
                runCatching {
                    track.write(scratchBytes, 0, remaining, AudioTrack.WRITE_NON_BLOCKING)
                }
            } else {
                val bytesPerFrameIn = inputChannelCount * 2
                val frames = remaining / bytesPerFrameIn
                val outBytes = frames * 4
                if (stereoDownmixBytes.size < outBytes) {
                    stereoDownmixBytes = ByteArray(outBytes * 2)
                }
                val inShortBuf = ByteBuffer.wrap(scratchBytes, 0, remaining)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                val outShortBuf = ByteBuffer.wrap(stereoDownmixBytes, 0, outBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()

                for (f in 0 until frames) {
                    val base = f * inputChannelCount
                    val left = inShortBuf.get(base).toInt()
                    val right = inShortBuf.get(base + 1).toInt()
                    val center = if (inputChannelCount >= 3) (inShortBuf.get(base + 2).toInt() * 7) / 10 else 0
                    val lMix = (left + center).coerceIn(-32768, 32767).toShort()
                    val rMix = (right + center).coerceIn(-32768, 32767).toShort()
                    outShortBuf.put(f * 2, lMix)
                    outShortBuf.put(f * 2 + 1, rMix)
                }
                runCatching {
                    track.write(stereoDownmixBytes, 0, outBytes, AudioTrack.WRITE_NON_BLOCKING)
                }
            }
        }
    }

    private fun rebuildTrackLocked() {
        releaseTrackLocked()
        val channelMask = if (outputChannelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val androidEncoding = when (pcmEncoding) {
            C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBuf = AudioTrack.getMinBufferSize(sampleRateHz, channelMask, androidEncoding)
            .coerceAtLeast(8192)
        val bytesPerSec = sampleRateHz * outputChannelCount * (if (androidEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2)
        val targetBuf = maxOf(minBuf * 2, (bytesPerSec * 28) / 100)

        val built = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(desiredUsage)
                        .setContentType(desiredContentType)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(channelMask)
                        .setEncoding(androidEncoding)
                        .build()
                )
                .setBufferSizeInBytes(targetBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            null
        } ?: return

        appliedUsage = desiredUsage
        appliedSpeed = 1.0f
        val effectiveVol = if (muted) 0f else gain
        runCatching { built.setVolume(effectiveVol) }
        if (preferredDevice != null) {
            runCatching { built.setPreferredDevice(preferredDevice) }
        }
        if (isPlayingState) {
            runCatching { built.play() }
        }
        audioTrack = built
    }

    private fun releaseTrackLocked() {
        val old = audioTrack ?: return
        audioTrack = null
        runCatching { old.pause() }
        runCatching { old.flush() }
        runCatching { old.release() }
    }

    fun release() {
        isPlayingState = false
        synchronized(lock) {
            releaseTrackLocked()
        }
    }
}
