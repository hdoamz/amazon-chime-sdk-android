package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import android.R.attr.data
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLContext
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger


class DefaultFileCaptureSource(
    private val context: Context,
    private val logger: Logger,
    private var uri: Uri? = null,
    override val contentHint: ContentHint = ContentHint.None,
    sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
) : SurfaceTextureCaptureSource(logger, sharedEGLContext),
    VideoCaptureSource, FileBrowserActivityAdapter.Callback {
    private var mediaPlayer : MediaPlayer? = null

    private val TAG = "DefaultFileCaptureSource"

    override fun start(format: VideoCaptureFormat) {
        super.start(format)

        if (uri == null) {
            FileBrowserActivityAdapter.callback = this
            val startIntent = Intent(context, FileBrowserActivityAdapter::class.java)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(startIntent)
            logger.info(TAG, "Media projection adapter activity started")
            return
        }
        mediaPlayer = MediaPlayer.create(context, uri)
        mediaPlayer?.setSurface(surface)
        mediaPlayer?.start()
    }

    override fun stop() {
        super.stop()
        mediaPlayer?.stop()
    }

    override fun onFileBrowserActivityResult(resultCode: Int, intent: Intent) {
        this.uri = intent.getData()

        uri?.let {
            mediaPlayer = MediaPlayer.create(context, uri)
            mediaPlayer?.setSurface(surface)
            mediaPlayer?.start()
        }
    }
}