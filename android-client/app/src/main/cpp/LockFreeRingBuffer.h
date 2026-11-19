#ifndef PULSELINK_LOCK_FREE_RING_BUFFER_H
#define PULSELINK_LOCK_FREE_RING_BUFFER_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>
#include <algorithm>

namespace pulselink {

/**
 * Cacheline-aligned Single-Producer Single-Consumer (SPSC) Lock-Free Ring Buffer.
 * Guaranteed zero-allocation and wait-free for the real-time audio thread.
 */
template <typename T>
class LockFreeRingBuffer {
public:
    explicit LockFreeRingBuffer(size_t capacity) {
        // Enforce power of two capacity for branchless modulo
        mCapacity = 1;
        while (mCapacity < capacity) {
            mCapacity <<= 1;
        }
        mMask = mCapacity - 1;
        mBuffer.resize(mCapacity);
        mWriteIndex.store(0, std::memory_order_relaxed);
        mReadIndex.store(0, std::memory_order_relaxed);
    }

    ~LockFreeRingBuffer() = default;

    size_t getCapacity() const noexcept {
        return mCapacity;
    }

    size_t getAvailableRead() const noexcept {
        const size_t writeIdx = mWriteIndex.load(std::memory_order_acquire);
        const size_t readIdx = mReadIndex.load(std::memory_order_relaxed);
        return writeIdx - readIdx;
    }

    size_t getAvailableWrite() const noexcept {
        return mCapacity - getAvailableRead();
    }

    /**
     * Push elements into ring buffer (Called exclusively by network consumer thread).
     * @return Number of elements successfully written.
     */
    size_t write(const T* data, size_t count) noexcept {
        const size_t available = getAvailableWrite();
        const size_t toWrite = std::min(count, available);
        if (toWrite == 0) return 0;

        const size_t writeIdx = mWriteIndex.load(std::memory_order_relaxed);
        const size_t pos = writeIdx & mMask;
        const size_t firstPart = std::min(toWrite, mCapacity - pos);
        const size_t secondPart = toWrite - firstPart;

        std::copy_n(data, firstPart, &mBuffer[pos]);
        if (secondPart > 0) {
            std::copy_n(data + firstPart, secondPart, &mBuffer[0]);
        }

        mWriteIndex.store(writeIdx + toWrite, std::memory_order_release);
        return toWrite;
    }

    /**
     * Read elements from ring buffer (Called exclusively by real-time audio callback thread).
     * @return Number of elements successfully read.
     */
    size_t read(T* destination, size_t count) noexcept {
        const size_t available = getAvailableRead();
        const size_t toRead = std::min(count, available);
        if (toRead == 0) return 0;

        const size_t readIdx = mReadIndex.load(std::memory_order_relaxed);
        const size_t pos = readIdx & mMask;
        const size_t firstPart = std::min(toRead, mCapacity - pos);
        const size_t secondPart = toRead - firstPart;

        std::copy_n(&mBuffer[pos], firstPart, destination);
        if (secondPart > 0) {
            std::copy_n(&mBuffer[0], secondPart, destination + firstPart);
        }

        mReadIndex.store(readIdx + toRead, std::memory_order_release);
        return toRead;
    }

    /**
     * Skip/discard elements from ring buffer (Called by audio consumer thread to drain backlog).
     * @return Number of elements actually skipped.
     */
    size_t skip(size_t count) noexcept {
        const size_t available = getAvailableRead();
        const size_t toSkip = std::min(count, available);
        if (toSkip == 0) return 0;

        const size_t readIdx = mReadIndex.load(std::memory_order_relaxed);
        mReadIndex.store(readIdx + toSkip, std::memory_order_release);
        return toSkip;
    }

    void reset() noexcept {
        mWriteIndex.store(0, std::memory_order_relaxed);
        mReadIndex.store(0, std::memory_order_relaxed);
    }

private:
    size_t mCapacity;
    size_t mMask;
    std::vector<T> mBuffer;

    // Cacheline padding (64 bytes) to prevent false sharing between core caches
    alignas(64) std::atomic<size_t> mWriteIndex{0};
    alignas(64) std::atomic<size_t> mReadIndex{0};
};

} // namespace pulselink

#endif // PULSELINK_LOCK_FREE_RING_BUFFER_H
