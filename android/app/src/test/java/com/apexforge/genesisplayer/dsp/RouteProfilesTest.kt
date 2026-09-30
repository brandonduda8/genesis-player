package com.apexforge.genesisplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProfilesTest {
    private class MemKv : KeyValueStore {
        val m = HashMap<String, String>()
        override fun getString(key: String): String? = m[key]
        override fun putString(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    private fun legal(p: EqParams): Boolean =
        p.bands.size == 8 && p.preampDb in EqParams.PREAMP_MIN_DB..EqParams.PREAMP_MAX_DB &&
            p.bands.all {
                it.gainDb in EqParams.GAIN_MIN_DB..EqParams.GAIN_MAX_DB && it.q in EqParams.Q_MIN..EqParams.Q_MAX &&
                    it.freqHz in EqParams.FREQ_MIN_HZ..EqParams.FREQ_MAX_HZ
            }

    private fun bitEqual(a: EqParams, b: EqParams): Boolean =
        a.preampDb.toBits() == b.preampDb.toBits() && a.bypass == b.bypass &&
            a.limiterEnabled == b.limiterEnabled && a.autoHeadroom == b.autoHeadroom &&
            a.bands.indices.all { i ->
                val x = a.bands[i]; val y = b.bands[i]
                x.type == y.type && x.freqHz.toBits() == y.freqHz.toBits() && x.gainDb.toBits() == y.gainDb.toBits() &&
                    x.q.toBits() == y.q.toBits() && x.enabled == y.enabled
            }

    @Test fun roundTripBitExactAllPresetsAllClasses() {
        for (cls in OutputClass.values()) for (n in DspPresets.names) {
            val kv = MemKv(); val st = RouteProfileStore(kv)
            val p = DspPresets.byName(n)!!.copy(preampDb = -1.2345678f, bypass = n == "FEEL IT")
            st.save(cls, p)
            val r = st.load(cls)
            assertNotNull(r)
            assertTrue("$cls/$n", bitEqual(p, r!!))
            assertTrue(kv.m["dsp_profile_" + cls.name]!!.startsWith("v1|"))
        }
    }

    @Test fun awkwardFloatsRoundTripExactly() {
        val b = EqParams.flatBands().mapIndexed { i, x -> x.copy(gainDb = 0.1f * (i + 1) - 0.37f, freqHz = 123.456789f + i, q = 0.3f + 0.1234567f * i, enabled = i % 2 == 0) }
        val p = EqParams(preampDb = 0.1f, bands = b, limiterEnabled = false, autoHeadroom = false)
        val st = RouteProfileStore(MemKv()); st.save(OutputClass.WIRED, p)
        assertTrue(bitEqual(p, st.load(OutputClass.WIRED)!!))
    }

    @Test fun neverSavedIsNull() {
        val st = RouteProfileStore(MemKv())
        for (c in OutputClass.values()) assertNull(st.load(c))
    }

    @Test fun corruptedStringsReturnNullAndNeverThrow() {
        val good = RouteProfileStore.serialize(DspPresets.byName("FEEL IT")!!)
        val bad = listOf(
            "", "garbage", "v1", "v1|", "v2" + good.substring(2), good.substring(0, good.length / 2),
            good.replace("PEAK", "PEEK"), good.replace("LOW_SHELF", "NOPE"),
            "v1|0|2|1|1|" + good.substringAfterLast('|'), "v1|7fc00000|0|1|1|" + good.substringAfterLast('|'),
            "v1|zz|0|1|1|" + good.substringAfterLast('|'), good + ";PEAK,0,0,0,1",
            "v1|0|0|1|1|" + good.substringAfterLast('|').replace(",", ";"), "\u0000\u0001", "|||||"
        )
        for (s in bad) assertTrue("should be null: $s", RouteProfileStore.parse(s) == null)
        val kv = MemKv(); kv.putString("dsp_profile_WIRED", "v1|junk")
        assertNull(RouteProfileStore(kv).load(OutputClass.WIRED))
    }

    @Test fun nanAndInfinityRejected() {
        val nan = Integer.toHexString(Float.NaN.toBits())
        val inf = Integer.toHexString(Float.POSITIVE_INFINITY.toBits())
        val s = RouteProfileStore.serialize(EqParams())
        val first = s.split('|')[5].split(';')[0].split(',')[2]
        assertNull(RouteProfileStore.parse(s.replaceFirst("," + first + ",", ",$nan,")))
        assertNull(RouteProfileStore.parse(s.replaceFirst("," + first + ",", ",$inf,")))
    }

    @Test fun outOfRangeValuesAreClampedOnLoad() {
        val hex = { f: Float -> Integer.toHexString(f.toBits()) }
        val band = "PEAK,${hex(1e9f)},${hex(99f)},${hex(100f)},1"
        val s = "v1|${hex(50f)}|0|1|1|" + List(8) { band }.joinToString(";")
        val p = RouteProfileStore.parse(s)
        assertNotNull(p)
        assertTrue(legal(p!!))
        assertEquals(EqParams.PREAMP_MAX_DB, p.preampDb, 0f)
        assertEquals(EqParams.GAIN_MAX_DB, p.bands[0].gainDb, 0f)
        assertEquals(EqParams.Q_MAX, p.bands[3].q, 0f)
        assertEquals(EqParams.FREQ_MAX_HZ, p.bands[7].freqHz, 0f)
    }

    @Test fun perClassIndependence() {
        val st = RouteProfileStore(MemKv())
        val bt = DspPresets.byName("Night Drive")!!
        st.save(OutputClass.BLUETOOTH, bt)
        st.save(OutputClass.WIRED, DspPresets.byName("Trap-Rock/Rage")!!)
        assertTrue(bitEqual(bt, st.load(OutputClass.BLUETOOTH)!!))
        st.clear(OutputClass.WIRED)
        assertNull(st.load(OutputClass.WIRED))
        assertTrue(bitEqual(bt, st.load(OutputClass.BLUETOOTH)!!))
        assertNull(st.load(OutputClass.CAR_EXTERNAL))
    }

    @Test fun loadOrDefaultFallbackOrder() {
        val st = RouteProfileStore(MemKv())
        assertTrue(bitEqual(EqParams(), st.loadOrDefault(OutputClass.WIRED)))
        val def = DspPresets.byName("Emo/Vocal")!!
        st.save(OutputClass.DEFAULT, def)
        assertTrue(bitEqual(def, st.loadOrDefault(OutputClass.WIRED)))
        val own = DspPresets.byName("Dark Cinematic")!!
        st.save(OutputClass.WIRED, own)
        assertTrue(bitEqual(own, st.loadOrDefault(OutputClass.WIRED)))
        assertTrue(bitEqual(def, st.loadOrDefault(OutputClass.BLUETOOTH)))
    }

    @Test fun corruptClassProfileFallsBackToDefault() {
        val kv = MemKv(); val st = RouteProfileStore(kv)
        st.save(OutputClass.DEFAULT, DspPresets.byName("FEEL IT")!!)
        kv.putString("dsp_profile_WIRED", "broken")
        assertTrue(bitEqual(DspPresets.byName("FEEL IT")!!, st.loadOrDefault(OutputClass.WIRED)))
    }

    @Test fun speakerCapsSubGain() {
        val p = DspPresets.byName("Trap-Rock/Rage")!!
        val r = RouteLimits.applyRouteLimits(p, OutputClass.PHONE_SPEAKER)
        assertEquals(3f, r.bands[0].gainDb, 0f)   // 55 Hz shelf +9 -> +3
        assertEquals(4f, p.bands[1].gainDb, 0f)
        assertEquals(4f, r.bands[1].gainDb, 0f)   // 100 Hz peak +4 <= cap+3 = 6, unchanged
        for (b in r.bands) if (b.freqHz < 100f && b.type != BandType.HIGH_SHELF) assertTrue(b.gainDb <= 3f)
    }

    @Test fun speakerCapsPunchRegionAtSix() {
        val bands = EqParams.flatBands().toMutableList()
        bands[1] = bands[1].copy(freqHz = 150f, gainDb = 9f)
        bands[2] = bands[2].copy(freqHz = 90f, gainDb = -5f)
        val r = RouteLimits.applyRouteLimits(EqParams(bands = bands), OutputClass.PHONE_SPEAKER)
        assertEquals(6f, r.bands[1].gainDb, 0f)
        assertEquals(-5f, r.bands[2].gainDb, 0f)   // cuts never altered
    }

    @Test fun headphonesAndFullRangeClassesUntouched() {
        val p = DspPresets.byName("Trap-Rock/Rage")!!
        assertEquals(p, RouteLimits.applyRouteLimits(p, OutputClass.WIRED))
        assertEquals(p, RouteLimits.applyRouteLimits(p, OutputClass.DEFAULT))
        assertEquals(9f, RouteLimits.applyRouteLimits(p, OutputClass.BLUETOOTH).bands[0].gainDb, 0f)
        assertEquals(9f, RouteLimits.applyRouteLimits(p, OutputClass.CAR_EXTERNAL).bands[0].gainDb, 0f)
    }

    @Test fun bluetoothAndCarCapsApplyWhenExceeded() {
        val bands = EqParams.flatBands().toMutableList()
        bands[0] = bands[0].copy(freqHz = 50f, gainDb = 12f)
        bands[1] = bands[1].copy(freqHz = 99f, gainDb = 11f)
        val p = EqParams(bands = bands)
        val bt = RouteLimits.applyRouteLimits(p, OutputClass.BLUETOOTH)
        assertEquals(9f, bt.bands[0].gainDb, 0f); assertEquals(9f, bt.bands[1].gainDb, 0f)
        val car = RouteLimits.applyRouteLimits(p, OutputClass.CAR_EXTERNAL)
        assertEquals(10f, car.bands[0].gainDb, 0f); assertEquals(10f, car.bands[1].gainDb, 0f)
        assertEquals(12f, RouteLimits.applyRouteLimits(p, OutputClass.WIRED).bands[0].gainDb, 0f)
    }

    @Test fun everyPresetAndClassStaysLegal() {
        for (cls in OutputClass.values()) for (n in DspPresets.names) {
            val r = RouteLimits.applyRouteLimits(DspPresets.byName(n)!!, cls)
            assertTrue("$cls/$n", legal(r))
            for (i in r.bands.indices) assertTrue(r.bands[i].gainDb <= DspPresets.byName(n)!!.bands[i].gainDb)
        }
    }

    @Test fun highShelfNeverTouched() {
        val r = RouteLimits.applyRouteLimits(DspPresets.byName("Trap-Rock/Rage")!!, OutputClass.PHONE_SPEAKER)
        assertEquals(3f, r.bands[7].gainDb, 0f)
        assertFalse(r.bypass)
    }
}
