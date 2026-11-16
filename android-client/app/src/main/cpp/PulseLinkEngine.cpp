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

    mJitterBuffer = std::make_unique<JitterBuffer>(sampleRate, channels, profile);
    mResampler = std::make_unique<PiResampler>(mJitterBuffer->getTargetFillMs());

    mResampleInputBuffer.resize(2048 * channels, 0);
    mResampleOutputBuffer.resize(2048 * channels, 0);

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
        std::fill_n(outTelemetry, 5, 0.0f);
        return;
    }

    outTelemetry[0] = 0.0f; // RTT ms (measured via control socket)
    outTelemetry[1] = mJitterBuffer->getPacketLossPct();
    outTelemetry[2] = mJitterBuffer->getCurrentFillMs();
    outTelemetry[3] = static_cast<float>(mJitterBuffer->getUnderrunCount());
    outTelemetry[4] = mResampler->updateRatio(mJitterBuffer->getCurrentFillMs(), 0.0f);
}

aaudio_data_callback_result_t PulseLinkEngine::onAudioCallback(
    AAudioStream* /*stream*/,
    void* audioData,
    int32_t numFrames) {

    auto* outPcm = static_cast<int16_t*>(audioData);
    if (!mIsPlaying.load(std::memory_order_relaxed) || !mJitterBuffer || !mResampler) {
        std::memset(outPcm, 0, numFrames * mChannels * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // 1. Update PI Resampler Clock Drift Controller
    const float currentFillMs = mJitterBuffer->getCurrentFillMs();
    const float dtSeconds = static_cast<float>(numFrames) / static_cast<float>(mSampleRate);
    mResampler->updateRatio(currentFillMs, dtSeconds);

    // 2. Pull audio frames from jitter buffer
    const size_t neededInputFrames = static_cast<size_t>(numFrames) + 16;
    if (mResampleInputBuffer.size() < neededInputFrames * mChannels) {
        mResampleInputBuffer.resize(neededInputFrames * mChannels * 2);
    }

    mJitterBuffer->pullFrames(mResampleInputBuffer.data(), neededInputFrames);

    // 3. Resample via Clock-Drift PI Resampler
    mResampler->resample(
        mResampleInputBuffer.data(),
        neededInputFrames,
        outPcm,
        static_cast<size_t>(numFrames),
        mChannels
    );

    // 4. Apply Channel Routing
    StereoRouting route = mRouting.load(std::memory_order_relaxed);
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

    // 5. Apply Volume Boost & Soft-Clipping Limiter
    float vol = mVolume.load(std::memory_order_relaxed);
    applyVolumeAndLimiter(outPcm, numFrames * mChannels, vol);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

float PulseLinkEngine::softClip(float sample) {
    float x = std::clamp(sample, -1.5f, 1.5f);
    if (x > 1.0f) {
        return 1.0f - (1.0f / (1.0f + (x - 1.0f))) * 0.25f;
    } else if (x < -1.0f) {
        return -1.0f + (1.0f / (1.0f + (-x - 1.0f))) * 0.25f;
    } else {
        return x - (x * x * x) / 6.0f;
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
