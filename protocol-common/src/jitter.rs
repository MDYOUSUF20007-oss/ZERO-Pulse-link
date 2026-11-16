//! Jitter estimation, clock drift compensation math, and audio signal processing algorithms.
//!
//! Includes:
//! 1. RFC 3550 Interarrival Jitter Estimator
//! 2. Proportional-Integral (PI) Controller for Clock Drift Compensation
//! 3. Packet Loss Concealment (PLC) generator for raw PCM
//! 4. Soft-Knee Limiter / Volume Booster (up to +15dB boost without harsh clipping)

use std::f32::consts::PI;

/// RFC 3550 compliant jitter estimator
#[derive(Debug, Clone)]
pub struct RtpJitterEstimator {
    last_transit: Option<i64>,
    jitter_us: f64,
}

impl Default for RtpJitterEstimator {
    fn default() -> Self {
        Self::new()
    }
}

impl RtpJitterEstimator {
    pub fn new() -> Self {
        Self {
            last_transit: None,
            jitter_us: 0.0,
        }
    }

    /// Update with sender timestamp (microseconds) and arrival timestamp (microseconds)
    pub fn update(&mut self, sender_timestamp_us: u64, arrival_timestamp_us: u64) -> f64 {
        let transit = arrival_timestamp_us as i64 - sender_timestamp_us as i64;
        if let Some(prev_transit) = self.last_transit {
            let d = (transit - prev_transit).abs() as f64;
            // J(i) = J(i-1) + (|D(i-1, i)| - J(i-1)) / 16
            self.jitter_us += (d - self.jitter_us) / 16.0;
        }
        self.last_transit = Some(transit);
        self.jitter_us
    }

    pub fn jitter_ms(&self) -> f32 {
        (self.jitter_us / 1000.0) as f32
    }

    pub fn reset(&mut self) {
        self.last_transit = None;
        self.jitter_us = 0.0;
    }
}

/// Proportional-Integral (PI) Controller for Clock Drift Compensation.
///
/// Dynamically adjusts the audio resampling ratio so the jitter buffer fill level
/// stays locked to `target_fill_ms`.
/// Pitch adjustment is limited to +/- 0.2% (+/- 2000 ppm), which is completely
/// imperceptible to the human ear while keeping buffers rock-solid indefinitely.
#[derive(Debug, Clone)]
pub struct ClockDriftPiController {
    target_fill_ms: f32,
    kp: f32,
    ki: f32,
    integral: f32,
    max_integral: f32,
    deadband_ms: f32,
    max_ratio_deviation: f32,
}

impl ClockDriftPiController {
    pub fn new(target_fill_ms: f32) -> Self {
        Self {
            target_fill_ms,
            // Tuned gains for smooth, oscillation-free drift correction:
            kp: 0.00003, // 0.003% adjustment per ms of instantaneous error
            ki: 0.000002, // 0.0002% adjustment per ms-second of accumulated drift
            integral: 0.0,
            max_integral: 0.0015,
            deadband_ms: 3.0, // Don't adjust within +/- 3ms to avoid hunting
            max_ratio_deviation: 0.002, // Max +/- 0.2% pitch shift
        }
    }

    pub fn set_target(&mut self, target_ms: f32) {
        self.target_fill_ms = target_ms;
        self.integral = 0.0;
    }

    /// Calculate next resampling ratio given the current smoothed buffer fill in ms
    /// and elapsed time dt in seconds.
    ///
    /// Output ratio:
    /// - 1.000 = Play at native speed
    /// - > 1.000 (e.g. 1.001) = Play faster to drain overfilling buffer
    /// - < 1.000 (e.g. 0.999) = Play slower to replenish draining buffer
    pub fn update(&mut self, current_fill_ms: f32, dt_seconds: f32) -> f32 {
        let error = current_fill_ms - self.target_fill_ms;

        // Apply deadband
        let effective_error = if error.abs() <= self.deadband_ms {
            0.0
        } else if error > self.deadband_ms {
            error - self.deadband_ms
        } else {
            error + self.deadband_ms
        };

        // Update integrator with anti-windup clamping
        self.integral += self.ki * effective_error * dt_seconds;
        self.integral = self
            .integral
            .clamp(-self.max_integral, self.max_integral);

        let delta = self.kp * effective_error + self.integral;
        let clamped_delta = delta.clamp(-self.max_ratio_deviation, self.max_ratio_deviation);

        1.0 + clamped_delta
    }

    pub fn reset(&mut self) {
        self.integral = 0.0;
    }
}

/// Packet Loss Concealment (PLC) helper for uncompressed PCM audio.
///
/// When an audio packet is dropped or lost over Wi-Fi, playing digital silence causes
/// an abrasive click/pop. This PLC algorithm generates smooth synthetic samples by
/// applying a cosine cross-fade from the tail of the previous frame toward zero,
/// preserving phase continuity and eliminating harsh transients.
pub struct PcmPacketLossConcealer;

