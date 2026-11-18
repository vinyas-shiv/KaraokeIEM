@file:OptIn(UnstableApi::class)

package com.vinyas.karaokeiem.audio

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
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
import kotlin.math.abs

enum class SplitRoutingMode(val label: String) {
    AUTO_SMART("Auto-Split"),
    BT_COMM_SCO_BLE("Force BT IEM (Aux+BT)"),
    BT_A2DP_STEREO("Stereo A2DP")
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
    val splitMode: SplitRoutingMode = SplitRoutingMode.AUTO_SMART,
    val activeSplitSummary: String = "Auto-Split ready",
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

    private val wiredPinnedSink = DirectPinnedAudioSink("WiredInstrumental")
    private val btPinnedSink = DirectPinnedAudioSink("BluetoothVocalIEM")

    val wiredPlayer: ExoPlayer = ExoPlayer.Builder(
        appContext,
        createPinnedRenderersFactory(appContext, wiredPinnedSink)
    )
        .setTrackSelector(wiredTrackSelector)
        .build()

    val btPlayer: ExoPlayer = ExoPlayer.Builder(
        appContext,
        createPinnedRenderersFactory(appContext, btPinnedSink)
    )
        .setTrackSelector(btTrackSelector)
        .build()

    private val _telemetry = MutableStateFlow(EngineTelemetry())
    val telemetry: StateFlow<EngineTelemetry> = _telemetry.asStateFlow()

    private var pllJob: Job? = null
    private var tracksAutoAssignedForCurrentMedia = false

    private var currentWiredSink: OutputSinkItem? = null
    private var currentBtSink: OutputSinkItem? = null

    private var autoEscalatedToComm = false
    private var autoPromotedWiredToAlarm = false
    private var playingTicksInCurrentRoute = 0

    private val initialAudioMode = audioManager.mode
    private val initialAlarmVolume = runCatching {
        audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
    }.getOrDefault(-1)
    private var raisedAlarmVolume = false

