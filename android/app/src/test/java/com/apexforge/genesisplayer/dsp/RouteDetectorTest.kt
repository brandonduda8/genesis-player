package com.apexforge.genesisplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDetectorTest {
    private fun c(vararg t: Int) = classify(t)

    @Test fun emptyIsDefault() { assertEquals(OutputClass.DEFAULT, c()) }

    @Test fun speakerAlone() {
        assertEquals(OutputClass.PHONE_SPEAKER, c(RouteTypes.BUILTIN_SPEAKER))
        assertEquals(OutputClass.PHONE_SPEAKER, c(RouteTypes.BUILTIN_SPEAKER_SAFE))
    }

    @Test fun wiredTypes() {
        for (t in intArrayOf(RouteTypes.WIRED_HEADPHONES, RouteTypes.WIRED_HEADSET, RouteTypes.USB_HEADSET,
            RouteTypes.USB_DEVICE, RouteTypes.LINE_ANALOG, RouteTypes.LINE_DIGITAL))
            assertEquals("type $t", OutputClass.WIRED, c(t))
    }

    @Test fun bluetoothTypes() {
        for (t in intArrayOf(RouteTypes.BLUETOOTH_A2DP, RouteTypes.BLUETOOTH_SCO,
            RouteTypes.BLE_HEADSET, RouteTypes.BLE_SPEAKER))
            assertEquals("type $t", OutputClass.BLUETOOTH, c(t))
    }

    @Test fun carExternalTypes() {
        for (t in intArrayOf(RouteTypes.HDMI, RouteTypes.HDMI_ARC, RouteTypes.DOCK,
            RouteTypes.AUX_LINE, RouteTypes.BUS))
            assertEquals("type $t", OutputClass.CAR_EXTERNAL, c(t))
    }

    @Test fun unknownAndEarpieceAreDefault() {
        assertEquals(OutputClass.DEFAULT, c(RouteTypes.BUILTIN_EARPIECE))
        assertEquals(OutputClass.DEFAULT, c(RouteTypes.HEARING_AID))
        assertEquals(OutputClass.DEFAULT, c(9999, -1))
    }

    @Test fun priorityWiredBeatsEverything() {
        assertEquals(OutputClass.WIRED, c(RouteTypes.BUILTIN_SPEAKER, RouteTypes.BLUETOOTH_A2DP,
            RouteTypes.HDMI, RouteTypes.WIRED_HEADPHONES))
        assertEquals(OutputClass.WIRED, c(RouteTypes.WIRED_HEADPHONES, RouteTypes.BUILTIN_SPEAKER))
    }

    @Test fun priorityBluetoothBeatsCarAndSpeaker() {
        assertEquals(OutputClass.BLUETOOTH, c(RouteTypes.BUILTIN_SPEAKER, RouteTypes.BUS, RouteTypes.BLUETOOTH_A2DP))
    }

    @Test fun priorityCarBeatsSpeaker() {
        assertEquals(OutputClass.CAR_EXTERNAL, c(RouteTypes.BUILTIN_SPEAKER, RouteTypes.BUS))
    }

    @Test fun orderIndependent() {
        val a = c(RouteTypes.BLUETOOTH_A2DP, RouteTypes.WIRED_HEADSET, RouteTypes.BUILTIN_SPEAKER)
        val b = c(RouteTypes.BUILTIN_SPEAKER, RouteTypes.WIRED_HEADSET, RouteTypes.BLUETOOTH_A2DP)
        assertEquals(a, b)
        assertTrue(a == OutputClass.WIRED)
    }

    @Test fun documentedAndroidConstantValues() {
        // Values from android.media.AudioDeviceInfo (API docs); guards against typos.
        assertEquals(2, RouteTypes.BUILTIN_SPEAKER)
        assertEquals(3, RouteTypes.WIRED_HEADSET)
        assertEquals(4, RouteTypes.WIRED_HEADPHONES)
        assertEquals(7, RouteTypes.BLUETOOTH_SCO)
        assertEquals(8, RouteTypes.BLUETOOTH_A2DP)
        assertEquals(21, RouteTypes.BUS)
        assertEquals(22, RouteTypes.USB_HEADSET)
        assertEquals(26, RouteTypes.BLE_HEADSET)
        assertEquals(27, RouteTypes.BLE_SPEAKER)
    }
}
