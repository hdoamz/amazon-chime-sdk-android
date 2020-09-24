package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

interface VideoCaptureSource : VideoSource {
    /**
     * Start capturing on this source and emitting video frames
     */
    fun start(format: VideoCaptureFormat)

    /**
     * Stop capturing on this source and cease emitting video frames
     */
    fun stop()

    /**
     * Add a capture source observer to receive callbacks from the source
     *
     * @param observer: [CaptureSourceObserver] - New observer
     */
    fun addCaptureSourceObserver(observer: CaptureSourceObserver)

    /**
     * Remove a capture source observer
     *
     * @param observer: [CaptureSourceObserver] - Observer to remove
     */
    fun removeCaptureSourceObserver(observer: CaptureSourceObserver)
}