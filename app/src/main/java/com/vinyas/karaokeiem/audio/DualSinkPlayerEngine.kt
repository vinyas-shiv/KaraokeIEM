@file:OptIn(UnstableApi::class)

package com.vinyas.karaokeiem.audio

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class SplitRoutingMode(val label: String) {
    STEREO_HIFI_A2DP("Stereo Hi-Fi (A2DP + Aux)"),
    BT_COMM_SCO_BLE("BT Voice IEM (Force Aux+BT)")
}

data class AudioTrackOption(
    val key: String,
    val audioGroupOrdinal: Int,
    val trackIndex: Int,
    val displayTitle: String,
    val details: String,
    val looksInstrumental: Boolean,
    val looksVocalGuide: Boolean
)

data class ActiveSinkConfig(
    val wiredUsage: Int,
    val wiredDevice: AudioDeviceInfo?,
    val btUsage: Int,
    val btDevice: AudioDeviceInfo?,
    val usingCommMode: Boolean
)

private data class AppliedRouteSignature(
    val wiredUsage: Int,
    val wiredDeviceId: Int,
    val btUsage: Int,
    val btDeviceId: Int,
    val usingCommMode: Boolean
)

data class EngineTelemetry(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val btAdvanceMs: Int = LatencyAutoCalibrator.DEFAULT_BT_OFFSET_MS,
    val syncErrorMs: Long = 0L,
    val isPhaseLocked: Boolean = false,
    val wiredVolume: Float = 1.0f,
    val btVolume: Float = 0.85f,
    val isBtGuideMuted: Boolean = false,
    val splitMode: SplitRoutingMode = SplitRoutingMode.STEREO_HIFI_A2DP,
    val activeSplitSummary: String = "Stereo Hi-Fi Split ready",
    val wiredActualRoute: String = "Idle",
    val btActualRoute: String = "Idle",
    val isSplitVerified: Boolean = false,
    val needsSystemMediaSwitchHint: Boolean = false,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val selectedWiredTrackKey: String? = null,
    val selectedBtTrackKey: String? = null
)

private class BtLinkKeepAlive {
    private var track: AudioTrack? = null
    private var boundDeviceId: Int = -1

