// 字节口径 SPSC 环形缓冲，两条直出通路共用（Oboe / USB 独占）。
// 位置用单调递增的字节偏移，取模只在拷贝时做，免去 wrap 边界判断。
#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

namespace annie
{

/** 软件增益的定点单位（Q16）：65536 = 1.0 */
constexpr int32_t kGainUnity = 65536;

/**
 * 就地施加软件增益（含 TPDF 抖动）。
 *
 * **必须放在消费侧**（Oboe 的 `onAudioReady` / USB 的 `fill()`），不能在写入侧乘：
 * 环形缓冲里压着几百毫秒已提交数据，写入侧乘的话换音量要等那一段播完才听得出来。
 *
 * @param samples 单声道样本数（帧数 × 声道数）
 * @param subslot 每个样本的字节数（2 / 3 / 4）
 * @param rng     抖动状态，调用方持有（每条流一份，跨调用连续）
 *
 * 抖动只加在 16 / 24 bit：32 bit 容器抖 1 LSB 是 −192 dB，没有意义。
 * 必须是三角分布（TPDF）—— 单个均匀分布（RPDF）会把噪声调制到信号上。
 */
inline void pcmApplyGain( uint8_t* p, size_t samples, size_t subslot, int32_t gainQ16,
                          uint32_t& rng )
{
    if( p == nullptr || gainQ16 == kGainUnity || samples == 0 || subslot < 2 )
    {
        return;
    }
    const float g = static_cast< float >( gainQ16 ) / static_cast< float >( kGainUnity );
    for( size_t i = 0; i < samples; i++, p += subslot )
    {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        if( subslot == 3 )
        {
            const float d = static_cast< float >(( rng >> 24 ) - (( rng >> 16 ) & 0xFF )) / 256.0f;
            int32_t v = p[ 0 ] | ( p[ 1 ] << 8 ) | ( p[ 2 ] << 16 );
            v = ( v << 8 ) >> 8;
            int32_t out = static_cast< int32_t >( static_cast< float >( v ) * g + d );
            out = out > 0x7FFFFF ? 0x7FFFFF : ( out < -0x800000 ? -0x800000 : out );
            p[ 0 ] = static_cast< uint8_t >( out & 0xFF );
            p[ 1 ] = static_cast< uint8_t >(( out >> 8 ) & 0xFF );
            p[ 2 ] = static_cast< uint8_t >(( out >> 16 ) & 0xFF );
        }
        else if( subslot == 2 )
        {
            const float d = static_cast< float >(( rng >> 24 ) - (( rng >> 16 ) & 0xFF )) / 256.0f;
            const int32_t v = static_cast< int16_t >( p[ 0 ] | ( p[ 1 ] << 8 ));
            int32_t out = static_cast< int32_t >( static_cast< float >( v ) * g + d );
            out = out > 32767 ? 32767 : ( out < -32768 ? -32768 : out );
            p[ 0 ] = static_cast< uint8_t >( out & 0xFF );
            p[ 1 ] = static_cast< uint8_t >(( out >> 8 ) & 0xFF );
        }
        else
        {
            const int32_t v = static_cast< int32_t >(
                static_cast< uint32_t >( p[ 0 ]) |
                ( static_cast< uint32_t >( p[ 1 ]) << 8 ) |
                ( static_cast< uint32_t >( p[ 2 ]) << 16 ) |
                ( static_cast< uint32_t >( p[ 3 ]) << 24 ));
            const double scaled = static_cast< double >( v ) * static_cast< double >( g );
            const int32_t out = scaled > 2147483647.0 ? 2147483647
                                : ( scaled < -2147483648.0 ? INT32_MIN
                                                           : static_cast< int32_t >( scaled ));
            p[ 0 ] = static_cast< uint8_t >( out & 0xFF );
            p[ 1 ] = static_cast< uint8_t >(( out >> 8 ) & 0xFF );
            p[ 2 ] = static_cast< uint8_t >(( out >> 16 ) & 0xFF );
            p[ 3 ] = static_cast< uint8_t >(( out >> 24 ) & 0xFF );
        }
    }
}

class ByteRing
{
public:
    void init( size_t capacityBytes, size_t frameBytes )
    {
        if( frameBytes == 0 )
        {
            frameBytes = 1;
        }
        capacityBytes = ( capacityBytes / frameBytes ) * frameBytes;
        const size_t minimal = frameBytes * 64;
        if( capacityBytes < minimal )
        {
            capacityBytes = minimal;
        }
        data.assign( capacityBytes, 0 );
        capacity = capacityBytes;
        this->frameBytes = frameBytes;
        writePos.store( 0, std::memory_order_relaxed );
        readPos.store( 0, std::memory_order_relaxed );
    }

