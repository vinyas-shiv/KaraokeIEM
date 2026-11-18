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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CalibrationUiState(
    val isBusy: Boolean = false,
    val statusBanner: String = "Connect wired headphones + Bluetooth earphones for auto-synced IEM playback.",
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

    private var lastAutoProbedBtId: Int? = null

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

                val activeBt = currentBtSink()
                if (activeBt != null && activeBt.id != lastAutoProbedBtId) {
                    lastAutoProbedBtId = activeBt.id
                    val saved = calibrator.getSavedOffsetForDevice(activeBt.name)
                    if (saved != null) {
                        engine.setBtAdvanceMs(saved)
                        _calibrationState.value = CalibrationUiState(
                            isBusy = false,
                            statusBanner = "Loaded saved chirp profile (${formatSignedMs(saved)}) for ${activeBt.name}.",
                            isError = false
                        )
                    } else {
                        runOsTimestampAutoProbe()
                    }
                }
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
        val bt = currentBtSink()
        val saved = calibrator.getSavedOffsetForDevice(bt?.name)
        if (saved != null && bt != null) {
            engine.setBtAdvanceMs(saved)
            _calibrationState.value = CalibrationUiState(
                statusBanner = "Loaded saved profile (${formatSignedMs(saved)}) for ${bt.name}."
            )
        }
    }

    fun refreshDevices() {
        deviceRouter.refreshDevices()
    }

    fun openMkvFile(uri: Uri) {
        val displayName = resolveDisplayName(uri)
        _loadedFileName.value = displayName
        applySelectedRoutes()
        engine.loadMkvUri(uri)
    }

    fun updateManualOffsetMs(offsetMs: Int) {
        engine.setBtAdvanceMs(offsetMs)
        calibrator.saveOffsetForDevice(currentBtSink()?.name, offsetMs)
    }

    fun runOsTimestampAutoProbe() {
        if (_calibrationState.value.isBusy) return
        val wired = currentWiredSink()
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
            _calibrationState.value = CalibrationUiState(
                isBusy = true,
                statusBanner = "Probing OS / AVDTP hardware timestamps for ${bt.name}..."
            )
            val res = calibrator.probeOsLatencyDeltaMs(wired?.deviceInfo, bt.deviceInfo)
            engine.setBtAdvanceMs(res.deltaMs)
            val modeLabel = if (res.usedHardwareTimestamps) {
                "OS AVDTP auto-estimate: ${formatSignedMs(res.deltaMs)} (BT ${res.btLatencyMs} ms vs Wired ${res.wiredLatencyMs} ms)."
            } else {
                "Applied baseline A2DP offset (${formatSignedMs(res.deltaMs)}). Tap 'Mic Chirp Sync' for +/-1 ms precision."
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
        val wired = currentWiredSink()
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
            _calibrationState.value = CalibrationUiState(
                isBusy = true,
                statusBanner = "Hold wired earpiece + BT earbud right by the phone mic (playing 2 chirps)..."
            )
            when (val res = calibrator.measureAcousticChirpDeltaMs(wired?.deviceInfo, bt.deviceInfo)) {
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
