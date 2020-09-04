package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.content.Context
import android.opengl.EGLContext
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14

class FileVideoSource(
    private val context: Context,
    private val logger: Logger,
    private val uri: Uri,
    sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
) : SurfaceTextureVideoSource(logger, sharedEGLContext), VideoCaptureSource  {
    private val mediaPlayer = MediaPlayer.create(context, uri)

    init {
        mediaPlayer.setSurface(surface)
    }

    override fun start() {
        mediaPlayer.start()
    }

    override fun stop() {
        mediaPlayer.stop()
    }
}