    fun ensureActive(btA2dpDevice: AudioDeviceInfo?) {
        if (btA2dpDevice == null) {
            stop()
            return
        }
        if (track != null && boundDeviceId == btA2dpDevice.id) {
            return
        }
        stop()
        runCatching {
            val sampleRate = 48_000
            val frameCount = 4_800
            val pcm = ShortArray(frameCount * 2)
            for (i in pcm.indices step 2) {
                val dither: Short = if ((i and 2) == 0) 1 else -1
                pcm[i] = dither
                pcm[i + 1] = dither
            }
            val built = AudioTrack.Builder()
                .setAudioAttributes(
                    PlatformAudioAttributes.Builder()
                        .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            built.setPreferredDevice(btA2dpDevice)
            built.setVolume(0.002f)
            built.write(pcm, 0, pcm.size)
            built.setLoopPoints(0, frameCount, -1)
            built.play()
            track = built
            boundDeviceId = btA2dpDevice.id
        }
    }

    fun stop() {
        boundDeviceId = -1
        val old = track
        track = null
        if (old != null) {
            runCatching { old.stop() }
            runCatching { old.release() }
        }
    }
}

class DualSinkPlayerEngine(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val wiredTrackSelector = DefaultTrackSelector(appContext)
    private val btTrackSelector = DefaultTrackSelector(appContext)

    private val btAudioOnlyRenderersFactory: RenderersFactory = RenderersFactory {
        handler, videoListener, audioListener, textOutput, metadataOutput ->
        DefaultRenderersFactory(appContext)
            .createRenderers(handler, videoListener, audioListener, textOutput, metadataOutput)
            .filter { it.trackType == C.TRACK_TYPE_AUDIO }
            .toTypedArray()
    }

    val wiredPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setTrackSelector(wiredTrackSelector)
        .setLoadControl(buildSmoothLoadControl())
        .build()

    val btPlayer: ExoPlayer = ExoPlayer.Builder(appContext, btAudioOnlyRenderersFactory)
        .setTrackSelector(btTrackSelector)
        .setLoadControl(buildSmoothLoadControl())
        .build()

    private val btKeepAlive = BtLinkKeepAlive()

    private val _telemetry = MutableStateFlow(EngineTelemetry())
    val telemetry: StateFlow<EngineTelemetry> = _telemetry.asStateFlow()

    private var syncLoopJob: Job? = null
    private var startCueJob: Job? = null
    private var routeProbeJob: Job? = null
    private var tracksAutoAssignedForCurrentMedia = false

    private var currentWiredSink: OutputSinkItem? = null
    private var currentBtSink: OutputSinkItem? = null

    private var lastAppliedSignature: AppliedRouteSignature? = null
    private var lastWiredMedia3Attrs: AudioAttributes? = null
    private var lastBtMedia3Attrs: AudioAttributes? = null
    private var commModeEngagedAtMs: Long = 0L

    private var isCueingStart = false
    private var baselineLocked = false
    private var baselineSamples = DoubleArray(3)
    private var baselineSampleCount = 0
    private var baselinePosDeltaMs = 0.0
    private var pendingManualShiftMs = 0.0
    private var syncSettleUntilMs = 0L
    private var currentAppliedBtSpeed = 1.0f

    private val initialAudioMode = audioManager.mode
    private val initialAlarmVolume = runCatching {
        audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
    }.getOrDefault(-1)
    private var raisedAlarmVolume = false

    private val audioFocusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    scope.launch(Dispatchers.Main) { pause() }
                }
            }
            .build()

    init {
        val initialMediaAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        wiredPlayer.setAudioAttributes(initialMediaAttrs, false)
        btPlayer.setAudioAttributes(initialMediaAttrs, false)
        lastWiredMedia3Attrs = initialMediaAttrs
        lastBtMedia3Attrs = initialMediaAttrs

        wiredPlayer.volume = _telemetry.value.wiredVolume
        btPlayer.volume = if (_telemetry.value.isBtGuideMuted) 0f else _telemetry.value.btVolume

        btPlayer.trackSelectionParameters = btPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        wiredPlayer.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (!tracksAutoAssignedForCurrentMedia) {
                    val discovered = parseAudioTracks(tracks)
                    if (discovered.isNotEmpty()) {
                        tracksAutoAssignedForCurrentMedia = true
                        autoAssignKaraokeTracks(discovered)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isCueingStart) {
                    _telemetry.value = _telemetry.value.copy(isPlaying = isPlaying)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    isCueingStart = false
                    btPlayer.pause()
                    resetBtSpeedToUnity()
                    _telemetry.value = _telemetry.value.copy(isPlaying = false, isPhaseLocked = false)
                }
                updateDurationAndPosition()
            }
        })

        startBaselineSyncLoop()
    }

    fun suspendKeepAliveForCalibration() {
        btKeepAlive.stop()
    }

    fun resumeKeepAliveAfterCalibration() {
        val cfg = currentActiveSinkConfig()
        if (!cfg.usingCommMode && currentBtSink != null) {
            btKeepAlive.ensureActive(cfg.btDevice)
        }
    }

    fun setSplitMode(mode: SplitRoutingMode) {
        if (_telemetry.value.splitMode == mode) return
        val wasPlaying = wiredPlayer.isPlaying || isCueingStart
        if (wasPlaying) pause()
        _telemetry.value = _telemetry.value.copy(
            splitMode = mode,
            needsSystemMediaSwitchHint = false
        )
        applyHardwareRoutingPolicy(force = true)
        if (wasPlaying) play()
    }

    fun setRoutedDevices(wiredSink: OutputSinkItem?, btSink: OutputSinkItem?) {
        currentWiredSink = wiredSink
        currentBtSink = btSink
        applyHardwareRoutingPolicy(force = false)
    }

    fun recheckRoutesAsync() {
        if (!wiredPlayer.isPlaying && !isCueingStart) {
            scheduleOneShotRouteVerification(currentActiveSinkConfig())
        }
    }

    fun currentActiveSinkConfig(): ActiveSinkConfig {
        val wiredItem = currentWiredSink
        val btItem = currentBtSink
        val useCommForBt = (_telemetry.value.splitMode == SplitRoutingMode.BT_COMM_SCO_BLE) && btItem != null

        return if (useCommForBt) {
            val btDev = resolveMatchingCommDevice(btItem) ?: btItem?.commDeviceInfo ?: btItem?.deviceInfo
            val wiredUsage = if (wiredItem?.deviceInfo?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                PlatformAudioAttributes.USAGE_ALARM
            } else {
                PlatformAudioAttributes.USAGE_MEDIA
            }
            ActiveSinkConfig(
                wiredUsage = wiredUsage,
                wiredDevice = wiredItem?.deviceInfo,
                btUsage = PlatformAudioAttributes.USAGE_VOICE_COMMUNICATION,
                btDevice = btDev,
                usingCommMode = true
            )
        } else {
            val btDev = btItem?.a2dpDeviceInfo ?: btItem?.deviceInfo
            val wiredUsage = if (btItem != null) {
                PlatformAudioAttributes.USAGE_ALARM
            } else {
                PlatformAudioAttributes.USAGE_MEDIA
            }
            ActiveSinkConfig(
                wiredUsage = wiredUsage,
                wiredDevice = wiredItem?.deviceInfo,
                btUsage = PlatformAudioAttributes.USAGE_MEDIA,
                btDevice = btDev,
                usingCommMode = false
            )
        }
    }

    fun applyHardwareRoutingPolicy(force: Boolean = false) {
        val cfg = currentActiveSinkConfig()
        val sig = AppliedRouteSignature(
            wiredUsage = cfg.wiredUsage,
            wiredDeviceId = cfg.wiredDevice?.id ?: -1,
            btUsage = cfg.btUsage,
            btDeviceId = cfg.btDevice?.id ?: -1,
            usingCommMode = cfg.usingCommMode
        )

        if (!force && sig == lastAppliedSignature) {
            return
        }
        lastAppliedSignature = sig

        if (cfg.usingCommMode && cfg.btDevice != null) {
            btKeepAlive.stop()
            engageBluetoothCommunicationRoute(cfg.btDevice)
            ensureStreamAudible(AudioManager.STREAM_VOICE_CALL, minFraction = 0.70f)
        } else {
            disengageBluetoothCommunicationRoute()
            if (currentBtSink != null) {
                btKeepAlive.ensureActive(cfg.btDevice)
            } else {
                btKeepAlive.stop()
            }
        }

        if (cfg.wiredUsage == PlatformAudioAttributes.USAGE_ALARM) {
            ensureAlarmStreamAudible()
        }

        val nextWiredAttrs = toMedia3AudioAttributes(cfg.wiredUsage, isSpeech = false)
        if (nextWiredAttrs != lastWiredMedia3Attrs) {
            wiredPlayer.setAudioAttributes(nextWiredAttrs, false)
            lastWiredMedia3Attrs = nextWiredAttrs
        }
        wiredPlayer.setPreferredAudioDevice(cfg.wiredDevice)

        val nextBtAttrs = toMedia3AudioAttributes(cfg.btUsage, isSpeech = cfg.usingCommMode)
        if (nextBtAttrs != lastBtMedia3Attrs) {
            btPlayer.setAudioAttributes(nextBtAttrs, false)
            lastBtMedia3Attrs = nextBtAttrs
        }
        btPlayer.setPreferredAudioDevice(cfg.btDevice)

        resetBaselineTracking()

        val summary = when {
            currentBtSink == null ->
                "Single output active (connect Bluetooth earphones for split IEM)"
            cfg.usingCommMode ->
                "Verified BT Voice Split: ${AudioDeviceRouter.describeDevice(cfg.wiredDevice)} + ${AudioDeviceRouter.describeDevice(cfg.btDevice)}"
            else ->
                "Stereo Hi-Fi Split: ${AudioDeviceRouter.describeDevice(cfg.wiredDevice)} + ${AudioDeviceRouter.describeDevice(cfg.btDevice)}"
        }

        _telemetry.value = _telemetry.value.copy(
            activeSplitSummary = summary,
            wiredActualRoute = AudioDeviceRouter.describeDevice(cfg.wiredDevice),
            btActualRoute = if (currentBtSink != null) {
                AudioDeviceRouter.describeDevice(cfg.btDevice)
            } else {
                "Disconnected"
            },
            isSplitVerified = cfg.usingCommMode && cfg.btDevice != null && cfg.wiredDevice != null,
            needsSystemMediaSwitchHint = false
        )

        scheduleOneShotRouteVerification(cfg)
    }

    private fun scheduleOneShotRouteVerification(cfg: ActiveSinkConfig) {
        routeProbeJob?.cancel()
        if (currentBtSink == null || cfg.usingCommMode) {
            return
        }

        routeProbeJob = scope.launch(Dispatchers.IO) {
            delay(200L)
            val wRouted = probeSingleOutputRoute(cfg.wiredUsage, cfg.wiredDevice)
            val bRouted = probeSingleOutputRoute(cfg.btUsage, cfg.btDevice)

            val wActual = wRouted ?: cfg.wiredDevice
            val bActual = bRouted ?: cfg.btDevice

            val wIsBt = wActual != null && AudioDeviceRouter.isBluetoothDeviceType(wActual.type)
            val bIsBt = bActual != null && AudioDeviceRouter.isBluetoothDeviceType(bActual.type)
            val splitVerified = bIsBt && !wIsBt && wActual != null
            val needMediaHint = bRouted != null && !bIsBt

            withContext(Dispatchers.Main) {
                if (_telemetry.value.splitMode == SplitRoutingMode.STEREO_HIFI_A2DP) {
                    _telemetry.value = _telemetry.value.copy(
                        wiredActualRoute = AudioDeviceRouter.describeDevice(wActual),
                        btActualRoute = AudioDeviceRouter.describeDevice(bActual),
                        isSplitVerified = splitVerified,
                        needsSystemMediaSwitchHint = needMediaHint,
                        activeSplitSummary = when {
                            splitVerified ->
                                "Verified 48 kHz Stereo Split: ${AudioDeviceRouter.describeDevice(wActual)} + ${AudioDeviceRouter.describeDevice(bActual)}"
                            needMediaHint ->
                                "Phone routed Media to ${AudioDeviceRouter.describeDevice(bActual)} — tap 'Select BT in Media Output' or 'Use BT Voice IEM'"
                            else -> _telemetry.value.activeSplitSummary
                        }
                    )
                }
            }
        }
    }

    private fun probeSingleOutputRoute(usage: Int, preferredDevice: AudioDeviceInfo?): AudioDeviceInfo? {
        val sampleRate = 48_000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    PlatformAudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return null
        }

        return try {
            track.setVolume(0f)
            if (preferredDevice != null) {
                track.setPreferredDevice(preferredDevice)
            }
            val silence = ShortArray(960)
            track.play()
            track.write(silence, 0, silence.size)
            Thread.sleep(30L)
            track.routedDevice
        } catch (_: Exception) {
            null
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun toMedia3AudioAttributes(platformUsage: Int, isSpeech: Boolean): AudioAttributes {
        val media3Usage = when (platformUsage) {
            PlatformAudioAttributes.USAGE_ALARM -> C.USAGE_ALARM
            PlatformAudioAttributes.USAGE_VOICE_COMMUNICATION -> C.USAGE_VOICE_COMMUNICATION
            else -> C.USAGE_MEDIA
        }
        val media3Content = if (isSpeech) {
            C.AUDIO_CONTENT_TYPE_SPEECH
        } else {
            C.AUDIO_CONTENT_TYPE_MUSIC
        }
        return AudioAttributes.Builder()
            .setUsage(media3Usage)
            .setContentType(media3Content)
            .build()
    }

    private fun resolveMatchingCommDevice(btItem: OutputSinkItem?): AudioDeviceInfo? {
        if (btItem == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commCandidates = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
            btItem.commDeviceInfo?.let { c ->
                commCandidates.firstOrNull { it.id == c.id }?.let { return it }
            }
            commCandidates.firstOrNull {
                it.productName?.toString() == btItem.name && AudioDeviceRouter.isBluetoothDeviceType(it.type)
            }?.let { return it }
            commCandidates.firstOrNull { it.type == 26 }?.let { return it }
            commCandidates.firstOrNull { AudioDeviceRouter.isBluetoothDeviceType(it.type) }?.let { return it }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    @Suppress("DEPRECATION")
    private fun engageBluetoothCommunicationRoute(btCommDevice: AudioDeviceInfo) {
        runCatching {
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                commModeEngagedAtMs = SystemClock.elapsedRealtime()
            }
            audioManager.isSpeakerphoneOn = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commCandidates = audioManager.availableCommunicationDevices
                val matched = commCandidates.firstOrNull { it.id == btCommDevice.id }
                    ?: commCandidates.firstOrNull {
                        it.productName?.toString() == btCommDevice.productName?.toString() &&
                            AudioDeviceRouter.isBluetoothDeviceType(it.type)
                    }
                    ?: commCandidates.firstOrNull { it.type == 26 }
                    ?: commCandidates.firstOrNull { AudioDeviceRouter.isBluetoothDeviceType(it.type) }

                val ok = if (matched != null) audioManager.setCommunicationDevice(matched) else false
                if (!ok) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            } else {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun disengageBluetoothCommunicationRoute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    private fun ensureStreamAudible(streamType: Int, minFraction: Float) {
        runCatching {
            val max = audioManager.getStreamMaxVolume(streamType)
            val cur = audioManager.getStreamVolume(streamType)
            val target = (max * minFraction).toInt().coerceAtLeast(1)
            if (max > 0 && cur < target) {
                audioManager.setStreamVolume(streamType, target, 0)
            }
        }
    }

    private fun ensureAlarmStreamAudible() {
        runCatching {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val minTarget = (max * 0.75f).toInt().coerceAtLeast(1)
            if (max > 0 && cur < minTarget) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, minTarget, 0)
                raisedAlarmVolume = true
            }
        }
    }

    fun loadMkvUri(uri: Uri) {
        startCueJob?.cancel()
        isCueingStart = false
        tracksAutoAssignedForCurrentMedia = false
        val item = MediaItem.fromUri(uri)

        wiredPlayer.stop()
        btPlayer.stop()
        resetBaselineTracking()

        wiredPlayer.setMediaItem(item)
        btPlayer.setMediaItem(item)

        wiredPlayer.prepare()
        btPlayer.prepare()

        _telemetry.value = _telemetry.value.copy(
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            audioTracks = emptyList(),
            selectedWiredTrackKey = null,
            selectedBtTrackKey = null,
            isPhaseLocked = false,
            syncErrorMs = 0L
        )
    }

    fun play() {
        startSynchronizedPlayback(wiredPlayer.currentPosition.coerceAtLeast(0L))
    }

    fun pause() {
        startCueJob?.cancel()
        isCueingStart = false
        wiredPlayer.pause()
        btPlayer.pause()
        resetBaselineTracking()
        _telemetry.value = _telemetry.value.copy(isPlaying = false, isPhaseLocked = false, syncErrorMs = 0L)
    }

    fun togglePlayPause() {
        if (wiredPlayer.isPlaying || isCueingStart) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, dur)
        _telemetry.value = _telemetry.value.copy(positionMs = clamped)
        if (wiredPlayer.isPlaying || isCueingStart) {
            startSynchronizedPlayback(clamped)
        } else {
            resetBaselineTracking()
            val (wTarget, bTarget) = computeTargetPair(clamped, _telemetry.value.btAdvanceMs, dur)
            wiredPlayer.seekTo(wTarget)
            if (currentBtSink != null) {
                btPlayer.seekTo(bTarget)
            }
        }
    }

    fun nudgePosition(deltaMs: Long) {
        seekTo(wiredPlayer.currentPosition + deltaMs)
    }

    fun setBtAdvanceMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-300, 1000)
        val prev = _telemetry.value.btAdvanceMs
        val delta = clamped - prev
        if (delta == 0) return

        _telemetry.value = _telemetry.value.copy(btAdvanceMs = clamped)

        if (wiredPlayer.isPlaying || isCueingStart) {
            if (currentBtSink == null) return
            if (abs(delta) > 140 || isCueingStart) {
                startSynchronizedPlayback(wiredPlayer.currentPosition.coerceAtLeast(0L))
            } else {
                if (baselineLocked) {
                    baselinePosDeltaMs += delta.toDouble()
                } else {
                    pendingManualShiftMs += delta.toDouble()
                }
            }
        } else if (currentBtSink != null && wiredPlayer.playbackState == Player.STATE_READY) {
            val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val (_, bTarget) = computeTargetPair(wiredPlayer.currentPosition, clamped, dur)
            btPlayer.seekTo(bTarget)
        }
    }

    private fun computeTargetPair(wiredRequestMs: Long, btAdvanceMs: Int, durationMs: Long): Pair<Long, Long> {
        val wClamped = wiredRequestMs.coerceIn(0L, durationMs)
        val rawBt = wClamped + btAdvanceMs.toLong()
        return if (rawBt < 0L) {
            val shiftedWired = (wClamped - rawBt).coerceIn(0L, durationMs)
            shiftedWired to 0L
        } else {
            wClamped to rawBt.coerceIn(0L, durationMs)
        }
    }

    private fun startSynchronizedPlayback(requestedWiredPosMs: Long) {
        routeProbeJob?.cancel()
        startCueJob?.cancel()
        runCatching { audioManager.requestAudioFocus(audioFocusRequest) }
        applyHardwareRoutingPolicy(force = false)

        isCueingStart = true
        _telemetry.value = _telemetry.value.copy(isPlaying = true, isPhaseLocked = false)

        startCueJob = scope.launch(Dispatchers.Main) {
            wiredPlayer.playWhenReady = false
            btPlayer.playWhenReady = false
            resetBaselineTracking()

            val cfg = currentActiveSinkConfig()
            if (cfg.usingCommMode) {
                val sinceComm = SystemClock.elapsedRealtime() - commModeEngagedAtMs
                if (sinceComm in 0L..650L) {
                    delay(650L - sinceComm)
                }
            } else if (currentBtSink != null) {
                btKeepAlive.ensureActive(cfg.btDevice)
            }

            if (wiredPlayer.playbackState == Player.STATE_IDLE) wiredPlayer.prepare()
            if (currentBtSink != null && btPlayer.playbackState == Player.STATE_IDLE) btPlayer.prepare()

            val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val (wTarget, bTarget) = computeTargetPair(requestedWiredPosMs, _telemetry.value.btAdvanceMs, dur)

            if (abs(wiredPlayer.currentPosition - wTarget) > 20L) {
                wiredPlayer.seekTo(wTarget)
            }
            if (currentBtSink != null && abs(btPlayer.currentPosition - bTarget) > 20L) {
                btPlayer.seekTo(bTarget)
            }

            val deadline = SystemClock.elapsedRealtime() + 2_500L
            while (isActive && SystemClock.elapsedRealtime() < deadline) {
                val wReady = wiredPlayer.playbackState == Player.STATE_READY
                val bReady = currentBtSink == null || btPlayer.playbackState == Player.STATE_READY
                if (wReady && bReady) break
                delay(12L)
            }

            if (!isActive) return@launch

            val now = SystemClock.elapsedRealtime()
            syncSettleUntilMs = now + 2_000L
            isCueingStart = false

            if (currentBtSink != null) {
                btPlayer.play()
            }
            wiredPlayer.play()
            _telemetry.value = _telemetry.value.copy(isPlaying = true)
        }
    }

    fun setWiredVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        wiredPlayer.volume = v
        _telemetry.value = _telemetry.value.copy(wiredVolume = v)
    }

    fun setBtVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        val muted = _telemetry.value.isBtGuideMuted
        btPlayer.volume = if (muted) 0f else v
        _telemetry.value = _telemetry.value.copy(btVolume = v)
    }

    fun toggleBtGuideMute() {
        val nextMute = !_telemetry.value.isBtGuideMuted
        btPlayer.volume = if (nextMute) 0f else _telemetry.value.btVolume
        _telemetry.value = _telemetry.value.copy(isBtGuideMuted = nextMute)
    }

    fun selectWiredTrack(trackKey: String) {
        val option = _telemetry.value.audioTracks.find { it.key == trackKey } ?: return
        applyAudioTrackOverride(wiredPlayer, option, disableVideoAndText = false)
        _telemetry.value = _telemetry.value.copy(selectedWiredTrackKey = option.key)
        if (wiredPlayer.isPlaying) {
            startSynchronizedPlayback(wiredPlayer.currentPosition)
        }
    }

    fun selectBtTrack(trackKey: String) {
        val option = _telemetry.value.audioTracks.find { it.key == trackKey } ?: return
        applyAudioTrackOverride(btPlayer, option, disableVideoAndText = true)
        _telemetry.value = _telemetry.value.copy(selectedBtTrackKey = option.key)
        if (wiredPlayer.isPlaying) {
            startSynchronizedPlayback(wiredPlayer.currentPosition)
        }
    }

    fun swapAudioTracks() {
        val currentWired = _telemetry.value.selectedWiredTrackKey ?: return
        val currentBt = _telemetry.value.selectedBtTrackKey ?: return
        val wOpt = _telemetry.value.audioTracks.find { it.key == currentBt } ?: return
        val bOpt = _telemetry.value.audioTracks.find { it.key == currentWired } ?: return
        applyAudioTrackOverride(wiredPlayer, wOpt, disableVideoAndText = false)
        applyAudioTrackOverride(btPlayer, bOpt, disableVideoAndText = true)
        _telemetry.value = _telemetry.value.copy(
            selectedWiredTrackKey = wOpt.key,
            selectedBtTrackKey = bOpt.key
        )
        if (wiredPlayer.isPlaying) {
            startSynchronizedPlayback(wiredPlayer.currentPosition)
        }
    }

    private fun resetBaselineTracking() {
        baselineLocked = false
        baselineSampleCount = 0
        pendingManualShiftMs = 0.0
        resetBtSpeedToUnity()
    }

    private fun resetBtSpeedToUnity() {
        if (currentAppliedBtSpeed != 1.0f || btPlayer.playbackParameters.speed != 1.0f) {
            btPlayer.playbackParameters = PlaybackParameters.DEFAULT
            currentAppliedBtSpeed = 1.0f
        }
    }

    private fun startBaselineSyncLoop() {
        syncLoopJob?.cancel()
        syncLoopJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                updateDurationAndPosition()
                evaluateBaselineSync()
                val sleepMs = if (currentAppliedBtSpeed != 1.0f || (!baselineLocked && wiredPlayer.isPlaying)) {
                    125L
                } else {
                    300L
                }
                delay(sleepMs)
            }
        }
    }

    private fun evaluateBaselineSync() {
        if (isCueingStart ||
            !wiredPlayer.isPlaying ||
            wiredPlayer.playbackState != Player.STATE_READY ||
            currentBtSink == null
        ) {
            return
        }

        if (btPlayer.playbackState != Player.STATE_READY || !btPlayer.isPlaying) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now < syncSettleUntilMs) {
            return
        }

        val currentDelta = (btPlayer.currentPosition - wiredPlayer.currentPosition).toDouble()

        if (!baselineLocked) {
            baselineSamples[baselineSampleCount] = currentDelta
            baselineSampleCount++
            if (baselineSampleCount >= 3) {
                val med = ChirpDsp.medianOfThree(
                    baselineSamples[0],
                    baselineSamples[1],
                    baselineSamples[2]
                )
                baselinePosDeltaMs = med + pendingManualShiftMs
                pendingManualShiftMs = 0.0
                baselineLocked = true
                _telemetry.value = _telemetry.value.copy(isPhaseLocked = true, syncErrorMs = 0L)
            }
            return
        }

        val gliding = currentAppliedBtSpeed != 1.0f
        val decision = ChirpDsp.computeBaselineDriftSpeed(
            currentPosDeltaMs = currentDelta,
            targetBaselineDeltaMs = baselinePosDeltaMs,
            currentlyGliding = gliding
        )

        if (decision.shouldRecueBoth) {
            startSynchronizedPlayback(wiredPlayer.currentPosition)
            return
        }

        val crossedTarget = (currentAppliedBtSpeed > 1.0f && decision.driftErrorMs <= 8L) ||
            (currentAppliedBtSpeed < 1.0f && decision.driftErrorMs >= -8L)

        val nextSpeed = if (crossedTarget) 1.0f else decision.playbackSpeed
        if (abs(nextSpeed - currentAppliedBtSpeed) > 0.001f) {
            currentAppliedBtSpeed = nextSpeed
            btPlayer.playbackParameters = if (nextSpeed == 1.0f) {
                PlaybackParameters.DEFAULT
            } else {
                PlaybackParameters(nextSpeed, 1.0f)
            }
        }

        val locked = nextSpeed == 1.0f && abs(decision.driftErrorMs) <= 35L
        if (_telemetry.value.syncErrorMs != decision.driftErrorMs || _telemetry.value.isPhaseLocked != locked) {
            _telemetry.value = _telemetry.value.copy(
                syncErrorMs = decision.driftErrorMs,
                isPhaseLocked = locked
            )
        }
    }

    private fun updateDurationAndPosition() {
        val dur = wiredPlayer.duration.takeIf { it > 0L } ?: 0L
        val pos = wiredPlayer.currentPosition.coerceAtLeast(0L)
        if (dur != _telemetry.value.durationMs || pos != _telemetry.value.positionMs) {
            _telemetry.value = _telemetry.value.copy(positionMs = pos, durationMs = dur)
        }
    }

    private fun parseAudioTracks(tracks: Tracks): List<AudioTrackOption> {
        val result = mutableListOf<AudioTrackOption>()
        var audioGroupOrd = 0
        var audioNumber = 1
        tracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (trackIdx in 0 until group.length) {
                    val fmt = group.getTrackFormat(trackIdx)
                    val rawLabel = fmt.label?.trim().orEmpty()
                    val lang = fmt.language?.trim().orEmpty()
                    val mime = fmt.sampleMimeType?.substringAfter('/')?.uppercase().orEmpty()
                    val sr = if (fmt.sampleRate > 0) "${fmt.sampleRate / 1000.0} kHz" else ""
                    val ch = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else ""

                    val title = if (rawLabel.isNotEmpty()) {
                        "Track $audioNumber: $rawLabel"
                    } else if (lang.isNotEmpty() && lang != "und") {
                        "Track $audioNumber ($lang)"
                    } else {
                        "Audio Track $audioNumber"
                    }

                    val details = listOf(mime, ch, sr).filter { it.isNotEmpty() }.joinToString(" · ")
                    val lower = "$rawLabel $lang".lowercase()
                    val isInst = listOf("inst", "karaoke", "minus", "no vocal", "backing", "music").any { it in lower }
                    val isVoc = listOf("vocal", "voice", "guide", "orig", "singer", "lead").any { it in lower }

                    result += AudioTrackOption(
                        key = "${audioGroupOrd}_$trackIdx",
                        audioGroupOrdinal = audioGroupOrd,
                        trackIndex = trackIdx,
                        displayTitle = title,
                        details = details,
                        looksInstrumental = isInst,
                        looksVocalGuide = isVoc
                    )
                    audioNumber++
                }
                audioGroupOrd++
            }
        }
        return result
    }

    private fun autoAssignKaraokeTracks(options: List<AudioTrackOption>) {
        val instPick = options.firstOrNull { it.looksInstrumental } ?: options.first()
        val vocalPick = options.firstOrNull { it.looksVocalGuide && it.key != instPick.key }
            ?: options.firstOrNull { it.key != instPick.key }
            ?: instPick

        _telemetry.value = _telemetry.value.copy(
            audioTracks = options,
            selectedWiredTrackKey = instPick.key,
            selectedBtTrackKey = vocalPick.key
        )

        applyAudioTrackOverride(wiredPlayer, instPick, disableVideoAndText = false)
        scope.launch(Dispatchers.Main) {
            repeat(25) {
                val btAudioGroups = btPlayer.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }
                if (btAudioGroups > vocalPick.audioGroupOrdinal) {
                    applyAudioTrackOverride(btPlayer, vocalPick, disableVideoAndText = true)
                    if (!wiredPlayer.isPlaying && !isCueingStart && currentBtSink != null) {
                        val preTarget = _telemetry.value.btAdvanceMs.coerceAtLeast(0).toLong()
                        if (preTarget > 0L) btPlayer.seekTo(preTarget)
                    }
                    return@launch
                }
                delay(60L)
            }
        }
    }

    private fun applyAudioTrackOverride(
        player: ExoPlayer,
        option: AudioTrackOption,
        disableVideoAndText: Boolean
    ) {
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (option.audioGroupOrdinal !in audioGroups.indices) return
        val mediaGroup = audioGroups[option.audioGroupOrdinal].mediaTrackGroup
        if (option.trackIndex !in 0 until mediaGroup.length) return

        val builder = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(mediaGroup, option.trackIndex))

        if (disableVideoAndText) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        player.trackSelectionParameters = builder.build()
    }

    fun release() {
        syncLoopJob?.cancel()
        startCueJob?.cancel()
        routeProbeJob?.cancel()
        btKeepAlive.stop()
        runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
        wiredPlayer.release()
        btPlayer.release()
        disengageBluetoothCommunicationRoute()
        if (raisedAlarmVolume && initialAlarmVolume >= 0) {
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, initialAlarmVolume, 0)
            }
        }
        runCatching { audioManager.mode = initialAudioMode }
    }

    companion object {
        private fun buildSmoothLoadControl(): DefaultLoadControl {
            return DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    12_000,
                    30_000,
                    400,
                    800
                )
                .build()
        }
    }
}
