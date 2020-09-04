package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.EGLContext
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import android.content.Intent
import android.opengl.EGL14
import android.util.DisplayMetrics
import android.view.Display


class ScreenCaptureVideoSource(
    private val context: Context,
    private val logger: Logger,
    sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
) : SurfaceTextureVideoSource(logger, sharedEGLContext), MediaProjectionActivityAdapter.Callback, VideoCaptureSource {
    private val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private lateinit var mediaProjection: MediaProjection

    private val TAG = "ScreenCaptureVideoSource"

    override fun start() {
        MediaProjectionActivityAdapter.callback = this
        val startIntent = Intent(context, MediaProjectionActivityAdapter::class.java)
        startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(startIntent)
    }

    override fun stop() {
        return
    }

    override fun onScreenCaptureActivityResult(resultCode: Int, intent: Intent, metrics: DisplayMetrics) {
        if (resultCode == Activity.RESULT_OK) {
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                ?: throw RuntimeException("No display found.")

            // Get the display size and density.
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val screenDensity = metrics.densityDpi

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, intent);
            mediaProjection.createVirtualDisplay("Recording Display", screenWidth,
                screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface,
                null /* callback */, handler /* handler */);
        } else {
            logger.error(TAG, "Failed to start screen capture activity, resultCode: $resultCode")
        }
    }
}