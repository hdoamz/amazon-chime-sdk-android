package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import android.util.Rational
import kotlin.math.abs

data class VideoCaptureFormat(
    /**
     * Width of video capture
     */
    val width: Int,

    /**
     * Height of video capture
     */
    val height: Int,

    /**
     * Max framerate of video capture.  This should also be the target framerate in most implementations which take this class.
     */
    val maxFramerate: Int
) {
    override fun toString(): String {
        return "$width x $height @ $maxFramerate FPS"

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

