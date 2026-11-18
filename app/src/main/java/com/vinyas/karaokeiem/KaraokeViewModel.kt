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
    val statusBanner: String = "Connect Aux/Speaker + Bluetooth earphones for independent split playback.",
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

    private var lastAutoProbedSignature: String? = null

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
                loadOrProbeOffsetForCurrentConfig()
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
        loadOrProbeOffsetForCurrentConfig()
    }

    fun selectSplitMode(mode: SplitRoutingMode) {
        engine.setSplitMode(mode)
        loadOrProbeOffsetForCurrentConfig()
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
        engine.setBtAdvanceMs(offsetMs)
        val cfg = engine.currentActiveSinkConfig()
        calibrator.saveOffsetForDevice(currentBtSink()?.name, cfg.usingCommMode, offsetMs)
    }

    private fun loadOrProbeOffsetForCurrentConfig() {
        val activeBt = currentBtSink() ?: return
        val cfg = engine.currentActiveSinkConfig()
        val sig = "${activeBt.name}_${cfg.usingCommMode}"
        val saved = calibrator.getSavedOffsetForDevice(activeBt.name, cfg.usingCommMode)
        if (saved != null) {
            lastAutoProbedSignature = sig
            engine.setBtAdvanceMs(saved)
            val chan = if (cfg.usingCommMode) "BT Voice IEM" else "Stereo A2DP"
            _calibrationState.value = CalibrationUiState(
                isBusy = false,
                statusBanner = "Loaded saved $chan offset (${formatSignedMs(saved)}) for ${activeBt.name}.",
                isError = false
            )
        } else if (sig != lastAutoProbedSignature) {
            lastAutoProbedSignature = sig
            runOsTimestampAutoProbe()
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
            val modeLabel = if (res.usedHardwareTimestamps) {
                "OS auto-estimate: ${formatSignedMs(res.deltaMs)} (BT ${res.btLatencyMs} ms vs Aux/Spk ${res.wiredLatencyMs} ms)."
            } else {
                "Applied baseline offset (${formatSignedMs(res.deltaMs)}). Tap 'Mic Chirp Sync' for +/-1 ms precision."
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
            engine.applyHardwareRoutingPolicy(force = false)
            val cfg = engine.currentActiveSinkConfig()
            _calibrationState.value = CalibrationUiState(
                isBusy = true,
                statusBanner = "Hold Aux earpiece/speaker + BT earbud right by the bottom phone mic (playing 2 chirps)..."
            )
            if (cfg.usingCommMode) {
                delay(500L)
            }
            when (val res = calibrator.measureAcousticChirpDeltaMs(cfg, bt.name)) {
                is ChirpCalibrationResult.Success -> {
                    engine.setBtAdvanceMs(res.deltaMs)
                    _calibrationState.value = CalibrationUiState(
                        isBusy = false,
                        statusBanner = "Chirp Sync locked at ${formatSignedMs(res.deltaMs)} for ${bt.name} " +
                            "(SNR ${"%.1f".format(res.wiredSnr)} / ${"%.1f".format(res.btSnr)}).",
                        isError = false
                    )
                }

                is ChirpCalibrationResult.Failure -> {
                    _calibrationState.value = CalibrationUiState(
                        isBusy = false,
                        statusBanner = res.reason,
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
