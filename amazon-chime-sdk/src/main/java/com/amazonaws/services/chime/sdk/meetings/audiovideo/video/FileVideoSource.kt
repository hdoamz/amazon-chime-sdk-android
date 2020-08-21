package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.content.Context
import android.opengl.EGLContext
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import android.media.MediaPlayer
import android.net.Uri

class FileVideoSource(
    private val context: Context,
    private val logger: Logger,
    private val uri: Uri,
    sharedEGLContext: EGLContext? = null
) : SurfaceTextureVideoSource(logger, sharedEGLContext) {
    private val mediaPlayer = MediaPlayer.create(context, uri)

    init {
        mediaPlayer.setSurface(surface)
    }

    fun start() {
        mediaPlayer.start()
    }
}