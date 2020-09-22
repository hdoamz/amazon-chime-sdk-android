package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

/**
 * [VideoFrameBuffer] is a buffer which contains a single video frame
 */
interface VideoFrameBuffer {
    /**
     * Width of the video frame buffer
     */
    val width: Int

    /**
     * Height of the video frame buffer
     */
    val height: Int

    /**
     * Convert to planar YUV for consistant support of any CPU encoders or processing
     *
     * @return [VideoFrameI420Buffer?] - Planar YUV buffer, or null if conversion failed
     */
    fun toI420(): VideoFrameI420Buffer?

    /**
     * Crop and scale to a new video frame buffer.  New buffer is not required
     * to be of same type as original buffer
     *
     * @param cropX: [Int] - New origin x-coordinate of cropped frame buffer
     * @param cropY: [Int] - New origin y-coordinate of cropped frame buffer
     * @param cropWidth: [Int] - New width of cropped frame buffer
     * @param cropHeight: [Int] - New height of cropped frame buffer
     * @param scaleWidth: [Int] - Width of scaled original frame (before cropping)
     * @param scaleHeight: [Int] - Height of scaled original frame (before cropping)
     *
     * @return [VideoFrameBuffer?] - Converted buffer, or null if conversion failed
     */
    fun cropAndScale(
        cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int
    ): VideoFrameBuffer?

    /**
     * Retain the video frame buffer.  retain/release may not always be necessary unless the buffer
     * is holding onto resourses that it needs to dispose or release itself
     */
    fun retain()

    /**
     * Retain the video frame buffer.  retain/release may be no-ops unless the buffer
     * is holding onto resourses that it needs to dispose or release itself
     */
    fun release()
}