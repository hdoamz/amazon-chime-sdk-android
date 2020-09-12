package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger

class SurfaceViewSink(
    context: Context,
    private val logger: Logger,
    sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
): SurfaceView(context), SurfaceHolder.Callback, VideoSink {

    private val TAG = "SurfaceViewSink"

    init {
        holder.addCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        logger.info(TAG, "Surface changed: format:$format, dimensions:${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        logger.info(TAG, "Surface destroyed")
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        logger.info(TAG, "Surface created")
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        logger.info(TAG, "Frame captured")
    }
}