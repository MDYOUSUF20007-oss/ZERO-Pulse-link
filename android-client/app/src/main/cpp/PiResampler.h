#ifndef PULSELINK_PI_RESAMPLER_H
#define PULSELINK_PI_RESAMPLER_H

#include <cstdint>
#include <vector>
#include <algorithm>

namespace pulselink {

class PiResampler {
public:
    explicit PiResampler(float targetFillMs);

    void setTargetFillMs(float targetMs);
    float getTargetFillMs() const { return mTargetFillMs; }

    /**
     * Update PI Controller and compute current resampling speed ratio.
     * @param currentFillMs Measured jitter buffer level in milliseconds.
     * @param dtSeconds Elapsed time since last update in seconds.
     * @return Speed ratio (e.g. 1.0005 to speed up, 0.9995 to slow down).
     */
    float updateRatio(float currentFillMs, float dtSeconds);

    /**
     * Resample interleaved 16-bit audio frames based on the current ratio.
     * @param input Source samples.
     * @param inputFrames Number of frames in input.
     * @param output Destination buffer for resampled samples.
     * @param maxOutputFrames Maximum frame capacity of output.
     * @param channels Channel count (e.g. 2 for stereo).
     * @return Number of frames generated in output.
     */
    size_t resample(const int16_t* input, size_t inputFrames,
                    int16_t* output, size_t maxOutputFrames,
                    int channels);

    void reset();

private:
    float mTargetFillMs;
    float mKp;
    float mKi;
    float mIntegral;
    float mMaxIntegral;
    float mDeadbandMs;
    float mMaxRatioDeviation;
    float mCurrentRatio;

    // Fractional phase accumulator for resampler
    double mPhase;
};

} // namespace pulselink

#endif // PULSELINK_PI_RESAMPLER_H
