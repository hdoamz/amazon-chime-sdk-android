package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

/**
 * [VideoPauseState] describes the pause status of a video tile.
 */
enum class ContentHint(val value: Int) {
    /**
     * The video tile is not paused
     */
    None(0),

    /**
     * The video tile is not paused
     */
    Motion(1),

    /**
     * The video tile has been paused by the user, and will only be unpaused if the user requests it to resume.
     */
    Detail(2),

    /**
     * The video tile has been paused to save on local downlink bandwidth.  When the connection improves,
     * it will be automatically unpaused by the client.  User requested pauses will shadow this pause,
     * but if the connection has not recovered on resume the tile will still be paused with this state.
     */
    Text(3);

    companion object {
        fun from(intValue: Int): ContentHint? = values().find { it.value == intValue }
    }
}