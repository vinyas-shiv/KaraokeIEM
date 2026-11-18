package com.vinyas.karaokeiem

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.vinyas.karaokeiem.audio.AudioTrackOption
import com.vinyas.karaokeiem.audio.OutputSinkItem
import com.vinyas.karaokeiem.audio.SplitRoutingMode
import kotlin.math.roundToInt

private val StageDarkColors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF041E2B),
    primaryContainer = Color(0xFF0C364D),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = Color(0xFFF472B6),
    onSecondary = Color(0xFF3B0822),
    secondaryContainer = Color(0xFF4A1530),
    onSecondaryContainer = Color(0xFFFBCFE8),
    tertiary = Color(0xFF34D399),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF131822),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF1E2636),
    onSurfaceVariant = Color(0xFF9CA3AF)
)

class MainActivity : ComponentActivity() {

    private val viewModel: KaraokeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
            }
        }

        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            intent?.data?.let { uri ->
                viewModel.openMkvFile(uri)
            }
        }

        setContent {
            MaterialTheme(colorScheme = StageDarkColors) {
                var isFullscreen by rememberSaveable { mutableStateOf(false) }

                val openMkvLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        viewModel.openMkvFile(uri)
                    }
                }

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.runAcousticChirpCalibration()
                    }
                }

                KaraokeScreen(
                    viewModel = viewModel,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = {
                        val next = !isFullscreen
                        isFullscreen = next
                        applyFullscreenMode(next)
                    },
                    onPickMkv = {
                        openMkvLauncher.launch(arrayOf("video/x-matroska", "video/*"))
                    },
                    onOpenSystemMediaOutput = {
                        openSystemMediaOutputSwitcher()
                    },
                    onRequestMicChirpCalibration = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            viewModel.runAcousticChirpCalibration()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            viewModel.refreshDevices()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { viewModel.openMkvFile(it) }
        }
    }

    private fun openSystemMediaOutputSwitcher() {
        val triedPanel = runCatching {
            startActivity(Intent("com.android.settings.panel.action.MEDIA_OUTPUT"))
        }.isSuccess
        if (!triedPanel) {
            runCatching {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }
    }

    private fun applyFullscreenMode(fullscreen: Boolean) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KaraokeScreen(
    viewModel: KaraokeViewModel,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPickMkv: () -> Unit,
    onOpenSystemMediaOutput: () -> Unit,
    onRequestMicChirpCalibration: () -> Unit
) {
    val telemetry by viewModel.engine.telemetry.collectAsState()
    val wiredDevices by viewModel.deviceRouter.wiredCandidates.collectAsState()
    val btDevices by viewModel.deviceRouter.bluetoothCandidates.collectAsState()
    val selectedWiredId by viewModel.selectedWiredId.collectAsState()
    val selectedBtId by viewModel.selectedBtId.collectAsState()
    val loadedFileName by viewModel.loadedFileName.collectAsState()
    val calState by viewModel.calibrationState.collectAsState()

    if (isFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            KaraokeVideoStage(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xAA0B0E14))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val badgeText = if (telemetry.isPhaseLocked) {
                    "IEM LOCKED · ${formatSignedMs(telemetry.btAdvanceMs)}"
                } else {
                    "IEM ${formatSignedMs(telemetry.btAdvanceMs)}"
                }
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (telemetry.isPhaseLocked) Color(0xFF34D399) else Color(0xFFBAE6FD)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { viewModel.engine.toggleBtGuideMute() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (telemetry.isBtGuideMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Mute IEM Vocal Guide",
                        tint = if (telemetry.isBtGuideMuted) Color(0xFFF87171) else Color.White
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onToggleFullscreen,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FullscreenExit,
                        contentDescription = "Exit Fullscreen",
                        tint = Color.White
                    )
                }
            }
        }
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Karaoke IEM Stage Player v1.1",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = loadedFileName ?: "No .mkv file loaded yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onPickMkv) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open MKV")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                KaraokeVideoStage(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xB30B0E14))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val syncStatus = when {
                        !telemetry.isPlaying -> "READY · ${formatSignedMs(telemetry.btAdvanceMs)}"
                        telemetry.isPhaseLocked -> "LOCKED (${telemetry.syncErrorMs} ms)"
                        else -> "SYNCING (${telemetry.syncErrorMs} ms)"
                    }
                    Text(
                        text = syncStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (telemetry.isPhaseLocked) Color(0xFF34D399) else Color(0xFF38BDF8)
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Fullscreen Lyrics",
                            tint = Color.White
                        )
                    }
                }
            }

            TransportBarCard(
                isPlaying = telemetry.isPlaying,
                positionMs = telemetry.positionMs,
                durationMs = telemetry.durationMs,
                onTogglePlay = { viewModel.engine.togglePlayPause() },
                onSeekTo = { viewModel.engine.seekTo(it) },
                onNudge = { viewModel.engine.nudgePosition(it) }
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hardware Split Engine",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (telemetry.audioTracks.size >= 2) {
                                AssistChip(
                                    onClick = { viewModel.engine.swapAudioTracks() },
                                    label = { Text("Swap Tracks") },
                                    leadingIcon = {
                                        Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            IconButton(onClick = { viewModel.refreshDevices() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh Audio Devices")
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SplitRoutingMode.entries.forEach { mode ->
                            FilterChip(
                                selected = telemetry.splitMode == mode,
                                onClick = { viewModel.selectSplitMode(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }

                    Text(
                        text = telemetry.activeSplitSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (telemetry.isSplitVerified) {
                            Color(0xFF34D399)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    if (telemetry.needsSystemMediaSwitchHint) {
                        OutlinedButton(
                            onClick = onOpenSystemMediaOutput,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Android locked A2DP to Aux/Speaker — Tap to select BT in Media Output")
                        }
                    }
                }
            }

            SinkChannelCard(
                title = "Wired / Speaker · Instrumental (PA / Backing)",
                accentColor = MaterialTheme.colorScheme.primary,
                leadingIcon = Icons.Default.Headphones,
                actualOsRoute = telemetry.wiredActualRoute,
                devices = wiredDevices,
                selectedDeviceId = selectedWiredId,
                emptyDeviceHint = "Plug in Aux / USB-C cable or use Phone Speaker",
                onSelectDevice = { viewModel.selectWiredSink(it) },
                tracks = telemetry.audioTracks,
                selectedTrackKey = telemetry.selectedWiredTrackKey,
                onSelectTrack = { viewModel.engine.selectWiredTrack(it) },
                volume = telemetry.wiredVolume,
                isMuted = false,
                onVolumeChange = { viewModel.engine.setWiredVolume(it) },
                onToggleMute = null
            )

            SinkChannelCard(
                title = "Bluetooth IEM · Vocal Guide (In-Ear Monitor)",
                accentColor = MaterialTheme.colorScheme.secondary,
                leadingIcon = Icons.Default.BluetoothAudio,
                actualOsRoute = telemetry.btActualRoute,
                devices = btDevices,
                selectedDeviceId = selectedBtId,
                emptyDeviceHint = "Connect Bluetooth earphones",
                onSelectDevice = { viewModel.selectBtSink(it) },
                tracks = telemetry.audioTracks,
                selectedTrackKey = telemetry.selectedBtTrackKey,
                onSelectTrack = { viewModel.engine.selectBtTrack(it) },
                volume = telemetry.btVolume,
                isMuted = telemetry.isBtGuideMuted,
                onVolumeChange = { viewModel.engine.setBtVolume(it) },
                onToggleMute = { viewModel.engine.toggleBtGuideMute() }
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Bluetooth IEM Delay Compensation",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Advances BT vocal track so IEM, Aux/Speaker & lyrics hit together",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatSignedMs(telemetry.btAdvanceMs),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = telemetry.btAdvanceMs.toFloat(),
                        onValueChange = { viewModel.updateManualOffsetMs(it.roundToInt()) },
                        valueRange = -100f..500f
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(-15, -5, +5, +15).forEach { step ->
                            OutlinedButton(
                                onClick = { viewModel.updateManualOffsetMs(telemetry.btAdvanceMs + step) }
                            ) {
                                Text(formatSignedMs(step))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.runOsTimestampAutoProbe() },
                            enabled = !calState.isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OS Auto-Detect")
                        }

                        Button(
                            onClick = onRequestMicChirpCalibration,
                            enabled = !calState.isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (calState.isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                            } else {
                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Mic Chirp Sync")
                        }
                    }

                    Text(
                        text = calState.statusBanner,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (calState.isError) {
                            Color(0xFFF87171)
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun KaraokeVideoStage(
    viewModel: KaraokeViewModel,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
                player = viewModel.engine.wiredPlayer
            }
        },
        update = { view ->
            if (view.player !== viewModel.engine.wiredPlayer) {
                view.player = viewModel.engine.wiredPlayer
            }
        }
    )
}

@Composable
private fun TransportBarCard(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNudge: (Long) -> Unit
) {
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var isScrubbing by remember { mutableStateOf(false) }

    val progress = if (isScrubbing) {
        scrubFraction
    } else if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Slider(
                value = progress,
                onValueChange = {
                    isScrubbing = true
                    scrubFraction = it
                },
                onValueChangeFinished = {
                    if (durationMs > 0L) {
                        onSeekTo((scrubFraction * durationMs).toLong())
                    }
                    isScrubbing = false
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatClock(positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onNudge(-10_000L) }) {
                        Icon(Icons.Default.Replay10, contentDescription = "Back 10s")
                    }
                    FilledTonalButton(onClick = onTogglePlay) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play or Pause"
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPlaying) "Pause" else "Play")
                    }
                    IconButton(onClick = { onNudge(10_000L) }) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s")
                    }
                }
                Text(
                    text = formatClock(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SinkChannelCard(
    title: String,
    accentColor: Color,
    leadingIcon: ImageVector,
    actualOsRoute: String,
    devices: List<OutputSinkItem>,
    selectedDeviceId: Int?,
    emptyDeviceHint: String,
    onSelectDevice: (Int) -> Unit,
    tracks: List<AudioTrackOption>,
    selectedTrackKey: String?,
    onSelectTrack: (String) -> Unit,
    volume: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: (() -> Unit)?
) {
    var trackDropdownOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(leadingIcon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                }
                Text(
                    text = "OS: $actualOsRoute",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (devices.isEmpty()) {
                Text(
                    text = emptyDeviceHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFBBF24)
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    devices.forEach { dev ->
                        FilterChip(
                            selected = dev.id == selectedDeviceId,
                            onClick = { onSelectDevice(dev.id) },
                            label = { Text("${dev.name} (${dev.typeLabel})") }
                        )
                    }
                }
            }

            Box {
                val activeTrack = tracks.find { it.key == selectedTrackKey }
                val labelText = when {
                    tracks.isEmpty() -> "Audio Track: Load an .mkv file to choose stream"
                    activeTrack != null -> "${activeTrack.displayTitle}  ${activeTrack.details}".trim()
                    else -> "Select MKV Audio Stream"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = tracks.isNotEmpty()) { trackDropdownOpen = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (tracks.isNotEmpty()) {
                        Text(
                            text = "Change",
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor
                        )
                    }
                }

                DropdownMenu(
                    expanded = trackDropdownOpen,
                    onDismissRequest = { trackDropdownOpen = false }
                ) {
                    tracks.forEach { opt ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(opt.displayTitle, fontWeight = FontWeight.Medium)
                                    if (opt.details.isNotEmpty()) {
                                        Text(
                                            opt.details,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelectTrack(opt.key)
                                trackDropdownOpen = false
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onToggleMute != null) {
                    IconButton(onClick = onToggleMute) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = "Mute Vocal Guide",
                            tint = if (isMuted) Color(0xFFF87171) else accentColor
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                Slider(
                    value = if (isMuted) 0f else volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isMuted) "MUTED" else "${(volume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(50.dp)
                )
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%02d:%02d".format(min, sec)
}

private fun formatSignedMs(ms: Int): String = if (ms >= 0) "+${ms} ms" else "${ms} ms"
