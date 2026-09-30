package com.apexforge.genesisplayer.dsp

import com.apexforge.genesisplayer.dsp.DspTestKit.FS
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO-AURUM-008 preset tests: response checkpoints, stability, clipping before/after protection, evidence dump. */
class PresetsTest {
    private val names = DspPresets.names
    private val checkpoints = doubleArrayOf(30.0, 45.0, 60.0, 80.0, 120.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 8000.0, 12000.0, 16000.0)

    private fun eq(p: EqParams) = ParametricEq(FS, 1).also { it.setParams(p); it.reset() }

    private fun stress(): List<Pair<String, FloatArray>> {
        val n = FS * 2
        val l = ArrayList<Pair<String, FloatArray>>()
        for (f in listOf(30.0, 45.0, 60.0, 80.0, 120.0, 250.0, 3000.0, 12000.0)) l.add("sine ${f.toInt()} Hz @0 dBFS" to DspTestKit.sine(n, f, 1.0))
        l.add("swept-sum @0 dBFS" to DspTestKit.sweptSum(n))
        l.add("pink @0 dBFS" to DspTestKit.normalizeToPeak(DspTestKit.pink(n, 7L), 1.0f))
        return l
    }

    private fun peakOf(p: EqParams, x: FloatArray): Double {
        val e = eq(p)
        val y = DspTestKit.run(e, x)
        assertTrue(DspTestKit.allFinite(y))
        return DspTestKit.maxAbs(y).toDouble()
    }

    @Test fun exactlySixPresetsWithExpectedNames() {
        assertEquals(listOf("Reference", "FEEL IT", "Night Drive", "Emo/Vocal", "Trap-Rock/Rage", "Dark Cinematic"), names)
    }

    @Test fun referenceIsTrulyFlat() {
        val p = DspPresets.byName("Reference")!!
        assertEquals(EqParams(), p)
        for (f in checkpoints) assertEquals(0.0, eq(p).responseDb(f), 1e-9)
    }

    @Test fun everyPresetHasFiniteStableImpulseResponse() {
        val imp = FloatArray(FS) ; imp[0] = 0.5f
        for (n in names) {
            val y = DspTestKit.run(eq(DspPresets.byName(n)!!), imp)
            assertTrue("$n finite", DspTestKit.allFinite(y))
            val tail = DspTestKit.maxAbs(y, FS - 4800, FS)
            assertTrue("$n tail decays ($tail)", tail < 1e-4f)
        }
    }

    @Test fun responseCheckpointsMatchMeasuredSineGain() {
        for (n in names) {
            val p = DspPresets.byName(n)!!.copy(limiterEnabled = false)
            for (f in doubleArrayOf(60.0, 250.0, 1000.0, 5000.0, 12000.0)) {
                val e = eq(p)
                val measured = DspTestKit.measuredGainDb(e, f, amp = 0.05)
                val predicted = e.responseDb(f)
                assertEquals("$n @$f", predicted, measured, 0.15)
            }
        }
    }

    @Test fun presetIntentShowsInTheResponse() {
        // shape only: auto-headroom preamp is a separate, flat offset (asserted in HeadroomTest)
        fun r(n: String, f: Double) = eq(DspPresets.byName(n)!!.copy(autoHeadroom = false, limiterEnabled = false)).responseDb(f)
        // FEEL IT: sub and punch up, mud down
        assertTrue(r("FEEL IT", 60.0) > r("FEEL IT", 1000.0) + 3.0)
        assertTrue(r("FEEL IT", 120.0) > 2.0)
        assertTrue(r("FEEL IT", 250.0) < 0.0)
        // Emo/Vocal: presence up, harshness down
        assertTrue(r("Emo/Vocal", 2200.0) > 2.0)
        assertTrue(r("Emo/Vocal", 7000.0) < -1.5)
        // Trap-Rock/Rage has the most sub of all presets
        for (n in names) if (n != "Trap-Rock/Rage") assertTrue(r("Trap-Rock/Rage", 55.0) >= r(n, 55.0))
        // Dark Cinematic: treble down
        assertTrue(r("Dark Cinematic", 10000.0) < -2.0)
        // Night Drive: top end tamed
        assertTrue(r("Night Drive", 7000.0) < -1.0)
    }

    @Test fun noPresetClipsAnyStressSignalWithProtection() {
        var worst = 0.0
        for (n in names) for ((label, x) in stress()) {
            val pk = peakOf(DspPresets.byName(n)!!, x)
            if (pk > worst) worst = pk
            assertTrue("$n / $label peak=$pk", pk <= 1.0 + 1e-6)
        }
        println("PRESETS worst protected peak over all presets x stress signals = $worst")
    }

    @Test fun beforeAfterClippingEvidence() {
        var unprotectedClips = 0
        for (n in names) {
            val base = DspPresets.byName(n)!!
            val off = base.copy(limiterEnabled = false, autoHeadroom = false)
            var worstOff = 0.0; var worstOn = 0.0
            for ((_, x) in stress()) {
                worstOff = maxOf(worstOff, peakOf(off, x)); worstOn = maxOf(worstOn, peakOf(base, x))
            }
            println("CLIP %-15s unprotected peak=%.3f (%.2f dBFS)  protected peak=%.3f (%.2f dBFS)".format(
                n, worstOff, 20 * Math.log10(worstOff), worstOn, 20 * Math.log10(worstOn)))
            if (worstOff > 1.0) unprotectedClips++
            assertTrue(worstOn <= 1.0 + 1e-6)
        }
        // the boosted presets must demonstrate the problem the protection solves
        assertTrue("expected unprotected clipping on boosted presets, got $unprotectedClips", unprotectedClips >= 4)
    }

    @Test fun dumpEvidenceWhenRequested() {
        val dir = System.getenv("DSP_EVIDENCE_DIR") ?: return
        File(dir).mkdirs()
        val pts = 96
        val freqs = DoubleArray(pts) { exp(ln(20.0) + (ln(20000.0) - ln(20.0)) * it / (pts - 1)) }
        File(dir, "preset_response.csv").printWriter().use { w ->
            w.println("freq_hz," + names.joinToString(",") { "\"$it\"" })
            for (f in freqs) w.println("%.3f,".format(f) + names.joinToString(",") { "%.4f".format(eq(DspPresets.byName(it)!!.copy(limiterEnabled = false, autoHeadroom = false)).responseDb(f)) })
        }
        File(dir, "preset_clipping.csv").printWriter().use { w ->
            w.println("preset,unprotected_peak,protected_peak,effective_preamp_db,worst_case_boost_db")
            for (n in names) {
                val base = DspPresets.byName(n)!!
                var off = 0.0; var on = 0.0
                for ((_, x) in stress()) { off = maxOf(off, peakOf(base.copy(limiterEnabled = false, autoHeadroom = false), x)); on = maxOf(on, peakOf(base, x)) }
                val e = eq(base)
                w.println("\"$n\",%.5f,%.5f,%.3f,%.3f".format(off, on, e.effectivePreampDb, Headroom.worstCaseBoostDb(base, FS.toDouble())))
            }
        }
    }
}
