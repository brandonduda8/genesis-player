package com.apexforge.genesisplayer.dsp

/*
 * WO-AURUM-008 route classification. PURE Kotlin (no Android imports) so it is
 * unit-testable on a plain JVM. The int values below are the documented
 * android.media.AudioDeviceInfo.TYPE_* constants; RouteDetector (Android glue,
 * audio/ package) feeds AudioDeviceInfo.getType() values into [classify].
 */
object RouteTypes {
    const val BUILTIN_EARPIECE = 1
    const val BUILTIN_SPEAKER = 2
    const val WIRED_HEADSET = 3
    const val WIRED_HEADPHONES = 4
    const val LINE_ANALOG = 5
    const val LINE_DIGITAL = 6
    const val BLUETOOTH_SCO = 7
    const val BLUETOOTH_A2DP = 8
    const val HDMI = 9
    const val HDMI_ARC = 10
    const val USB_DEVICE = 11
    const val USB_ACCESSORY = 12
    const val DOCK = 13
    const val AUX_LINE = 19
    const val BUS = 21
    const val USB_HEADSET = 22
    const val HEARING_AID = 23
    const val BUILTIN_SPEAKER_SAFE = 24
    /** API 31+ constants; values are fixed, so using the ints is safe on API 26. */
    const val BLE_HEADSET = 26
    const val BLE_SPEAKER = 27
    const val HDMI_EARC = 29
}

object RouteClassifier {
    /** Class of a single device type; null when the type maps to DEFAULT. */
    fun classOfType(type: Int): OutputClass? = when (type) {
        RouteTypes.WIRED_HEADPHONES, RouteTypes.WIRED_HEADSET, RouteTypes.USB_HEADSET,
        RouteTypes.USB_DEVICE, RouteTypes.LINE_ANALOG, RouteTypes.LINE_DIGITAL -> OutputClass.WIRED
        RouteTypes.BLUETOOTH_A2DP, RouteTypes.BLUETOOTH_SCO,
        RouteTypes.BLE_HEADSET, RouteTypes.BLE_SPEAKER -> OutputClass.BLUETOOTH
        RouteTypes.HDMI, RouteTypes.HDMI_ARC, RouteTypes.HDMI_EARC,
        RouteTypes.DOCK, RouteTypes.AUX_LINE, RouteTypes.BUS -> OutputClass.CAR_EXTERNAL
        RouteTypes.BUILTIN_SPEAKER, RouteTypes.BUILTIN_SPEAKER_SAFE -> OutputClass.PHONE_SPEAKER
        else -> null
    }

    /**
     * Priority when several outputs are connected: wired > bluetooth > car/external > phone
     * speaker. Earpiece, hearing aid, unknown types and an empty set give DEFAULT.
     */
    fun classify(types: IntArray): OutputClass {
        var wired = false; var bt = false; var car = false; var spk = false
        for (t in types) {
            when (classOfType(t)) {
                OutputClass.WIRED -> wired = true
                OutputClass.BLUETOOTH -> bt = true
                OutputClass.CAR_EXTERNAL -> car = true
                OutputClass.PHONE_SPEAKER -> spk = true
                else -> {}
            }
        }
        return when {
            wired -> OutputClass.WIRED
            bt -> OutputClass.BLUETOOTH
            car -> OutputClass.CAR_EXTERNAL
            spk -> OutputClass.PHONE_SPEAKER
            else -> OutputClass.DEFAULT
        }
    }
}

/** Top-level alias matching the work order's `fun classify(types: IntArray): OutputClass`. */
fun classify(types: IntArray): OutputClass = RouteClassifier.classify(types)
