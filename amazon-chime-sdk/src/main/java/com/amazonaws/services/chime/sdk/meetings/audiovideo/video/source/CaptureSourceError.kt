package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

/**
 * [CaptureSourceError] describes an error resulting from a capture source failure
 */
enum class CaptureSourceError(val value: Int) {
    /**
     * Unknown error, and catch-all for errors not otherwise covered
     */
    Unknown(0),

    /**
     * A failure observed from a system API used for capturing
     */
    SystemFailure(1),

    /**
     * A failure observer during configuration
     */
    ConfigurationFailure(2),

    /**
     * An unexpected stop of capturing before stop() is called
     */
    UnexpectedStop(3);

    companion object {
        fun from(intValue: Int): CaptureSourceError? = values().find { it.value == intValue }
    }
}