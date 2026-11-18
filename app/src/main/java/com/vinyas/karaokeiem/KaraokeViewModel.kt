package com.vinyas.karaokeiem

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vinyas.karaokeiem.audio.AudioDeviceRouter
import com.vinyas.karaokeiem.audio.ChirpCalibrationResult
import com.vinyas.karaokeiem.audio.DualSinkPlayerEngine
import com.vinyas.karaokeiem.audio.LatencyAutoCalibrator
import com.vinyas.karaokeiem.audio.OutputSinkItem
import com.vinyas.karaokeiem.audio.SplitRoutingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CalibrationUiState(
    val isBusy: Boolean = false,
    val statusBanner: String = "Tap +50 / -50 ms while playing to ear-lock sync, or run Mic Chirp Sync.",
    val isError: Boolean = false
)

class KaraokeViewModel(application: Application) : AndroidViewModel(application) {

    val deviceRouter = AudioDeviceRouter(application)
    val engine = DualSinkPlayerEngine(application, viewModelScope)
    private val calibrator = LatencyAutoCalibrator(application)

    private val _selectedWiredId = MutableStateFlow<Int?>(null)
    val selectedWiredId: StateFlow<Int?> = _selectedWiredId.asStateFlow()

    private val _selectedBtId = MutableStateFlow<Int?>(null)
    val selectedBtId: StateFlow<Int?> = _selectedBtId.asStateFlow()

    private val _loadedFileName = MutableStateFlow<String?>(null)
    val loadedFileName: StateFlow<String?> = _loadedFileName.asStateFlow()

    private val _calibrationState = MutableStateFlow(CalibrationUiState())
    val calibrationState: StateFlow<CalibrationUiState> = _calibrationState.asStateFlow()

    private var lastAppliedProfileSignature: String? = null

    init {
        viewModelScope.launch {
            deviceRouter.wiredCandidates.collect { wiredList ->
                val current = _selectedWiredId.value
                if (current == null || wiredList.none { it.id == current }) {
                    _selectedWiredId.value = wiredList.firstOrNull()?.id
                }
                applySelectedRoutes()
            }
        }

        viewModelScope.launch {
            deviceRouter.bluetoothCandidates.collect { btList ->
                val current = _selectedBtId.value
                if (current == null || btList.none { it.id == current }) {
                    _selectedBtId.value = btList.firstOrNull()?.id
                }
                applySelectedRoutes()
                applySavedOrDefaultOffsetForCurrentConfig()
            }
        }
    }

    fun selectWiredSink(deviceId: Int) {
        _selectedWiredId.value = deviceId
        applySelectedRoutes()
    }

    fun selectBtSink(deviceId: Int) {
        _selectedBtId.value = deviceId
        applySelectedRoutes()
        applySavedOrDefaultOffsetForCurrentConfig()
    }

    fun selectSplitMode(mode: SplitRoutingMode) {
        engine.setSplitMode(mode)
        applySavedOrDefaultOffsetForCurrentConfig()
    }

    fun refreshDevices() {
        deviceRouter.refreshDevices()
        engine.recheckRoutesAsync()
    }

    fun onAppResumed() {
        deviceRouter.refreshDevices()
        engine.recheckRoutesAsync()
    }

    fun openMkvFile(uri: Uri) {
        val displayName = resolveDisplayName(uri)
        _loadedFileName.value = displayName
        applySelectedRoutes()
        engine.loadMkvUri(uri)
    }

