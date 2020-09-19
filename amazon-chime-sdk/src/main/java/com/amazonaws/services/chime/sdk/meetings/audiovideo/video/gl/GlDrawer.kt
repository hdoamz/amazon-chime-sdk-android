package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

/**
 * Interface for rendering frames on an EGLSurface with specified viewport location. Rotation,
 * mirror, and cropping is specified using a 4x4 texture coordinate transform matrix. The frame
 * input can either be an OES texture, RGB texture, or YUV textures in I420 format. The function
 * release() must be called manually to free the resources held by this object.
 */
interface GlDrawer {
    /**
     * Functions for drawing frames with different sources. The rendering surface target is
     * implied by the current EGL context of the calling thread and requires no explicit argument.
     * The coordinates specify the viewport location on the surface target.
     */
    fun drawOes(
        oesTextureId: Int, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int,
        viewportX: Int, viewportY: Int, viewportWidth: Int, viewportHeight: Int
    )

    fun drawRgb(
        textureId: Int,
        texMatrix: FloatArray?,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int
    )

    fun drawYuv(
        yuvTextures: IntArray?,
        texMatrix: FloatArray?,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int
    )

    /**
     * Release all GL resources. This needs to be done manually, otherwise resources may leak.
     */
    fun release()
}