// Kotlin wrapper for audio returned by the sherpa-onnx speech denoisers.
//
// Copied from k2-fsa/sherpa-onnx v1.12.38, sherpa-onnx/kotlin-api/DenoisedAudio.kt (Apache-2.0,
// Copyright (c) Xiaomi Corporation). The bundled lib-sherpa-onnx AAR ships the native
// functions (libsherpa-onnx-jni.so, version 1.12.38) but not this Kotlin class. The native code
// creates it by name with the (FloatArray, Int) constructor, so names must stay as they are.
package com.k2fsa.sherpa.onnx

class DenoisedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun save(filename: String) =
        saveImpl(filename = filename, samples = samples, sampleRate = sampleRate)

    private external fun saveImpl(
        filename: String,
        samples: FloatArray,
        sampleRate: Int
    ): Boolean
}
