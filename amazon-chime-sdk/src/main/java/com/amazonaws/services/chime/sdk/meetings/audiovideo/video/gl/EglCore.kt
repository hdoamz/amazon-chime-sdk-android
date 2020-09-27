package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.opengl.EGLContext
import android.opengl.EGLSurface

interface EglCore {
    // Public so it can be reused
    var eglContext: EGLContext?

    /**
     * Discards all resources held by this class, notably the EGL context.  This must be
     * called from the thread where the context was created.
     *
     * On completion, no context will be current.
     */
    fun release()
    fun finalize()
    fun createPbufferSurface(width: Int, height: Int)

    /**
     * Creates an EGL surface associated with a Surface.
     *
     *
     * If this is destined for MediaCodec, the EGLConfig should have the "recordable" attribute.
     */
    fun createWindowSurface(surface: Any): EGLSurface?

    /**
     * Creates an EGL surface associated with an offscreen buffer.
     */
    fun createOffscreenSurface(width: Int, height: Int): EGLSurface?

    /**
     * Makes our EGL context current, using the supplied surface for both "draw" and "read".
     */
    fun makeCurrent()

    /**
     * Makes no context current.
     */
    fun makeNothingCurrent()

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     *
     * @return false on failure
     */
    fun swapBuffers(): Boolean

    /**
     * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
     */
    fun setPresentationTime(nsecs: Long)
    fun hasSurface(): Boolean
    fun surfaceWidth(): Int
    fun surfaceHeight(): Int

    /**
     * Destroys the specified surface.  Note the EGLSurface won't actually be destroyed if it's
     * still current in a context.
     */
    fun releaseSurface()
}