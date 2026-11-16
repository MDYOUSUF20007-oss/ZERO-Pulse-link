package com.zero.pulselink.engine

object NativeAudioEngine {

    init {
        try {
            System.loadLibrary("pulselink_engine")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun initEngine(sampleRate: Int, channels: Int, profileCode: Int): Boolean
    external fun destroyEngine()
    external fun startStream(): Boolean
    external fun stopStream()
    external fun pushAudioPacket(packetArray: ByteArray, length: Int): Boolean
    external fun setVolume(volume: Float)
    external fun setStereoRouting(routingCode: Int)
    external fun setBufferProfile(profileCode: Int)
    external fun getTelemetry(): FloatArray
}
