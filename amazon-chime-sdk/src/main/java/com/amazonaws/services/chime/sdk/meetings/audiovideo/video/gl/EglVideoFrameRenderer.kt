package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.view.Surface
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger

interface EglVideoFrameRenderer {
    /**
     * Initialize this class, sharing resources with |sharedContext|. The custom |drawer| will be used
     * for drawing frames on the EGLSurface. This class is responsible for calling release() on
     * |drawer|. It is allowed to call init() to reinitialize the renderer after a previous
     * init()/release() cycle. If usePresentationTimeStamp is true, eglPresentationTimeANDROID will be
     * set with the frame timestamps, which specifies desired presentation time and might be useful
     * for e.g. syncing audio and video.
     */
    fun init(
        eglContext: EGLContext,
        logger: Logger
    )

    fun createEglSurface(surface: Surface)
    fun createEglSurface(surfaceTexture: SurfaceTexture)
    fun createEglSurfaceInternal(surface: Any)
    fun releaseEglSurface()
    fun render(frame: VideoFrame)
    fun setLayoutAspectRatio(layoutAspectRatio: Float)

    /**
     * Block until any pending frame is returned and all GL resources released, even if an interrupt
     * occurs. If an interrupt occurs during release(), the interrupt flag will be set. This function
     * should be called before the Activity is destroyed and the EGLContext is still valid. If you
     * don't call this function, the GL resources might leak.
     */
    fun release()
}