    init {
        val mediaAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        wiredPlayer.setAudioAttributes(mediaAttrs, true)
        btPlayer.setAudioAttributes(mediaAttrs, false)

        wiredPlayer.volume = 0f
        btPlayer.volume = 0f

        wiredPinnedSink.setVolume(_telemetry.value.wiredVolume, false)
        btPinnedSink.setVolume(_telemetry.value.btVolume, _telemetry.value.isBtGuideMuted)

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
                    playingTicksInCurrentRoute = 0
                    applyHardwareRoutingPolicy()
                    wiredPinnedSink.onPlay()
                    btPinnedSink.onPlay()
                    alignAndStartBtPlayer()
                } else {
                    wiredPinnedSink.onPause()
                    btPinnedSink.onPause()
                    btPlayer.pause()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    wiredPinnedSink.onPause()
                    btPinnedSink.onPause()
                    btPlayer.pause()
                }
                updateDurationAndPosition()
            }
        })

        startPllAndRouteWatchdogLoop()
    }

    fun setSplitMode(mode: SplitRoutingMode) {
        autoEscalatedToComm = false
        autoPromotedWiredToAlarm = false
        playingTicksInCurrentRoute = 0
        _telemetry.value = _telemetry.value.copy(
            splitMode = mode,
            needsSystemMediaSwitchHint = false
        )
        applyHardwareRoutingPolicy()
    }

    fun setRoutedDevices(wiredSink: OutputSinkItem?, btSink: OutputSinkItem?) {
        val changed = (wiredSink?.id != currentWiredSink?.id || btSink?.id != currentBtSink?.id)
        currentWiredSink = wiredSink
        currentBtSink = btSink
        if (changed) {
            autoEscalatedToComm = false
            autoPromotedWiredToAlarm = false
            playingTicksInCurrentRoute = 0
        }
        wiredPlayer.setPreferredAudioDevice(wiredSink?.deviceInfo)
        applyHardwareRoutingPolicy()
    }

    fun currentActiveSinkConfig(): ActiveSinkConfig {
        val wiredItem = currentWiredSink
        val btItem = currentBtSink
        val mode = _telemetry.value.splitMode

        val useCommForBt = when (mode) {
            SplitRoutingMode.BT_COMM_SCO_BLE -> btItem != null
            SplitRoutingMode.BT_A2DP_STEREO -> false
            SplitRoutingMode.AUTO_SMART -> {
                if (btItem == null) {
                    false
                } else if (autoEscalatedToComm || btItem.a2dpDeviceInfo == null) {
                    true
                } else if (wiredItem != null && wiredItem.isWiredHeadsetOrUsb &&
                    wiredItem.deviceInfo.type != AudioDeviceInfo.TYPE_USB_HEADSET &&
                    wiredItem.deviceInfo.type != AudioDeviceInfo.TYPE_USB_DEVICE
                ) {
                    true
                } else {
                    false
                }
            }
        }

        return if (useCommForBt) {
            val btDev = btItem?.commDeviceInfo ?: findFallbackBtCommDevice() ?: btItem?.deviceInfo
            val useAlarmForWired = autoPromotedWiredToAlarm ||
                (wiredItem?.deviceInfo?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            val wiredUsage = if (useAlarmForWired) {
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

    fun applyHardwareRoutingPolicy() {
        val cfg = currentActiveSinkConfig()

        if (cfg.usingCommMode && cfg.btDevice != null) {
            engageBluetoothCommunicationRoute(cfg.btDevice)
            ensureStreamAudible(AudioManager.STREAM_VOICE_CALL, minFraction = 0.65f)
        } else {
            disengageBluetoothCommunicationRoute()
        }

        if (cfg.wiredUsage == PlatformAudioAttributes.USAGE_ALARM) {
            ensureAlarmStreamAudible()
        }

        wiredPinnedSink.configureRoute(
            usage = cfg.wiredUsage,
            contentType = PlatformAudioAttributes.CONTENT_TYPE_MUSIC,
            device = cfg.wiredDevice
        )
        btPinnedSink.configureRoute(
            usage = cfg.btUsage,
            contentType = if (cfg.usingCommMode) {
                PlatformAudioAttributes.CONTENT_TYPE_SPEECH
            } else {
                PlatformAudioAttributes.CONTENT_TYPE_MUSIC
            },
            device = cfg.btDevice
        )

        val summary = if (currentBtSink == null) {
            "Single output (connect Bluetooth earphones for split IEM)"
        } else if (cfg.usingCommMode) {
            "Active Split: Aux/Speaker Media + Bluetooth IEM Voice Channel"
        } else {
            "Active Split: Aux/Speaker Sonification + Bluetooth Stereo A2DP"
        }
        _telemetry.value = _telemetry.value.copy(activeSplitSummary = summary)
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

        wiredPinnedSink.onPause()
        btPinnedSink.onPause()
        wiredPlayer.stop()
        btPlayer.stop()

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
        applyHardwareRoutingPolicy()
        val pos = wiredPlayer.currentPosition
        val adv = _telemetry.value.btAdvanceMs
        val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        btPlayer.seekTo((pos + adv).coerceIn(0L, dur))
        btPlayer.playbackParameters = PlaybackParameters.DEFAULT
        btPinnedSink.setPlaybackSpeed(1.0f)
        wiredPinnedSink.onPlay()
        btPinnedSink.onPlay()
        wiredPlayer.play()
        btPlayer.play()
    }

    fun pause() {
        wiredPinnedSink.onPause()
        btPinnedSink.onPause()
        wiredPlayer.pause()
        btPlayer.pause()
    }

    fun togglePlayPause() {
        if (wiredPlayer.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, dur)
        val btTarget = (clamped + _telemetry.value.btAdvanceMs).coerceIn(0L, dur)
        wiredPlayer.seekTo(clamped)
        btPlayer.seekTo(btTarget)
        wiredPinnedSink.onSeekFlush()
        btPinnedSink.onSeekFlush()
        btPlayer.playbackParameters = PlaybackParameters.DEFAULT
        btPinnedSink.setPlaybackSpeed(1.0f)
        _telemetry.value = _telemetry.value.copy(positionMs = clamped)
    }

    fun nudgePosition(deltaMs: Long) {
        seekTo(wiredPlayer.currentPosition + deltaMs)
    }

    fun setBtAdvanceMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-250, 600)
        _telemetry.value = _telemetry.value.copy(btAdvanceMs = clamped)
    }

    fun setWiredVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        wiredPinnedSink.setVolume(v, false)
        _telemetry.value = _telemetry.value.copy(wiredVolume = v)
    }

    fun setBtVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        val muted = _telemetry.value.isBtGuideMuted
        btPinnedSink.setVolume(v, muted)
        _telemetry.value = _telemetry.value.copy(btVolume = v)
    }

    fun toggleBtGuideMute() {
        val nextMute = !_telemetry.value.isBtGuideMuted
        btPinnedSink.setVolume(_telemetry.value.btVolume, nextMute)
        _telemetry.value = _telemetry.value.copy(isBtGuideMuted = nextMute)
    }

    fun selectWiredTrack(trackKey: String) {
        val option = _telemetry.value.audioTracks.find { it.key == trackKey } ?: return
        applyAudioTrackOverride(wiredPlayer, option, disableVideoAndText = false)
        wiredPinnedSink.onSeekFlush()
        _telemetry.value = _telemetry.value.copy(selectedWiredTrackKey = option.key)
    }

    fun selectBtTrack(trackKey: String) {
        val option = _telemetry.value.audioTracks.find { it.key == trackKey } ?: return
        applyAudioTrackOverride(btPlayer, option, disableVideoAndText = true)
        btPinnedSink.onSeekFlush()
        _telemetry.value = _telemetry.value.copy(selectedBtTrackKey = option.key)
    }

    fun swapAudioTracks() {
        val currentWired = _telemetry.value.selectedWiredTrackKey ?: return
        val currentBt = _telemetry.value.selectedBtTrackKey ?: return
        selectWiredTrack(currentBt)
        selectBtTrack(currentWired)
    }

    private fun alignAndStartBtPlayer() {
        val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (wiredPlayer.currentPosition + _telemetry.value.btAdvanceMs).coerceIn(0L, dur)
        if (abs(btPlayer.currentPosition - target) > 40L) {
            btPlayer.seekTo(target)
            btPinnedSink.onSeekFlush()
        }
        btPlayer.play()
    }

    private fun startPllAndRouteWatchdogLoop() {
        pllJob?.cancel()
        pllJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                updateDurationAndPosition()
                if (wiredPlayer.isPlaying && wiredPlayer.playbackState == Player.STATE_READY) {
                    if (!btPlayer.isPlaying && btPlayer.playbackState == Player.STATE_READY) {
                        btPlayer.play()
                    }
                    val dur = wiredPlayer.duration.takeIf { it > 0L } ?: 0L
                    val decision = ChirpDsp.computePllPlaybackSpeed(
                        wiredPosMs = wiredPlayer.currentPosition,
                        btPosMs = btPlayer.currentPosition,
                        targetAdvanceMs = _telemetry.value.btAdvanceMs,
                        durationMs = dur
                    )

                    if (decision.shouldHardSeek) {
                        btPlayer.seekTo(decision.seekTargetMs)
                        btPinnedSink.onSeekFlush()
                        if (btPlayer.playbackParameters.speed != 1.0f) {
                            btPlayer.playbackParameters = PlaybackParameters.DEFAULT
                        }
                        btPinnedSink.setPlaybackSpeed(1.0f)
                    } else {
                        if (abs(btPlayer.playbackParameters.speed - decision.playbackSpeed) > 0.001f) {
                            btPlayer.playbackParameters = PlaybackParameters(decision.playbackSpeed, 1.0f)
                        }
                        btPinnedSink.setPlaybackSpeed(decision.playbackSpeed)
                    }

                    playingTicksInCurrentRoute++
                    inspectLiveOsRoutesAndAutoHeal(decision.errorMs)
                } else {
                    if (_telemetry.value.isPhaseLocked) {
                        _telemetry.value = _telemetry.value.copy(isPhaseLocked = false)
                    }
                }
                delay(50L)
            }
        }
    }

    private fun inspectLiveOsRoutesAndAutoHeal(syncErrMs: Long) {
        val wActual = wiredPinnedSink.queryActualRoutedDevice()
        val bActual = btPinnedSink.queryActualRoutedDevice()

        val wLabel = AudioDeviceRouter.describeDevice(wActual)
        val bLabel = AudioDeviceRouter.describeDevice(bActual)

        val wIsBt = wActual != null && AudioDeviceRouter.isBluetoothDeviceType(wActual.type)
        val bIsBt = bActual != null && AudioDeviceRouter.isBluetoothDeviceType(bActual.type)
        val hasBtTarget = currentBtSink != null

        var needSwitchHint = false

        if (hasBtTarget && playingTicksInCurrentRoute >= 9) {
            val mode = _telemetry.value.splitMode
            if (!bIsBt && bActual != null) {
                if (mode == SplitRoutingMode.AUTO_SMART && !autoEscalatedToComm) {
                    autoEscalatedToComm = true
                    playingTicksInCurrentRoute = 0
                    applyHardwareRoutingPolicy()
                } else if (mode == SplitRoutingMode.BT_A2DP_STEREO) {
                    needSwitchHint = true
                }
            }

            if (wIsBt && !autoPromotedWiredToAlarm) {
                autoPromotedWiredToAlarm = true
                playingTicksInCurrentRoute = 0
                applyHardwareRoutingPolicy()
            }
        }

        val splitOk = hasBtTarget && bIsBt && !wIsBt && wActual != null

        _telemetry.value = _telemetry.value.copy(
            syncErrorMs = syncErrMs,
            isPhaseLocked = abs(syncErrMs) <= 4L,
            wiredActualRoute = wLabel,
            btActualRoute = bLabel,
            isSplitVerified = splitOk,
            needsSystemMediaSwitchHint = needSwitchHint
        )
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
        pllJob?.cancel()
        wiredPinnedSink.release()
        btPinnedSink.release()
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
        private fun createPinnedRenderersFactory(
            context: Context,
            sink: DirectPinnedAudioSink
        ): DefaultRenderersFactory {
            return object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(sink)))
                        .build()
                }
            }
        }
    }
}
