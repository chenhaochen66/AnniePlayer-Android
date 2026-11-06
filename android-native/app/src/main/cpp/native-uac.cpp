// USB 独占播放内核（libusb）。
//
// 唯一能绕开 AudioFlinger 的通路：AudioTrack 建不出 44.1k（DAC 的 port profile 没有
// 44100）也建不出 >192k（AudioFormat 上限），只有直接写端点才能原样送出。
// 代价是 claim 接口时内核驱动被摘掉，设备对系统暂时消失。
#include <android/log.h>
#include <jni.h>
#include <libusb.h>
#include <pthread.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>
#include "pcm-ring.h"
namespace
{
using annie::ByteRing;
constexpr int kMicroframesPerSecond = 8000;   
// high speed 等时端点，125 µs/包
/** 
* 每个 URB 装几个微帧。**这个数字直接决定唤醒率**，而唤醒率是这个通路的主要耗电项。 
* 
* 一次 URB 完成 = 一次内核回调 + 一次 `poll` 唤醒 + 一次 `USBDEVFS_SUBMITURB`。 
* 曾经是 8（1 ms/URB）配 64 条在飞 → 每秒 1000 次唤醒。 
* 现在 24 包（3 ms/URB）：**每秒约 333 次**。24 是 ALSA 长期使用的每 URB 包数上限 
* （`max_packs`），USB 核心侧的上限是 128，所以 24 不需要赌 HCD 的实现细节。 */
constexpr int kPacketsPerUrb = 24;
// 流水线深度 = 用户态线程被调度挪开时的容忍时间。32 ms 只够前台，
// 进后台后线程落进 background 调度组、timer slack 放大，几十毫秒的空档是常态，
// 内核那边 URB 一用尽 DAC 就断（听感是断续，再久被判停滞直接掐流）。
// 21 条 × 24 包 = 63 ms：深度与原来的 64 条 × 8 包持平，**唤醒次数降到三分之一**。
constexpr int kUrbCount = 21;
/** 
* 反馈端点一批读几个微帧。 
* 
* 反馈值报的是**时钟漂移**（几百 ppm 量级），根本不需要微帧级的刷新率。以前一个 
* iso transfer 只装 1 个包，而 `onFeedback` 里立刻重提交 —— 等于每秒 **8000 次*
* 
* 完整的"内核回调 → 用户态唤醒 → 提交 ioctl"往返。现在一批 8 个（1 ms）， 
* 取其中最后一条有效读数，唤醒降到 1000/s。ALSA 也是每 URB 取一次。 */
constexpr int kFbPackets = 8;
/** 
* 暂停多久之后才**真正停掉**事件线程（毫秒）。 
* 
* 短暂停继续按原节奏送零：拨进度、切出去看一眼、回个消息 —— 这些如果让流断掉， 
* 恢复时要重新起 URB，设备侧常常咔哒一下。超过这个时长（暂停放着、熄屏）就没必要 
* 继续付每秒上千次唤醒的代价了。 */
constexpr int kPauseDeepAfterMs = 2000;
constexpr uint8_t kClassAudio = 0x01;
constexpr uint8_t kSubclassAudioControl = 0x01;
constexpr uint8_t kSubclassAudioStreaming = 0x02;
constexpr uint8_t kDescCsInterface = 0x24;
constexpr uint8_t kCsAsGeneral = 0x01;
constexpr uint8_t kCsAsFormatType = 0x02;
// FORMAT_TYPE_I 的 `bFormatType` 字段（与上面的 subtype 编号巧合相同，含义完全不同）。
// USB Audio 1.0 Table A-2：0x00 UNDEFINED / 0x01 FORMAT_TYPE_I / 0x02 FORMAT_TYPE_II 
/// 0x03 FORMAT_TYPE_III。UNDEFINED 是表达 raw data 的老办法；**0x02 不是它** ——
// 把 Type-II 当 raw 会让那类 PCM alt 彻底不可用（详见 parseStreamExtra 的注释）。
constexpr uint8_t kFormatTypeUndefined = 0x00;
constexpr uint8_t kFormatTypeI = 0x01;
// UAC2 `AS_GENERAL.bmFormats` 的 bit31 = raw data（Linux 的 UAC_FORMAT_TYPE_I_RAW_DATA）。
constexpr uint32_t kAsFormatRawData = 0x80000000u;
constexpr uint8_t kCsAcInputTerminal = 0x02;
constexpr uint8_t kCsAcFeatureUnit = 0x06;
constexpr uint8_t kCsAcClockSource = 0x0A;
constexpr uint8_t kCsAcClockSelector = 0x0B;
/** Feature Unit 的 `bmaControls` 位：Master（ch0）那一字节的 bit0 / bit1 */
constexpr uint8_t kFeatureMute = 0x01;
constexpr uint8_t kFeatureVolume = 0x02;
/** CS_VOLUME_CONTROL：UAC1 与 UAC2 编号相同，单位 1/256 dB，**数值是衰减量**（0 = 不衰减） */
constexpr uint8_t kCsVolumeControl = 0x02;
/** CS_SELECTOR_CONTROL：Clock Selector 的"选哪一路输入"（1 字节 = 被选中的 bClockID） */
constexpr uint8_t kCsSelectorControl = 0x01;
/** `pendingHwVol` 的空值（没有待下发） */
constexpr int32_t kNoHwVolPending = INT32_MIN;
constexpr uint8_t kRequestTypeClassOut = 0x21;
constexpr uint8_t kRequestTypeClassIn = 0xA1;
constexpr uint8_t kRequestSetCur = 0x01;
constexpr uint8_t kRequestGetCur = 0x81;
constexpr uint8_t kRequestGetRange = 0x82;
constexpr uint8_t kCsSamFreqControl = 0x01;
constexpr uint8_t kRequestGetInterface = 0x0A;
// 与 Media3 `C.ENCODING_PCM_*` 一字不差。**不能用"位深"代替编码**：
// 32 位既可能是 float 也可能是 int32，两者的字节解释完全不同。
constexpr int kEnc16 = 0x2;
constexpr int kEnc24 = 0x15;
constexpr int kEncFloat = 0x4;
thread_local int g_lastError = 0;
struct AltProfile
{
    int iface = -1;
    int alt = 0;
    int epOut = -1;
    int epFeedback = -1;
    int subslot = 0;
    int bits = 0;
    int channels = 0;
    int maxPacket = 0;
    int clockId = 0;    /** `FORMAT_TYPE_I.tSamFreq[]`。**空 = 设备没声明**（continuous 或描述符残缺），     
*  那时只能照旧盲设率 —— 很多 DAC 的描述符不全，但确实能跑。 */
    std::vector< int > rates;    /**     
* 这个 alt 是 raw DSD 而不是 PCM。     
*     
* 判据两个，见 `parseStreamExtra`。必须标出来：raw DSD 的 `subslot` 常常与 S32_LE     
* 相同（都是 4），位深/声道/率全部一样，光看这些字段分不开两者 ——     
* 而把 PCM 送进 raw DSD 通道，设备不会报错，只会把它当 1-bit 解成满幅噪声。     */
    bool rawDsd = false;
}
;
struct ParsedConfig
{
    std::vector< AltProfile > alts;   
// 已按位深偏好排序：24 → 32 → 16 → 其它
std::vector< int > clockSources;
    std::vector< int > clockSelectors;    /** Clock Selector 的输入清单：`{selectorId, {可选的 ClockSourceId…}}`。
*
* **这是 UAC2 时钟链上唯一必须下发、而以前从来没下发过的一环。**
* 设备一旦有选择器（PCM 时钟 / DSD 时钟二选一），不 SET_CUR 它就一直停在默认的那个输入上。
* 对 raw DSD 的后果是：alt 选对了、数据也发出去了，但 DSD 解调器跑在 PCM 时钟上 ——
* 设备不报错、URP 照常完成、进度条照走，**耳朵里什么都没有**。
* 所以这里要把输入清单留下来，开 raw 流之前按"哪个输入真的接受这个率"去选。 */
    std::vector< std::pair< int, std::vector< int > > > clockSelectorInputs;    /** AudioControl 接口号：时钟源挂在它上面，class 请求 wIndex 要落在这个接口里 */
    int acInterface = -1;    /**     
* Feature Unit 的 `bUnitID`（-1 = 设备没暴露）。     
*     
* 有它才能做**硬件音量**：衰减发生在 DAC 内部，送过去的 PCM 一个字节都不动，     
* 那是唯一能在 bit perfect 下改音量的办法（软件衰减每 −6 dB 丢 1 bit）。     */
    int featureUnitId = -1;    /**     
* Feature Unit 的 `bmaControls`：第 0 个是 Master，之后每个声道一个（每项 `bControlSize` 字节）。     
*     
* 必须整条留下来 —— 只看 Master 是错的：规范允许 Master 什么都不声明、     
* 让每个声道各自带 Volume（KA13 就是 `Master = 0x00`）。那种设备只能逐声道下发。     */
    std::vector< uint8_t > featureControls;
    std::string summary;
}
;
/** 这条 FU 的任意一项（Master 或某个声道）声明了 Volume */
bool featureHasVolume( const std::vector< uint8_t >& controls )
{
    for( uint8_t c : controls )    
{
        if(( c & kFeatureVolume ) != 0 )        
{
            return( true );
        
}
    
}
    return( false );
}
const char* formatName( const AltProfile& p )
{
    if( p.rawDsd )    
{
        return( "DSD(raw)" );
    
}
    switch( p.subslot )    
{
        case 2:            return( "S16_LE" );
        case 3:            return( "S24_3LE" );
        case 4:            return( "S32_LE" );
        default:            return( "?" );
    
}
}
int terminalLinkOf( const uint8_t* extra, int length )
{
    for( int i = 0; i + 4 <= length; i += extra[ i ] )    
{
        if( extra[ i ] < 3 )        
{
            break;
        
}
        if( extra[ i + 1 ] == kDescCsInterface && extra[ i + 2 ] == kCsAsGeneral && extra[ i ] >= 4 )        
{
            return( extra[ i + 3 ] );
        
}
    
}
    return( 0 );
}
// UAC1 与 UAC2 的 FORMAT_TYPE_I 布局不同，唯一稳定区分点是 bLength（UAC2 恒 6，UAC1 ≥ 8）。
void parseStreamExtra( const uint8_t* extra, int length, AltProfile& out )
{
    for( int i = 0; i + 3 <= length; i += extra[ i ] )    
{
        
// bLength 是描述符自报的长度，读它的字段前先确保它没越出 extra 的边界 ——        
// AS_GENERAL 要读到 i+10、FORMAT_TYPE 还要按 bSamFreqType 往后数。
        const int dlen = extra[ i ];
        if( dlen < 3 || i + dlen > length )        
{
            break;
        
}
        if( extra[ i + 1 ] != kDescCsInterface )        
{
            continue;
        
}
        const uint8_t subtype = extra[ i + 2 ];
        if( subtype == kCsAsGeneral )        
{
            
// UAC2 的 AS_GENERAL 是 16 字节（bTerminalLink/bmControls/bFormatType/            
// bmFormats/bNrChannels/…），UAC1 只有 7（bTerminalLink/bDelay/wFormatTag）。            
// 所以 `dlen >= 11` 是一条可靠的 UAC2 判据，bmFormats 只在这个分支存在。
            if( dlen >= 11 )            
{
                const uint32_t formats =                    static_cast< uint32_t >( extra[ i + 6 ] ) |                    ( static_cast< uint32_t >( extra[ i + 7 ] ) << 8 ) |                    ( static_cast< uint32_t >( extra[ i + 8 ] ) << 16 ) |                    ( static_cast< uint32_t >( extra[ i + 9 ] ) << 24 );
                if(( formats & kAsFormatRawData ) != 0u )                
{
                    out.rawDsd = true;
                
}
                const int channels = extra[ i + 10 ];
                if( channels > 0 )                
{
                    out.channels = channels;
                
}
            
}
        
}        else if( subtype == kCsAsFormatType )        
{
            if( dlen == 6 )            
{
                out.subslot = extra[ i + 4 ];
                out.bits = extra[ i + 5 ];
            
}            else if( dlen >= 8 )            
{
                
// USB Audio 1.0 Table A-2：0x00 = UNDEFINED、0x01 = FORMAT_TYPE_I、                
// **0x02 = FORMAT_TYPE_II**、0x03 = FORMAT_TYPE_III。                
//                
// 旧代码只认 0x02 当 UNDEFINED —— 那其实是 FORMAT_TYPE_II，两个方向都错：                
// ① 真正的 Type-II PCM alt 被误标 raw，接着又按 Type-I 布局去读声道/位深，                
//    读出来的是 `bMaxBitRate` 的字节，导致它既不是 PCM 也不像 raw；                
// ② 最常见的原生 DSD 声明（FORMAT_TYPE_I + bBitResolution = 1）反而漏判，                
//    Native 从此不可达。
const uint8_t formatType = extra[ i + 3 ];
                const bool typeI = formatType == kFormatTypeI;
                const bool undefinedType = formatType == kFormatTypeUndefined;
                const int bitResolution = ( dlen >= 7 ) ? extra[ i + 6 ] : 0;
                if( undefinedType || ( typeI && bitResolution == 1 ))                
{
                    out.rawDsd = true;
                
}
                const int channels = extra[ i + 4 ];
                if( channels > 0 )                
{
                    out.channels = channels;
                
}
                out.subslot = extra[ i + 5 ];
                out.bits = extra[ i + 6 ];                
// bSamFreqType：0 = continuous（只给上下界，不列举），N = 后面跟 N 个                
// 24 位小端 Hz。**这是判断"这个 alt 能不能跑到这个率"的唯一依据**：                
// 多个 alt 常常按率段划分（低段一个小 maxPacket、高段一个大 maxPacket），                
// 只按声道选会挑到跑不动高率的那个。
                const int freqType = extra[ i + 7 ];
                if( freqType > 0 && i + 8 + freqType * 3 <= i + dlen )                
{
                    for( int n = 0; n < freqType; n++ )                    
{
                        const int off = i + 8 + n * 3;
                        const int hz = extra[ off ] | ( extra[ off + 1 ] << 8 ) |                                       ( extra[ off + 2 ] << 16 );
                        if( hz > 0 )                        
{
                            out.rates.push_back( hz );
                        
}
                    
}
                
}
            
}
        
}
    
}
}
ParsedConfig parseConfig( libusb_device_handle* dev )
{
    ParsedConfig parsed;
    libusb_config_descriptor* config = nullptr;
    if( libusb_get_active_config_descriptor( libusb_get_device( dev ), &config ) != LIBUSB_SUCCESS || config == nullptr )    
{
        if( libusb_get_config_descriptor( libusb_get_device( dev ), 0, &config ) != LIBUSB_SUCCESS )        
{
            return( parsed );
        
}
    
}
    
// UAC2 的时钟源挂在 Input Terminal 的 bCSourceID 上，AS 只知道 bTerminalLink。
std::vector< std::pair< int, int > > terminalToClock;
    for( int i = 0; i < config->bNumInterfaces; i++ )    
{
        const libusb_interface& iface = config->interface[ i ];
        for( int a = 0; a < iface.num_altsetting; a++ )        
{
            const libusb_interface_descriptor& d = iface.altsetting[ a ];
            if( d.bInterfaceClass != kClassAudio || d.bInterfaceSubClass != kSubclassAudioControl )            
{
                continue;
            
}
            if( parsed.acInterface < 0 )            
{
                parsed.acInterface = d.bInterfaceNumber;
            
}
            const uint8_t* extra = d.extra;
            const int length = d.extra_length;
            for( int k = 0; k + 3 <= length; k += extra[ k ] )            
{
                if( extra[ k ] < 3 )                
{
                    break;
                
}
                if( extra[ k + 1 ] != kDescCsInterface )                
{
                    continue;
                
}
                const uint8_t subtype = extra[ k + 2 ];
                if( subtype == kCsAcInputTerminal && extra[ k ] >= 8 )                
{
                    terminalToClock.emplace_back( extra[ k + 3 ], extra[ k + 7 ] );
                
}                else if( subtype == kCsAcFeatureUnit && extra[ k ] >= 8 )                
{
                    
// UAC1 与 UAC2 的 Feature Unit 布局一致：                    
//   bLength bDescriptorType(0x24) bDescriptorSubtype(6)                    
//   bUnitID bSourceID bControlSize bmaControls[0..n] iFeature                    
// `bmaControls[0]` 是 Master，之后每个声道一项，每项 `bControlSize` 字节。
                    const int unitId = extra[ k + 3 ];
                    const int ctlSize = extra[ k + 5 ];
                    if( ctlSize >= 1 && unitId > 0 )                    
{
                        const int len = extra[ k ];
                        std::vector< uint8_t > controls;
                        for( int off = k + 6; off < k + len && off < k + 6 + 32 * ctlSize; off += ctlSize )                        
{
                            controls.push_back( extra[ off ] );
                        
}
                        
// 多条 FU 时优先取「真的有 Volume」的那条，否则留第一条备查
const bool better = parsed.featureUnitId < 0 ||                                            ( featureHasVolume( controls ) &&                                              !featureHasVolume( parsed.featureControls ));
                        if( better )                        
{
                            parsed.featureUnitId = unitId;
                            parsed.featureControls = controls;
                        
}
                    
}
                
}                else if( subtype == kCsAcClockSource && extra[ k ] >= 8 )                
{
                    parsed.clockSources.push_back( extra[ k + 3 ] );
                
}                else if( subtype == kCsAcClockSelector && extra[ k ] >= 5 )                
{
                    // UAC2 Clock Selector：bClockID(3) bNrInPins(4) baCSourceID[n](5…) bmControls iClockSelector
                    const int selectorId = extra[ k + 3 ];
                    const int pins = extra[ k + 4 ];
                    std::vector< int > inputs;
                    for( int n = 0; n < pins && k + 5 + n < k + extra[ k ]; n++ )                    
{
                        const int src = extra[ k + 5 + n ];
                        if( src > 0 )                        
{
                            inputs.push_back( src );
                        
}
                    
}
                    if( selectorId > 0 )                    
{
                        parsed.clockSelectors.push_back( selectorId );
                        parsed.clockSelectorInputs.emplace_back( selectorId, inputs );
                    
}
                
}
            
}
        
}
    
}
    for( int i = 0; i < config->bNumInterfaces; i++ )    
{
        const libusb_interface& iface = config->interface[ i ];
        for( int a = 0; a < iface.num_altsetting; a++ )        
{
            const libusb_interface_descriptor& d = iface.altsetting[ a ];
            if( d.bInterfaceClass != kClassAudio || d.bInterfaceSubClass != kSubclassAudioStreaming )            
{
                continue;
            
}
            if( d.bAlternateSetting == 0 )            
{
                continue;
            
}
            AltProfile profile;
            profile.iface = d.bInterfaceNumber;
            profile.alt = d.bAlternateSetting;
            parseStreamExtra( d.extra, d.extra_length, profile );
            const int terminal = terminalLinkOf( d.extra, d.extra_length );
            for( const auto& pair : terminalToClock )            
{
                if( pair.first == terminal )                
{
                    profile.clockId = pair.second;
                    break;
                
}
            
}
            for( int e = 0; e < d.bNumEndpoints; e++ )            
{
                const libusb_endpoint_descriptor& ep = d.endpoint[ e ];
                if(( ep.bmAttributes & 0x03 ) != LIBUSB_TRANSFER_TYPE_ISOCHRONOUS )                
{
                    continue;
                
}
                const int perMicroframe = 1 + (( ep.wMaxPacketSize >> 11 ) & 0x03 );
                if(( ep.bEndpointAddress & 0x80 ) != 0 )                
{
                    
// 同一个 alt 上的 IN 等时端点就是反馈端点（KA13 这类设备不填 bSynchAddress）
if( profile.epFeedback < 0 )                    
{
                        profile.epFeedback = ep.bEndpointAddress;
                    
}
                    continue;
                
}
                profile.epOut = ep.bEndpointAddress;
                profile.maxPacket = ( ep.wMaxPacketSize & 0x7FF ) * perMicroframe;
                if(( ep.bSynchAddress & 0x0F ) != 0 )                
{
                    profile.epFeedback = 0x80 | ( ep.bSynchAddress & 0x0F );
                
}
            
}
            if( profile.epOut < 0 || profile.maxPacket <= 0 )            
{
                continue;
            
}
            
// 只接受 2/3/4 字节样本：其余值说明描述符没读懂，硬放音就是按错字节数打包
if( profile.subslot < 2 || profile.subslot > 4 )            
{
                continue;
            
}
            if( profile.bits == 0 )            
{
                profile.bits = profile.subslot * 8;
            
}
            parsed.alts.push_back( profile );
        
}
    
}
    libusb_free_config_descriptor( config );
    const auto weight = []( int subslot )    
{
        switch( subslot )        
{
            case 3:                return( 0 );
            case 4:                return( 1 );
            case 2:                return( 2 );
            default:                return( 3 );
        
}
    
}
;
    std::stable_sort( parsed.alts.begin(), parsed.alts.end(),                      [ &weight ]( const AltProfile& x, const AltProfile& y )                      
{
                          
// raw DSD 永远排在 PCM 之后。只按 subslot 排是不够的：                          
// raw DSD 的 subslot 常常就是 4，与 S32_LE 同权重，顺序取决于                          
// 描述符出现顺序 —— 那台 DAC 把 DSD alt 写在前面，PCM 就会选中它。
if( x.rawDsd != y.rawDsd )                          
{
                              return( !x.rawDsd );                          
}
                          return( weight( x.subslot ) < weight( y.subslot ));                      
} );
    for( auto& alt : parsed.alts )    
{
        if( alt.clockId == 0 && !parsed.clockSources.empty())        
{
            alt.clockId = parsed.clockSources.front();
        
}
    
}
    char line[ 256 ];
    for( const auto& alt : parsed.alts )    
{
        std::snprintf( line, sizeof( line ),                       "alt %d · %s · %d bit · %d ch · 包长 %d · ep 0x%02X · clock %d\n",                       alt.alt, formatName( alt ), alt.bits, alt.channels, alt.maxPacket,                       alt.epOut, alt.clockId );
        parsed.summary += line;
    
}
    return( parsed );
}
std::string rateRangesOf( libusb_device_handle* dev, int clockId )
{
    if( dev == nullptr || clockId <= 0 )    
{
        return( "" );
    
}
    uint8_t data[ 2 + 3 * 4 * 16 ] = 
{
 0 
}
;
    const int read = libusb_control_transfer(        dev, kRequestTypeClassIn, kRequestGetRange,        static_cast< uint16_t >( kCsSamFreqControl << 8 ),        static_cast< uint16_t >( clockId ), data, sizeof( data ), 500 );
    if( read < 2 )    
{
        return( "" );
    
}
    const int count = data[ 0 ] | ( data[ 1 ] << 8 );
    std::string out;
    char line[ 128 ];
    for( int i = 0; i < count && ( 2 + ( i + 1 ) * 12 ) <= read; i++ )    
{
        const uint8_t* p = data + 2 + i * 12;
        const auto u32 = [ p ]( int at )        
{
            return( static_cast< uint32_t >( p[ at ] ) | ( static_cast< uint32_t >( p[ at + 1 ] ) << 8 ) |                    ( static_cast< uint32_t >( p[ at + 2 ] ) << 16 ) | ( static_cast< uint32_t >( p[ at + 3 ] ) << 24 ));
        
}
;
        std::snprintf( line, sizeof( line ), "  %u..%u 步进 %u\n", u32( 0 ), u32( 4 ), u32( 8 ));
        out += line;
    
}
    return( out );
}
struct Urb
{
    libusb_transfer* transfer = nullptr;
    std::vector< uint8_t > buffer;    /** 在飞标记：只有 `refill()` 用 CAS 置位、回调清位，避免重复提交同一条 URB */
    std::atomic< bool > busy
{
 false 
}
;
    Urb() = default;
    Urb( const Urb& ) = delete;
    Urb& operator=( const Urb& ) = delete;
    Urb( Urb&& other ) noexcept        : transfer( other.transfer ),          buffer( std::move( other.buffer )),          busy( other.busy.load( std::memory_order_relaxed ))    
{
    
}
    Urb& operator=( Urb&& other ) noexcept    
{
        if( this != &other )        
{
            transfer = other.transfer;
            buffer = std::move( other.buffer );
            busy.store( other.busy.load( std::memory_order_relaxed ), std::memory_order_relaxed );
        
}
        return( *this );
    
}
}
;
size_t encodingBytes( int encoding )
{
    switch( encoding )    
{
        case kEnc16:            return( 2 );
        case kEnc24:            return( 3 );
        default:            return( 4 );
    
}
}
class UacStream
{
public:    libusb_context* ctx = nullptr;
    libusb_device_handle* dev = nullptr;
    AltProfile profile;
    ParsedConfig config;
    int sampleRate = 0;
    int inEncoding = kEncFloat;    /**     
* 走 raw DSD alt（Native DSD）。     
*     
* 此时送的 32-bit word 不是 PCM，`fill()` 里「统一转 Q31 再按 subslot 打包」那一步     
* 对它是**恒等映射**（4 字节进、4 字节出，`memcpy` 保序），所以字节序由上游 packer     
* 决定，本文件不需要知道 BE 还是 LE。     */
    bool rawDsd = false;    /**     
* 内容位宽（bit）：0 = 不表态，按包长猜；否则挑「装得下且最窄」的 alt。     
*     
* 只影响**选哪个 alt**，不影响 `fill()` 的换算 —— 后者按 `subslot` 走。     */
    int wireBits = 0;
    int channels = 2;
    size_t inFrameBytes = 8;
    size_t outFrameBytes = 6;
    ByteRing ring;
    std::vector< uint8_t > scratch;    
// 异步 OUT 端点的节奏由设备决定：反馈端点报"每微帧多少样本"(16.16)
libusb_transfer* fbTransfer = nullptr;
    uint8_t fbBuffer[ kFbPackets * 4 ] = 
{
 0 
}
;
    std::atomic< int64_t > feedbackQ16
{
 0 
}
;
    std::atomic< int64_t > fbMin
{
 0 
}
;
    std::atomic< int64_t > fbMax
{
 0 
}
;
    std::atomic< int64_t > fbCount
{
 0 
}
;
    std::atomic< bool > paused
{
 false 
}
;    /** 暂停时事件线程停在这里，**一个 URB 都不提交**（见 `workerLoop`） */
    std::mutex pauseMx;
    std::condition_variable pauseCv;    /** 暂停是否已经进入"真停"阶段；只在事件线程里读写 */
    bool pauseTimerStarted = false;
    std::chrono::steady_clock::time_point pauseSince
{
}
;
    std::atomic< bool > stop
{
 false 
}
;
    std::atomic< bool > dead
{
 false 
}
;
    std::atomic< int64_t > framesRead
{
 0 
}
;
    std::atomic< int64_t > framesWritten
{
 0 
}
;
    std::atomic< int64_t > underruns
{
 0 
}
;
    std::atomic< int64_t > droppedSamples
{
 0 
}
;
    int error = 0;
    std::string detail;    /**     
* 每微帧的样本数（16.16 定点）。     
*     
* **原子**：换率现在可以在流运行中发生（`reopen` 的无缝路径），播放线程写、事件线程     
* 在 `fill()` 里读，非原子的话可能读到撕裂的值 —— 那会让某一包的样本数错掉。     */
    std::atomic< int64_t > perMicroframeQ16
{
 0 
}
;    /**     
* 换率之后要先送多少帧**静音**（由 `fill()` 消费，**不计入 `framesRead`**）。     
*     
* 设备的 PLL 重锁需要几毫秒，那段时间里它可能把通道相位解错（听感就是"一路先哑"）。     
* 把重锁落在静音里，相位错乱就听不出来；静音不计帧数，所以时间轴不受影响。     */
    std::atomic< uint32_t > padFramesLeft
{
 0 
}
;    /**     
* 上一次「运行中换率」是否被设备接受。     
*     
* 有些固件只在 alt 0 下接受 `SET_CUR`。被拒一次之后就**不再白付那次控制传输**，     
* 直接走拆流重建 —— 否则每次切歌都多一次注定失败、还要等超时的同步请求。     */
    bool runtimeRateSwitchOk = true;
    uint32_t accQ16 = 0;
    std::thread worker;
    std::vector< Urb > urbs;
    bool interfaceClaimed = false;    
// ── 硬件音量（Feature Unit 的 Volume 控制）──    
//    
// 单位 1/256 dB，**数值是衰减量**：0 = 不衰减，负值 = 衰减多少 dB。    
// 这是唯一能在 bit perfect 下改音量的途径 —— 衰减在 DAC 内部完成，    
// 我方送过去的 PCM 一个字节都不动。
    bool hwVolPresent = false;
    int hwVolMin = 0;
    int hwVolMax = 0;    /**     
* 要下发音量的 Feature Unit 通道号。     
*     
* 空 = 用不到；`{0}` = Master（一次设置，最理想）；`{1,2}` = 各声道分别设置     
* （设备没给 Master 但每个声道都有 Volume 的情况）。     */
    std::vector< int > hwVolChannels;    /** 设备声明的步进（通常 1 = 1/256 dB）；0 会被当成 1 */
    int hwVolRes = 1;    /** 最近一次确认生效的值，换 alt 之后用它重下发 */
    int hwVolCur = 0;    /**     
* `wIndex` 的两种约定（0 = 带接口号，1 = 只有 bUnitID）。     
*     
* USB Audio 规范写的是 `(接口号 << 8) | bUnitID`，Linux 的 `snd_usb` 也这么发；     
* 但确实有固件只认裸 `bUnitID`。`setRate` 早就为时钟源两种都试过，音量同理 ——     
* 只实现一种就会在另一半设备上"看不见硬件音量"，然后静默退回软件衰减。     */
    int hwVolIndexKind = 0;    /**     
* 下发通道：播放线程只写这个 atomic，事件线程在 `refill()` 前比对后执行**一次*
*     
* 控制传输。绝不在播放线程上直接发 —— 一次同步控制传输要几百微秒到几毫秒，     
* 落在这条每毫秒都要交 URB 的线程上就是欠载与爆音。     */
    std::atomic< int32_t > pendingHwVol
{
 kNoHwVolPending 
}
;    /**     
* 软件增益（Q16 定点，65536 = 1.0），在 `fill()` 里施加。     
*     
* **必须在这里施加、不能在 Kotlin 侧乘完再写进环**：环里压着约 250 ms 已提交的数据，     
* 换音量时那一段是旧增益算的，要等它播完新音量才生效 —— 手感就是"调了没反应，     
* 过一会儿才变"。放在 `fill()` 里，环里存的是未衰减的原始样本，     
* 生效延迟退化为在飞 URB 的深度（63 ms）。     
*     
* 用定点而不是 `atomic<float>`：float 的原子性与平台实现有关，     
* 而这里每个采样都要读一次，定点读是一个普通的 32 位原子读。     */
    std::atomic< int32_t > gainQ16{ annie::kGainUnity };    /** TPDF 抖动的随机状态；只在事件线程（`fill()`）里读写 */
    uint32_t ditherRng = 0x9E3779B9u;
    /** attach 时 claim 的全部 AS 接口（PCM 与 raw DSD 可能不在同一个接口号上） */
    std::vector< int > claimedIfaces;
    bool acClaimed = false;
    int acIfNumber = -1;
    int acClaimResult = 0;
    int fbSubmitResult = 0;
    std::atomic< int > fbLastStatus
{
 -1 
}
;
    std::atomic< int > inFlight
{
 0 
}
;
    bool drained = true;
    bool leaked = false;
    std::atomic< bool > flushRequested
{
 false 
}
;
    std::atomic< uint64_t > flushTarget
{
 0 
}
;
    bool open( int fd, int wantRate, int wantEncoding, int wantChannels, std::string& why,               bool wantRaw = false, int wantWire = 0 )    
{
        sampleRate = wantRate;
        inEncoding = wantEncoding;
        channels = wantChannels;
        rawDsd = wantRaw;
        wireBits = wantWire;        
// 首次打开 = 播放态。`startStream()` 不碰这个标志了（重建要继承），所以由这里清。
paused.store( false, std::memory_order_release );
        if( !attach( fd, why ))        
{
            return( false );
        
}
        if( !startStream( why ))        
{
            close();
            return( false );
        
}
        return( true );
    
}
    /**     
* 换采样率。     
*     
* 分两条路，界是「当前这条 alt 还能不能承载新率」：     
*     
* 1. **能承载 → 一个字节的流都不拆。*
* 只 `SET_CUR SAMPLING_FREQ`、换掉     
*    `perMicroframeQ16`、把环里换曲前的残留丢掉，URB 一直挂着。     
*    alt setting 与 `outFrameBytes` 都不变，所以缓冲大小、每包样本数全都不用动。     
* 2. 承载不了（新率更快的包塞不进 `wMaxPacketSize`，或声道 / 位宽 / raw 变了）→     
*    走 `alt 0 → alt N` 彻底重建。     
*     
* ## 为什么第 1 条是必须的，不是优化     
*     
* `alt 0 → alt N` 会让设备**重建内部音频通道*
* —— `workerLoop` 末尾那段注释记过：     
* "流还在跑时 `SET_INTERFACE alt 0` 会让设备重建内部通道，'一路先哑'就是从这来的"。     
* 那一下正是"不同采样率之间切歌，一开始一路暂时没声"的来源：同率切歌走的是     
* `AaudioSink` 的复用分支（只 flush，根本不碰 alt），所以不出现。     
*     
* 附带还有一处白掉的时长：`startStream` 之后环是空的，第一批 URB 必然送静音     
* （约 32 ms），每次换率都付一次。不拆流就没有这一段。     */
    bool reopen( int rate, int wantEncoding, int wantChannels, std::string& why,                 bool wantRaw = false, int wantWire = 0 )    
{
        
// 每微帧要送多少字节（多算一帧余量），与 `fill()` 的 `maxSamples` 同一口径
const size_t needPerMicroframe =            ( static_cast< size_t >( rate ) * outFrameBytes ) / kMicroframesPerSecond +            outFrameBytes;        
// 位宽只要**装得下**就留在原地：宽容器装窄内容不丢位（`fill()` 先左对齐到 Q31        
// 再按 subslot 落地），窄容器装宽内容才真的截断。
const bool wireFits = wantWire <= 0 || profile.subslot * 8 >= wantWire;
        const bool canStay = runtimeRateSwitchOk && wantRaw == rawDsd &&                             wantChannels == channels && wireFits && profile.clockId > 0 &&                             static_cast< size_t >( profile.maxPacket ) >= needPerMicroframe;
        if( canStay )        
{
            const int state = setRate( profile.clockId, rate );
            if( state >= 0 )            
{
                sampleRate = rate;
                inEncoding = wantEncoding;
                wireBits = wantWire;
                perMicroframeQ16.store(                    ( static_cast< int64_t >( rate ) << 16 ) / kMicroframesPerSecond,                    std::memory_order_release );
                accQ16 = 0;                
// **反馈值必须作废。** 它报的是设备侧的每微帧样本数，换率之后旧的读数                
// 按新率解释就是错的包长；`onFeedback` 只在偏离名义率 0.5x–2x **之外**                
// 才丢弃，44.1k↔48k 这种小改正好落在区间内，会被当真。                
// 清零之后 `fill()` 退回名义率，下一次反馈回来（1 ms 内）再跟。
feedbackQ16.store( 0, std::memory_order_relaxed );
                fbMin.store( 0, std::memory_order_relaxed );
                fbMax.store( 0, std::memory_order_relaxed );
                fbCount.store( 0, std::memory_order_relaxed );                
// 环里还压着**上一首（旧率）**的数据：不丢的话它会被按新率播出去，                
// 那一段音高全错。`flushRequested` 由 worker 线程在 `fill()` 里执行，                
// 读指针只在那边动，安全。
flushTarget.store( ring.writtenBytes(), std::memory_order_relaxed );
                flushRequested.store( true, std::memory_order_release );                
// 设备重锁 PLL 的那几毫秒里，通道相位可能解错（听感"一路先哑"）。                
// 垫 10 ms 静音把它盖住；静音不计 `framesRead`，时间轴不受影响。
padFramesLeft.store(                    static_cast< uint32_t >( std::max( sampleRate / 100, 64 )),                    std::memory_order_release );
                detail += state == 1 ? "⟳ 只换时钟（alt 未动）✔\n"                                     : "⟳ 只换时钟（alt 未动）⚠ 率未回读\n";
                return( true );
            
}
            
            // 设备拒绝"运行中换率"：记住这件事，之后不再白付这次注定失败的控制传输
            runtimeRateSwitchOk = false;
            detail += "⛔ 设备拒绝运行中换率，改用拆流重建\n";
        
}
        if( !stopStream())        
{
            why = "上一条流未排空";
            return( false );
        
}
        detail += "⛔ 拆流重建（alt 需要换）\n";
        sampleRate = rate;
        inEncoding = wantEncoding;
        channels = wantChannels;        
// raw 与否决定选哪个 alt（raw 与 PCM 的 alt 是互斥的），换曲换模式时必须跟着变，        
// 否则会拿 PCM alt 去送 32 bit DSD word —— 听感是满幅噪声，而且完全不报错。
        rawDsd = wantRaw;        
// 位宽同理：换曲可能换位深，沿用上一条的 alt 会把 24 bit 截成 16 bit
        wireBits = wantWire;
        return( startStream( why ));
    
}
    /**     
* 环形缓冲要开多大 —— **按这条 alt 能承载的最高率算，与当前曲目无关**。     
*     
* 以前按 `sampleRate` 算，而换率现在不重建流（`reopen` 的无缝路径），容量就跟着旧率     
* 停在原地：44.1k 时开的 250 ms，跳到 384k 只剩 29 ms —— 缓冲一薄就欠载，     
* `fill()` 补零的次数上去，听感是断续。按最高率开、**之后永不 resize**，     
* 也顺手消掉了"`ring.init` 与事件线程抢 `data` 的 realloc"这个隐患。     
*     
* 上界卡在 **1 MB**：描述符里的 `wMaxPacketSize` 偶尔是脏值，不能拿它直接乘出容量；     
* 而 1 MB 已经够 250 ms @ 384k/32bit（约 800 KB）。以前卡的是 4 MB，等于常驻     
* 多占 3 MB，且音频数据摊在更大的地址范围里、缓存局部性更差。     */
    size_t ringCapacityBytes( size_t pipeFrames ) const    
{
        const size_t perSecond = outFrameBytes > 0            ? ( static_cast< size_t >( profile.maxPacket ) / outFrameBytes ) *              static_cast< size_t >( kMicroframesPerSecond )            : 0;
        size_t frames = std::max( 
{
 static_cast< size_t >( sampleRate ) / 4,                                    perSecond / 4,                                    pipeFrames * 4 
} );
        const size_t capBytes = 1u << 20;
        if( outFrameBytes > 0 && frames * outFrameBytes > capBytes )        
{
            frames = capBytes / outFrameBytes;
        
}
        return( frames * outFrameBytes );
    
}
    /** 建立连接并 claim 接口 —— 一段会话只做一次 */
    bool attach( int fd, std::string& why )    
{
        
// NO_DEVICE_DISCOVERY：无 root 时不允许枚举 sysfs，必须跳过；随后直接接管 usbfs fd。
libusb_set_option( nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr );
        if( libusb_init( &ctx ) != LIBUSB_SUCCESS )        
{
            error = -1;
            why = "libusb_init 失败";
            return( false );
        
}
        if( libusb_wrap_sys_device( ctx, static_cast< intptr_t >( fd ), &dev ) != LIBUSB_SUCCESS || dev == nullptr )        
{
            error = -2;
            why = "libusb 接管 fd 失败";
            return( false );
        
}
        config = parseConfig( dev );
        detail = config.summary;
        if( config.alts.empty())        
{
            error = -3;
            why = "设备没有可用的音频流接口";
            return( false );
        
}
        libusb_set_auto_detach_kernel_driver( dev, 1 );        
// 时钟源挂在 AudioControl 接口上，而 class 请求的 wIndex 就是它。        
// usbfs 的 check_ctrlrecip 只放行"本 fd 已 claim 的接口"收 interface 收件人的        
// standard/class 请求：不 claim 它，SET_CUR 会被内核直接回 EBUSY，设备停在默认率，        
// 而我们仍按源率算包长 —— 听到的就是噪音。
        if( config.acInterface >= 0 )        
{
            acClaimResult = libusb_claim_interface( dev, config.acInterface );
            if( acClaimResult == LIBUSB_SUCCESS )            
{
                acClaimed = true;
                acIfNumber = config.acInterface;
            
}
        
}
        
// **所有 AS 接口都要 claim**：raw DSD 的 alt 常常和 PCM 分属不同接口号        
// （UAC2 里很常见）。只 claim 一个的话，`usbfs` 的 SET_INTERFACE 会对        
// 未 claim 的接口回 EACCES —— 表现为「设置 alt setting 失败」，        
// 与「设备没有 raw DSD 通道」完全同症状，极难分辨。
        claimedIfaces.clear();
        for( const auto& alt : config.alts )        
{
            if( std::find( claimedIfaces.begin(), claimedIfaces.end(), alt.iface ) ==                claimedIfaces.end())            
{
                claimedIfaces.push_back( alt.iface );
            
}
        
}
        for( int iface : claimedIfaces )        
{
            if( libusb_claim_interface( dev, iface ) != LIBUSB_SUCCESS )            
{
                error = -5;
                why = "claim 接口失败（被其它应用占用？iface " + std::to_string( iface ) + "）";
                return( false );
            
}
        
}
        interfaceClaimed = !claimedIfaces.empty();        
// 硬件音量探测放在最后：它要 claim 了 AC 接口才应答（见 `probeHardwareVolume`）
        probeHardwareVolume();
        return( true );
    
}
    /** 按当前 `sampleRate`/`channels` 起一条流。接口必须已 claim。 */
    bool startStream( std::string& why )    
{
        // **上一条流没排空时，绝对不许在同一条流上重建。**
        //
        // `stopStream()` 在 `drained == false`（还有 transfer 留在内核里）时会提前 return false
        // 并且**故意不 free、也不清 `urbs`** —— 那是为了避开 UAF：free 在飞的 transfer 会崩。
        // 但调用方以前不看这个返回值的后果，`reopen()` 失败后仍然可能走到这里继续 `startStream()`，
        // 而 `urbs.resize(kUrbCount)` 会**保留那 21 个旧条目**，随后循环把 `urb.transfer`
        // 换成新指针、把 `urb.buffer` 整块 assign 掉 —— 也就是：
        //
        //   · 内核仍在按旧 transfer 往**已被替换的缓冲**里 DMA（旧 URB 还挂着端点）；
        //   · 旧 transfer 的回调回来时，`urb.transfer == transfer` 匹配不到任何条目，
        //     于是 `busy` 不会复位，而任何非 COMPLETED 状态都会走到
        //     `error = -8; dead = true` —— 把**新建的这条流**判死；
        //   · `dead` 一置，`fill()` 从此只送静音、`refill()` 不再提交，而 `framesRead` 也不再增长。
        //
        // 表现正是"流开好了、URB 在跑、进度条在走、DAC 一片安静"，而且**只有经过 alt 4
        // （raw DSD）的路径才会踩到**：raw alt 上的 transfer 最容易排不空。
        // 一旦发生，只有把整个会话拆掉重开（`closeEngine()` + `engine.release()`，
        // 也就是"切歌"那条路）才能恢复。
        //
        // 所以这里硬拒绝：让上层走"重开会话"，而不是在同一条脏流上继续。
        if( !urbs.empty() || fbTransfer != nullptr )        
{
            error = -14;
            why = "上一条流未排空（残留 " + std::to_string( urbs.size()) +                  " 条 transfer）：必须重开会话，不能在同一条流上重建";
            return( false );
        
}
        
// 先按率、再按声道。`config.alts` 已按位深偏好排序，所以第一个命中的就是最优。        
//        
// 取得支持的 alt 是 352.8k / 705.6k 能不能跑的前提：这些率需要更大的        
// `wMaxPacketSize`，设备往往把它们放在另一个 alt 里，而那个 alt 的位深可能更差。        
// 声明里找不到目标率时仍退回"按声道选"——描述符不全但实际能跑的设备不少，        
// 直接拒绝等于把它们一并判死。真跑不了会由 `SET_CUR` / alt 切换报错。
        const AltProfile* byRate = nullptr;
        const AltProfile* byChannels = nullptr;
        const AltProfile* byPacket = nullptr;
        const AltProfile* byWide = nullptr;        
// 「装得下」的门槛固定成 24 位，**不跟随当前曲目的位宽**。        
//        
// 曾经按 `wireBits`（当前曲目的源位宽）挑"最窄且装得下"的 alt，目标是让位深显示        
// 与内容一致。但那把 **alt 的选择绑到了曲目**上：16 bit 曲选 subslot 2，        
// 下一首 24 bit 就装不下 → 必须 `alt 0 → alt N` 重建流 → 设备重建内部通道 →        
// "一开始一路先哑"。混排播放列表里每两首就命中一次。        
//        
// 现在固定 24：16 / 24 bit 内容都精确（`fill()` 先左对齐到 Q31 再按 subslot 落地，        
// 宽容器装窄内容不丢位），alt 因此在整条会话里**不会再变**，换率只需要换时钟。        
// 32 bit float 素材才需要更宽，那种情况才退回按 `wireBits` 挑。
const int wantBits = wireBits > 24 ? wireBits : 24;
        for( const auto& alt : config.alts )        
{
            
// raw DSD alt 与 PCM alt 是**互斥的两条路**，必须严格按标志过滤：            
// - 把 PCM 送进 raw alt：设备不报错，只会把 32 bit 当 1-bit 解成满幅噪声            
// - 把 raw 送进 PCM alt：DAC 当普通 PCM 播，同样是噪声            
// 两者 subslot / 声道 / 率可能完全一样，光看这些字段分不开。
            if( alt.rawDsd != rawDsd )            
{
                continue;
            
}
            
// **raw DSD 要求 subslot == 4**：`fill()` 里只有这条分支把 32 bit Q31            
// 原样 memcpy 出去；subslot 2/3 会把它当 16/24 bit PCM 截断或移位 ——            
// 数据当场毁掉，听感就是满幅噪声。宁可不选它，交给上层降级。
if( rawDsd && alt.subslot != 4 )            
{
                continue;
            
}
            if( alt.channels != channels )            
{
                continue;
            
}
            if( byChannels == nullptr )            
{
                byChannels = &alt;
            
}
            
// UAC2 没有 `tSamFreq`（速率在 Clock Source 的 GET_RANGE 里），所以            
// `alt.rates` 常常是空的 —— 那时"按率选"永远失效，只能退化成选第一个，            
// 很容易挑到包长不够装目标率的 alt（高率会被 maxPacket 截断成噪音）。            
// 包长与率成正比，所以无声明时按 maxPacket 降序挑，是唯一有依据的启发式。
if( byPacket == nullptr || alt.maxPacket > byPacket->maxPacket )            
{
                byPacket = &alt;
            
}
            
// 装得下要求的位宽时，在**这些**里挑包最长的 —— 包越长，这条 alt 能承载的            
// 最高率越高，换率时就越不容易掉进"包长不够"的重建分支。
if( alt.subslot * 8 >= wantBits &&                ( byWide == nullptr || alt.maxPacket > byWide->maxPacket ))            
{
                byWide = &alt;
            
}
            if( std::find( alt.rates.begin(), alt.rates.end(), sampleRate ) != alt.rates.end())            
{
                byRate = &alt;
                break;
            
}
        
}
        
// 优先级：声明里命中的率 > 装得下且包最长 > 包最长 > 第一个同声道        
//        
// `byRate` 仍然最高：描述符**逐 alt 声明了 `tSamFreq`** 的设备（UAC1 老机器）        
// 换了 alt 才认那个率，这时"会话里不换 alt"必须让位于正确性。
const AltProfile* chosen = byRate != nullptr            ? byRate            : ( byWide != nullptr ? byWide : ( byPacket != nullptr ? byPacket : byChannels ));
        if( chosen == nullptr )        
{
            error = -4;
            why = "没有匹配声道数的音频流（设备声明 " +                  std::to_string( config.alts.front().channels ) + " ch）";
            return( false );
        
}
        profile = *chosen;        
// 开流参数只在这里能看到：raw 与 PCM 走不同 alt，而"没声 / 满幅噪声"在听感上分不开。
// 这台 ROM 会丢应用日志（连 liblog 都收不到），所以写进 `detail` 由能力面板读走。
        {
            char line[ 176 ];
            std::snprintf( line, sizeof( line ),
                           "流 raw=%d alt=%d iface=%d subslot=%d ch=%d rate=%d clock=%d maxPkt=%d\n",
                           rawDsd ? 1 : 0, profile.alt, profile.iface, profile.subslot,
                           channels, sampleRate, profile.clockId, profile.maxPacket );
            detail += line;
        }
// 顺序与内核 `snd_usb_pcm_prepare()` 一致：**先选 alt setting 再设率**。
        // 反过来（alt 0 时设率）设备会接受请求但不承诺该频率 —— 流没开，时钟是闲的，
        // 于是 SET 返回成功、GET 读不出值，设备照自己的默认率消费，听感就是噪音。
        if( libusb_set_interface_alt_setting( dev, profile.iface, profile.alt ) != LIBUSB_SUCCESS )        
{
            error = -6;
            why = "设置 alt setting 失败";
            return( false );
        
}
        const int activeAlt = currentAltSetting( profile.iface );
        if( activeAlt >= 0 && activeAlt != profile.alt )        
{
            error = -10;
            why = "alt 回读不符：设 " + std::to_string( profile.alt ) +                  "，实际 " + std::to_string( activeAlt );
            return( false );
        
}
        if( profile.clockId > 0 )        
{
            // Clock Selector：率要设到"接受这个率的那个时钟源"上（没有选择器时就是 clockId 本身）
            const int rateClock = rawDsd ? selectClockSource( profile.clockId, sampleRate )                                 : profile.clockId;
            if( rateClock <= 0 )            
{
                error = -13;
                why = "时钟选择器 " + std::to_string( profile.clockId ) +                      " 没有任何一路输入接受 " + std::to_string( sampleRate ) + " Hz";
                return( false );
            
}
            const int rateState = setRate( rateClock, sampleRate );
            if( rateState < 0 )            
{
                error = -11;
                why = "设备拒绝采样率 " + std::to_string( sampleRate ) + " Hz（时钟源 " +                      std::to_string( profile.clockId ) + "）";
                return( false );
            
}
            detail += rateState == 1 ? "✔ 率已确认\n" : "⚠ 率未回读\n";
        
}
        libusb_clear_halt( dev, profile.epOut );
        outFrameBytes = static_cast< size_t >( profile.subslot ) * static_cast< size_t >( channels );
        inFrameBytes = static_cast< size_t >( encodingBytes( inEncoding )) * static_cast< size_t >( channels );
        const size_t pipeFrames = static_cast< size_t >( kPacketsPerUrb ) * kUrbCount *                                  static_cast< size_t >( sampleRate ) / kMicroframesPerSecond;        
// 按这条 alt 的最高承载率分配，换率时不再 resize（理由见 `ringCapacityBytes`）
ring.init( ringCapacityBytes( pipeFrames ), outFrameBytes );
        perMicroframeQ16.store(            ( static_cast< int64_t >( sampleRate ) << 16 ) / kMicroframesPerSecond,            std::memory_order_release );
        feedbackQ16.store( 0, std::memory_order_relaxed );
        fbMin.store( 0, std::memory_order_relaxed );
        fbMax.store( 0, std::memory_order_relaxed );
        fbCount.store( 0, std::memory_order_relaxed );
        fbLastStatus.store( -1, std::memory_order_relaxed );
        fbSubmitResult = 0;        
// 新流：环是空的，第一批 URB 天然是静音，不需要额外垫子
padFramesLeft.store( 0, std::memory_order_release );
        stop.store( false, std::memory_order_release );
        dead.store( false, std::memory_order_release );        
// 新流一律回到"播放"态。
//        
// 曾经刻意不清，让 `reopen()` 的拆流重建继承上一条流的暂停状态（为了"暂停中换率
// 不自己响"）。但拆流重建的主要场景是**切歌 / 切模式**，那时旧流刚被
// `AaudioSink.reset()` 置成暂停 —— 继承下来就是新流一出生只送静音，
// 听感正好是"切到 DSD 之后没声音"。
//
// 真正的播放意图由 `AaudioSink.configure()` 在返回前按 `playRequested` 纠正：
// 三个分支末尾都写了 `resume` / `pause`，所以这里清成 false 只会让"本该播"立刻出声。
        paused.store( false, std::memory_order_release );
        error = 0;
        urbs.resize( kUrbCount );
        for( auto& urb : urbs )        
{
            urb.transfer = libusb_alloc_transfer( kPacketsPerUrb );
            if( urb.transfer == nullptr )            
{
                error = -7;
                why = "分配 transfer 失败";
                return( false );
            
}
            const size_t packetMax = static_cast< size_t >( profile.maxPacket ) + outFrameBytes * 4;
            urb.buffer.assign( packetMax * kPacketsPerUrb, 0 );
            libusb_fill_iso_transfer( urb.transfer, dev, static_cast< unsigned char >( profile.epOut ),                                      urb.buffer.data(), static_cast< int >( urb.buffer.size()),                                      kPacketsPerUrb, &UacStream::onTransfer, this, 0 );
        
}
        prepareFeedback();
        drained = false;        
        // 换 alt / 设备重枚举可能把 DAC 的内部音量打回默认，把确认过的值重新排一次下发
        if( hwVolPresent )
{
            pendingHwVol.store( hwVolCur, std::memory_order_release );
        
}
        worker = std::thread( &UacStream::workerLoop, this );
        return( true );
    
}
    /** 异步 OUT 端点：不读反馈就只能按名义率硬塞，设备按自己的时钟消费 → 持续欠载/溢出 */
    void prepareFeedback()    
{
        if( profile.epFeedback <= 0 || fbTransfer != nullptr )        
{
            return;
        
}
        fbTransfer = libusb_alloc_transfer( kFbPackets );
        if( fbTransfer == nullptr )        
{
            return;
        
}
        libusb_fill_iso_transfer( fbTransfer, dev, static_cast< unsigned char >( profile.epFeedback ),                                  fbBuffer, sizeof( fbBuffer ), kFbPackets,                                  &UacStream::onFeedback, this, 0 );
        for( int i = 0; i < kFbPackets; i++ )        
{
            fbTransfer->iso_packet_desc[ i ].length = 4;
        
}
    
}
    static void LIBUSB_CALL onFeedback( libusb_transfer* transfer )    
{
        auto* self = static_cast< UacStream* >( transfer->user_data );
        if( self == nullptr )        
{
            return;
        
}
        
// 与提交时的 `++` 配对：漏掉这一步 `inFlight` 会单调增长，        
// `close()` 里"等在飞请求回来"就永远退化成等满上限。
self->inFlight.fetch_sub( 1, std::memory_order_release );
        self->fbLastStatus.store( static_cast< int >( transfer->status ), std::memory_order_relaxed );
        if( transfer->status == LIBUSB_TRANSFER_COMPLETED )        
{
            
// 取**最后一个**有效读数：一批微帧里设备通常只更新一两个，            
// 其余是重复或空包。用最后一个 = 用最新的那个。
            int64_t last = 0;
            for( int i = 0; i < kFbPackets; i++ )            
{
                if( transfer->iso_packet_desc[ i ].actual_length < 4 )                
{
                    continue;
                
}
                const uint8_t* p = self->fbBuffer + i * 4;
                const uint32_t raw = static_cast< uint32_t >( p[ 0 ] ) |                                     ( static_cast< uint32_t >( p[ 1 ] ) << 8 ) |                                     ( static_cast< uint32_t >( p[ 2 ] ) << 16 ) |                                     ( static_cast< uint32_t >( p[ 3 ] ) << 24 );
                const int64_t v = static_cast< int64_t >( static_cast< int32_t >( raw ));
                if( v > 0 )                
{
                    last = v;
                
}
            
}
            int64_t value = last;
            const int64_t nominal = self->perMicroframeQ16.load( std::memory_order_acquire );
            if( nominal > 0 && value > 0 )            
{
                const double ratio = static_cast< double >( value ) / static_cast< double >( nominal );                
// 少数固件按"每 1 ms"报（8 个微帧的量），用与名义率的比值兜住
if( ratio > 4.0 && ratio < 12.0 )                
{
                    value /= 8;
                
}
                
// 超出 0.5x..2x 的读数一律当噪声丢弃
if( value < nominal / 2 || value > nominal * 2 )                
{
                    value = 0;
                
}
            
}
            if( value > 0 )            
{
                if( self->fbCount.load( std::memory_order_relaxed ) == 0 )                
{
                    self->fbMin.store( value, std::memory_order_relaxed );
                    self->fbMax.store( value, std::memory_order_relaxed );
                
}                else                
{
                    if( value < self->fbMin.load( std::memory_order_relaxed ))                    
{
                        self->fbMin.store( value, std::memory_order_relaxed );
                    
}
                    if( value > self->fbMax.load( std::memory_order_relaxed ))                    
{
                        self->fbMax.store( value, std::memory_order_relaxed );
                    
}
                
}
                self->fbCount.fetch_add( 1, std::memory_order_relaxed );
                self->feedbackQ16.store( value, std::memory_order_relaxed );
            
}
        
}
        if( !self->stop.load( std::memory_order_acquire ) && !self->dead.load( std::memory_order_acquire ))        
{
            self->inFlight.fetch_add( 1, std::memory_order_release );
            libusb_submit_transfer( transfer );
        
}
    
}
    /**     
* 设率。`wIndex` 的编码在不同固件上不一致（UAC2 规范是实体 ID，     
* 但也有固件要求 (接口号<<8)|实体 ID），所以两种都试一遍，并记录每一步的返回码。     
*     
* @return 1 = 已确认，0 = 发出去了但设备不回读，-1 = 两种形式都被拒     */
    int setRate( int clockId, int rate )    
{
        uint8_t data[ 4 ] = 
{
            static_cast< uint8_t >( rate & 0xFF ),            static_cast< uint8_t >(( rate >> 8 ) & 0xFF ),            static_cast< uint8_t >(( rate >> 16 ) & 0xFF ),            static_cast< uint8_t >(( rate >> 24 ) & 0xFF ),        
}
;
        const int candidates[ 2 ] = 
{
            clockId,            (( acIfNumber > 0 ? acIfNumber : 0 ) << 8 ) | clockId,        
}
;
        int best = -1;
        char line[ 192 ];
        for( int i = 0; i < 2; i++ )        
{
            const int wi = candidates[ i ];
            if( i == 1 && wi == candidates[ 0 ] )            
{
                continue;
            
}
            int sent = libusb_control_transfer(                dev, kRequestTypeClassOut, kRequestSetCur,                static_cast< uint16_t >( kCsSamFreqControl << 8 ),                static_cast< uint16_t >( wi ), data, 4, 500 );
            // EPIPE（-9）在内核里的含义是"这个控制传输被 STALL 了"，而 STALL 常常只是
            // 端点停机状态留下的残留。内核 `snd_usb_clock_set_rate` 的做法是清 halt 再来一次；
            // 海贝 `set_sample_rate_v2` 同样是 `if (r == -9) { clear_halt(dev, 0); 重发 }`。
            // 不做这一步，一次偶发 STALL 就会被当成"设备拒绝这个率"而整条流起不来。
            if( sent == LIBUSB_ERROR_PIPE )            
{
                libusb_clear_halt( dev, 0 );
                sent = libusb_control_transfer(                    dev, kRequestTypeClassOut, kRequestSetCur,                    static_cast< uint16_t >( kCsSamFreqControl << 8 ),                    static_cast< uint16_t >( wi ), data, 4, 500 );
            
}
            uint8_t back[ 4 ] = 
{
 0 
}
;
            const int got = libusb_control_transfer(                dev, kRequestTypeClassIn, kRequestGetCur,                static_cast< uint16_t >( kCsSamFreqControl << 8 ),                static_cast< uint16_t >( wi ), back, 4, 200 );
            int read = 0;
            if( got >= 4 )            
{
                read = static_cast< int >( static_cast< uint32_t >( back[ 0 ] ) |                                           ( static_cast< uint32_t >( back[ 1 ] ) << 8 ) |                                           ( static_cast< uint32_t >( back[ 2 ] ) << 16 ) |                                           ( static_cast< uint32_t >( back[ 3 ] ) << 24 ));
            
}
            std::snprintf( line, sizeof( line ),                           "设率 wIndex=0x%04X: SET=%d GET=%d 回读=%d\n", wi, sent, got, read );
            detail += line;
            if( sent >= 0 && read == rate )            
{
                return( 1 );
            
}
            if( sent >= 0 && best < 0 )            
{
                best = 0;
            
}
        
}
        return( best );
    
}
    static int readU24( const uint8_t* p )    
{
        return( static_cast< int >( static_cast< uint32_t >( p[ 0 ]) |                                              ( static_cast< uint32_t >( p[ 1 ]) << 8 ) |                                              ( static_cast< uint32_t >( p[ 2 ]) << 16 )));
    
}
    /** 这个 Clock Source 是否接受 `rate`（GET_RANGE）。**查不到一律 false —— 不猜。** */
    bool rateInRange( int clockSourceId, int rate ) const    
{
        uint8_t data[ 2 + 3 * 4 * 16 ] = 
{
 0 
}
;
        const int read = libusb_control_transfer(            dev, kRequestTypeClassIn, kRequestGetRange,            static_cast< uint16_t >( kCsSamFreqControl << 8 ),            static_cast< uint16_t >( clockSourceId ), data, static_cast< int >( sizeof( data )), 300 );
        if( read < 2 )        
{
            return( false );
        
}
        const int count = data[ 0 ];        
// bSamFreqType == 0：连续区间，只给 [min, max]
        if( count == 0 )        
{
            if( read < 8 )            
{
                return( false );
            
}
            const int lo = readU24( data + 2 );
            const int hi = readU24( data + 5 );
            return( rate >= lo && rate <= hi );
        
}
        for( int i = 0; i < count; i++ )        
{
            const int off = 2 + i * 12;
            if( off + 9 > read )            
{
                break;
            
}
            const int lo = readU24( data + off );
            const int hi = readU24( data + off + 3 );
            const int res = readU24( data + off + 6 );
            if( rate >= lo && rate <= hi && ( res == 0 || (( rate - lo ) % res ) == 0 ))            
{
                return( true );
            
}
        
}
        return( false );
    
}
    /**
* 给 Clock Selector 选一路输入，返回"这个率应该设给哪个时钟实体"。
* 
* 返回 0 = 什么都不选（没有可确认接受该率的输入，保持默认）。
* 返回 `selectorId` 本身 = 这个设备根本没有选择器（常态），率还是设在它身上。
* 返回某个输入 id = 已选中那一路，**率要设在那个 Clock Source 上**，不是设在选择器上
* （选择器没有 SAMPLING_FREQ 控制，往它身上 SET_CUR 只会吃到 STALL）。
* 
* **只选"真的接受这个率"的那一路**：每个候选都问它自己的 `GET_RANGE`，问不到就跳过。
* 宁可一个都不选（保持默认），也不按描述符顺序瞎点 —— 选错那一路的后果与不选一样是没声音，
* 但"选错"会把一台本来正常的设备搞坏，而且事后完全看不出原因。
*/
    int selectClockSource( int selectorId, int rate )    
{
        for( const auto& entry : config.clockSelectorInputs )        
{
            if( entry.first != selectorId )            
{
                continue;
            
}
            if( entry.second.empty())            
{
                return( 0 );
            
}
            const int wiCandidates[ 2 ] = 
{
  selectorId,                (( acIfNumber > 0 ? acIfNumber : 0 ) << 8 ) | selectorId            
}
;
            for( int src : entry.second )            
{
                if( !rateInRange( src, rate ))                
{
                    continue;
                
}
                uint8_t value = static_cast< uint8_t >( src );
                for( int i = 0; i < 2; i++ )                
{
                    const int wi = wiCandidates[ i ];
                    if( i == 1 && wi == wiCandidates[ 0 ] )                    
{
                        continue;
                    
}
                    const int sent = libusb_control_transfer(                        dev, kRequestTypeClassOut, kRequestSetCur,                        static_cast< uint16_t >(( kCsSelectorControl << 8 ) | 0 ),                        static_cast< uint16_t >( wi ), &value, 1, 300 );
                    if( sent >= 1 )                    
{
                        detail += "时钟选择器 " + std::to_string( selectorId ) +                                  " → 时钟源 " + std::to_string( src ) + "（该源接受这个率）\n";
                        return( src );
                    
}
                
}
            
}
            return( 0 );
        
}
        
// 没有选择器：`selectorId` 本身就是 Clock Source，不需要选，率还是设在它身上
        return( selectorId );
    
}
    
// ── 硬件音量（Feature Unit 的 Volume 控制）──    
//    
// 单位 1/256 dB，**数值是衰减量**：0 = 不衰减。衰减在 DAC 内部完成，    
// 我方送过去的 PCM 一个字节都不动 —— 这是 bit perfect 下唯一能改音量的途径。
    int hwVolIndex() const    
{
        if( hwVolIndexKind == 1 )        
{
            return( config.featureUnitId & 0xFF );
        
}
        return((( acIfNumber > 0 ? acIfNumber : 0 ) << 8 ) | ( config.featureUnitId & 0xFF ));
    
}
    /** 两种 `wIndex` 约定；返回候选个数 */
    int hwVolIndexCandidates( int* out ) const    
{
        out[ 0 ] = (( acIfNumber > 0 ? acIfNumber : 0 ) << 8 ) | ( config.featureUnitId & 0xFF );
        out[ 1 ] = config.featureUnitId & 0xFF;
        return( out[ 0 ] == out[ 1 ] ? 1 : 2 );
    
}
    /** 读某个通道的 GET_CUR；-1 = 设备不应答。`wValue` 低位就是通道号（0 = Master） */
    int getHardwareVolume( int channel )    
{
        if( config.featureUnitId <= 0 )        
{
            return( -1 );
        
}
        uint8_t buf[ 2 ] = 
{
 0 
}
;
        const int read = libusb_control_transfer(            dev, kRequestTypeClassIn, kRequestGetCur,            static_cast< uint16_t >(( kCsVolumeControl << 8 ) | ( channel & 0xFF )),            static_cast< uint16_t >( hwVolIndex()),            buf, 2, 200 );
        if( read < 2 )        
{
            return( -1 );
        
}
        return( static_cast< int16_t >( buf[ 0 ] | ( buf[ 1 ] << 8 )));
    
}
    /**     
* 探测 Feature Unit 的音量范围。     
*     
* **必须在 claim 了 AudioControl 接口之后调*
* —— 部分设备在接口未被 claim 时     
* 直接 STALL 这些 class 请求（`wIndex` 的高字节就是那个接口号）。     
*     
* GET_RANGE 返回 `wNumSubRanges` 后跟每段 (wMIN, wMAX, wRES)，各 2 字节。     
* 音量控制基本只有一段，只读第一段。     */
    void probeHardwareVolume()    
{
        hwVolPresent = false;
        hwVolChannels.clear();
        if( dev == nullptr )        
{
            detail += "硬件音量 ✗ 没有设备句柄\n";
            return;
        
}
        if( config.featureUnitId <= 0 )        
{
            detail += "硬件音量 ✗ 描述符里没有 Feature Unit\n";
            return;
        
}
        
// 选通道：Master 声明了 Volume 就用它（一次设置覆盖全部声道）；        
// 否则逐个声道 —— 但**必须每个声道都有**，只调一路会造成左右不平衡，        
// 那种情况宁可不做硬件音量，退回软件衰减（代价明确、不会跑偏）。
if( !config.featureControls.empty() &&            ( config.featureControls[ 0 ] & kFeatureVolume ) != 0 )        
{
            hwVolChannels.push_back( 0 );
        
}        else        
{
            std::vector< int > perChannel;
            for( int c = 1; c < static_cast< int >( config.featureControls.size()); c++ )            
{
                if(( config.featureControls[ c ] & kFeatureVolume ) == 0 )                
{
                    perChannel.clear();
                    break;
                
}
                perChannel.push_back( c );
            
}
            if( perChannel.size() >= 2 )            
{
                hwVolChannels = perChannel;
            
}
        
}
        if( hwVolChannels.empty())        
{
            std::string bits;
            for( size_t i = 0; i < config.featureControls.size(); i++ )            
{
                char b[ 12 ];
                std::snprintf( b, sizeof( b ), "%s0x%02X", i ? " " : "", config.featureControls[ i ] );
                bits += b;
            
}
            char line[ 220 ];
            std::snprintf( line, sizeof( line ),                           "硬件音量 ✗ FU=%d 没有可用的 Volume 控制（控制位 %s = Master/各声道）\n",                           config.featureUnitId, bits.empty() ? "?" : bits.c_str());
            detail += line;
            return;
        
}
        const int channel = hwVolChannels.front();
        int candidates[ 2 ] = 
{
 0, 0 
}
;
        const int count = hwVolIndexCandidates( candidates );
        uint8_t buf[ 8 ] = 
{
 0 
}
;
        int chosen = -1;
        int read = 0;
        for( int i = 0; i < count; i++ )        
{
            std::memset( buf, 0, sizeof( buf ));
            read = libusb_control_transfer(                dev, kRequestTypeClassIn, kRequestGetRange,                static_cast< uint16_t >(( kCsVolumeControl << 8 ) | ( channel & 0xFF )),                static_cast< uint16_t >( candidates[ i ]),                buf, static_cast< int >( sizeof( buf )), 300 );
            if( read >= 8 )            
{
                chosen = i;
                break;
            
}
            char line[ 128 ];
            std::snprintf( line, sizeof( line ),                           "硬件音量 · GET_RANGE wIndex=0x%04X → %d\n", candidates[ i ], read );
            detail += line;
        
}
        if( chosen < 0 )        
{
            detail += "硬件音量 ✗ 设备不应答 GET_RANGE\n";
            hwVolChannels.clear();
            return;
        
}
        hwVolIndexKind = chosen;
        const int min = static_cast< int16_t >( buf[ 2 ] | ( buf[ 3 ] << 8 ));
        const int max = static_cast< int16_t >( buf[ 4 ] | ( buf[ 5 ] << 8 ));
        const int res = static_cast< int16_t >( buf[ 6 ] | ( buf[ 7 ] << 8 ));        
        // 只有一个档位等于没有音量控制 —— 与 `has_hardware_vol_ctrl` 的判据同源
        if( max <= min )
{
            char line[ 128 ];
            std::snprintf( line, sizeof( line ),                           "硬件音量 ✗ 只有一个档位 [%d, %d]\n", min, max );
            detail += line;
            hwVolChannels.clear();
            return;
        
}
        int cur = getHardwareVolume( channel );
        if( cur < min || cur > max )        
{
            cur = max;
        
}
        hwVolMin = min;
        hwVolMax = max;
        hwVolRes = res > 0 ? res : 1;
        hwVolCur = cur;
        hwVolPresent = true;
        std::string chan;
        for( size_t i = 0; i < hwVolChannels.size(); i++ )        
{
            chan += ( i ? "," : "" ) + std::to_string( hwVolChannels[ i ] );
        
}
        char line[ 192 ];
        std::snprintf( line, sizeof( line ),                       "硬件音量 ✔ FU=%d 通道 %s wIndex=0x%04X 范围 [%d, %d] 步进 %d 当前 %d\n",                       config.featureUnitId, chan.c_str(), candidates[ chosen ],                       min, max, hwVolRes, cur );
        detail += line;
    
}
    /**     
* 在**事件线程**上执行挂起的音量下发。     
*     
* 播放线程只写 `pendingHwVol`：一次同步控制传输要几百微秒到几毫秒，     
* 落在每毫秒都要交 URB 的那条线程上就是欠载与爆音。     */
    void applyPendingHardwareVolume()    
{
        if( !hwVolPresent )        
{
            pendingHwVol.store( kNoHwVolPending, std::memory_order_release );
            return;
        
}
        const int32_t v = pendingHwVol.exchange( kNoHwVolPending, std::memory_order_acquire );
        if( v == kNoHwVolPending )        
{
            return;
        
}
        const int clamped = v < hwVolMin ? hwVolMin : ( v > hwVolMax ? hwVolMax : v );
        const int16_t raw = static_cast< int16_t >( clamped );
        uint8_t data[ 2 ] = 
{
            static_cast< uint8_t >( raw & 0xFF ),            static_cast< uint8_t >(( raw >> 8 ) & 0xFF ),        
}
;        
// 没有 Master 的设备要逐个声道下发（见 `probeHardwareVolume` 的通道选择）
        bool allOk = true;
        for( int channel : hwVolChannels )        
{
            const int sent = libusb_control_transfer(                dev, kRequestTypeClassOut, kRequestSetCur,                static_cast< uint16_t >(( kCsVolumeControl << 8 ) | ( channel & 0xFF )),                static_cast< uint16_t >( hwVolIndex()),                data, 2, 300 );
            if( sent < 0 )            
{
                allOk = false;
            
}
        
}
        if( allOk )        
{
            hwVolCur = clamped;
        
}
    
}
    /** `GET_INTERFACE`：当前生效的 alt setting；-1 = 设备不应答 */
    int currentAltSetting( int iface ) const    
{
        uint8_t value = 0;
        const int read = libusb_control_transfer(            dev, 0x81, kRequestGetInterface, 0, static_cast< uint16_t >( iface ), &value, 1, 200 );
        return( read == 1 ? static_cast< int >( value ) : -1 );
    
}
    int deviceRate() const    
{
        if( dev == nullptr || profile.clockId <= 0 )        
{
            return( 0 );
        
}
        uint8_t back[ 4 ] = 
{
 0 
}
;
        const int read = libusb_control_transfer(            dev, kRequestTypeClassIn, kRequestGetCur,            static_cast< uint16_t >( kCsSamFreqControl << 8 ),            static_cast< uint16_t >( profile.clockId ), back, 4, 200 );
        if( read < 4 )        
{
            return( 0 );
        
}
        return( static_cast< int >( static_cast< uint32_t >( back[ 0 ] ) |                                    ( static_cast< uint32_t >( back[ 1 ] ) << 8 ) |                                    ( static_cast< uint32_t >( back[ 2 ] ) << 16 ) |                                    ( static_cast< uint32_t >( back[ 3 ] ) << 24 )));
    
}
    size_t push( const uint8_t* src, size_t frames, size_t frameBytesIn )    
{
        if( src == nullptr || frames == 0 || frameBytesIn == 0 )        
{
            return( 0 );
        
}
        const size_t needed = frames * outFrameBytes;
        if( scratch.size() < needed )        
{
            scratch.resize( needed );
        
}
        
// 源样本宽度以 `frameBytesIn` 为准，不看 `inEncoding`：同一条流会被连续几首复用        
// （`AaudioSink` 只在采样率/声道数变化时才重建），位深可以逐首不同。
const size_t perChannel = static_cast< size_t >( channels ) > 0            ? frameBytesIn / static_cast< size_t >( channels )            : 0;
        if( perChannel < 2 || perChannel > 4 )        
{
            return( 0 );
        
}
        const int outSubslot = profile.subslot;
        uint8_t* dst = scratch.data();
        for( size_t f = 0; f < frames; f++ )        
{
            const uint8_t* in = src + f * frameBytesIn;
            uint8_t* out = dst + f * outFrameBytes;
            for( int c = 0; c < channels; c++ )            
{
                const uint8_t* s = in + c * perChannel;
                int32_t q;   
                // Q31
                if( perChannel == 2 )
{
                    int16_t v;
                    std::memcpy( &v, s, 2 );
                    q = static_cast< int32_t >( v ) << 16;
                
}
                else if( perChannel == 3 )
{
                    const uint32_t v = static_cast< uint32_t >( s[ 0 ] ) |                                       ( static_cast< uint32_t >( s[ 1 ] ) << 8 ) |                                       ( static_cast< uint32_t >( s[ 2 ] ) << 16 );                    
// 符号位在 bit23，整体左移 8 位落到 Q31；用无符号移位避免有符号左移的 UB
                    q = static_cast< int32_t >( v << 8 );
                
}
                else if( inEncoding == kEncFloat )
{
                    float v;
                    std::memcpy( &v, s, 4 );
                    v = std::max( -1.0f, std::min( 1.0f, v ));
                    q = static_cast< int32_t >( std::lrintf( v * 2147483520.0f ));
                
}                else                
{
                    
                    // 整数 32 位：左对齐到 Q31 是恒等
                    std::memcpy( &q, s, 4 );
                
}
                if( outSubslot == 2 )                
{
                    
// half-ULP 舍入：直接截断会让 45% 的样本低 1 LSB（均值 −0.5 LSB）
                    const int32_t rounded = q + ( 1 << 15 );
                    const int16_t v = static_cast< int16_t >( rounded >> 16 );
                    std::memcpy( out + c * 2, &v, 2 );
                
}                else if( outSubslot == 3 )                
{
                    const int32_t rounded = q + ( 1 << 7 );
                    out[ c * 3 + 0 ] = static_cast< uint8_t >(( rounded >> 8 ) & 0xFF );
                    out[ c * 3 + 1 ] = static_cast< uint8_t >(( rounded >> 16 ) & 0xFF );
                    out[ c * 3 + 2 ] = static_cast< uint8_t >(( rounded >> 24 ) & 0xFF );
                
}                else                
{
                    std::memcpy( out + c * 4, &q, 4 );
                
}
            
}
        
}
        const size_t written = ring.write( dst, frames );
        if( written > 0 )        
{
            framesWritten.fetch_add( static_cast< int64_t >( written ), std::memory_order_relaxed );
        
}
        return( written );
    
}
    /** 流复用期间位深可以变，只有 float 与 int32 同为 4 字节、必须靠编码区分 */
    void setInputEncoding( int encoding )    
{
        inEncoding = encoding;
        inFrameBytes = static_cast< size_t >( encodingBytes( encoding )) * static_cast< size_t >( channels );
    
}
    static void LIBUSB_CALL onTransfer( libusb_transfer* transfer )    
{
        auto* self = static_cast< UacStream* >( transfer->user_data );
        if( self == nullptr )        
{
            return;
        
}
        self->inFlight.fetch_sub( 1, std::memory_order_release );        
// 只标记空闲，**重提交一律交给 `refill()`**：回调里再提交的话，        
// 它与事件循环的补提会抢同一条 URB。
        for( auto& urb : self->urbs )        
{
            if( urb.transfer == transfer )            
{
                urb.busy.store( false, std::memory_order_release );
                break;
            
}
        
}
        if( transfer->status != LIBUSB_TRANSFER_COMPLETED )        
{
            
// 正常关闭时所有在飞 transfer 都会以 CANCELLED 回来，那不是故障。            
// 以前这里无条件盖 `dead`/`error=-8`，于是每次关闭都给自己留一个坏标志。
if( transfer->status == LIBUSB_TRANSFER_CANCELLED &&                self->stop.load( std::memory_order_acquire ))            
{
                return;
            
}
            self->error = -8;
            self->dead.store( true, std::memory_order_release );
            return;
        
}
        for( int i = 0; i < kPacketsPerUrb; i++ )        
{
            if( transfer->iso_packet_desc[ i ].status != LIBUSB_TRANSFER_COMPLETED )            
{
                self->underruns.fetch_add( 1, std::memory_order_relaxed );
            
}
        
}
    
}
    /** 按在飞数量补齐流水线。内核在 HCD 完成上下文里重提，不受用户态调度影响；我们必须自己补。 */
    void refill()    
{
        for( auto& urb : urbs )        
{
            if( stop.load( std::memory_order_acquire ) || dead.load( std::memory_order_acquire ))            
{
                return;
            
}
            bool expected = false;
            if( !urb.busy.compare_exchange_strong( expected, true, std::memory_order_acq_rel ))            
{
                continue;
            
}
            fill( urb );
            inFlight.fetch_add( 1, std::memory_order_release );
            const int submit = libusb_submit_transfer( urb.transfer );
            if( submit != LIBUSB_SUCCESS )            
{
                urb.busy.store( false, std::memory_order_release );
                inFlight.fetch_sub( 1, std::memory_order_release );
                error = -9;
                dead.store( true, std::memory_order_release );
                return;
            
}
        
}
    
}
    
// 不做水位修正。    
//    
// 环形缓冲的水位**不是**设备时钟的观测点：Media3 会一直推到 `write` 返回 0（环满），    
// 所以水位恒接近容量，拿它当误差信号等于恒定偏置 —— 饱和时是 ±8000 帧/秒（44.1k 上    
// ±18%），设备侧只能整块丢样或反复重同步，听感就是噪音。    
// 设备时钟与主机的差值只能靠 `bSynchAddress` 指向的反馈端点测；在接上它之前，    
// 按名义率的精确分数累加送出，漂移由环形缓冲吸收（250 ms 缓冲 / 100 ppm ≈ 每几分钟一次欠载）。    /** 就地施加软件增益（含 TPDF 抖动）；实现在 `pcm-ring.h`，两个直出内核共用一份 */
    void applyGain( uint8_t* p, size_t samples, size_t subslot, int32_t gainQ16 )    
{
        annie::pcmApplyGain( p, samples, subslot, gainQ16, ditherRng );
    
}
    /**
* 送不出数据时该填什么字节（暂停 / 开流初期 / 欠载）。
* 
* **PCM 填 0x00，raw DSD 必须填 0x96。**
* 
* 0x00 的 DSD 位流不是一个"静音"，而是一个**满幅负直流**：每个 1-bit 样本都是 0，
* 解调后是一根恒定电平。DAC 对这种流的处理各不相同 —— 有的当无效数据直接静音，
* 有的把直流送出去（照样听不见）。而 **0x96 才是 DSD 的静音模式**（交替位型，
* 均值正好落在零），任何 DSD 解调器都认它。
* 
* 这条路径不是边角情况：`startStream()` 之后环是空的，21 条 URB 先提交、数据后到，
* 所以**每一首 raw DSD 都以一段全 0 开头**（约 63 ms）。那一段正是"设备不报错、
* 进度条照走、耳朵里什么都没有"的来历。海贝的 `prepare_out_urbs` 在欠载时对
* DSD 用的是 `memset(0x96)`、对 PCM 才用 0，这里对齐它。
*/
    void silenceFill( uint8_t* p, size_t bytes )    
{
        std::memset( p, rawDsd ? 0x96 : 0x00, bytes );
    
}
    void fill( Urb& urb )    
{
        
// flush 的指针推进只在**本线程**做。播放线程直接改读指针会与这里的 `read()`        
// 抢同一个变量：轻则读指针回退（重放旧音频），重则 `readPos > writePos`。
if( flushRequested.exchange( false, std::memory_order_acq_rel ))        
{
            ring.discardTo( flushTarget.load( std::memory_order_relaxed ));
        
}
        
// 有反馈就用反馈：设备按自己的时钟收，我们按它要的量给
const int64_t fb = feedbackQ16.load( std::memory_order_relaxed );
        const int64_t nominal = perMicroframeQ16.load( std::memory_order_acquire );
        const int64_t perMicroframe = fb > 0 ? fb : nominal;
        const size_t maxSamples = outFrameBytes > 0            ? static_cast< size_t >( profile.maxPacket ) / outFrameBytes            : 0;
        size_t offset = 0;
        for( int i = 0; i < kPacketsPerUrb; i++ )        
{
            accQ16 += static_cast< uint32_t >( perMicroframe );
            size_t samples = accQ16 >> 16;
            accQ16 &= 0xFFFF;            
// 每包长度受端点 wMaxPacketSize 硬约束，超了内核直接 EMSGSIZE
            if( maxSamples > 0 && samples > maxSamples )
{
                samples = maxSamples;
                droppedSamples.fetch_add( 1, std::memory_order_relaxed );
            
}
            const size_t bytes = samples * outFrameBytes;
            uint8_t* dst = urb.buffer.data() + offset;            
// 换率后的静音垫：先把它吃掉，剩下的才从环里取。设备重锁 PLL 的那几毫秒            
// 落在静音里，通道相位就算解错也听不出来。**不计 `framesRead`** ——            
// 它不是媒体内容，计进去进度条与 `startMediaTimeUs` 会整体偏移。
            size_t pad = 0;
            const uint32_t padLeft = padFramesLeft.load( std::memory_order_acquire );
            if( padLeft > 0 )            
{
                pad = std::min< size_t >( samples, padLeft );
                padFramesLeft.store(                    static_cast< uint32_t >( padLeft - pad ), std::memory_order_release );
            
}
            if( paused.load( std::memory_order_acquire ) || dead.load( std::memory_order_acquire ))            
{
                silenceFill( dst, bytes );
            
}            else            
{
                if( pad > 0 )                
{
                    silenceFill( dst, pad * outFrameBytes );
                
}
                const size_t want = samples - pad;
                const size_t got = want > 0 ? ring.read( dst + pad * outFrameBytes, want ) : 0;
                if( got < want )                
{
                    silenceFill( dst + ( pad + got ) * outFrameBytes,                                 ( want - got ) * outFrameBytes );
                    underruns.fetch_add( 1, std::memory_order_relaxed );
                
}
                if( got > 0 )                
{
                    framesRead.fetch_add( static_cast< int64_t >( got ), std::memory_order_relaxed );                    
// 增益在这里施加：环里存的是未衰减的原始样本，所以换音量时                    
// 只剩在飞 URB（63 ms）的延迟，而不是整个环（250 ms）
//                    
// **raw DSD 一律跳过**：那个 32 bit word 装的是 32 个 1-bit 样本，乘任何系数
// （包括 0 与 0.9）都会把它毁成静音或满幅噪声。上层的 `ExclusiveVolume` 状态是
// 异步更新的，切模式那几块数据并不可靠 —— 这里读的是本条流自己的 `rawDsd`。
if( !rawDsd )                    
{
                        applyGain(                        dst + pad * outFrameBytes,                        got * static_cast< size_t >( channels ),                        outFrameBytes / static_cast< size_t >( channels ),                        gainQ16.load( std::memory_order_acquire ));
                    
}
                }
            
}
            urb.transfer->iso_packet_desc[ i ].length = static_cast< unsigned int >( bytes );
            offset += bytes;
        
}
        urb.transfer->length = static_cast< int >( offset );
    
}
    /**     
* 事件线程**不提优先级**。     
*     
* 这里以前先试 `SCHED_FIFO` 优先级 2，失败退 `setpriority(-16)`。两个理由都不成立了：     
*     
* 1. **它本来就不该优先。*
* 这个线程只做"收完成、回填、重提交"，真正吃 CPU 的     
*    （ffmpeg 解码 / r8brain / 打包 / ring 拷贝）全在播放线程上。给它 FIFO 或 −16     
*    等于让一个每毫秒醒一次的轮询线程去抢播放线程的 CPU —— 重载时会直接制造欠载。     
* 2. **唤醒率已经降下来了**（URB 24 包 + 反馈 8 包 ≈ 每秒 1300 次，原来是 9000 次），     
*    不再需要靠抢占换及时性。     
*     
* 需要提优先级的是**播放线程**（喂数据那一侧），在 Kotlin 侧做（`AaudioSink`）。     */
    static void raiseWorkerPriority()    
{
        
// 普通调度即可；只把 nice 从后台继承值抬回 0，避免落进 background 调度组
setpriority( PRIO_PROCESS, 0, 0 );
    
}
    void workerLoop()    
{
        raiseWorkerPriority();
        submitFeedback();
        refill();
        while( !stop.load( std::memory_order_acquire ) && !dead.load( std::memory_order_acquire ))        
{
            
// 暂停分两段：            
//            
// · **前 2 秒照常送零**。拨进度、回个消息这种短暂停不该让设备断流 ——            
//   断流再恢复要重新起 URB，设备侧可能咔哒一下。            
// · **超过 2 秒就真停**：不再收事件、不再重提交，线程停在条件变量上。            
//   以前暂停只是往包里塞零，URB 与反馈转移照常按微帧节奏完成            
//   （反馈那条曾经是 8000 次/秒），暂停和正在播放一样耗电。            
//            
// 停的时候 alt setting 与时钟**都不动**，所以恢复不需要重锁时钟。            
// 已提交的 URB 会在内核里完成并等在那里，用户态不收就没有回调；            
// 在飞数量有上限（kUrbCount），不会累积。
const bool isPaused = paused.load( std::memory_order_acquire );
            if( isPaused )            
{
                const auto now = std::chrono::steady_clock::now();
                if( !pauseTimerStarted )                
{
                    pauseTimerStarted = true;
                    pauseSince = now;
                
}
                if( now - pauseSince >= std::chrono::milliseconds( kPauseDeepAfterMs ))                
{
                    std::unique_lock< std::mutex > lock( pauseMx );
                    pauseCv.wait_for( lock, std::chrono::milliseconds( 500 ), [ this ] 
{
                        return( !paused.load( std::memory_order_acquire ) ||                                stop.load( std::memory_order_acquire ) ||                                dead.load( std::memory_order_acquire ));                    
} );
                    continue;
                
}
            
}            else            
{
                pauseTimerStarted = false;
            
}
            
// 2 ms：`refill()` 要趁早补，但其实现阶段靠的是完成事件本身在驱动唤醒，            
// 这个超时只是兜底（万一完成事件丢了也能醒过来）。
            timeval tv
{
 0, 2 * 1000 
}
;
            libusb_handle_events_timeout_completed( ctx, &tv, nullptr );
            refill();            
// 音量下发在这里做：**不能**放到播放线程（一次控制传输几百微秒起，            
// 那条线程每毫秒都要交 URB）。已挂起的值取一次就清，不会堆积。
            applyPendingHardwareVolume();
        
}
        for( auto& urb : urbs )        
{
            if( urb.transfer != nullptr )            
{
                libusb_cancel_transfer( urb.transfer );
            
}
        
}
        if( fbTransfer != nullptr )        
{
            libusb_cancel_transfer( fbTransfer );
        
}
        
// 必须在**所有 transfer 都回来之后**才能 free + 切 alt：free 在飞的 transfer 是 UAF，        
// 而流还在跑时 `SET_INTERFACE alt 0` 会让设备重建内部通道（"一路先哑"就是从这来的）。        
// 所以这里等得比原来久；只有确实归零才做拆除，否则留给泄漏，绝不冒险。
const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds( 1000 );
        while( inFlight.load( std::memory_order_acquire ) > 0 &&               std::chrono::steady_clock::now() < deadline )        
{
            timeval tv
{
 0, 2 * 1000 
}
;
            libusb_handle_events_timeout_completed( ctx, &tv, nullptr );
        
}
        drained = inFlight.load( std::memory_order_acquire ) == 0;
    
}
    /** 反馈转移的首次提交（`workerLoop` 与 `startStream` 之外只有这一处） */
    void submitFeedback()    
{
        if( fbTransfer == nullptr )        
{
            return;
        
}
        inFlight.fetch_add( 1, std::memory_order_release );
        fbSubmitResult = libusb_submit_transfer( fbTransfer );
        if( fbSubmitResult != LIBUSB_SUCCESS )        
{
            
// 反馈拿不到只意味着退回名义率，不是流坏了；            
// 但不减 `inFlight` 会让 `close()` 的 drain 永远等满上限。
inFlight.fetch_sub( 1, std::memory_order_release );
        
}
    
}
    /** 停流：停 URB、放 transfer、回 alt 0。**保留*
* dev/ctx 与接口 claim。 */
    bool stopStream()    
{
        stop.store( true, std::memory_order_release );        
        // 事件线程可能正停在暂停的条件变量上，必须叫醒，否则 join 会一直等
        pauseCv.notify_all();
        if( worker.joinable())        
{
            worker.join();
        
}
        
        // 深暂停的时间戳属于上一条流，留着会让重建后的新流一上来就判"已暂停超时"
        pauseTimerStarted = false;
        if( !drained )        
{
            
// 还有 transfer 没回来：free 它就是 UAF，`libusb_close`/`libusb_exit` 也一样。            
// 对象带病，只能整体放弃（几百 KB 泄漏，好过在音频路径上崩掉）。
            return( false );
        
}
        for( auto& urb : urbs )        
{
            if( urb.transfer != nullptr )            
{
                libusb_free_transfer( urb.transfer );
                urb.transfer = nullptr;
            
}
        
}
        urbs.clear();
        if( fbTransfer != nullptr )        
{
            libusb_free_transfer( fbTransfer );
            fbTransfer = nullptr;
        
}
        if( dev != nullptr && interfaceClaimed && profile.iface >= 0 )        
{
            libusb_set_interface_alt_setting( dev, profile.iface, 0 );
        
}
        return( true );
    
}
    void close()    
{
        if( !stopStream())        
{
            leaked = true;
            return;
        
}
        if( dev != nullptr )        
{
            if( interfaceClaimed )            
{
                
// 释放 attach 时 claim 的**全部** AS 接口（PCM 与 raw DSD 可能不同号）
for( int iface : claimedIfaces )                
{
                    libusb_release_interface( dev, iface );
                
}
                claimedIfaces.clear();
                interfaceClaimed = false;
            
}
            if( acClaimed && acIfNumber >= 0 )            
{
                libusb_release_interface( dev, acIfNumber );
                acClaimed = false;
            
}
            libusb_close( dev );
            dev = nullptr;
        
}
        if( ctx != nullptr )        
{
            libusb_exit( ctx );
            ctx = nullptr;
        
}
    
}
}
;
UacStream* asStream( jlong handle )
{
    return( reinterpret_cast< UacStream* >( handle ));
}
}
 
 // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeOpen(
        JNIEnv* /* env */, jclass /* 静态方法 */, jint fd, jint sampleRate, jint encoding,
        jint channels, jint wireBits )
{
    g_lastError = 0;    
// 每微帧样本数由 rate/8000 得出；低于 8000 会算出 0 个样本的包，等于不送数据
if( fd < 0 || sampleRate < kMicroframesPerSecond || channels <= 0 )    
{
        g_lastError = -100;
        return( 0 );
    
}
    auto* stream = new UacStream();
    std::string why;
    if( !stream->open( fd, sampleRate, encoding, channels, why, /*wantRaw=*/
false, wireBits ))    
{
        g_lastError = stream->error;
        stream->detail = why;
        stream->close();
        if( !stream->leaked )        
{
            delete stream;
        
}
        return( 0 );
    
}
    return( reinterpret_cast< jlong >( stream ));
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeOpenRaw(        JNIEnv* /* env */, jclass /* 静态方法 */, jint fd, jint wordRate, jint channels )
{
    g_lastError = 0;
    if( fd < 0 || wordRate < kMicroframesPerSecond || channels <= 0 )    
{
        g_lastError = -100;
        return( 0 );
    
}
    auto* stream = new UacStream();
    std::string why;    
// 编码位 22（`ENCODING_PCM_32BIT`）是关键，不能传 `kEncFloat`：    
// `fill()` 前那段统一转换在 `inEncoding == kEncFloat` 时会做 clamp + lrintf，    
// 而 raw DSD 的 32 bit word 里装的是 32 个 1-bit 样本，任何数值解释都会毁掉它。    
// 22 走 `else` 分支 = `memcpy` 恒等，与 outFrameBytes 一致（4 字节/声道）。
if( !stream->open( fd, wordRate, 22, channels, why, /*wantRaw=*/
true ))    
{
        g_lastError = stream->error;
        stream->detail = why;
        stream->close();
        if( !stream->leaked )        
{
            delete stream;
        
}
        return( 0 );
    
}
    return( reinterpret_cast< jlong >( stream ));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeReopen(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle, jint sampleRate, jint encoding,        jint channels, jint wireBits )
{
    UacStream* s = asStream( handle );
    if( s == nullptr )    
{
        return( JNI_FALSE );
    
}
    g_lastError = 0;
    std::string why;
    if( !s->reopen( sampleRate, encoding, channels, why, /*wantRaw=*/
false, wireBits ))    
{
        g_lastError = s->error;
        s->detail = why;
        return( JNI_FALSE );
    
}
    return( JNI_TRUE );
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeReopenRaw(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle, jint wordRate, jint channels )
{
    UacStream* s = asStream( handle );
    if( s == nullptr || wordRate <= 0 || channels <= 0 )    
{
        return( JNI_FALSE );
    
}
    g_lastError = 0;
    std::string why;    
// 22 与 `nativeOpenRaw` 同源：raw DSD 的 32 bit word 必须走 `fill()` 的恒等分支
if( !s->reopen( wordRate, 22, channels, why, /*wantRaw=*/
true ))    
{
        g_lastError = s->error;
        s->detail = why;
        return( JNI_FALSE );
    
}
    return( JNI_TRUE );
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeDescribeFd(        JNIEnv* env, jclass /* 静态方法 */, jint fd )
{
    libusb_context* ctx = nullptr;
    libusb_device_handle* dev = nullptr;
    std::string text;
    libusb_set_option( nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr );
    if( libusb_init( &ctx ) != LIBUSB_SUCCESS )    
{
        return( env->NewStringUTF( "libusb_init 失败" ));
    
}
    if( libusb_wrap_sys_device( ctx, static_cast< intptr_t >( fd ), &dev ) != LIBUSB_SUCCESS || dev == nullptr )    
{
        libusb_exit( ctx );
        return( env->NewStringUTF( "无法接管 USB 句柄" ));
    
}
    const ParsedConfig parsed = parseConfig( dev );
    text = parsed.summary;
    char line[ 64 ];
    for( int clock : parsed.clockSources )    
{
        std::snprintf( line, sizeof( line ), "时钟源 %d：\n", clock );
        text += line;
        text += rateRangesOf( dev, clock );
    
}
    libusb_close( dev );
    libusb_exit( ctx );
    return( env->NewStringUTF( text.c_str()));
}
/** 
* 只读设备能力：`[0]=hasRawAlt, [1]=pcmCount, [2..]=pcmRates, [..]=rawCount, [..]=rawRates` 
* 
* 与 `nativeDescribeFd` 同一条路径（接管 fd → 解析描述符 → 立刻关掉，不 claim 接口）， 
* 区别只是返回结构化数据 —— 上层要拿它决定"能不能走 Native DSD"， 
* 让 Kotlin 去解析描述字符串既脆又容易漏。 
* 
* 注意 UAC2 的速率来自 Clock Source 的 GET_RANGE，不在 FORMAT_TYPE_I 里， 
* 所以 `rates` 常常是空的。空 = **设备没声明**，上层按"可以试"处理，不是"不支持"。 */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeCapabilities(        JNIEnv* env, jclass /* 静态方法 */, jint fd )
{
    libusb_context* ctx = nullptr;
    libusb_device_handle* dev = nullptr;
    libusb_set_option( nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr );
    if( libusb_init( &ctx ) != LIBUSB_SUCCESS )    
{
        return( nullptr );
    
}
    if( libusb_wrap_sys_device( ctx, static_cast< intptr_t >( fd ), &dev ) != LIBUSB_SUCCESS || dev == nullptr )    
{
        libusb_exit( ctx );
        return( nullptr );
    
}
    const ParsedConfig parsed = parseConfig( dev );
    libusb_close( dev );
    libusb_exit( ctx );
    std::vector< int > pcmRates;
    std::vector< int > rawRates;
    bool hasRaw = false;
    for( const auto& alt : parsed.alts )    
{
        auto& dst = alt.rawDsd ? rawRates : pcmRates;
        if( alt.rawDsd )        
{
            hasRaw = true;
        
}
        for( int hz : alt.rates )        
{
            if( std::find( dst.begin(), dst.end(), hz ) == dst.end())            
{
                dst.push_back( hz );
            
}
        
}
    
}
    std::sort( pcmRates.begin(), pcmRates.end());
    std::sort( rawRates.begin(), rawRates.end());
    const int total = 2 + static_cast< int >( pcmRates.size()) + 1 +                      static_cast< int >( rawRates.size());
    std::vector< jint > out;
    out.reserve( static_cast< size_t >( total ));
    out.push_back( hasRaw ? 1 : 0 );
    out.push_back( static_cast< jint >( pcmRates.size()));
    for( int hz : pcmRates ) out.push_back( hz );
    out.push_back( static_cast< jint >( rawRates.size()));
    for( int hz : rawRates ) out.push_back( hz );
    jintArray arr = env->NewIntArray( total );
    if( arr != nullptr )    
{
        env->SetIntArrayRegion( arr, 0, total, out.data());
    
}
    return( arr );
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeDescribe(        JNIEnv* env, jclass /* 静态方法 */, jlong handle )
{
    UacStream* s = asStream( handle );
    if( s == nullptr )    
{
        return( env->NewStringUTF( "" ));
    
}
    char line[ 256 ];
    std::snprintf( line, sizeof( line ), "alt %d · %s · %d Hz · %d ch · ep 0x%02X · clock %d\n",                   s->profile.alt, formatName( s->profile ), s->sampleRate, s->channels,                   s->profile.epOut, s->profile.clockId );
    return( env->NewStringUTF(( s->detail + line ).c_str()));
}
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeLastError(        JNIEnv* /* env */, jclass /* 静态方法 */ )
{
    return( g_lastError );
}
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeWrite(        JNIEnv* env, jclass /* 静态方法 */, jlong handle, jbyteArray data,        jint byteOffset, jint byteCount, jint frameBytesIn )
{
    UacStream* s = asStream( handle );
    if( s == nullptr || frameBytesIn <= 0 )    
{
        return( -1 );
    
}
    if( s->dead.load( std::memory_order_acquire ))    
{
        return( -1 );
    
}
    if( byteCount <= 0 || data == nullptr )    
{
        return( 0 );
    
}
    const int32_t frames = byteCount / frameBytesIn;
    if( frames <= 0 )    
{
        return( 0 );
    
}
    jbyte* raw = env->GetByteArrayElements( data, nullptr );
    if( raw == nullptr )    
{
        return( -2 );
    
}
    const size_t written = s->push(        reinterpret_cast< const uint8_t* >( raw ) + byteOffset,        static_cast< size_t >( frames ), static_cast< size_t >( frameBytesIn ));
    env->ReleaseByteArrayElements( data, raw, JNI_ABORT );
    return( static_cast< jint >( written ));
}
/** 
* 设置软件增益（Q16 定点，65536 = 1.0）。 
* 
* 只写 atomic，`fill()` 每包读一次 —— 所以换音量在下一批 URB 就生效， 
* 不受环形缓冲里已提交数据的影响。 */
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeSetGain(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle, jint gainQ16 )
{
    if( UacStream* s = asStream( handle ); s != nullptr )    
{
        const int32_t clamped = gainQ16 < 0 ? 0 : ( gainQ16 > annie::kGainUnity ? annie::kGainUnity : gainQ16 );
        s->gainQ16.store( clamped, std::memory_order_release );
    
}
}
/** 
* 硬件音量能力：`{ has, min, max, res, cur }`（单位 1/256 dB 衰减量）。 
* 
* `has == 0` 时其余字段无意义 —— 设备没有声明 Volume 控制，或只有一个档位。 */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeVolumeCaps(        JNIEnv* env, jclass /* 静态方法 */, jlong handle )
{
    jint out[ 5 ] = 
{
 0, 0, 0, 1, 0 
}
;
    if( UacStream* s = asStream( handle ); s != nullptr && s->hwVolPresent )    
{
        out[ 0 ] = 1;
        out[ 1 ] = s->hwVolMin;
        out[ 2 ] = s->hwVolMax;
        out[ 3 ] = s->hwVolRes;
        out[ 4 ] = s->hwVolCur;
    
}
    jintArray result = env->NewIntArray( 5 );
    if( result != nullptr )    
{
        env->SetIntArrayRegion( result, 0, 5, out );
    
}
    return( result );
}
/** 
* 请求硬件音量（1/256 dB 衰减量）。 
* 
* 只写 atomic：真正的控制传输由事件线程发出（见 `applyPendingHardwareVolume`）。 
* 这条路径可能从任意线程（含 UI 线程）调用，所以它必须是 O(1) 且不加锁。 */
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeSetHardwareVolume(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle, jint value )
{
    if( UacStream* s = asStream( handle ); s != nullptr )    
{
        s->pendingHwVol.store( static_cast< int32_t >( value ), std::memory_order_release );
    
}
}
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativePause(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    if( UacStream* s = asStream( handle ); s != nullptr )    
{
        s->paused.store( true, std::memory_order_release );        
// pause 本身让 `refill()` 停下，但已经提交的 URB 还在内核里排着；        
// 叫醒事件线程让它走回暂停分支，之后就再也不收事件、不重提交了。
        s->pauseCv.notify_all();
    
}
}
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeResume(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    if( UacStream* s = asStream( handle ); s != nullptr )    
{
        s->paused.store( false, std::memory_order_release );
        s->pauseCv.notify_all();
    
}
}
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeFlush(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle, jint keepFrames )
{
    UacStream* s = asStream( handle );
    if( s == nullptr )    
{
        return;
    
}
    
// 只丢环里的未播数据，且**指针推进交给 `fill()` 线程**（本函数在播放线程上）。    
//    
// `keepFrames` > 0 时保留最后这么多帧：切歌时输出一旦断在零上，设备要重新对齐    
// 通道（听感就是某一路先哑、之后才同步）。用 ≤20 ms 的旧尾部把断点垫住；    
// seek 必须传 0，否则会先听到旧位置的内容。    
//    
// 计数器**不归零**：位置模型用的是「当前读数 − 基准」，Kotlin 侧在 flush 之后    
// 立刻重新取基准（`readBaseFrames`），归零反而会和并发累加打架。    
// `accQ16` 同样不动：它只是"这一微帧该给 11 还是 12 帧"的累加相位，    
// 从 `fill()` 线程私有的值变成跨线程写会引入竞争，而清不清对输出没有任何影响。    
// 设备里已经收走的音频（≤32 ms 的 URB）不追回 —— 那远小于可感知的延迟，    
// 比"拆掉整条流重建"划算得多。
const uint64_t w = s->ring.writtenBytes();
    uint64_t target = w;
    if( keepFrames > 0 )    
{
        const uint64_t keep = static_cast< uint64_t >( keepFrames ) * s->outFrameBytes;
        target = w > keep ? w - keep : 0;
    
}
    s->flushTarget.store( target, std::memory_order_relaxed );
    s->flushRequested.store( true, std::memory_order_release );
}
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeSetEncoding(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle, jint encoding )
{
    UacStream* s = asStream( handle );
    if( s == nullptr )    
{
        return;
    
}
    s->setInputEncoding( static_cast< int >( encoding ));
}
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeClose(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    UacStream* s = asStream( handle );
    if( s == nullptr )    
{
        return;
    
}
    s->close();
    if( !s->leaked )    
{
        delete s;
    
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeFramesRead(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    UacStream* s = asStream( handle );
    return( s == nullptr ? 0 : static_cast< jlong >( s->framesRead.load( std::memory_order_relaxed )));
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeFramesWritten(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    UacStream* s = asStream( handle );
    return( s == nullptr ? -1 : static_cast< jlong >( s->framesWritten.load( std::memory_order_relaxed )));
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeUnderrunCount(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    UacStream* s = asStream( handle );
    return( s == nullptr ? 0 : static_cast< jlong >( s->underruns.load( std::memory_order_relaxed )));
}
/**
* 这条流还能不能出声。
* 
* `dead` 一旦置上（任一 transfer 以非 COMPLETED 状态回来，且不是我们主动停流），
* `fill()` 就只送静音、`refill()` 也不再提交 —— 流"还在"，但永远不会再有声音。
* 而它**只在 `startStream()` 里被清**，也就是只有"拆流重建"能救；
* 复用分支（`reusable` / `rebuilt`）会一直抱着这条死流，于是**连 PCM 也一起哑**，
* 直到换会话为止。
* 
* 上层据此把"复用"改成"重开会话"——那才是实测唯一能恢复的路径。
*/
extern "C" JNIEXPORT jboolean JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeStreamHealthy(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    UacStream* s = asStream( handle );
    if( s == nullptr )    
{
        return( JNI_FALSE );
    
}
    const bool ok = !s->dead.load( std::memory_order_acquire ) &&                  !s->stop.load( std::memory_order_acquire );
    return( ok ? JNI_TRUE : JNI_FALSE );
}
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeDeviceRate(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    UacStream* s = asStream( handle );
    return( s == nullptr ? 0 : s->deviceRate());
}
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeBufferSize(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    UacStream* s = asStream( handle );
    return( s == nullptr ? 0 : static_cast< jint >( s->ring.capacityFrames()));
}
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeUacStream_nativeOutFrameBytes(        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    UacStream* s = asStream( handle );
    return( s == nullptr ? 0 : static_cast< jint >( s->outFrameBytes ));
}
