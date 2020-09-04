package com.amazonaws.services.chime.sdk.meetings.device

import android.util.Rational
import kotlin.math.abs

data class VideoCaptureFormat(
    var width: Int,
    var height: Int,
    var maxFps: Int
) {
    override fun toString(): String {
        return "$width x $height @ $maxFps FPS"

    }

    companion object {
        private val aspectRatioMaxDiff = 0.05
        fun filterToAspectRatio(formats: List<VideoCaptureFormat>, aspectRatio: Rational): List<VideoCaptureFormat> {
            return formats.filter {format ->
                return@filter abs((format.width.toDouble() / format.height) - aspectRatio.toDouble()) <= aspectRatioMaxDiff
            }
        }
    }
}

