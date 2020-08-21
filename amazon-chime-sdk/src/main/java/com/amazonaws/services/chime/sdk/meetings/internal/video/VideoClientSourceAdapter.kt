package com.amazonaws.services.chime.sdk.meetings.internal.video

import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger

class VideoClientSourceAdapter(
    private val source: com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSource,
    private val logger: Logger
) : com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSink, com.xodee.client.video.VideoSource {
    private var sinks = mutableSetOf<com.xodee.client.video.VideoSink>()

    init {
        source.addSink(this)
    }

    override fun addSink(sink: com.xodee.client.video.VideoSink) {
        sinks.add(sink)
    }

    override fun removeSink(sink: com.xodee.client.video.VideoSink) {
        sinks.remove(sink)
    }

    override fun onFrameCaptured(frame: com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame) {
        var buffer: com.xodee.client.video.VideoFrame.Buffer?
        if (frame.buffer is com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame.TextureBuffer) {
            val textureBuffer = (frame.buffer as com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame.TextureBuffer)
            buffer = VideoFrameTextureBufferAdapter(textureBuffer)
        } else if (frame.buffer is com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame.I420Buffer) {
            val i420Buffer = (frame.buffer as com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame.I420Buffer)
            buffer = VideoFrameI420BufferAdapter(i420Buffer)
        } else {
            buffer = frame.buffer?.let { VideoFrameBufferAdapter(it) }
        }

        val videoClientFrame = com.xodee.client.video.VideoFrame(
            frame.width.toInt(), frame.height.toInt(),
            frame.timestamp, buffer
        )
        for (sink in sinks) {
            sink.onFrameCaptured(videoClientFrame)
        }
    }
}