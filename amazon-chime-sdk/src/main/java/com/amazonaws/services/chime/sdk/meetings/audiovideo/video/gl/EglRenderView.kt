package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.opengl.EGL14
import android.opengl.EGLContext
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoRenderView
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger

interface EglRenderView :
    VideoRenderView {
    /**
     * To initialize any platform specifc resource for the view For eg. EGL context if used.
     * Should be called when the view is created
     *
     * @param initParams: [Any] - Helper object to pass any data required for initialization
     */
    fun init(logger: Logger, eglContext: EGLContext = EGL14.EGL_NO_CONTEXT)

    /**
     * To cleanup any platform specifc resource For eg. EGL context if used.
     * Should be called when the view is no longer used and needs to be destroyed
     */
    fun dispose()
}