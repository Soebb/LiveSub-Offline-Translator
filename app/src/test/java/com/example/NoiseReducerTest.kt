package com.example

import com.example.audio.NoiseReducer
import com.example.audio.NoiseReduction
import com.example.audio.StreamingDenoiser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoiseReducerTest {

    /**
     * Works like the real streaming denoiser: in 256-sample steps, one step late, so it returns
     * less than it is given. [gain] stands in for the cleaning (0 = removes everything).
     */
    private class FakeDenoiser(private val gain: Float = 1f, private val failOnRun: Boolean = false) : StreamingDenoiser {
        private val pending = ArrayList<Float>()
        private var stepsSeen = 0
        var resets = 0
        var released = false

        override fun run(samples: FloatArray): FloatArray {
            if (failOnRun) throw IllegalStateException("model failed")
            pending.addAll(samples.toList())
            val out = ArrayList<Float>()
            while (pending.size - stepsSeen * STEP >= 2 * STEP) {
                for (i in 0 until STEP) out.add(pending[stepsSeen * STEP + i] * gain)
                stepsSeen++
            }
            return out.toFloatArray()
        }

        override fun reset() {
            pending.clear()
            stepsSeen = 0
            resets++
        }

        override fun release() {
            released = true
        }

        companion object {
            const val STEP = 256
        }
    }

    private fun ramp(size: Int, from: Int = 0) = FloatArray(size) { i -> ((from + i) % 1000) / 1000f }

    /** Feeds [input] in uneven chunks, like AudioRecord reads after resampling. */
    private fun feed(reducer: NoiseReducer, input: FloatArray, level: NoiseReduction): FloatArray {
        val chunks = intArrayOf(683, 1, 100, 1365, 7)
        val out = ArrayList<Float>()
        var offset = 0
        var c = 0
        while (offset < input.size) {
            val size = minOf(chunks[c++ % chunks.size], input.size - offset)
            out.addAll(reducer.process(input.copyOfRange(offset, offset + size), level).toList())
            offset += size
        }
        return out.toFloatArray()
    }

    @Test
    fun cleanedAndOriginalLineUpSampleForSample() {
        // A denoiser that changes nothing: mixing it with the held-back original must give the
        // input back exactly, which only happens if the two are aligned
        val reducer = NoiseReducer(FakeDenoiser(gain = 1f))
        val input = ramp(16000)
        val out = feed(reducer, input, NoiseReduction.NORMAL)
        assertTrue("nothing came out", out.size > 15000)
        assertTrue("more out than in", out.size <= input.size)
        for (i in out.indices) assertEquals("sample $i", input[i], out[i], 1e-6f)
    }

    @Test
    fun normalKeepsAQuarterOfTheOriginalAndStrongNone() {
        val input = ramp(8000, from = 1)
        val normal = feed(NoiseReducer(FakeDenoiser(gain = 0f)), input, NoiseReduction.NORMAL)
        for (i in normal.indices) assertEquals(input[i] * 0.25f, normal[i], 1e-6f)

        val strong = feed(NoiseReducer(FakeDenoiser(gain = 0f)), input, NoiseReduction.STRONG)
        assertTrue(strong.isNotEmpty())
        assertTrue(strong.all { it == 0f })
    }

    @Test
    fun offPassesAudioThroughUntouched() {
        val denoiser = FakeDenoiser(gain = 0f)
        val reducer = NoiseReducer(denoiser)
        val input = ramp(500)
        assertSame(input, reducer.process(input, NoiseReduction.OFF))
        assertEquals(null, reducer.takeStats())
    }

    @Test
    fun turningBackOnStartsFresh() {
        val denoiser = FakeDenoiser(gain = 1f)
        val reducer = NoiseReducer(denoiser)
        feed(reducer, ramp(3000), NoiseReduction.NORMAL)
        reducer.process(ramp(400), NoiseReduction.OFF)
        val resetsBefore = denoiser.resets
        // After the switch, output must line up with the new audio, not the old
        val input = ramp(4000, from = 500)
        val out = feed(reducer, input, NoiseReduction.STRONG)
        assertEquals(resetsBefore + 1, denoiser.resets)
        for (i in out.indices) assertEquals("sample $i", input[i], out[i], 1e-6f)
    }

    @Test
    fun aFailingModelFallsBackToTheOriginalAudio() {
        val reducer = NoiseReducer(FakeDenoiser(failOnRun = true))
        val input = ramp(1000)
        assertArrayEquals(input, reducer.process(input, NoiseReduction.NORMAL), 0f)
        // and stays off for the rest of the capture
        val next = ramp(300)
        assertSame(next, reducer.process(next, NoiseReduction.STRONG))
    }

    @Test
    fun statsReportHowMuchQuieterTheAudioGot() {
        val reducer = NoiseReducer(FakeDenoiser(gain = 0f))
        feed(reducer, ramp(16000, from = 1), NoiseReduction.NORMAL)
        val stats = reducer.takeStats()!!
        // A quarter of the original level is 12 dB quieter
        assertEquals(-12.04, stats.reductionDb, 0.05)
        assertTrue(stats.msPerSecond >= 0.0)
        assertEquals(null, reducer.takeStats())
    }

    @Test
    fun releaseFreesTheModel() {
        val denoiser = FakeDenoiser()
        NoiseReducer(denoiser).release()
        assertTrue(denoiser.released)
    }

    @Test
    fun savedCodesMapToLevels() {
        assertEquals(NoiseReduction.OFF, NoiseReduction.fromCode(0))
        assertEquals(NoiseReduction.NORMAL, NoiseReduction.fromCode(1))
        assertEquals(NoiseReduction.STRONG, NoiseReduction.fromCode(2))
        // Unknown codes fall back to the default
        assertEquals(NoiseReduction.DEFAULT, NoiseReduction.fromCode(42))
    }
}
