package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.GlShader

interface ShaderCallbacks {
    /**
     * This callback is called when a new shader has been compiled and created. It will be called
     * for the first frame as well as when the shader type is changed. This callback can be used to
     * do custom initialization of the shader that only needs to happen once.
     */
    fun onNewShader(shader: GlShader?)

    /**
     * This callback is called before rendering a frame. It can be used to do custom preparation of
     * the shader that needs to happen every frame.
     */
    fun onPrepareShader(
        shader: GlShader?,
        texMatrix: FloatArray?,
        frameWidth: Int,
        frameHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int
    )
}