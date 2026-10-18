// Kotlin wrapper for the sherpa-onnx streaming speech denoiser (cleans audio chunk by chunk).
//
// Copied from k2-fsa/sherpa-onnx v1.12.38, sherpa-onnx/kotlin-api/OnlineSpeechDenoiser.kt
// (Apache-2.0, Copyright (c) Xiaomi Corporation). The bundled lib-sherpa-onnx AAR ships the
// native functions (libsherpa-onnx-jni.so, version 1.12.38) but not this Kotlin class. The class,
// field and method names must stay exactly as they are: the native code looks them up by name
// (see sherpa-onnx/jni/online-speech-denoiser.cc at the same version).
// One change from upstream: a model the native code refuses (it returns 0) throws here, instead
// of crashing later on the first run() with an empty pointer.
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OnlineSpeechDenoiserConfig(
    var model: OfflineSpeechDenoiserModelConfig = OfflineSpeechDenoiserModelConfig(),
)

class OnlineSpeechDenoiser(
    assetManager: AssetManager? = null,
    config: OnlineSpeechDenoiserConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        if (ptr == 0L) throw IllegalStateException("The speech denoiser model could not be loaded")
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun run(samples: FloatArray, sampleRate: Int) = run(ptr, samples, sampleRate)

    fun flush() = flush(ptr)

    fun reset() = reset(ptr)

    val sampleRate
      get() = getSampleRate(ptr)

    val frameShiftInSamples
      get() = getFrameShiftInSamples(ptr)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OnlineSpeechDenoiserConfig,
    ): Long

    private external fun newFromFile(
        config: OnlineSpeechDenoiserConfig,
    ): Long

    private external fun delete(ptr: Long)

    private external fun run(ptr: Long, samples: FloatArray, sampleRate: Int): DenoisedAudio

    private external fun flush(ptr: Long): DenoisedAudio

    private external fun reset(ptr: Long)

    private external fun getSampleRate(ptr: Long): Int

    private external fun getFrameShiftInSamples(ptr: Long): Int

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
