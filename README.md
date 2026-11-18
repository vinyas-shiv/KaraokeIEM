# KaraokeIEM

Android dual-output `.mkv` karaoke player with wired instrumental routing, Bluetooth vocal IEM, and automatic chirp latency sync.

## Features

### 1. Dual-Output `.mkv` Karaoke Playback
- **`wiredPlayer`**: Renders rolling lyrics video and routes the instrumental audio track over **USB-C / 3.5mm wired audio** for your room speakers or mixer.
- **`btPlayer`**: Routes the vocal guide track to your **Bluetooth A2DP / LE Audio** earbuds or in-ear monitor (IEM) independently using `setPreferredAudioDevice`.

### 2. Click-Free 50 ms Phase-Locked Loop (`DualSinkPlayerEngine.kt`)
- Keeps the wired instrumental and Bluetooth vocal guide players synchronized without audible clicks or pops using a continuous **50 ms Phase-Locked Loop (PLL)** drift-correction loop.

### 3. Two-Stage Automatic Bluetooth Latency Calibration (`LatencyAutoCalibrator.kt` & `ChirpDsp.kt`)
- **Stage 1 — `OS Auto-Detect`**: Queries Android `AudioTimestamp` metrics to estimate the baseline Bluetooth output pipeline delay.
- **Stage 2 — `Mic Chirp Sync`**: Plays orthogonal Linear FM (chirp) sweeps over the wired and Bluetooth outputs and captures them via the device microphone, using **Radix-2 FFT Hilbert matched filtering** in `ChirpDsp.kt` to measure the exact acoustic offset between the two paths.

## Quick Singing Setup

1. **Connect Audio Outputs**:
   - Plug in your **USB-C or 3.5mm wired audio** connection (speakers / mixer).
   - Pair and connect your **Bluetooth earbuds / IEM**.
2. **Calibrate Bluetooth Latency**:
   - Run **OS Auto-Detect** for an instant baseline estimate, or run **Mic Chirp Sync** in a quiet room to measure the exact acoustic offset.
3. **Load a Dual-Track `.mkv` Karaoke File**:
   - Select your `.mkv` file, assign the instrumental track to `wiredPlayer` and the vocal guide track to `btPlayer`, and start singing with rolling lyrics on screen.

## Download

Pre-built signed APKs are available on the **Releases** page of this repository.
