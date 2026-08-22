package com.dsann.phonebridge

/** Native diagnostic for determining whether an ordinary APK can see the vendor audio HAL. */
object NativeAudioProbe {
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("phonebridge_native")
        } catch (e: Throwable) {
            loadError = "LOAD_ERROR:${e.javaClass.simpleName}:${e.message ?: ""}"
        }
    }

    @JvmStatic
    external fun probeHal(): String

    fun run(): String = loadError ?: try {
        probeHal()
    } catch (e: Throwable) {
        "NATIVE_PROBE_ERROR:${e.javaClass.simpleName}:${e.message ?: ""}"
    }
}
