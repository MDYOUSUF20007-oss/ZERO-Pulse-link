#include "PulseLinkEngine.h"
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <algorithm>

#define TAG "PulseLinkEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pulselink {

static aaudio_data_callback_result_t aaudioDataCallback(
    AAudioStream* stream,
    void* userData,
    void* audioData,
    int32_t numFrames) {
    auto* engine = static_cast<PulseLinkEngine*>(userData);
    return engine->onAudioCallback(stream, audioData, numFrames);
}

PulseLinkEngine::PulseLinkEngine()
    : mSampleRate(48000),
      mChannels(2),
      mAudioStream(nullptr) {}

PulseLinkEngine::~PulseLinkEngine() {
    destroy();
}

bool PulseLinkEngine::init(uint32_t sampleRate, int channels, BufferProfile profile) {
    mSampleRate = sampleRate;
    mChannels = channels;
    mPendingFrames = 0;
    mResamplePhase = 0.0;
    mPeakLeft.store(0.0f, std::memory_order_relaxed);
    mPeakRight.store(0.0f, std::memory_order_relaxed);

    mJitterBuffer = std::make_unique<JitterBuffer>(sampleRate, channels, profile);
    mResampler = std::make_unique<PiResampler>(mJitterBuffer->getTargetFillMs());

    // Pre-allocate generous scratch buffer (8192 frames) so audio callback never allocates
    mResampleInputBuffer.assign(8192 * channels, 0);
    mResampleOutputBuffer.assign(8192 * channels, 0);

    return true;
}

bool PulseLinkEngine::start() {
    if (mAudioStream != nullptr) {
        return true;
    }

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("Failed to create AAudio stream builder: %s", AAudio_convertResultToText(result));
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, mChannels);
    AAudioStreamBuilder_setSampleRate(builder, mSampleRate);
    AAudioStreamBuilder_setDataCallback(builder, aaudioDataCallback, this);

    result = AAudioStreamBuilder_openStream(builder, &mAudioStream);
    if (result != AAUDIO_OK) {
        // Fallback to SHARED sharing mode if EXCLUSIVE is denied by OEM ROM
        LOGI("Exclusive mode unavailable, falling back to Shared mode: %s", AAudio_convertResultToText(result));
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        result = AAudioStreamBuilder_openStream(builder, &mAudioStream);
    }
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK || mAudioStream == nullptr) {
        LOGE("Failed to open AAudio stream: %s", AAudio_convertResultToText(result));
        return false;
    }

    result = AAudioStream_requestStart(mAudioStream);
    if (result != AAUDIO_OK) {
        LOGE("Failed to start AAudio stream: %s", AAudio_convertResultToText(result));
        AAudioStream_close(mAudioStream);
        mAudioStream = nullptr;
        return false;
    }

    int32_t actualRate = AAudioStream_getSampleRate(mAudioStream);
    if (actualRate > 0) {
        mSampleRate = static_cast<uint32_t>(actualRate);
    }

    mIsPlaying.store(true, std::memory_order_relaxed);
    LOGI("PulseLink AAudio Low-Latency Engine started at %u Hz, %d channels", mSampleRate, mChannels);
    return true;
}

void PulseLinkEngine::stop() {
    mIsPlaying.store(false, std::memory_order_relaxed);
    if (mAudioStream != nullptr) {
        AAudioStream_requestStop(mAudioStream);
        AAudioStream_close(mAudioStream);
        mAudioStream = nullptr;
    }
}

void PulseLinkEngine::destroy() {
    stop();
    mJitterBuffer.reset();
    mResampler.reset();
}

bool PulseLinkEngine::pushAudioPacket(const uint8_t* data, size_t length) {
    if (length < 16 || mJitterBuffer == nullptr) {
        return false;
    }

    // Check Magic: 'Z', 'P' (0x5A, 0x50)
    if (data[0] != 0x5A || data[1] != 0x50) {
        return false;
    }

    // Sequence number (Little-endian at offset 4)
    uint32_t sequence = static_cast<uint32_t>(data[4]) |
                       (static_cast<uint32_t>(data[5]) << 8) |
                       (static_cast<uint32_t>(data[6]) << 16) |
                       (static_cast<uint32_t>(data[7]) << 24);

    // Timestamp microseconds (Little-endian at offset 8)
    uint64_t timestampUs = 0;
    for (int i = 0; i < 8; ++i) {
        timestampUs |= (static_cast<uint64_t>(data[8 + i]) << (i * 8));
    }

    const size_t payloadBytes = length - 16;
    const size_t sampleCount = payloadBytes / sizeof(int16_t);
    const auto* pcmData = reinterpret_cast<const int16_t*>(data + 16);

    return mJitterBuffer->pushPacket(sequence, timestampUs, pcmData, sampleCount);
}

