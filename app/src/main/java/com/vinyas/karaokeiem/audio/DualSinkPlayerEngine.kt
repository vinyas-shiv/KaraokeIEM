@file:OptIn(UnstableApi::class)

package com.vinyas.karaokeiem.audio

import android.content.Context
import android.net.Uri
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
import kotlin.math.abs

data class AudioTrackOption(
    val key: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val displayTitle: String,
    val details: String,
    val looksInstrumental: Boolean,
    val looksVocalGuide: Boolean
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
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val selectedWiredTrackKey: String? = null,
    val selectedBtTrackKey: String? = null
)

class DualSinkPlayerEngine(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
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

    private var pllJob: Job? = null
    private var tracksAutoAssignedForCurrentMedia = false

    init {
        val mediaAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        wiredPlayer.setAudioAttributes(mediaAttrs, true)
        btPlayer.setAudioAttributes(mediaAttrs, false)

        btPlayer.trackSelectionParameters = btPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        wiredPlayer.volume = _telemetry.value.wiredVolume
        btPlayer.volume = _telemetry.value.btVolume

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
                    alignAndStartBtPlayer()
                } else {
                    btPlayer.pause()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    btPlayer.pause()
                }
                updateDurationAndPosition()
            }
        })

        startPllLoop()
    }

    fun loadMkvUri(uri: Uri) {
        tracksAutoAssignedForCurrentMedia = false
        val item = MediaItem.fromUri(uri)

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

    fun setRoutedDevices(wiredSink: OutputSinkItem?, btSink: OutputSinkItem?) {
        wiredPlayer.setPreferredAudioDevice(wiredSink?.deviceInfo)
        btPlayer.setPreferredAudioDevice(btSink?.deviceInfo)
    }

    fun play() {
        val pos = wiredPlayer.currentPosition
        val adv = _telemetry.value.btAdvanceMs
        val dur = wiredPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        btPlayer.seekTo((pos + adv).coerceIn(0L, dur))
        btPlayer.playbackParameters = PlaybackParameters.DEFAULT
        wiredPlayer.play()
        btPlayer.play()
    }

    fun pause() {
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
        btPlayer.playbackParameters = PlaybackParameters.DEFAULT
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
        }
        btPlayer.play()
    }

    private fun startPllLoop() {
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
                        if (btPlayer.playbackParameters.speed != 1.0f) {
                            btPlayer.playbackParameters = PlaybackParameters.DEFAULT
                        }
                    } else if (abs(btPlayer.playbackParameters.speed - decision.playbackSpeed) > 0.001f) {
                        btPlayer.playbackParameters = PlaybackParameters(decision.playbackSpeed, 1.0f)
                    }

                    _telemetry.value = _telemetry.value.copy(
                        syncErrorMs = decision.errorMs,
                        isPhaseLocked = abs(decision.errorMs) <= 4L
                    )
                } else {
                    if (_telemetry.value.isPhaseLocked) {
                        _telemetry.value.let { _telemetry.value = it.copy(isPhaseLocked = false) }
                    }
                }
                delay(50L)
            }
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
        pllJob?.cancel()
        wiredPlayer.release()
        btPlayer.release()
    }
}
