package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.EGLContext
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import android.content.Intent
import android.graphics.Point
import android.opengl.EGL14
import android.util.DisplayMetrics
import android.util.Size
import android.view.Display
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame


class DefaultScreenCaptureSource(
    private val context: Context,
    private val logger: Logger,
    sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
) : SurfaceTextureCaptureSource(logger, sharedEGLContext),
    MediaProjectionActivityAdapter.Callback,
    VideoCaptureSource {
    private val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private lateinit var mediaProjection: MediaProjection

    override val contentHint = ContentHint.Detail

    private val TAG = "DefaultScreenCaptureSource"

    override fun start(format: VideoCaptureFormat) {
        format.width = 1080
        format.height = 2280
        super.start(format)

        MediaProjectionActivityAdapter.callback = this
        val startIntent = Intent(context, MediaProjectionActivityAdapter::class.java)
        startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(startIntent)
        logger.info(TAG, "Media projection adapter activity started")
    }

    override fun stop() {
        super.stop()
        return
    }

    override fun onScreenCaptureActivityResult(resultCode: Int, intent: Intent, metrics: DisplayMetrics) {
        if (resultCode == Activity.RESULT_OK) {
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: throw RuntimeException("No display found.")

            val size = Point()
            display.getRealSize(size)
            // Get the display size and density.
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val screenDensity = metrics.densityDpi

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, intent);
            mediaProjection.createVirtualDisplay("Screen Capture Display", 1080,
                2280, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface,
                null /* callback */, handler);
            logger.info(TAG, "Media projection adapter activity succeeded, virtual display created")
        } else {
            logger.error(TAG, "Failed to start screen capture activity, resultCode: $resultCode")
        }
    }
}