    fun updateManualOffsetMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-300, 1000)
        engine.setBtAdvanceMs(clamped)
        val cfg = engine.currentActiveSinkConfig()
        calibrator.saveOffsetForDevice(currentBtSink()?.name, cfg.usingCommMode, clamped)
    }

    private fun applySavedOrDefaultOffsetForCurrentConfig() {
        val activeBt = currentBtSink() ?: return
        val cfg = engine.currentActiveSinkConfig()
        val sig = "${activeBt.name}_${cfg.usingCommMode}"
        if (sig == lastAppliedProfileSignature) return
        lastAppliedProfileSignature = sig

        val saved = calibrator.getSavedOffsetForDevice(activeBt.name, cfg.usingCommMode)
        val chan = if (cfg.usingCommMode) "BT Voice IEM" else "Stereo A2DP"
        if (saved != null) {
            engine.setBtAdvanceMs(saved)
            _calibrationState.value = CalibrationUiState(
                isBusy = false,
                statusBanner = "Loaded saved $chan offset (${formatSignedMs(saved)}) for ${activeBt.name}.",
                isError = false
            )
        } else {
            val defaultMs = calibrator.defaultOffset(cfg.usingCommMode)
            engine.setBtAdvanceMs(defaultMs)
            _calibrationState.value = CalibrationUiState(
                isBusy = false,
                statusBanner = "Applied $chan baseline (${formatSignedMs(defaultMs)}) for ${activeBt.name}. Fine-tune live with +/-15 or +/-50 ms, or tap Mic Chirp Sync.",
                isError = false
            )
        }
    }

    fun runOsTimestampAutoProbe() {
        if (_calibrationState.value.isBusy) return
        val bt = currentBtSink()
        if (bt == null) {
            _calibrationState.value = CalibrationUiState(
                statusBanner = "No Bluetooth IEM connected. Pair/connect Bluetooth earphones first.",
                isError = true
            )
            return
        }

        val wasPlaying = engine.telemetry.value.isPlaying
        if (wasPlaying) engine.pause()

        viewModelScope.launch {
            engine.suspendKeepAliveForCalibration()
            engine.applyHardwareRoutingPolicy(force = false)
            val cfg = engine.currentActiveSinkConfig()
            _calibrationState.value = CalibrationUiState(
                isBusy = true,
                statusBanner = "Probing OS hardware latency for ${bt.name}..."
            )
            if (cfg.usingCommMode) {
                delay(450L)
            }
            val res = calibrator.probeOsLatencyDeltaMs(cfg)
            engine.setBtAdvanceMs(res.deltaMs)
            calibrator.saveOffsetForDevice(bt.name, cfg.usingCommMode, res.deltaMs)
            engine.resumeKeepAliveAfterCalibration()

            val modeLabel = if (res.usedHardwareTimestamps) {
                "OS estimate: ${formatSignedMs(res.deltaMs)} (BT ${res.btLatencyMs} ms vs Aux/Spk ${res.wiredLatencyMs} ms). Nudge +/-15 ms by ear if needed."
            } else {
                "Applied baseline (${formatSignedMs(res.deltaMs)}). Tap 'Mic Chirp Sync' or nudge +/-50 ms by ear."
            }
            _calibrationState.value = CalibrationUiState(
                isBusy = false,
                statusBanner = modeLabel,
                isError = false
            )
            if (wasPlaying) engine.play()
        }
    }

    fun runAcousticChirpCalibration() {
        if (_calibrationState.value.isBusy) return
        val bt = currentBtSink()
        if (bt == null) {
            _calibrationState.value = CalibrationUiState(
                statusBanner = "Connect Bluetooth earphones before running Mic Chirp Sync.",
                isError = true
            )
            return
        }

        val wasPlaying = engine.telemetry.value.isPlaying
        if (wasPlaying) engine.pause()

        viewModelScope.launch {
            engine.suspendKeepAliveForCalibration()
            engine.applyHardwareRoutingPolicy(force = false)
            val cfg = engine.currentActiveSinkConfig()
            _calibrationState.value = CalibrationUiState(
                isBusy = true,
                statusBanner = "Hold Aux earpiece/speaker + BT earbud right by the bottom phone mic (playing 2 chirps)..."
            )
            if (cfg.usingCommMode) {
                delay(500L)
            }
            val result = calibrator.measureAcousticChirpDeltaMs(cfg, bt.name)
            engine.resumeKeepAliveAfterCalibration()

            when (result) {
                is ChirpCalibrationResult.Success -> {
                    engine.setBtAdvanceMs(result.deltaMs)
                    _calibrationState.value = CalibrationUiState(
                        isBusy = false,
                        statusBanner = "Chirp Sync locked at ${formatSignedMs(result.deltaMs)} for ${bt.name} " +
                            "(SNR ${"%.1f".format(result.wiredSnr)} / ${"%.1f".format(result.btSnr)}).",
                        isError = false
                    )
                }

                is ChirpCalibrationResult.Failure -> {
                    _calibrationState.value = CalibrationUiState(
                        isBusy = false,
                        statusBanner = result.reason,
                        isError = true
                    )
                }
            }
            if (wasPlaying) engine.play()
        }
    }

    private fun currentWiredSink(): OutputSinkItem? =
        deviceRouter.wiredCandidates.value.find { it.id == _selectedWiredId.value }

    private fun currentBtSink(): OutputSinkItem? =
        deviceRouter.bluetoothCandidates.value.find { it.id == _selectedBtId.value }

    private fun applySelectedRoutes() {
        engine.setRoutedDevices(currentWiredSink(), currentBtSink())
    }

    private fun resolveDisplayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Karaoke_Track.mkv"
    }

    private fun formatSignedMs(ms: Int): String = if (ms >= 0) "+${ms} ms" else "${ms} ms"

    override fun onCleared() {
        super.onCleared()
        deviceRouter.release()
        engine.release()
    }
}
