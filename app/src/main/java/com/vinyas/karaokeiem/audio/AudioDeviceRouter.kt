package com.vinyas.karaokeiem.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
    val deviceInfo: AudioDeviceInfo,
    val a2dpDeviceInfo: AudioDeviceInfo? = null,
    val commDeviceInfo: AudioDeviceInfo? = null
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

        val commDevices: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val wiredList = mutableListOf<OutputSinkItem>()
        val btA2dpByKey = linkedMapOf<String, AudioDeviceInfo>()
        val btCommByKey = linkedMapOf<String, AudioDeviceInfo>()
        val btDisplayNames = linkedMapOf<String, String>()

        fun normalizeBtKey(dev: AudioDeviceInfo): String {
            val prod = dev.productName?.toString()?.trim().orEmpty()
            return if (prod.isNotEmpty()) prod.lowercase() else "bt_${dev.id}"
        }

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
                    val label = "Aux / 3.5mm Wired"
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
                    wiredList += OutputSinkItem(
                        id = dev.id,
                        name = "Phone Speaker",
                        typeLabel = "Speaker",
                        isBluetooth = false,
                        isWiredHeadsetOrUsb = false,
                        deviceInfo = dev
                    )
                }

                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                27,
                30 -> {
                    val key = normalizeBtKey(dev)
                    btA2dpByKey[key] = dev
                    if (rawProduct.isNotEmpty()) btDisplayNames[key] = rawProduct
                }

                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                26,
                AudioDeviceInfo.TYPE_HEARING_AID -> {
                    val key = normalizeBtKey(dev)
                    val existing = btCommByKey[key]
                    if (existing == null || dev.type == 26) {
                        btCommByKey[key] = dev
                    }
                    if (rawProduct.isNotEmpty()) btDisplayNames[key] = rawProduct
                }
            }
        }

        for (cDev in commDevices) {
            if (isBluetoothDeviceType(cDev.type)) {
                val key = normalizeBtKey(cDev)
                val existing = btCommByKey[key]
                if (existing == null || cDev.type == 26) {
                    btCommByKey[key] = cDev
                }
                val rawProduct = cDev.productName?.toString()?.trim().orEmpty()
                if (rawProduct.isNotEmpty() && key !in btDisplayNames) {
                    btDisplayNames[key] = rawProduct
                }
            }
        }

        val allBtKeys = (btA2dpByKey.keys + btCommByKey.keys).distinct()
        val btList = allBtKeys.map { key ->
            val a2dp = btA2dpByKey[key]
            val comm = btCommByKey[key]
            val primary = a2dp ?: comm!!
            val name = btDisplayNames[key] ?: "Bluetooth Earphones"
            val capabilities = when {
                a2dp != null && comm != null -> "A2DP + IEM Voice"
                a2dp != null -> "Bluetooth A2DP"
                comm?.type == 26 -> "BLE Audio IEM"
                else -> "Bluetooth IEM (SCO)"
            }
            OutputSinkItem(
                id = primary.id,
                name = name,
                typeLabel = capabilities,
                isBluetooth = true,
                isWiredHeadsetOrUsb = false,
                deviceInfo = primary,
                a2dpDeviceInfo = a2dp,
                commDeviceInfo = comm
            )
        }

        wiredList.sortByDescending { it.isWiredHeadsetOrUsb }

        _wiredCandidates.value = wiredList
        _bluetoothCandidates.value = btList
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    companion object {
        fun isBluetoothDeviceType(type: Int): Boolean {
            return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == 26 ||
                type == 27 ||
                type == 30 ||
                type == AudioDeviceInfo.TYPE_HEARING_AID
        }

        fun describeDevice(dev: AudioDeviceInfo?): String {
            if (dev == null) return "Inactive"
            val prod = dev.productName?.toString()?.trim().orEmpty()
            val kind = when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
                26, 27, 30 -> "BT LE Audio"
                AudioDeviceInfo.TYPE_HEARING_AID -> "BT Hearing Aid"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "Aux / 3.5mm"
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-C Audio"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                else -> "Type ${dev.type}"
            }
            return if (prod.isNotEmpty() && !prod.equals(kind, ignoreCase = true)) {
                "$prod ($kind)"
            } else {
                kind
            }
        }
    }
}
