package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.opengl.GLES20

/**
 * Helper class for handling OpenGL framebuffer with only color attachment and no depth or stencil
 * buffer. Intended for simple tasks such as texture copy, texture downscaling, and texture color
 * conversion. This class is not thread safe and must be used by a thread with an active GL context.
 */
class GlFrameBuffer(pixelFormat: Int) {
    private var pixelFormat = 0

    /** Gets the OpenGL frame buffer id. This value is only valid after setSize() has been called.  */
    var frameBufferId = 0
        private set

    /** Gets the OpenGL texture id. This value is only valid after setSize() has been called.  */
    var textureId = 0
        private set
    var width: Int
        private set
    var height: Int
        private set

    /**
     * (Re)allocate texture. Will do nothing if the requested size equals the current size. An
     * EGLContext must be bound on the current thread when calling this function. Must be called at
     * least once before using the framebuffer. May be called multiple times to change size.
     */
    fun setSize(width: Int, height: Int) {
        require(!(width <= 0 || height <= 0)) { "Invalid size: " + width + "x" + height }
        if (width == this.width && height == this.height) {
            return
        }
        this.width = width
        this.height = height
        // Lazy allocation the first time setSize() is called.
        if (textureId == 0) {
            textureId =
                GlUtil.generateTexture(
                    GLES20.GL_TEXTURE_2D
                )
        }
        if (frameBufferId == 0) {
            val frameBuffers = IntArray(1)
            GLES20.glGenFramebuffers(1, frameBuffers, 0)
            frameBufferId = frameBuffers[0]
        }

        // Allocate texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, pixelFormat, width, height, 0, pixelFormat,
            GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GlUtil.checkGlError("GlTextureFrameBuffer setSize")

        // Attach the texture to the framebuffer as color attachment.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textureId, 0
        )

        // Check that the framebuffer is in a good state.
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer not complete, status: $status" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Release texture and framebuffer. An EGLContext must be bound on the current thread when calling
     * this function. This object should not be used after this call.
     */
    fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
        GLES20.glDeleteFramebuffers(1, intArrayOf(frameBufferId), 0)
        frameBufferId = 0
        width = 0
        height = 0
    }

    /**
     * Generate texture and framebuffer resources. An EGLContext must be bound on the current thread
     * when calling this function. The framebuffer is not complete until setSize() is called.
     */
    init {
        when (pixelFormat) {
            GLES20.GL_LUMINANCE, GLES20.GL_RGB, GLES20.GL_RGBA -> this.pixelFormat = pixelFormat
            else -> throw IllegalArgumentException("Invalid pixel format: $pixelFormat")
        }
        width = 0
        height = 0
    }
}