    size_t readableFrames() const
    {
        const uint64_t w = writePos.load( std::memory_order_acquire );
        const uint64_t r = readPos.load( std::memory_order_relaxed );
        return static_cast< size_t >(( w - r ) / frameBytes );
    }

    size_t capacityFrames() const
    {
        return( frameBytes > 0 ? capacity / frameBytes : 0 );
    }

    size_t frameSize() const
    {
        return( frameBytes );
    }

    /** 写入端累计字节数（单调递增，只作快照用） */
    uint64_t writtenBytes() const
    {
        return( writePos.load( std::memory_order_acquire ));
    }

    /**
     * 丢弃 `target` 之前尚未读出的数据，保留其后的。
     *
     * `target` 取调用时刻的 [writtenBytes] 快照 —— 只有这样才区分得开"flush 之前写进来的"
     * 与"flush 之后新写的"，`clear()` 做不到这一点。
     */
    void discardTo( uint64_t target )
    {
        const uint64_t w = writePos.load( std::memory_order_acquire );
        const uint64_t t = target < w ? target : w;
        if( t > readPos.load( std::memory_order_relaxed ))
        {
            readPos.store( t, std::memory_order_release );
        }
    }

    /** 非阻塞；返回实际写入帧数，空间不足时为 0（调用方稍后重试） */
    size_t write( const uint8_t* src, size_t frames )
    {
        if( src == nullptr || frames == 0 )
        {
            return( 0 );
        }
        const uint64_t r = readPos.load( std::memory_order_acquire );
        uint64_t w = writePos.load( std::memory_order_relaxed );
        // 并发的 `clear()` 可能把写指针退到读指针之前；不修正的话 `w - r` 下溢成天文数字，
        // `freeBytes` 恒为 0，这条流就永久写不进去了。
        if( w < r )
        {
            writePos.store( r, std::memory_order_release );
            w = r;
        }
        const size_t used = static_cast< size_t >( w - r );
        const size_t freeBytes = capacity > used ? capacity - used : 0;
        const size_t n = std::min( frames, freeBytes / frameBytes );
        if( n == 0 )
        {
            return( 0 );
        }
        copyInto( src, w, n );
        writePos.store( w + n * frameBytes, std::memory_order_release );
        return( n );
    }

    /** 返回实际读出帧数；不足部分由调用方填静音 */
    size_t read( uint8_t* dst, size_t frames )
    {
        if( dst == nullptr || frames == 0 )
        {
            return( 0 );
        }
        const uint64_t r = readPos.load( std::memory_order_relaxed );
        const uint64_t w = writePos.load( std::memory_order_acquire );
        // 并发 `clear()` 可能让 `w < r`；不挡的话 `w - r` 下溢 → 读到未写区域、
        // 读指针飞到未来，此后整条流的连续性就没了。
        if( w <= r )
        {
            return( 0 );
        }
        const size_t n = std::min( frames, static_cast< size_t >(( w - r ) / frameBytes ));
        if( n == 0 )
        {
            return( 0 );
        }
        copyOut( dst, r, n );
        readPos.store( r + n * frameBytes, std::memory_order_release );
        return( n );
    }

    void clear()
    {
        // `read()` 在另一线程推进读指针：写指针落在**不早于**它的位置才算清干净。
        // 只读一次 readPos 就写回，可能把写指针退到"读指针之后"，下次 write 就下溢。
        for( ;; )
        {
            const uint64_t r = readPos.load( std::memory_order_acquire );
            if( writePos.load( std::memory_order_relaxed ) <= r )
            {
                return;
            }
            writePos.store( r, std::memory_order_release );
            if( readPos.load( std::memory_order_acquire ) == r )
            {
                return;
            }
        }
    }

private:
    void copyInto( const uint8_t* src, uint64_t pos, size_t frames )
    {
        const size_t bytes = frames * frameBytes;
        const size_t offset = static_cast< size_t >( pos % capacity );
        const size_t firstPart = std::min( bytes, capacity - offset );
        std::memcpy( data.data() + offset, src, firstPart );
        if( bytes > firstPart )
        {
            std::memcpy( data.data(), src + firstPart, bytes - firstPart );
        }
    }

    void copyOut( uint8_t* dst, uint64_t pos, size_t frames )
    {
        const size_t bytes = frames * frameBytes;
        const size_t offset = static_cast< size_t >( pos % capacity );
        const size_t firstPart = std::min( bytes, capacity - offset );
        std::memcpy( dst, data.data() + offset, firstPart );
        if( bytes > firstPart )
        {
            std::memcpy( dst + firstPart, data.data(), bytes - firstPart );
        }
    }

    std::vector< uint8_t > data;
    size_t capacity = 0;
    size_t frameBytes = 1;
    std::atomic< uint64_t > writePos{ 0 };
    std::atomic< uint64_t > readPos{ 0 };
};

} // namespace annie
