package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

internal class RefCountDelegate(private val releaseCallback: Runnable) {
    private val refCount: AtomicInteger = AtomicInteger(1)

    fun retain() {
        val updatedCount: Int = refCount.incrementAndGet()
        check(updatedCount >= 2) { "retain() called on an object with refcount < 1" }
    }

    fun release() {
        val updatedCount: Int = refCount.decrementAndGet()
        check(updatedCount >= 0) { "release() called on an object with refcount < 1" }
        if (updatedCount == 0) {
            releaseCallback.run()
        }
    }
}