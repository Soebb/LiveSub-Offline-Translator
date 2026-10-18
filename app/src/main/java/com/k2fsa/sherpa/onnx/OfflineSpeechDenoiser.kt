// Kotlin wrapper for the sherpa-onnx speech denoiser (GTCRN, DPDFNet) and its model settings.
//
// Copied from k2-fsa/sherpa-onnx v1.12.38, sherpa-onnx/kotlin-api/OfflineSpeechDenoiser.kt
// (Apache-2.0, Copyright (c) Xiaomi Corporation). The bundled lib-sherpa-onnx AAR ships the
// native functions (libsherpa-onnx-jni.so, version 1.12.38) but not this Kotlin class. The class,
// field and method names must stay exactly as they are: the native code looks them up by name
// (see sherpa-onnx/jni/speech-denoiser.cc at the same version). The streaming denoiser
// (OnlineSpeechDenoiser.kt) uses the model settings defined here.
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineSpeechDenoiserGtcrnModelConfig(
    var model: String = "",
)

data class OfflineSpeechDenoiserDpdfNetModelConfig(
    var model: String = "",
)

data class OfflineSpeechDenoiserModelConfig(
    var gtcrn: OfflineSpeechDenoiserGtcrnModelConfig = OfflineSpeechDenoiserGtcrnModelConfig(),
    var dpdfnet: OfflineSpeechDenoiserDpdfNetModelConfig = OfflineSpeechDenoiserDpdfNetModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineSpeechDenoiserConfig(
    var model: OfflineSpeechDenoiserModelConfig = OfflineSpeechDenoiserModelConfig(),
)

class OfflineSpeechDenoiser(
    assetManager: AssetManager? = null,
    config: OfflineSpeechDenoiserConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun run(samples: FloatArray, sampleRate: Int) = run(ptr, samples, sampleRate)

    val sampleRate
      get() = getSampleRate(ptr)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineSpeechDenoiserConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineSpeechDenoiserConfig,
    ): Long

    private external fun delete(ptr: Long)

    private external fun run(ptr: Long, samples: FloatArray, sampleRate: Int): DenoisedAudio

    private external fun getSampleRate(ptr: Long): Int

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
