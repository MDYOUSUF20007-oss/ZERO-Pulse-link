#include "JitterBuffer.h"
#include <cmath>
#include <algorithm>

namespace pulselink {

JitterBuffer::JitterBuffer(uint32_t sampleRate, int channels, BufferProfile profile)
    : mSampleRate(sampleRate),
      mChannels(channels),
      mProfile(profile),
      // 500ms max buffer capacity
      mRingBuffer((sampleRate * channels * 500) / 1000),
      mExpectedSequence(0),
      mHasFirstPacket(false) {
    mLastGoodSamples.resize(channels, 0);
}

void JitterBuffer::setProfile(BufferProfile profile) {
    mProfile = profile;
}

float JitterBuffer::getTargetFillMs() const {
    switch (mProfile) {
        case BufferProfile::UltraLowLatency: return 10.0f;
        case BufferProfile::Balanced: return 25.0f;
        case BufferProfile::HighStability: return 80.0f;
    }
    return 25.0f;
}

bool JitterBuffer::pushPacket(uint32_t sequence, uint64_t /*timestampUs*/,
                              const int16_t* pcmData, size_t sampleCount) {
    mPacketsReceived.fetch_add(1, std::memory_order_relaxed);

    if (!mHasFirstPacket) {
        mExpectedSequence = sequence;
        mHasFirstPacket = true;
    } else {
        int32_t diff = static_cast<int32_t>(sequence - mExpectedSequence);
        if (diff < 0) {
            // Late out-of-order packet arriving after sequence has already advanced:
            // Discard to prevent audible backwards time glitching
            return false;
        }
        if (diff > 1) {
            // Gap detected: increment lost packet count
            mPacketsLost.fetch_add(diff - 1, std::memory_order_relaxed);
        }
        mExpectedSequence = sequence + 1;
    }

    // Cache the last frame for Packet Loss Concealment
    if (sampleCount >= static_cast<size_t>(mChannels)) {
        for (int ch = 0; ch < mChannels; ++ch) {
            mLastGoodSamples[ch] = pcmData[sampleCount - mChannels + ch];
        }
    }

    size_t written = mRingBuffer.write(pcmData, sampleCount);

    // If pre-buffering, check if target fill level is reached
    if (mIsBuffering.load(std::memory_order_relaxed)) {
        if (getCurrentFillMs() >= getTargetFillMs()) {
            mIsBuffering.store(false, std::memory_order_release);
        }
    }

    return written == sampleCount;
}

size_t JitterBuffer::pullFrames(int16_t* output, size_t frameCount) {
    const size_t requestedSamples = frameCount * mChannels;

    // During pre-buffering (startup or after deep underrun), output silence
    // to allow the jitter buffer to accumulate target fill level
    if (mIsBuffering.load(std::memory_order_acquire)) {
        std::memset(output, 0, requestedSamples * sizeof(int16_t));
        return frameCount;
    }

    size_t readSamples = mRingBuffer.read(output, requestedSamples);

    if (readSamples < requestedSamples) {
        // Buffer underrun! Apply Dynamic Packet Loss Concealment (PLC)
        mUnderrunCount.fetch_add(1, std::memory_order_relaxed);
        size_t missingSamples = requestedSamples - readSamples;
        generatePlc(output + readSamples, missingSamples / mChannels);

        // If buffer completely emptied, re-enter buffering to prevent continuous micro-stutter
        if (readSamples == 0) {
            mIsBuffering.store(true, std::memory_order_release);
        }
    }

    return frameCount;
}

void JitterBuffer::generatePlc(int16_t* destination, size_t frameCount) {
    // Cosine crossfade decay from last good sample toward digital zero
    constexpr float PI = 3.14159265358979323846f;
    const size_t fadeFrames = std::min(frameCount, static_cast<size_t>(128));

    for (size_t f = 0; f < frameCount; ++f) {
        float gain = 0.0f;
        if (f < fadeFrames) {
            gain = 0.5f * (1.0f + std::cos((static_cast<float>(f) / fadeFrames) * PI));
        }

        for (int ch = 0; ch < mChannels; ++ch) {
            float val = static_cast<float>(mLastGoodSamples[ch]) * gain;
            destination[f * mChannels + ch] = static_cast<int16_t>(std::clamp(val, -32768.0f, 32767.0f));
        }
    }

    // Once faded to zero, clear last good samples to prevent repetitive pulse clicks
    for (int ch = 0; ch < mChannels; ++ch) {
        mLastGoodSamples[ch] = 0;
    }
}

float JitterBuffer::getCurrentFillMs() const {
    size_t availableSamples = mRingBuffer.getAvailableRead();
    size_t availableFrames = availableSamples / mChannels;
    return (static_cast<float>(availableFrames) * 1000.0f) / static_cast<float>(mSampleRate);
}

float JitterBuffer::getPacketLossPct() {
    uint32_t received = mPacketsReceived.load(std::memory_order_relaxed);
    uint32_t lost = mPacketsLost.load(std::memory_order_relaxed);
    uint32_t total = received + lost;
    if (total == 0) return 0.0f;
    return (static_cast<float>(lost) * 100.0f) / static_cast<float>(total);
}

void JitterBuffer::reset() {
    mRingBuffer.reset();
    mHasFirstPacket = false;
    mIsBuffering.store(true, std::memory_order_release);
    mPacketsReceived.store(0, std::memory_order_relaxed);
    mPacketsLost.store(0, std::memory_order_relaxed);
    mUnderrunCount.store(0, std::memory_order_relaxed);
    std::fill(mLastGoodSamples.begin(), mLastGoodSamples.end(), 0);
}

} // namespace pulselink
