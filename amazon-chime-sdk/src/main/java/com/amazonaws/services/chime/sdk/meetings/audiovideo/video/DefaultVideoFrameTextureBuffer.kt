package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.graphics.Matrix
import android.opengl.GLES20
import android.os.Handler
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.*
import com.amazonaws.services.chime.sdk.meetings.utils.RefCountDelegate
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import com.xodee.client.video.JniUtil
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import kotlin.math.roundToInt


class DefaultVideoFrameTextureBuffer(
    private val logger: Logger,
    override val width: Int,
    override val height: Int,
    override val textureId: Int,
    override val transformMatrix: Matrix?,
    override val type: VideoFrameTextureBuffer.Type,
    releaseCallback: Runnable,
    override val handler: Handler
) : VideoFrameTextureBuffer {
    private var unscaledWidth: Int = width
    private var unscaledHeight: Int = height

    private val refCountDelegate =
        RefCountDelegate(
            releaseCallback
        )

    private constructor(
        logger: Logger,
        unscaledWidth: Int,
        unscaledHeight: Int,
        width: Int,
        height: Int,
        textureId: Int,
        transformMatrix: Matrix,
        type: VideoFrameTextureBuffer.Type,
        releaseCallback: Runnable,
        handler: Handler
    ) : this(logger, width, height, textureId, transformMatrix, type, releaseCallback, handler) {
        this.unscaledWidth = unscaledWidth
        this.unscaledHeight = unscaledHeight
    }

    override fun retain() {
        refCountDelegate.retain()
    }

    override fun release() {
        refCountDelegate.release()
    }

    /**
     * Create a new TextureBufferImpl with an applied transform matrix and a new size. The
     * existing buffer is unchanged. The given transform matrix is applied first when texture
     * coordinates are still in the unmodified [0, 1] range.
     */
    fun applyTransformMatrix(
        transformMatrix: Matrix, newWidth: Int, newHeight: Int
    ): DefaultVideoFrameTextureBuffer {
        return applyTransformMatrix(
            transformMatrix, /* unscaledWidth= */ newWidth, /* unscaledHeight= */ newHeight,
            /* scaledWidth= */ newWidth, /* scaledHeight= */ newHeight
        )
    }

    private fun applyTransformMatrix(
        transformMatrix: Matrix, unscaledWidth: Int,
        unscaledHeight: Int, scaledWidth: Int, scaledHeight: Int
    ): DefaultVideoFrameTextureBuffer {
        val newMatrix = Matrix(this.transformMatrix)
        newMatrix.preConcat(transformMatrix)
        retain()
        return DefaultVideoFrameTextureBuffer(logger,
            unscaledWidth, unscaledHeight, scaledWidth, scaledHeight,
            textureId, newMatrix, type, Runnable { release() },
            handler
        )
    }
}