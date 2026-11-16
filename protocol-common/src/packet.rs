//! Binary packet header and serialization for Zero Pulse Link audio transport.
//!
//! Dual-channel protocol operating on port 59100 (Control) and 59101 (Audio Data).
//! 16-byte fixed binary packet header:
//! - Magic (2 bytes): 0x5A 0x50 ("ZP")
//! - Flags (1 byte): Codec (bits 0-1), Direction (bit 2), Channels (bit 3)
//! - SampleRateCode (1 byte): 0=44.1kHz, 1=48kHz, 2=96kHz
//! - SequenceNumber (4 bytes, u32 LE): Monotonic sequence counter
//! - TimestampMicroseconds (8 bytes, u64 LE): Sender capture clock
//! - Payload: Variable raw audio frame bytes

use byteorder::{ByteOrder, LittleEndian};
use thiserror::Error;

pub const PACKET_MAGIC: [u8; 2] = [0x5A, 0x50]; // 'Z', 'P'
pub const HEADER_SIZE: usize = 16;
pub const DEFAULT_CONTROL_PORT: u16 = 59100;
pub const DEFAULT_AUDIO_PORT: u16 = 59101;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum PacketError {
    #[error("Buffer too small: expected at least {expected} bytes, got {actual}")]
    BufferTooSmall { expected: usize, actual: usize },
    #[error("Invalid magic: expected 0x5A 0x50, got {0:02X?}")]
    InvalidMagic([u8; 2]),
    #[error("Invalid sample rate code: {0}")]
    InvalidSampleRate(u8),
    #[error("Invalid codec bits: {0}")]
    InvalidCodec(u8),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum AudioCodec {
    Pcm16 = 0,
    Pcm24 = 1,
    Opus = 2,
    Flac = 3,
}

impl AudioCodec {
    pub fn from_bits(bits: u8) -> Result<Self, PacketError> {
        match bits & 0x03 {
            0 => Ok(AudioCodec::Pcm16),
            1 => Ok(AudioCodec::Pcm24),
            2 => Ok(AudioCodec::Opus),
            3 => Ok(AudioCodec::Flac),
            other => Err(PacketError::InvalidCodec(other)),
        }
    }

    pub fn to_bits(self) -> u8 {
        self as u8
    }

    pub fn is_lossless(self) -> bool {
        matches!(self, AudioCodec::Pcm16 | AudioCodec::Pcm24 | AudioCodec::Flac)
    }

    pub fn name(self) -> &'static str {
        match self {
            AudioCodec::Pcm16 => "PCM 16-bit Lossless",
            AudioCodec::Pcm24 => "PCM 24-bit Lossless",
            AudioCodec::Opus => "Opus Low-Delay",
            AudioCodec::Flac => "FLAC Stream Lossless",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum StreamDirection {
    ServerToClient = 0, // PC -> Phone (Speaker)
    ClientToServer = 1, // Phone -> PC (Microphone)
}

impl StreamDirection {
    pub fn from_bit(bit: bool) -> Self {
        if bit {
            StreamDirection::ClientToServer
        } else {
            StreamDirection::ServerToClient
        }
    }

    pub fn is_mic(self) -> bool {
        matches!(self, StreamDirection::ClientToServer)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum ChannelCount {
    Mono = 1,
    Stereo = 2,
}

impl ChannelCount {
    pub fn from_bit(bit: bool) -> Self {
        if bit {
            ChannelCount::Stereo
        } else {
            ChannelCount::Mono
        }
    }

    pub fn as_u8(self) -> u8 {
        self as u8
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum SampleRate {
    Rate44100 = 0,
    Rate48000 = 1,
    Rate96000 = 2,
}

impl SampleRate {
    pub fn from_code(code: u8) -> Result<Self, PacketError> {
        match code {
            0 => Ok(SampleRate::Rate44100),
            1 => Ok(SampleRate::Rate48000),
            2 => Ok(SampleRate::Rate96000),
            _ => Err(PacketError::InvalidSampleRate(code)),
        }
    }

    pub fn to_code(self) -> u8 {
        self as u8
    }

    pub fn hz(self) -> u32 {
        match self {
            SampleRate::Rate44100 => 44100,
            SampleRate::Rate48000 => 48000,
            SampleRate::Rate96000 => 96000,
        }
    }

    pub fn from_hz(hz: u32) -> Option<Self> {
        match hz {
            44100 => Some(SampleRate::Rate44100),
            48000 => Some(SampleRate::Rate48000),
            96000 => Some(SampleRate::Rate96000),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AudioPacketHeader {
    pub codec: AudioCodec,
    pub direction: StreamDirection,
    pub channels: ChannelCount,
    pub sample_rate: SampleRate,
    pub sequence_number: u32,
    pub timestamp_us: u64,
}

impl AudioPacketHeader {
    pub fn new(
        codec: AudioCodec,
        direction: StreamDirection,
        channels: ChannelCount,
        sample_rate: SampleRate,
        sequence_number: u32,
        timestamp_us: u64,
    ) -> Self {
        Self {
            codec,
            direction,
            channels,
            sample_rate,
            sequence_number,
            timestamp_us,
        }
    }

    /// Encodes header into a 16-byte slice.
    pub fn encode(&self, out: &mut [u8]) -> Result<usize, PacketError> {
        if out.len() < HEADER_SIZE {
            return Err(PacketError::BufferTooSmall {
                expected: HEADER_SIZE,
                actual: out.len(),
            });
        }

        // Magic (2 bytes)
        out[0..2].copy_from_slice(&PACKET_MAGIC);

        // Flags: [7..4 reserved] [3: stereo] [2: client_to_server] [1..0: codec]
        let mut flags = self.codec.to_bits() & 0x03;
        if self.direction == StreamDirection::ClientToServer {
            flags |= 0x04;
        }
        if self.channels == ChannelCount::Stereo {
            flags |= 0x08;
        }
        out[2] = flags;

        // SampleRateCode (1 byte)
        out[3] = self.sample_rate.to_code();

        // Sequence number (4 bytes LE)
        LittleEndian::write_u32(&mut out[4..8], self.sequence_number);

        // Timestamp in microseconds (8 bytes LE)
        LittleEndian::write_u64(&mut out[8..16], self.timestamp_us);

        Ok(HEADER_SIZE)
    }

    /// Decodes header from at least 16 bytes.
    pub fn decode(src: &[u8]) -> Result<Self, PacketError> {
        if src.len() < HEADER_SIZE {
            return Err(PacketError::BufferTooSmall {
                expected: HEADER_SIZE,
                actual: src.len(),
            });
        }

        let magic = [src[0], src[1]];
        if magic != PACKET_MAGIC {
            return Err(PacketError::InvalidMagic(magic));
        }

        let flags = src[2];
        let codec = AudioCodec::from_bits(flags & 0x03)?;
        let direction = StreamDirection::from_bit((flags & 0x04) != 0);
        let channels = ChannelCount::from_bit((flags & 0x08) != 0);

        let sample_rate = SampleRate::from_code(src[3])?;
        let sequence_number = LittleEndian::read_u32(&src[4..8]);
        let timestamp_us = LittleEndian::read_u64(&src[8..16]);

        Ok(Self {
            codec,
            direction,
            channels,
            sample_rate,
            sequence_number,
            timestamp_us,
        })
    }

    /// Calculate theoretical uncompressed bitrate in bits per second for PCM formats
    pub fn calculate_pcm_bitrate_bps(&self) -> u32 {
        let bytes_per_sample = match self.codec {
            AudioCodec::Pcm16 => 2,
            AudioCodec::Pcm24 => 3,
            _ => 2,
        };
        self.sample_rate.hz() * (self.channels.as_u8() as u32) * bytes_per_sample * 8
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_header_roundtrip() {
        let header = AudioPacketHeader::new(
            AudioCodec::Pcm24,
            StreamDirection::ServerToClient,
            ChannelCount::Stereo,
            SampleRate::Rate96000,
            4242,
            1_700_000_000_123_456,
        );

        let mut buf = [0u8; 16];
        header.encode(&mut buf).unwrap();

        let decoded = AudioPacketHeader::decode(&buf).unwrap();
        assert_eq!(header, decoded);
    }

    #[test]
    fn test_invalid_magic() {
        let buf = [0u8; 16];
        assert!(matches!(
            AudioPacketHeader::decode(&buf),
            Err(PacketError::InvalidMagic(_))
        ));
    }
}
