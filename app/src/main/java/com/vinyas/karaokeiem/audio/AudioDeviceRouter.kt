package com.vinyas.karaokeiem.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OutputSinkItem(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val isBluetooth: Boolean,
    val isWiredHeadsetOrUsb: Boolean,
    val deviceInfo: AudioDeviceInfo
)

class AudioDeviceRouter(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _wiredCandidates = MutableStateFlow<List<OutputSinkItem>>(emptyList())
    val wiredCandidates: StateFlow<List<OutputSinkItem>> = _wiredCandidates.asStateFlow()

    private val _bluetoothCandidates = MutableStateFlow<List<OutputSinkItem>>(emptyList())
    val bluetoothCandidates: StateFlow<List<OutputSinkItem>> = _bluetoothCandidates.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }
    }

    init {
        refreshDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    fun refreshDevices() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }

        val wiredList = mutableListOf<OutputSinkItem>()
        val btList = mutableListOf<OutputSinkItem>()

        for (dev in outputs) {
            val type = dev.type
            val rawProduct = dev.productName?.toString()?.trim().orEmpty()

            when (type) {
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                    val label = "USB-C DAC / Headset"
                    wiredList += OutputSinkItem(
                        id = dev.id,
                        name = rawProduct.ifEmpty { label },
                        typeLabel = label,
                        isBluetooth = false,
                        isWiredHeadsetOrUsb = true,
                        deviceInfo = dev
                    )
                }

                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_AUX_LINE -> {
                    val label = "3.5mm Wired Audio"
                    wiredList += OutputSinkItem(
                        id = dev.id,
                        name = rawProduct.ifEmpty { label },
                        typeLabel = label,
                        isBluetooth = false,
                        isWiredHeadsetOrUsb = true,
                        deviceInfo = dev
                    )
                }

                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC -> {
                    val label = "HDMI Audio Out"
                    wiredList += OutputSinkItem(
                        id = dev.id,
                        name = rawProduct.ifEmpty { label },
                        typeLabel = label,
                        isBluetooth = false,
                        isWiredHeadsetOrUsb = true,
                        deviceInfo = dev
                    )
                }

                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                    val label = "Phone Speaker (Fallback)"
                    wiredList += OutputSinkItem(
                        id = dev.id,
                        name = "Phone Speaker",
                        typeLabel = label,
                        isBluetooth = false,
                        isWiredHeadsetOrUsb = false,
                        deviceInfo = dev
                    )
                }

                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                26,
                27,
                30,
                AudioDeviceInfo.TYPE_HEARING_AID -> {
                    val label = if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        "Bluetooth A2DP IEM"
                    } else {
                        "Bluetooth LE Audio IEM"
                    }
                    btList += OutputSinkItem(
                        id = dev.id,
                        name = rawProduct.ifEmpty { label },
                        typeLabel = label,
                        isBluetooth = true,
                        isWiredHeadsetOrUsb = false,
                        deviceInfo = dev
                    )
                }
            }
        }

        wiredList.sortByDescending { it.isWiredHeadsetOrUsb }

        _wiredCandidates.value = wiredList
        _bluetoothCandidates.value = btList
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }
}
