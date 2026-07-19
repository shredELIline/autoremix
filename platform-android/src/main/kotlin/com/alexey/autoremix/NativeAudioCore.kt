package com.alexey.autoremix

internal object NativeAudioCore {
    fun available(): Boolean = NativeAudioEngine.isAvailable()
}
