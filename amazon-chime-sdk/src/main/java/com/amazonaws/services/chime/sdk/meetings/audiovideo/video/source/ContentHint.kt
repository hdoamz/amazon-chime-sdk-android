package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

/**
 * [ContentHint] describes the content type of a video source so that downstream encoders, etc. can properly
 * decide on what parameters will work best.
 */
enum class ContentHint(val value: Int) {
    /**
     * No hint has been provided.
     */
    None(0),

    /**
     * The track should be treated as if it contains video where motion is important.
     */
    Motion(1),

    /**
     * The track should be treated as if video details are extra important.
     */
    Detail(2),

    /**
     * The track should be treated as if video details are extra important, and that
     * significant sharp edges and areas of consistent color can occur frequently.
     */
    Text(3);

    companion object {
        fun from(intValue: Int): ContentHint? = values().find { it.value == intValue }
    }
}