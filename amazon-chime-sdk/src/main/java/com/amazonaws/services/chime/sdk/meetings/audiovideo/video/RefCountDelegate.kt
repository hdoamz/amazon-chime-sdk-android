package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import java.util.concurrent.atomic.AtomicInteger

internal class RefCountDelegate(private val releaseCallback: Runnable) {
    private val refCount: AtomicInteger = AtomicInteger(1)

    fun retain() {
        val updatedCount: Int = refCount.incrementAndGet()
        check(updatedCount >= 2) { "retain() called on an object with refcount < 1" }
    }

    fun release() {
        val updated_count: Int = refCount.decrementAndGet()
        check(updated_count >= 0) { "release() called on an object with refcount < 1" }
        if (updated_count == 0) {
            releaseCallback.run()
        }
    }
}