#ifndef PULSELINK_PULSE_LINK_ENGINE_H
#define PULSELINK_PULSE_LINK_ENGINE_H

#include <cstdint>
#include <memory>
#include <atomic>
#include <aaudio/AAudio.h>
#include "JitterBuffer.h"
#include "PiResampler.h"

namespace pulselink {

enum class StereoRouting {
    Stereo = 0,
    LeftOnly = 1,
    RightOnly = 2,
    MonoDownmix = 3
};

class PulseLinkEngine {
public:
    PulseLinkEngine();
    ~PulseLinkEngine();

    bool init(uint32_t sampleRate, int channels, BufferProfile profile);
    bool start();
    void stop();
    void destroy();

    // Ingest network packet from JNI
    bool pushAudioPacket(const uint8_t* data, size_t length);

    // Audio controls
    void setVolume(float volume); // 0.0 to 2.0 (boost)
    void setStereoRouting(StereoRouting routing);
    void setBufferProfile(BufferProfile profile);

    // Real-time telemetry extraction
    void getTelemetry(float* outTelemetry);

    // AAudio Callback
    aaudio_data_callback_result_t onAudioCallback(AAudioStream* stream, void* audioData, int32_t numFrames);

private:
    uint32_t mSampleRate;
    int mChannels;

    AAudioStream* mAudioStream;
    std::unique_ptr<JitterBuffer> mJitterBuffer;
    std::unique_ptr<PiResampler> mResampler;

    std::atomic<float> mVolume{1.0f};
    std::atomic<StereoRouting> mRouting{StereoRouting::Stereo};
    std::atomic<bool> mIsPlaying{false};

    // Intermediate scratch buffers for resampling and limiter
    std::vector<int16_t> mResampleInputBuffer;
    std::vector<int16_t> mResampleOutputBuffer;

    // Soft-clipping limiter & volume curve
    static float softClip(float sample);
    void applyVolumeAndLimiter(int16_t* samples, size_t sampleCount, float vol);
};

} // namespace pulselink

#endif // PULSELINK_PULSE_LINK_ENGINE_H