void PulseLinkEngine::setVolume(float volume) {
    mVolume.store(volume, std::memory_order_relaxed);
}

void PulseLinkEngine::setStereoRouting(StereoRouting routing) {
    mRouting.store(routing, std::memory_order_relaxed);
}

void PulseLinkEngine::setBufferProfile(BufferProfile profile) {
    if (mJitterBuffer) {
        mJitterBuffer->setProfile(profile);
        if (mResampler) {
            mResampler->setTargetFillMs(mJitterBuffer->getTargetFillMs());
        }
    }
}

void PulseLinkEngine::getTelemetry(float* outTelemetry) {
    if (!mJitterBuffer || !mResampler) {
        std::fill_n(outTelemetry, 7, 0.0f);
        return;
    }

    outTelemetry[0] = 0.0f; // RTT ms (measured via control socket)
    outTelemetry[1] = mJitterBuffer->getPacketLossPct();
    outTelemetry[2] = mJitterBuffer->getCurrentFillMs();
    outTelemetry[3] = static_cast<float>(mJitterBuffer->getUnderrunCount());
    outTelemetry[4] = mResampler->updateRatio(mJitterBuffer->getCurrentFillMs(), 0.0f);
    outTelemetry[5] = mPeakLeft.load(std::memory_order_relaxed);
    outTelemetry[6] = mPeakRight.load(std::memory_order_relaxed);
}

