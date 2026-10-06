package io.github.fartown.movo.platform

import android.graphics.Bitmap

/** Device screenshot route that does not require a root shell. Caller owns the returned bitmap. */
internal interface DeviceScreenCapture {
    val available: Boolean
    /** Yield the assistant before observing either nodes or pixels. */
    fun prepareObservation(): Boolean = true
    fun capture(): Result

    data class Result(val bitmap: Bitmap? = null, val failure: String? = null)
}
