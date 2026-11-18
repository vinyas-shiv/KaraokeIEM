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
import androidx.media3.exoplayer.ExoPlayer
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
    val groupIndex: Int,
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

class DualSinkPlayerEngine(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val wiredTrackSelector = DefaultTrackSelector(appContext)
    private val btTrackSelector = DefaultTrackSelector(appContext)

    val wiredPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setTrackSelector(wiredTrackSelector)
        .build()

    val btPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setTrackSelector(btTrackSelector)
        .build()

    private val _telemetry = MutableStateFlow(EngineTelemetry())
    val telemetry: StateFlow<EngineTelemetry> = _telemetry.asStateFlow()

    private var syncLoopJob: Job? = null
    private var routeProbeJob: Job? = null
    private var tracksAutoAssignedForCurrentMedia = false

    private var currentWiredSink: OutputSinkItem? = null
    private var currentBtSink: OutputSinkItem? = null

    private var lastAppliedSignature: AppliedRouteSignature? = null
    private var lastWiredMedia3Attrs: AudioAttributes? = null
    private var lastBtMedia3Attrs: AudioAttributes? = null

    private var settleUntilRealtimeMs: Long = 0L
    private var currentAppliedBtSpeed: Float = 1.0f
    private var driftConfirmCount: Int = 0

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
                _telemetry.value = _telemetry.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    if (!btPlayer.isPlaying && currentBtSink != null) {
                        alignBtPlayerIfNeeded(maxToleranceMs = 45L)
                        btPlayer.play()
                    }
                } else {
                    if (btPlayer.isPlaying) {
                        btPlayer.pause()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    btPlayer.pause()
                }
                updateDurationAndPosition()
            }
        })

        startGentleSyncLoop()
    }

    fun setSplitMode(mode: SplitRoutingMode) {
        if (_telemetry.value.splitMode == mode) return
        _telemetry.value = _telemetry.value.copy(
            splitMode = mode,
            needsSystemMediaSwitchHint = false
        )
        applyHardwareRoutingPolicy(force = true)
    }

    fun setRoutedDevices(wiredSink: OutputSinkItem?, btSink: OutputSinkItem?) {
        currentWiredSink = wiredSink
        currentBtSink = btSink
        applyHardwareRoutingPolicy(force = false)
    }

    fun recheckRoutesAsync() {
        if (!wiredPlayer.isPlaying) {
            scheduleOneShotRouteVerification(currentActiveSinkConfig())
        }
    }

    fun currentActiveSinkConfig(): ActiveSinkConfig {
        val wiredItem = currentWiredSink
        val btItem = currentBtSink
        val useCommForBt = (_telemetry.value.splitMode == SplitRoutingMode.BT_COMM_SCO_BLE) && btItem != null

        return if (useCommForBt) {
            val btDev = btItem?.commDeviceInfo ?: findFallbackBtCommDevice() ?: btItem?.deviceInfo
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
            engageBluetoothCommunicationRoute(cfg.btDevice)
            ensureStreamAudible(AudioManager.STREAM_VOICE_CALL, minFraction = 0.65f)
        } else {
            disengageBluetoothCommunicationRoute()
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

        resetBtSpeedToUnity()
        settleUntilRealtimeMs = SystemClock.elapsedRealtime() + 1_200L

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

    @Suppress("DEPRECATION")
    private fun engageBluetoothCommunicationRoute(btCommDevice: AudioDeviceInfo) {
        runCatching {
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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

    private fun findFallbackBtCommDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val comm = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
            comm.firstOrNull { it.type == 26 }?.let { return it }
            comm.firstOrNull { AudioDeviceRouter.isBluetoothDeviceType(it.type) }?.let { return it }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
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
        tracksAutoAssignedForCurrentMedia = false
        val item = MediaItem.fromUri(uri)

        wiredPlayer.stop()
        btPlayer.stop()
        resetBtSpeedToUnity()

        wiredPlayer.setMediaItem(item)
        btPlayer.setMediaItem(item)

        wiredPlayer.prepare()
        btPlayer.prepare()

        _telemetry.value = _telemetry.value.copy(
            positionMs = 0L,
            durationMs = 0L,
            audioTracks = emptyList(),
            selectedWiredTrackKey = null,
            selectedBtTrackKey = null,
            isPhaseLocked = false
        )
    }

    fun play() {
        routeProbeJob?.cancel()
        runCatching { audioManager.requestAudioFocus(audioFocusRequest) }
        applyHardwareRoutingPolicy(force = false)
        resetBtSpeedToUnity()
        settleUntilRealtimeMs = SystemClock.elapsedRealtime() + 1_200L

        if (currentBtSink != null) {
            alignBtPlayerIfNeeded(maxToleranceMs = 25L)
            btPlayer.play()
        }
        wiredPlayer.play()
    }

    fun pause() {
        wiredPlayer.pause()
        btPlayer.pause()
        resetBtSpeedToUnity()
    }

    fun togglePlayPause() {
        if (wiredPlayer.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, dur)
        val btTarget = (clamped + _telemetry.value.btAdvanceMs).coerceIn(0L, dur)
        resetBtSpeedToUnity()
        settleUntilRealtimeMs = SystemClock.elapsedRealtime() + 1_200L
        wiredPlayer.seekTo(clamped)
        btPlayer.seekTo(btTarget)
        _telemetry.value = _telemetry.value.copy(positionMs = clamped)
    }

    fun nudgePosition(deltaMs: Long) {
        seekTo(wiredPlayer.currentPosition + deltaMs)
    }

    fun setBtAdvanceMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-250, 600)
        _telemetry.value = _telemetry.value.copy(btAdvanceMs = clamped)
        if (wiredPlayer.isPlaying && currentBtSink != null) {
            val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val target = (wiredPlayer.currentPosition + clamped).coerceIn(0L, dur)
            if (abs(btPlayer.currentPosition - target) > 35L) {
                resetBtSpeedToUnity()
                settleUntilRealtimeMs = SystemClock.elapsedRealtime() + 1_000L
                btPlayer.seekTo(target)
            }
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
    }

    fun selectBtTrack(trackKey: String) {
        val option = _telemetry.value.audioTracks.find { it.key == trackKey } ?: return
        applyAudioTrackOverride(btPlayer, option, disableVideoAndText = true)
        settleUntilRealtimeMs = SystemClock.elapsedRealtime() + 1_000L
        _telemetry.value = _telemetry.value.copy(selectedBtTrackKey = option.key)
    }

    fun swapAudioTracks() {
        val currentWired = _telemetry.value.selectedWiredTrackKey ?: return
        val currentBt = _telemetry.value.selectedBtTrackKey ?: return
        selectWiredTrack(currentBt)
        selectBtTrack(currentWired)
    }

    private fun alignBtPlayerIfNeeded(maxToleranceMs: Long) {
        val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (wiredPlayer.currentPosition + _telemetry.value.btAdvanceMs).coerceIn(0L, dur)
        if (abs(btPlayer.currentPosition - target) > maxToleranceMs) {
            btPlayer.seekTo(target)
        }
    }

    private fun resetBtSpeedToUnity() {
        driftConfirmCount = 0
        if (currentAppliedBtSpeed != 1.0f || btPlayer.playbackParameters.speed != 1.0f) {
            btPlayer.playbackParameters = PlaybackParameters.DEFAULT
            currentAppliedBtSpeed = 1.0f
        }
    }

    private fun startGentleSyncLoop() {
        syncLoopJob?.cancel()
        syncLoopJob = scope.launch(Dispatchers.Main) {
            var uiTick = 0
            while (isActive) {
                updateDurationAndPosition()
                uiTick++

                if (uiTick % 3 == 0) {
                    evaluateSyncEvery750Ms()
                }

                delay(250L)
            }
        }
    }

    private fun evaluateSyncEvery750Ms() {
        if (!wiredPlayer.isPlaying || wiredPlayer.playbackState != Player.STATE_READY || currentBtSink == null) {
            if (_telemetry.value.isPhaseLocked) {
                _telemetry.value = _telemetry.value.copy(isPhaseLocked = false, syncErrorMs = 0L)
            }
            return
        }

        if (!btPlayer.isPlaying && btPlayer.playbackState == Player.STATE_READY) {
            btPlayer.play()
        }
        if (btPlayer.playbackState != Player.STATE_READY) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val dur = wiredPlayer.duration.takeIf { it > 0L } ?: 0L
        val decision = ChirpDsp.computePllPlaybackSpeed(
            wiredPosMs = wiredPlayer.currentPosition,
            btPosMs = btPlayer.currentPosition,
            targetAdvanceMs = _telemetry.value.btAdvanceMs,
            durationMs = dur
        )

        if (now >= settleUntilRealtimeMs) {
            when {
                decision.shouldHardSeek -> {
                    resetBtSpeedToUnity()
                    settleUntilRealtimeMs = now + 1_200L
                    btPlayer.seekTo(decision.seekTargetMs)
                }

                currentAppliedBtSpeed != 1.0f -> {
                    val crossedZero = (currentAppliedBtSpeed > 1.0f && decision.errorMs <= 12L) ||
                        (currentAppliedBtSpeed < 1.0f && decision.errorMs >= -12L)
                    if (crossedZero) {
                        resetBtSpeedToUnity()
                    }
                }

                decision.playbackSpeed != 1.0f -> {
                    driftConfirmCount++
                    if (driftConfirmCount >= 2) {
                        driftConfirmCount = 0
                        currentAppliedBtSpeed = decision.playbackSpeed
                        btPlayer.playbackParameters = PlaybackParameters(decision.playbackSpeed, 1.0f)
                    }
                }

                else -> {
                    driftConfirmCount = 0
                }
            }
        }

        val locked = abs(decision.errorMs) <= 25L
        if (_telemetry.value.syncErrorMs != decision.errorMs || _telemetry.value.isPhaseLocked != locked) {
            _telemetry.value = _telemetry.value.copy(
                syncErrorMs = decision.errorMs,
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
        var audioNumber = 1
        tracks.groups.forEachIndexed { groupIdx, group ->
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
                        key = "${groupIdx}_$trackIdx",
                        groupIndex = groupIdx,
                        trackIndex = trackIdx,
                        displayTitle = title,
                        details = details,
                        looksInstrumental = isInst,
                        looksVocalGuide = isVoc
                    )
                    audioNumber++
                }
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
            repeat(20) {
                if (btPlayer.currentTracks.groups.size > vocalPick.groupIndex) {
                    applyAudioTrackOverride(btPlayer, vocalPick, disableVideoAndText = true)
                    return@launch
                }
                delay(75L)
            }
        }
    }

    private fun applyAudioTrackOverride(
        player: ExoPlayer,
        option: AudioTrackOption,
        disableVideoAndText: Boolean
    ) {
        val groups = player.currentTracks.groups
        if (option.groupIndex !in groups.indices) return
        val mediaGroup = groups[option.groupIndex].mediaTrackGroup
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
        routeProbeJob?.cancel()
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
}
