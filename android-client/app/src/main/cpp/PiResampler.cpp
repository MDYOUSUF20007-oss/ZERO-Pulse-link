#include "PiResampler.h"
#include <cmath>

namespace pulselink {

PiResampler::PiResampler(float targetFillMs)
    : mTargetFillMs(targetFillMs),
      mKp(0.00003f),        // 0.003% adjustment per ms instantaneous error
      mKi(0.000002f),       // 0.0002% per ms-second accumulated drift
      mIntegral(0.0f),
      mMaxIntegral(0.0015f),
      mDeadbandMs(2.5f),    // +/- 2.5ms deadband to eliminate hunting
      mMaxRatioDeviation(0.002f), // +/- 0.2% max ratio deviation
      mCurrentRatio(1.0f),
      mPhase(0.0) {}

void PiResampler::setTargetFillMs(float targetMs) {
    mTargetFillMs = targetMs;
    mIntegral = 0.0f;
    mCurrentRatio = 1.0f;
}

float PiResampler::updateRatio(float currentFillMs, float dtSeconds) {
    float error = currentFillMs - mTargetFillMs;

    // Apply deadband
    float effectiveError = 0.0f;
    if (std::abs(error) > mDeadbandMs) {
        effectiveError = (error > 0.0f) ? (error - mDeadbandMs) : (error + mDeadbandMs);
    }

    // Accumulate integral with anti-windup clamping
    mIntegral += mKi * effectiveError * dtSeconds;
    mIntegral = std::clamp(mIntegral, -mMaxIntegral, mMaxIntegral);

    float delta = mKp * effectiveError + mIntegral;
    float clampedDelta = std::clamp(delta, -mMaxRatioDeviation, mMaxRatioDeviation);

    mCurrentRatio = 1.0f + clampedDelta;
    return mCurrentRatio;
}

size_t PiResampler::resample(const int16_t* input, size_t inputFrames,
                             int16_t* output, size_t maxOutputFrames,
                             int channels) {
    if (inputFrames == 0 || maxOutputFrames == 0) return 0;

    size_t outFrames = 0;
    const double step = mCurrentRatio;

    while (outFrames < maxOutputFrames) {
        size_t idx0 = static_cast<size_t>(mPhase);
        if (idx0 + 1 >= inputFrames) {
            // Need more input frames
            mPhase -= idx0;
            break;
        }

        size_t idx1 = idx0 + 1;
        float frac = static_cast<float>(mPhase - idx0);

        for (int ch = 0; ch < channels; ++ch) {
            float s0 = static_cast<float>(input[idx0 * channels + ch]);
            float s1 = static_cast<float>(input[idx1 * channels + ch]);
            float interpolated = s0 + frac * (s1 - s0);
            output[outFrames * channels + ch] = static_cast<int16_t>(std::clamp(interpolated, -32768.0f, 32767.0f));
        }

        outFrames++;
        mPhase += step;
    }

    return outFrames;
}

void PiResampler::reset() {
    mIntegral = 0.0f;
    mCurrentRatio = 1.0f;
    mPhase = 0.0;
}

} // namespace pulselink
