package com.example.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import java.io.File

/** How much background noise to remove before speech recognition (saved as its [code]). */
enum class NoiseReduction(val code: Int, val label: String) {
    OFF(0, "Off"),
    NORMAL(1, "Normal"),
    STRONG(2, "Strong");

    /** Share of the original (noisy) audio mixed back into the cleaned audio. */
    val originalShare: Float
        get() = when (this) {
            OFF -> 1f
            // Noise about 12 dB quieter, and the model's small distortions are masked
            NORMAL -> 0.25f
            STRONG -> 0f
        }

    companion object {
        val DEFAULT = NORMAL

        fun fromCode(code: Int): NoiseReduction = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/**
 * Cleans 16 kHz audio piece by piece. It works in fixed steps, so it may return a little less
 * than it was given (the rest comes out with the next call), but never more in total.
 */
interface StreamingDenoiser {
    fun run(samples: FloatArray): FloatArray
    fun reset()
    fun release()
}

/**
 * The first step of the audio clean-up, before the rumble filter, the quiet-voice boost and
 * speech recognition: a small speech-enhancement model removes background noise (crowd, street,
 * fans, hiss, and some music), so the speech detector and the recognizer hear the voice rather
 * than the noise. The boost comes after it, so it lifts the voice, not the noise.
 *
 * Speech recognizers are hurt more by the model's small distortions than by leftover noise, so
 * [NoiseReduction.NORMAL] mixes a share of the original back in ("observation adding"), which
 * masks them. [NoiseReduction.STRONG] uses only the cleaned audio.
 *
 * The cleaned audio comes out one model step late; the original is held back by the same
 * amount, so the two are mixed sample for sample. Must be used from one thread.
 */
class NoiseReducer(private val denoiser: StreamingDenoiser) {

    // Original samples waiting for their cleaned counterpart (a small ring buffer)
    private var held = FloatArray(8192)
    private var heldStart = 0
    private var heldCount = 0

    private var bypassed = false
    private var failed = false

    // For the input-level log: loudness before and after, and the time spent
    private var statOriginalSq = 0.0
    private var statOutputSq = 0.0
    private var statSamples = 0L
    private var statNanos = 0L

    /** Level change ([reductionDb], negative = quieter) and time cost since the last call. */
    data class Stats(val reductionDb: Double, val msPerSecond: Double)

    /**
     * Returns the cleaned audio ready so far for [input]. With [NoiseReduction.OFF], or after
     * the model failed, the input is returned unchanged.
     */
    fun process(input: FloatArray, level: NoiseReduction): FloatArray {
        if (failed || level == NoiseReduction.OFF) {
            bypassed = true
            return input
        }
        if (bypassed) {
            // Coming back on: start clean, so no stale audio is mixed with the new
            reset()
            bypassed = false
        }
        if (input.isEmpty()) return input

        val started = System.nanoTime()
        val cleaned = try {
            denoiser.run(input)
        } catch (t: Throwable) {
            Log.e(TAG, "Noise removal failed; continuing without it", t)
            failed = true
            return input
        }
        hold(input)

        val share = level.originalShare
        val output = FloatArray(cleaned.size)
        for (i in cleaned.indices) {
            // Never more cleaned samples than given in total; if it ever happens, use them as they are
            val original = if (heldCount > 0) takeHeld() else cleaned[i]
            val mixed = cleaned[i] * (1f - share) + original * share
            output[i] = mixed
            statOriginalSq += original * original
            statOutputSq += mixed * mixed
        }
        statSamples += output.size
        statNanos += System.nanoTime() - started
        return output
    }

    /** Stats since the last call, or null if nothing was cleaned. */
    fun takeStats(): Stats? {
        if (statSamples == 0L) return null
        val reductionDb = when {
            statOriginalSq <= 0.0 -> 0.0
            statOutputSq <= 0.0 -> -120.0
            else -> 10 * kotlin.math.log10(statOutputSq / statOriginalSq)
        }
        val audioSeconds = statSamples / SAMPLE_RATE.toDouble()
        val stats = Stats(reductionDb, statNanos / 1e6 / audioSeconds)
        statOriginalSq = 0.0
        statOutputSq = 0.0
        statSamples = 0L
        statNanos = 0L
        return stats
    }

    fun reset() {
        try {
            denoiser.reset()
        } catch (t: Throwable) {
            Log.e(TAG, "Noise removal reset failed; continuing without it", t)
            failed = true
        }
        heldStart = 0
        heldCount = 0
    }

    fun release() {
        try {
            denoiser.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not release the noise model: ${t.message}")
        }
    }

    private fun hold(samples: FloatArray) {
        if (heldCount + samples.size > held.size) {
            var size = held.size
            while (heldCount + samples.size > size) size *= 2
            val bigger = FloatArray(size)
            for (i in 0 until heldCount) bigger[i] = held[(heldStart + i) % held.size]
            held = bigger
            heldStart = 0
        }
        for (sample in samples) {
            held[(heldStart + heldCount) % held.size] = sample
            heldCount++
        }
    }

    private fun takeHeld(): Float {
        val sample = held[heldStart]
        heldStart = (heldStart + 1) % held.size
        heldCount--
        return sample
    }

    companion object {
        private const val TAG = "NoiseReducer"
        const val SAMPLE_RATE = 16000

        /**
         * Loads the GTCRN noise model (models/_denoiser/gtcrn_simple.onnx). Throws if it can't be
         * loaded. A damaged file can abort the app in native code, so callers use the model
         * manager's crash guard around this.
         */
        fun loadGtcrn(modelFile: File): NoiseReducer = NoiseReducer(SherpaGtcrnDenoiser(modelFile))
    }
}

/** [StreamingDenoiser] backed by the sherpa-onnx streaming denoiser with a GTCRN model. */
private class SherpaGtcrnDenoiser(modelFile: File) : StreamingDenoiser {
    private val denoiser = OnlineSpeechDenoiser(
        config = OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = modelFile.absolutePath),
                // A small model: one core is plenty, and leaves the rest to speech recognition
                numThreads = 1
            )
        )
    )

    init {
        // The output must have the input's sample rate, or the original could not be mixed back in
        if (denoiser.sampleRate != NoiseReducer.SAMPLE_RATE) {
            val rate = denoiser.sampleRate
            denoiser.release()
            throw IllegalStateException("Noise model works at $rate Hz, expected ${NoiseReducer.SAMPLE_RATE} Hz")
        }
    }

    override fun run(samples: FloatArray): FloatArray = denoiser.run(samples, NoiseReducer.SAMPLE_RATE).samples

    override fun reset() = denoiser.reset()

    override fun release() = denoiser.release()
}