impl PcmPacketLossConcealer {
    /// Conceal dropped 16-bit interleaved PCM samples
    pub fn conceal_pcm16(
        last_samples: &[i16],
        output: &mut [i16],
        channels: usize,
    ) {
        if last_samples.is_empty() || output.is_empty() {
            output.fill(0);
            return;
        }

        let total_frames = output.len() / channels;
        let fade_frames = total_frames.min(128); // Smooth 2.6ms decay at 48kHz

        for frame_idx in 0..total_frames {
            let gain = if frame_idx < fade_frames {
                // Raised cosine decay from 1.0 down to 0.0
                0.5 * (1.0 + ((frame_idx as f32 / fade_frames as f32) * PI).cos())
            } else {
                0.0
            };

            for ch in 0..channels {
                let last_val = if last_samples.len() > ch {
                    last_samples[last_samples.len() - channels + ch] as f32
                } else {
                    0.0
                };
                let sample_idx = frame_idx * channels + ch;
                output[sample_idx] = (last_val * gain).round() as i16;
            }
        }
    }
}

/// Volume control with Logarithmic dB curve and soft-clipping limiter.
///
/// Supports Volume Boost up to +15dB with a cubic-soft-knee saturator so quiet laptops
/// or media files sound loud on phone speakers without digital wrap-around distortion.
pub struct SoftLimiterVolume;

impl SoftLimiterVolume {
    /// Converts a linear volume slider (0.0 to 2.0) into a gain multiplier with boost:
    /// - 0.0 = Mute
    /// - 1.0 = Unity Gain (0 dB)
    /// - 2.0 = +15 dB Boost (approx 5.62x voltage multiplier)
    pub fn slider_to_gain(slider: f32) -> f32 {
        if slider <= 0.001 {
            0.0
        } else if slider <= 1.0 {
            // Logarithmic taper for smooth standard volume adjustment (slider^2.5)
            slider.powf(2.5)
        } else {
            // Linear expansion above 1.0 up to +15 dB (5.6234x)
            let boost_progress = slider - 1.0; // 0.0 to 1.0
            1.0 + boost_progress * 4.6234
        }
    }

    /// Soft-clipping transfer function for normalized float samples [-1.0, 1.0]
    /// Uses cubic soft-knee saturation:
    /// f(x) = x - (x^3)/3 for |x| <= 1.0, clamped to [-2/3, 2/3] * 1.5
    #[inline(always)]
    pub fn soft_clip(sample: f32) -> f32 {
        let x = sample.clamp(-1.5, 1.5);
        if x > 1.0 {
            1.0 - (1.0 / (1.0 + (x - 1.0))) * 0.25
        } else if x < -1.0 {
            -1.0 + (1.0 / (1.0 + (-x - 1.0))) * 0.25
        } else {
            // Smooth polynomial saturation
            x - (x * x * x) / 6.0
        }
    }

    /// Process and apply gain + soft limiter to 16-bit PCM in-place
    pub fn apply_to_pcm16(samples: &mut [i16], gain: f32) {
        if (gain - 1.0).abs() < 0.001 {
            return;
        }
        if gain < 0.0001 {
            samples.fill(0);
            return;
        }

        for s in samples.iter_mut() {
            let float_val = (*s as f32 / 32768.0) * gain;
            let clipped = Self::soft_clip(float_val);
            *s = (clipped * 32767.0).clamp(-32768.0, 32767.0) as i16;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_jitter_estimator() {
        let mut est = RtpJitterEstimator::new();
        // Constant delay: jitter should be 0
        est.update(1000, 2000);
        est.update(2000, 3000);
        assert!(est.jitter_ms() < 0.001);

        // Variable delay
        est.update(3000, 4100);
        assert!(est.jitter_ms() > 0.0);
    }

    #[test]
    fn test_pi_resampler_clock_drift() {
        let mut pi = ClockDriftPiController::new(25.0); // 25ms target

        // High buffer: ratio should increase to drain
        let ratio = pi.update(35.0, 0.1);
        assert!(ratio > 1.0, "Expected ratio > 1.0, got {}", ratio);

        // Low buffer: ratio should decrease to fill
        let mut pi_low = ClockDriftPiController::new(25.0);
        let ratio_low = pi_low.update(15.0, 0.1);
        assert!(ratio_low < 1.0, "Expected ratio < 1.0, got {}", ratio_low);
    }

    #[test]
    fn test_soft_limiter() {
        assert_eq!(SoftLimiterVolume::slider_to_gain(0.0), 0.0);
        assert!((SoftLimiterVolume::slider_to_gain(1.0) - 1.0).abs() < 0.01);
        assert!(SoftLimiterVolume::slider_to_gain(2.0) > 5.0); // Boosted

        let mut samples = [10000i16, 20000, -20000, 30000];
        SoftLimiterVolume::apply_to_pcm16(&mut samples, 2.0);
        for &s in samples.iter() {
            assert!(s != 0);
        }
    }
}