aaudio_data_callback_result_t PulseLinkEngine::onAudioCallback(
    AAudioStream* /*stream*/,
    void* audioData,
    int32_t numFrames) {

    auto* outPcm = static_cast<int16_t*>(audioData);
    if (!mIsPlaying.load(std::memory_order_relaxed) || !mJitterBuffer || !mResampler || numFrames <= 0) {
        std::memset(outPcm, 0, numFrames * mChannels * sizeof(int16_t));
        mPeakLeft.store(0.0f, std::memory_order_relaxed);
        mPeakRight.store(0.0f, std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // 1. Update PI Resampler Clock Drift Controller
    const float currentFillMs = mJitterBuffer->getCurrentFillMs();
    const float dtSeconds = static_cast<float>(numFrames) / static_cast<float>(mSampleRate);
    const float ratio = mJitterBuffer->isBuffering() ? 1.0f : mResampler->updateRatio(currentFillMs, dtSeconds);
    const double step = static_cast<double>(ratio);

    // 2. Pull exact number of input frames required for glitch-free linear interpolation
    const size_t maxIdx = static_cast<size_t>(std::floor(mResamplePhase + static_cast<double>(numFrames - 1) * step)) + 1;
    const size_t totalNeeded = maxIdx + 1;

    if (totalNeeded * mChannels > mResampleInputBuffer.size()) {
        // Safety guard: never allocate on real-time audio thread
        std::memset(outPcm, 0, numFrames * mChannels * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    if (totalNeeded > mPendingFrames) {
        const size_t framesToPull = totalNeeded - mPendingFrames;
        mJitterBuffer->pullFrames(mResampleInputBuffer.data() + mPendingFrames * mChannels, framesToPull);
        mPendingFrames = totalNeeded;
    }

    // 3. Sample-accurate linear interpolation across sliding input window
    for (int32_t f = 0; f < numFrames; ++f) {
        const size_t idx0 = static_cast<size_t>(mResamplePhase);
        const size_t idx1 = idx0 + 1;
        const float frac = static_cast<float>(mResamplePhase - static_cast<double>(idx0));

        for (int ch = 0; ch < mChannels; ++ch) {
            const float s0 = static_cast<float>(mResampleInputBuffer[idx0 * mChannels + ch]);
            const float s1 = static_cast<float>(mResampleInputBuffer[idx1 * mChannels + ch]);
            const float interp = s0 + frac * (s1 - s0);
            outPcm[f * mChannels + ch] = static_cast<int16_t>(std::clamp(interp, -32768.0f, 32767.0f));
        }

        mResamplePhase += step;
    }

    // Shift leftover unconsumed frames to front of sliding window
    size_t consumed = static_cast<size_t>(std::floor(mResamplePhase));
    if (consumed > mPendingFrames) {
        consumed = mPendingFrames;
    }
    mResamplePhase -= static_cast<double>(consumed);
    const size_t remaining = mPendingFrames - consumed;
    if (remaining > 0 && consumed > 0) {
        std::memmove(
            mResampleInputBuffer.data(),
            mResampleInputBuffer.data() + consumed * mChannels,
            remaining * mChannels * sizeof(int16_t)
        );
    }
    mPendingFrames = remaining;

    // 4. Apply Channel Routing
    StereoRouting route = mRouting.load(std::memory_order_relaxed);
    if (mChannels >= 2) {
        if (route == StereoRouting::LeftOnly) {
            for (int32_t i = 0; i < numFrames; ++i) {
                outPcm[i * 2 + 1] = outPcm[i * 2];
            }
        } else if (route == StereoRouting::RightOnly) {
            for (int32_t i = 0; i < numFrames; ++i) {
                outPcm[i * 2] = outPcm[i * 2 + 1];
            }
        } else if (route == StereoRouting::MonoDownmix) {
            for (int32_t i = 0; i < numFrames; ++i) {
                int16_t mono = static_cast<int16_t>((static_cast<int32_t>(outPcm[i * 2]) + static_cast<int32_t>(outPcm[i * 2 + 1])) / 2);
                outPcm[i * 2] = mono;
                outPcm[i * 2 + 1] = mono;
            }
        }
    }

    // 5. Apply Volume Boost & Soft-Clipping Limiter
    float vol = mVolume.load(std::memory_order_relaxed);
    applyVolumeAndLimiter(outPcm, numFrames * mChannels, vol);

    // 6. Compute real-time peak VU meter levels
    float peakL = 0.0f;
    float peakR = 0.0f;
    if (mChannels >= 2) {
        for (int32_t i = 0; i < numFrames; ++i) {
            float l = std::abs(static_cast<float>(outPcm[i * 2])) / 32768.0f;
            float r = std::abs(static_cast<float>(outPcm[i * 2 + 1])) / 32768.0f;
            if (l > peakL) peakL = l;
            if (r > peakR) peakR = r;
        }
    } else {
        for (int32_t i = 0; i < numFrames; ++i) {
            float v = std::abs(static_cast<float>(outPcm[i])) / 32768.0f;
            if (v > peakL) { peakL = v; peakR = v; }
        }
    }
    mPeakLeft.store(peakL, std::memory_order_relaxed);
    mPeakRight.store(peakR, std::memory_order_relaxed);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

float PulseLinkEngine::softClip(float sample) {
    if (sample > 1.0f) {
        return 1.0f + std::tanh(sample - 1.0f) * 0.2f;
    } else if (sample < -1.0f) {
        return -1.0f + std::tanh(sample + 1.0f) * 0.2f;
    } else {
        return sample; // 100% Bit-perfect lossless transparency when within standard range
    }
}

void PulseLinkEngine::applyVolumeAndLimiter(int16_t* samples, size_t sampleCount, float vol) {
    if (vol <= 0.001f) {
        std::memset(samples, 0, sampleCount * sizeof(int16_t));
        return;
    }

    // Convert linear volume slider (0.0 to 2.0) into gain multiplier
    float gain;
    if (vol <= 1.0f) {
        gain = std::pow(vol, 2.5f); // Logarithmic natural dB response
    } else {
        gain = 1.0f + (vol - 1.0f) * 4.6234f; // Up to +15 dB boost (5.62x)
    }

    if (std::abs(gain - 1.0f) < 0.001f) {
        return;
    }

    for (size_t i = 0; i < sampleCount; ++i) {
        float normalized = (static_cast<float>(samples[i]) / 32768.0f) * gain;
        float clipped = softClip(normalized);
        samples[i] = static_cast<int16_t>(std::clamp(clipped * 32767.0f, -32768.0f, 32767.0f));
    }
}

} // namespace pulselink
