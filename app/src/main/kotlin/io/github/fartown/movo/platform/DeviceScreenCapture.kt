package io.github.fartown.movo.platform

import android.graphics.Bitmap

/** Device screenshot route that does not require a root shell. Caller owns the returned bitmap. */
internal interface DeviceScreenCapture {
    val available: Boolean
    /** Yield the assistant before observing either nodes or pixels. */
    fun prepareObservation(): Boolean = true
    /** 这条截图路线自己保证拍不到这些应用的窗口（调用方要求排除它们时，仍可以用这条路线）。 */
    fun excludes(packages: Set<String>): Boolean = false
    fun capture(): Result

    data class Result(val bitmap: Bitmap? = null, val failure: String? = null)
}
