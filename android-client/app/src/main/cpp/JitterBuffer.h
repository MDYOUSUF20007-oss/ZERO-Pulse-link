#ifndef PULSELINK_JITTER_BUFFER_H
#define PULSELINK_JITTER_BUFFER_H

#include <cstdint>
#include <vector>
#include <atomic>
#include <mutex>
#include "LockFreeRingBuffer.h"

namespace pulselink {

enum class BufferProfile {
    UltraLowLatency = 0, // 10ms target
    Balanced = 1,        // 25ms target
    HighStability = 2    // 80ms target
};

class JitterBuffer {
public:
    JitterBuffer(uint32_t sampleRate, int channels, BufferProfile profile);
    ~JitterBuffer() = default;

    void setProfile(BufferProfile profile);
    BufferProfile getProfile() const { return mProfile; }
    float getTargetFillMs() const;

    /**
     * Push incoming network audio frame.
     * Handles sequence numbering, gap detection, and insertion into ring buffer.
     */
    bool pushPacket(uint32_t sequence, uint64_t timestampUs, const int16_t* pcmData, size_t sampleCount);

    /**
     * Pull audio frames for real-time audio callback.
     * Invokes Packet Loss Concealment (PLC) if buffer underflows.
     */
    size_t pullFrames(int16_t* output, size_t frameCount);

    // Telemetry
    float getCurrentFillMs() const;
    float getPacketLossPct();
    uint32_t getUnderrunCount() const { return mUnderrunCount.load(std::memory_order_relaxed); }
    bool isBuffering() const { return mIsBuffering.load(std::memory_order_relaxed); }
    void reset();

private:
    uint32_t mSampleRate;
    int mChannels;
    BufferProfile mProfile;

    LockFreeRingBuffer<int16_t> mRingBuffer;

    uint32_t mExpectedSequence;
    bool mHasFirstPacket;
    std::atomic<bool> mIsBuffering{true};

    // Telemetry counters
    std::atomic<uint32_t> mPacketsReceived{0};
    std::atomic<uint32_t> mPacketsLost{0};
    std::atomic<uint32_t> mUnderrunCount{0};

    // Last known samples for Packet Loss Concealment
    std::vector<int16_t> mLastGoodSamples;

    void generatePlc(int16_t* destination, size_t frameCount);
};

} // namespace pulselink

#endif // PULSELINK_JITTER_BUFFER_H
