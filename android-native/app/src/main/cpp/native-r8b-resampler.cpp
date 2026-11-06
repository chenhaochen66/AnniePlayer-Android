//$ nobt
/**
 * @file native-r8b-resampler.cpp
 *
 * @brief 重采样：r8brain-free-src（MIT，header-only）的 JNI 适配。
 *
 * 之前这里是自研的多相 sinc（48 → 512 taps）。换成 r8brain 的原因：
 * 单级多相表要同时满足「通带 ≥0.99×Nyquist + 阻带 −180 dB」时，滤波器长度会到
 * 2400 taps、系数表 4.9 MB、算力 2.3 亿次乘加/秒 —— 那是架构问题，不是参数问题。
 * r8brain 用的是「2× 半带 + 短分数延迟 sinc」的两级结构，天生就在这个档位，
 * 而且参数全开（过渡带 0.5–45%、阻带 49–218 dB）。
 *
 * ## 与上游的接口约定
 *
 * - `r8b::CDSPResampler` 是**单声道**的：每声道一个实例（上游文档明确要求）。
 *   JNI 侧负责交错 ↔ 解交错。
 * - `process(ip, l, op)` 里 `l` **不能超过构造函数给的 MaxInLen**，所以大块输入要切段。
 * - 返回的输出样本数是**可变**的（暖机期可能是 0），Kotlin 侧不能假设"输出 = 输入×比"。
 * - 上游没有 throw/catch、没有 typeid → 与本库 `-fno-exceptions -fno-rtti` 兼容。
 * - 主低通滤波器**必须**用最小相位（`kPhase`），见 `kTransBand` 上面那整段推导：
 *   线性相位会让每个瞬态之前长出 6 ms 的前置回声，听成沙状底噪。
 * - 除了「重采样」，本文件还兼「DSD 链路的输出低通」：`create()` 的 `lpfHz > 0` 时
 *   会在输出端再挂一级**最小相位**低通，**即使 `srcRate == dstRate`（不需要重采样）**。
 *   那一级的作用与参数见 `kOutputLpfTaps` 上面的整段说明。
 */

#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#include "CDSPResampler.h"

namespace {

// 16 Bit 没有单独的分支：它是 `readSample` / `writeSample` 的兜底路径，
// 所以也没有 `kEnc16` 这个常量（以前留了一个，编译器一直报 unused）。
constexpr int kEnc24 = 21;
constexpr int kEncFloat = 4;

/** 单次喂给 r8brain 的最大输入帧数（构造参数 MaxInLen）；超过就切段 */
constexpr int kMaxInLen = 32768;

/**
 * 过渡带（% of Nyquist）。上游默认 2.0 —— **这里就用 2.0**。
 *
 * ## 症状与三次试错（按发生顺序，每次都有实测支撑）
 *
 * 症状：16 Bit / 44.1k 的打击乐密集曲目有一层持续的"沙沙"底噪，换系统重采样就干净，
 * 瞬态稀疏的曲目完全听不出来。频域怎么比都查不到（源 Nyquist 之上 −153 dB、
 * 15–21 kHz 与原始同电平，我方重采样残留 −117.5 dB、声道串扰精确为 0）。
 *
 * 1. **2% + 线性相位** → 有沙沙。线性相位 FIR 冲激响应对称，每个瞬态**之前**
 *    先长出一段回声；2% 对应 1621 抽头（44100→48000 实测 `flt_len=1621`，206.91 dB），
 *    前置回声达 18 ms 量级，而听觉的**后向掩蔽只有 1–2 ms**，超出部分不被掩蔽。
 * 2. **5% + 线性相位** → 沙沙消失。当时把它归因于"砍掉 21–22 kHz"，**这是误读**。
 * 3. **2% + 最小相位** → 仍有沙沙。最小相位把振铃从瞬态前挪到瞬态后，
 *    但**总振铃时长没变**，密集打击乐里后置回声照样叠成一层。
 * 4. **3.5% + 最小相位** → 仍无改善（试过，见用户实测）。
 *
 * ## 关键：外挂滤波救不了它
 *
 * 为了确认"沙沙是不是某段频谱里的能量"，专门做过一次外挂高切实验：
 * 叠加 16 阶巴特沃斯低通（最小相位双二阶级联），实测把 14–22 kHz 压掉 **−38 dB**，
 * 沙沙**依旧在**。结论很硬：**它不是频域能量，是时间轴上被摊开的结果**，
 * 而外挂滤波发生在摊开之后。
 *
 * ## 为什么最终留在 2.0
 *
 * 4 种组合试完，用户实测结论是：**缩短滤波器（5%）与保持 2.0 的差别不足以抵消
 * 高频滚降的代价**。既然最小相位已经消掉了前置那一半、剩下的后置振铃在
 * 后向掩蔽窗口里（20 ms 以上），就没有理由再用带宽去换振铃。
 * 所以最终配置 = **最窄过渡带（保高频）+ 最小相位（无前置回声）**。
 *
 * ⚠️ **`kPhase` 不要退回 `fprLinearPhase`**：那会让 18 ms 的前置回声整条回来，
 * 这是本文件里唯一被实测反复确认过的因果关系。
 * 输出端那一级低通（`toMinPhase`）同理 —— 任何新加的滤波级都必须是最小相位。
 *
 * ## ⚠️ 上面那一整段只对**无损**成立；有损源走 4.5%
 *
 * 「2% 比 5% 好」的结论建立在"要保住高频"这个前提上。有损源没有这个前提：
 * 编码器自己的低通在 17.5–21.6 kHz（实测：MP3 128k 切在 ~17.5k、MP3 320k ~21.6k、
 * Opus 128k ~21.6k、AAC 128k ~19.5k），再往上基本是编码噪声 —— 那不是信号，砍掉不心疼。
 *
 * 所以判据翻过来：**过渡带从 21.6 kHz 挪到 21.06 kHz（4.5%）不损失任何东西，
 * 却能换来更短的滤波器**（44.1k→48k / 136 dB 实测：2% → 1081 抽头，
 * 4.5% → 481 抽头）。抽头变短 = 脉冲响应更短（振铃 2.3 ms → 1.0 ms），
 * 对**有损这种本来就带噪声的源**尤其划算（振铃不落在音乐上）。
 *
 * ⚠️ **但这条不省 CPU，别按抽头比例想象力**：44.1k→48k / 16 Bit 实测，
 * 2% 的 `in_len = 3016`（FFT 2048），4.5% 的 `in_len = 544`（FFT 512）——
 * 块变小意味着每秒的 FFT 次数变多，把好处吃掉还倒亏（10 次 30 s 立体声：
 * 0.63 s → 0.67 s）。省电靠的是阻带（32→16 Bit，−15%），不是过渡带。
 *
 * 唯一"看得到"的代价是全带宽的有损编码：ffmpeg 的原生 AAC 在 256k 并不切高频
 * （实测 21.6–22.05 kHz 仍有 −47.9 dBFS 的能量，与无损同量级），
 * 4.5% 会把 21.06 kHz 以上按 −3 dB 起滚。21 kHz 处的 3 dB 落在绝大多数人的听阈之外，
 * 这也是本项目接受它的原因 —— 与当年"无损不许滚降"的判断**不矛盾**，前提不同而已。
 *
 * 值由 Kotlin 侧给（`ResampleDepth.resampleTransitionBandPercent()`），本文件只认数字；
 * 传 0 = 用这里的默认值。
 */
constexpr double kTransBand = 2.0;

/**
 * ## 2.0 换算成多少 Hz（别靠感觉，这里是实测口径）
 *
 * 上游定义：`ReqTransBand` = 「−3 dB 点到 Nyquist」这段占**较低那个采样率的
 * 半带（0…Nyquist）**的百分比（升采样按源，降采样按目标）。
 * 所以 **通带边缘 = 0.98 × min(fs_in, fs_out) / 2**，阻带从 Nyquist 起算。
 *
 * | 链路 | min 率 | Nyquist | 过渡带 | −3 dB 点 |
 * |---|---|---|---|---|
 * | 44100 → 48000 | 44100 | 22050 | 441 Hz | **21609 Hz** |
 * | 48000 → 44100 | 44100 | 22050 | 441 Hz | 21609 Hz |
 * | 96000 → 48000 | 48000 | 24000 | 480 Hz | 23520 Hz |
 * | **352800 → 176400（DSD64）** | 176400 | 88200 | 1764 Hz | **86436 Hz** |
 * | 705600 → 176400（DSD128） | 176400 | 88200 | 1764 Hz | 86436 Hz |
 *
 * 阻带目标按源位深走 `attenuationForBits()`：16 Bit → 136.3 dB、
 * 24 Bit → 184.5 dB、float（DSD）→ 封顶 206.91 dB。
 * 对应抽头数（`flt_len`，NormFreq=0.5 / tb=2.0 那套参数）：16 Bit ≈ 1078、
 * 24 Bit ≈ 1449、float ≈ 1621。
 *
 * 结论：**20 kHz 以下一个 dB 都不动**，被砍掉的只有 Nyquist 下方最后 2%。
 * 反过来也要清楚：DSD 那条通带到 86.4 kHz，等于把 DSD 噪声整形推到
 * 20 k–86.4 kHz 的那层噪声原样送给 DAC —— 这不是滤波器不够好，是 DSD 的固有代价。
 * 那一级由调用方通过 `create()` 的 `lpfHz` 补上（见 `kOutputLpfTaps`）。
 */

/**
 * 主低通滤波器的相位响应：**最小相位**（见 `kTransBand` 的整段推导）。
 *
 * 取最小相位的唯一代价是相位非线性 —— 而两个声道过的是**同一个**滤波器，
 * 声道间相位关系不变，所以立体声像、单声道兼容性都不受影响。
 */
constexpr r8b::EDSPFilterPhaseResponse kPhase = r8b::fprMinPhase;

/**
 * 阻带目标：按源位深自适应 `6.02×bits + 40`（上游文档给的公式），封顶 206.91 dB
 * （上游默认值）。
 *
 * 为什么不无脑用 206.91：16 bit 源设 207 dB 只会让滤波器更长、延迟更大，
 * 而它的极限噪声底本来就在 −96 dB。这是"发烧级"该有的细节 —— 按源算，不是按信仰算。
 *
 * ⚠️ **`bits` 的含义是「源的实际精度」，不是「解码器出口的容器位深」**。
 * 这个区别在 Apple 链路上很重要：ALAC 与 AAC 都走 ffmpeg，解出来一律是 float，
 * 若直接按 32 Bit 算，有损 AAC 也会拿到 206.91 dB / 1621 抽头。谁传这个值、
 * 怎么判断有损，见 Kotlin 侧 `core/audio/ResampleDepth.kt`（带实测表）。
 *
 * 实测（44.1k→48k / 2% / 最小相位，上游 `R8BCONSOLE` 打印）：16 → 1081 抽头、
 * 24 → 1451、32 → 1621；而 BlockConvolver 的 `in_len` 是 3016 / 2646 / 2476，
 * 所以 32→16 的算力差是 **22%**（实测 30 s 立体声 0.18 s → 0.14 s），
 * 不是按抽头比例算的一半。
 */
double attenuationForBits( int bits )
{
	if( bits <= 0 )
	{
		return( 206.91 );
	}

	const double need = 6.02 * bits + 40.0;
	return( need > 206.91 ? 206.91 : need );
}

inline double readSample( const uint8_t* p, int encoding )
{
	if( encoding == kEncFloat )
	{
		float v;
		std::memcpy( &v, p, sizeof( float ));
		return( static_cast< double >( v ));
	}

	if( encoding == kEnc24 )
	{
		int v = static_cast< int >( p[ 0 ] | ( p[ 1 ] << 8 ) | ( p[ 2 ] << 16 ));
		if(( v & 0x800000 ) != 0 )
		{
			v |= ~0xFFFFFF;
		}

		return( static_cast< double >( v ) / 8388608.0 );
	}

	int16_t v;
	std::memcpy( &v, p, sizeof( int16_t ));
	return( static_cast< double >( v ) / 32768.0 );
}

/** xorshift128+：16 bit 出口的 TPDF dither 源（比 std::rand 周期长、无相关性） */
inline uint64_t xorshift128plus( uint64_t* s )
{
	uint64_t x = s[ 0 ];
	const uint64_t y = s[ 1 ];
	s[ 0 ] = y;
	x ^= x << 23;
	s[ 1 ] = x ^ y ^ ( x >> 17 ) ^ ( y >> 26 );
	return( s[ 1 ] + y );
}

inline double ditherUnit( uint64_t* s )
{
	return( static_cast< double >( xorshift128plus( s ) >> 11 ) *
		( 1.0 / 9007199254740992.0 ));
}

/**
 * 每声道的噪声整形状态（2 阶误差反馈，`e1` = 上一个量化误差，`e2` = 上上个）。
 *
 * **必须每声道独立**：两个声道共用一份状态等于把两边的量化误差混在一起，
 * 立体声像会被一层相关噪声糊住（这也是错的做法里最常见的一个）。
 */
struct NoiseShaper
{
	double e1 = 0.0;
	double e2 = 0.0;
};

/**
 * 写一个样本。
 *
 * @param peakAbs 自上次取用以来的最大 `|v|`（会就地更新）。**这是真峰值监测**：
 *   `≥ 1.0` 就说明这一笔样本超出了满刻度，而以前这里只是**悄悄夹住**。
 * @param clipCount 超满刻度的样本数（同上，累计）。
 *
 * ## 16 Bit 出口：TPDF + 2 阶噪声整形
 *
 * 以前只有 TPDF（平直白噪）。TPDF 本身是对的（无调制噪声、无相关性），但它是
 * **平铺在 0…Nyquist 上的**，而人耳在 3–4 kHz 以上灵敏度迅速下降。所以 16 Bit
 * 降位深时再加一层 2 阶误差反馈整形，把量化噪声推到听阈以上：
 *
 * ```
 * fed = v - (2·e1 - e2)   ⇒ NTF = (1 - z⁻¹)²，噪声底随频率 +12 dB/oct
 * y   = Q(fed + tpdf)      ⇒ 抖动本身也被一起整形（这是对的：整的是总误差）
 * e1' = y - fed            ⇒ 用**真实**误差反馈（含夹住之后的结果），才不会饱和发散
 * ```
 *
 * 为什么不用更激进的「心理声学优化整形」（SoX 那套 shibata 表）：那需要为每种采样率
 * 一份系数表 + 一套设计工具，收益在 16 Bit、−93 dB 这个量级上已经接近人耳极限。
 * 2 阶是"标准做法"，代价是 HF 噪声抬 ~12 dB（16 Bit 下仍然听不见）。
 */
inline void writeSample( uint8_t* p, int encoding, double v, bool dither,
	uint64_t* rng, NoiseShaper* ns, double* peakAbs, long long* clipCount )
{
	// 真峰值监测：先记，再夹。记录的是**我们交给下游的那个值**（`v` 本身）。
	const double av = std::fabs( v );

	if( av > *peakAbs )
	{
		*peakAbs = av;
	}

	// 只有**整数**出口才真的夹：float 出口原样交付，超出满刻度的部分由下游决定，
	// 把它算成"我们削了波"就是谎报。所以 float 只记峰值，不记削波。
	if( encoding != kEncFloat && av >= 1.0 )
	{
		++( *clipCount );
	}

	if( encoding == kEncFloat )
	{
		float f = static_cast< float >( v );
		std::memcpy( p, &f, sizeof( float ));
		return;
	}

	if( encoding == kEnc24 )
	{
		/**
		 * 比例必须与 readSample 的 `÷ 8388608` **同一个**：
		 * 用 8388607 会让满刻度落不到 −8388608，且往返有约 1/8388608 的系统偏差
		 * （0.00001 dB，听不出，但"读写的比例不一致"本身是错的）。
		 * 夹在 [−8388608, 8388607] 是二进制补码的固有非对称：−1.0 精确，+1.0 少 1 LSB。
		 */
		int s = static_cast< int >( std::llround( v * 8388608.0 ));
		if( s > 8388607 )
		{
			s = 8388607;
		}

		if( s < -8388608 )
		{
			s = -8388608;
		}

		p[ 0 ] = static_cast< uint8_t >( s & 0xFF );
		p[ 1 ] = static_cast< uint8_t >(( s >> 8 ) & 0xFF );
		p[ 2 ] = static_cast< uint8_t >(( s >> 16 ) & 0xFF );
		return;
	}

	if( dither )
	{
		const double fed = v - ( 2.0 * ns->e1 - ns->e2 );
		// TPDF：两个独立均匀分布之差，峰峰值 2 LSB（±1 LSB），无调制噪声。
		const double tpdf = ( ditherUnit( rng ) - ditherUnit( rng )) / 32768.0;
		double q = fed + tpdf;

		if( q > 1.0 )
		{
			q = 1.0;
		}
		else
		if( q < -1.0 )
		{
			q = -1.0;
		}

		int s = static_cast< int >( std::llround( q * 32768.0 ));

		if( s > 32767 )
		{
			s = 32767;
		}
		else
		if( s < -32768 )
		{
			s = -32768;
		}

		// 真实误差（含上面两次夹住的结果）→ 反馈才不会因为饱和而发散
		const double y = static_cast< double >( s ) / 32768.0;
		ns->e2 = ns->e1;
		ns->e1 = y - fed;

		// 正常量化误差在 ±1 LSB（3e-5）量级；超过 1e-3 只可能是数值异常，夹住防发散
		if( ns->e1 > 1e-3 )
		{
			ns->e1 = 1e-3;
		}
		else
		if( ns->e1 < -1e-3 )
		{
			ns->e1 = -1e-3;
		}

		int16_t out = static_cast< int16_t >( s );
		std::memcpy( p, &out, sizeof( int16_t ));
		return;
	}

	// 比例与 readSample 的 `÷ 32768` 一致（以前是 × 32767：往返一次就带 0.0003 dB 的偏差）
	int s = static_cast< int >( std::llround( v * 32768.0 ));
	if( s > 32767 )
	{
		s = 32767;
	}

	if( s < -32768 )
	{
		s = -32768;
	}

	int16_t out = static_cast< int16_t >( s );
	std::memcpy( p, &out, sizeof( int16_t ));
}

/**
 * 输出端低通：截止频率由调用方给（`create()` 的 `lpfHz`），**只有 DSD 链路会传非 0**。
 *
 * ## 为什么会有这一级
 *
 * DSD 的噪声整形把量化噪声整个推到 20 kHz 以上（见 `native-ffmpeg-demux.cpp` 的推导）。
 * 我们的抽取只砍到 `min(源, 目标)` 的 Nyquist —— DSD64 到 176.4k 时是 88.2 kHz，
 * 也就是说 **20 k–86.4 kHz 这一大片噪声原样送给了 DAC**。
 *
 * 那之前选 352.8k 档位是**更糟**的：`srcRate == dstRate` 时 r8brain 整条旁路，
 * 177 kHz 以内一片不筛（现在这条已被下面这级低通堵住了，见 `create()`）。
 *
 * 这一级就是补那个顶：40 kHz 截止（策略在 Kotlin 侧 `DsdOutput.dsdPcmLowpassHz()`，
 * 本文件只收 Hz 数字，不猜是不是 DSD）。
 *
 * ## 为什么必须是最小相位
 *
 * 以前这里有一版 20 kHz 的**线性相位**低通，配着"16 Bit 源有高频杂质"的错误假设，
 * 实测**完全无效** —— 真正的问题是预振铃（时间域、全频段），不是高频杂质。
 * 而线性相位的一级挂在末端，等于把主滤波器改最小相位才消掉的那层预振铃**加回来**。
 * 所以这级造完必须过一遍 `calcMinPhaseTransform()`（上游 `CDSPRealFFT.h` 里现成的，
 * `CDSPFIRFilter` 自己就是这么用的），并且变换之后要**重新归一化直流增益**
 * （`DoFinalMul = false` 时它不帮你归一）。
 *
 * ## 参数（实测，不是估的）
 *
 * 127 抽头 / β=8。Kaiser 窗 sinc 的固有口径是「**标称截止处 −6 dB**」，所以 −3 dB 点比
 * `lpfHz` 低一点。三种真实输出率上量到的（在 PC 上用同一份源码跑频响，逐点 DFT）：
 *
 * | 输出率 | `lpfHz` | −3 dB | −80 dB | 20 kHz 处偏差 |
 * |---|---|---|---|---|
 * | 88200 | 39690 | 39 342 Hz | 41 472 Hz | −0.00016 dB |
 * | 176400 | 40000 | 39 305 Hz | 43 558 Hz | −0.00013 dB |
 * | 352800 | 40000 | 38 609 Hz | 47 123 Hz | −0.00001 dB |
 *
 * 三条都远在各自 Nyquist（44.1k / 88.2k / 176.4k）以内，20 kHz 以内动都不动。
 * 阻带深度足够：源噪声在 100 kHz 处约 −30 dB，压 −100 dB 后剩 −130 dBFS。
 *
 * 冲击响应的峰值落在第 22 个抽头（线性相位版是第 63 个），直流增益精确为 1 ——
 * 这两条就是「最小相位变换真的生效了」的证据。
 */

/** 低通抽头数（最小相位变换后的核长度不变） */
constexpr int kOutputLpfTaps = 127;

/** Kaiser 窗的 β（≈ 8 → 阻带约 −80 dB，与可闻门槛留足余量） */
constexpr double kOutputLpfBeta = 8.0;

/** 第一类零阶修正贝塞尔函数（Kaiser 窗用） */
inline double besselI0( double x )
{
	double sum = 1.0;
	double term = 1.0;
	const double hx = x * 0.5;

	for( int k = 1; k < 32; ++k )
	{
		term *= ( hx / k ) * ( hx / k );
		sum += term;
		if( term < 1e-16 * sum )
		{
			break;
		}
	}

	return( sum );
}

/**
 * 设计低通（Kaiser 窗 sinc），并归一化到直流增益 1。
 *
 * **出来的是线性相位核，不能直接拿去用** —— 必须再过 `toMinPhase()`，
 * 否则就是把这级加在链条末端的预振铃（见 `kOutputLpfTaps` 上面那段）。
 *
 * 归一化是必须的：不做的话通带会有固定增益偏差，等于整体音量被改。
 */
inline void designLowpass( std::vector< double >& h, int taps, double fcNorm, double beta )
{
	h.assign( taps, 0.0 );
	const int M = taps - 1;
	const double i0b = besselI0( beta );
	double sum = 0.0;

	for( int n = 0; n < taps; ++n )
	{
		const double m = n - M * 0.5;
		const double sinc = ( std::fabs( m ) < 1e-9 )
			? 2.0 * fcNorm
			: std::sin( 2.0 * M_PI * fcNorm * m ) / ( M_PI * m );
		const double r = ( M > 0 ) ? ( 2.0 * n / M ) - 1.0 : 0.0;
		const double w = besselI0( beta * std::sqrt( std::max( 0.0, 1.0 - r * r ))) / i0b;
		h[ n ] = sinc * w;
		sum += h[ n ];
	}

	if( std::fabs( sum ) > 1e-12 )
	{
		for( double& v : h )
		{
			v /= sum;
		}
	}
}

/**
 * 把线性相位核就地转成最小相位核，并**重新**归一化到直流增益 1。
 *
 * 用的是上游 `calcMinPhaseTransform()`（`CDSPRealFFT.h`）—— 与 `CDSPFIRFilter`
 * 构造里对 `fprMinPhase` 的处理完全同一条路，我们只是换个位置用它。
 *
 * 两个易踩的点：
 * 1. `LenMult = 16`（频域过采样倍数）不能省：衰减高时倍数不够，变换的「平滑」效应
 *    会让实际阻带达不到要求。上游对高衰减滤波器用的就是这个值。
 * 2. `DoFinalMul = false` 表示**不**恢复增益 → 必须自己归一化，否则整体音量被改。
 *
 * 变换后群延迟变成非整数且远小于 `(taps-1)/2`（能量前置）。所有声道过的是**同一个**核，
 * 所以声道间不会有相对延迟，只是整链少几十个样本 —— 对播放无意义。
 */
inline void toMinPhase( std::vector< double >& h )
{
	if( h.size() < 2 )
	{
		return;
	}

	r8b::calcMinPhaseTransform( h.data(), static_cast< int >( h.size()), 16, false );

	double sum = 0.0;

	for( const double v : h )
	{
		sum += v;
	}

	if( std::fabs( sum ) > 1e-12 )
	{
		for( double& v : h )
		{
			v /= sum;
		}
	}
}

struct R8bState
{	int channels = 2;
	int srcRate = 44100;
	int dstRate = 48000;
	/** 每声道一个实例（上游要求） */
	std::vector< r8b::CDSPResampler* > rs;
	/** 每声道的解交错输入暂存，长度 kMaxInLen */
	std::vector< std::vector< double > > inBuf;
	/** 输出端低通（**最小相位** FIR，见 `toMinPhase`）：系数、每声道历史、写入位置 */
	std::vector< double > lpfCoef;
	std::vector< std::vector< double > > lpfHist;
	int lpfPos = 0;
	/** 每声道的噪声整形状态（16 Bit 出口用；见 `writeSample`） */
	std::vector< NoiseShaper > ns;
	/** 真峰值监测：自上次取用以来的最大 |样本|（1.0 = 满刻度）与超满刻度样本数 */
	double peakAbs = 0.0;
	long long clipCount = 0;
	uint64_t rng[ 2 ] = { 0x9E3779B97F4A7C15ULL, 0xBF58476D1CE4E5B9ULL };
	std::mutex mutex;
};

/**
 * 对第 `i` 个输出样本应用输出低通。
 *
 * 写入位置由 `lpfPos + i` 决定（而不是在函数内推进 `lpfPos`）——
 * 因为声道 0 和其余声道是在**两个独立循环**里写的，若函数自己推进，
 * 第二个循环会从错误的位置开始，音频直接错乱。
 * 所有声道处理完后由调用方统一 `lpfPos = (lpfPos + got) % taps`。
 *
 * 核是最小相位（`toMinPhase`），所以群延迟**不是** `(taps-1)/2` 的整数倍，
 * 而是一个小得多的非整数值。别照线性相位的直觉去算延迟。
 */
inline double applyOutputLpf( R8bState* s, int ch, int i, double x )
{
	if( s->lpfCoef.empty() )
	{
		return( x );
	}

	std::vector< double >& hist = s->lpfHist[ ch ];
	const int n = static_cast< int >( s->lpfCoef.size());
	const int w = ( s->lpfPos + i ) % n;
	hist[ w ] = x;

	double acc = 0.0;
	int idx = w;

	for( int t = 0; t < n; ++t )
	{
		acc += s->lpfCoef[ t ] * hist[ idx ];

		if( --idx < 0 )
		{
			idx = n - 1;
		}
	}

	return( acc );
}

} // namespace

extern "C" {

/**
 * 建立重采样 /（可选的）输出低通链。
 *
 * @param lpfHz 输出端低通截止频率；0 = 不要这一级。**只有 DSD 链路会传非 0**，
 *   策略（40 kHz / 按目标率夹取 / 什么时候干脆不启用）在 Kotlin 的
 *   `DsdOutput.dsdPcmLowpassHz()` 里，本函数只认 Hz。
 * @return 句柄；0 = 这条链没有任何活要干（同率且无低通），或者参数不成立
 *
 * ## `srcRate == dstRate` 不再等于「直接旁路」
 *
 * 以前这里一律 `return 0`，于是 **DSD 的 352.8 kHz 档位（decode 与目标同为 352800）
 * 连低通都拿不到** —— 噪声整形的能量从 20 kHz 一路到 176.4 kHz 原样送进 DAC。
 * 现在只要 `lpfHz > 0` 就照建：`rs` 留空，只剩低通和位深/抖动在干活。
 */
JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeR8bResampler_create(
	JNIEnv*, jobject, jint channels, jint srcRate, jint dstRate, jint srcBits,
	jint lpfHz, jdouble transBand )
{
	auto* s = new R8bState();
	s->channels = channels < 1 ? 1 : ( channels > 8 ? 8 : channels );
	s->srcRate = srcRate > 0 ? srcRate : 44100;
	s->dstRate = dstRate > 0 ? dstRate : 48000;
	s->ns.assign( static_cast< size_t >( s->channels ), NoiseShaper());

	// 过渡带：0 = 用默认（无损那一档）。参数语义与「为什么有两个值」见 `kTransBand`。
	double tb = ( transBand > 0.0 ) ? transBand : kTransBand;

	if( tb < r8b::CDSPFIRFilter::getLPMinTransBand())
	{
		tb = r8b::CDSPFIRFilter::getLPMinTransBand();
	}

	if( tb > r8b::CDSPFIRFilter::getLPMaxTransBand())
	{
		tb = r8b::CDSPFIRFilter::getLPMaxTransBand();
	}

	// 低通必须先于任何判断定下来：它决定「同率」还算不算有活干
	const double lpf = ( lpfHz > 0 && lpfHz < s->dstRate / 2 )
		? static_cast< double >( lpfHz ) : 0.0;

	if( s->srcRate == s->dstRate && lpf <= 0.0 )
	{
		// 同率 + 无低通 = 真的没事干，让上层走位深直通或整体旁路
		delete s;
		return( 0 );
	}

	const double atten = attenuationForBits( srcBits );
	// 本库以 `-fno-exceptions` 编译，不能用 try/catch：分配失败会走 operator new 的
	// terminate 路径（OOM 本来也活不下去）。r8brain 自身不抛异常（上游 0 处 throw/catch）。
	if( s->srcRate != s->dstRate )
	{
		for( int c = 0; c < s->channels; ++c )
		{
			s->rs.push_back( new r8b::CDSPResampler( s->srcRate, s->dstRate,
				kMaxInLen, tb, atten, kPhase ));
			s->inBuf.emplace_back( kMaxInLen, 0.0 );
		}
	}

	// 输出端低通：截止已在上面夹到输出 Nyquist 以下，这里只负责造核
	if( lpf > 0.0 )
	{
		const double fc = lpf / static_cast< double >( s->dstRate );
		designLowpass( s->lpfCoef, kOutputLpfTaps, fc, kOutputLpfBeta );
		// 线性相位 → 最小相位（这级必须，见 `kOutputLpfTaps` 上面那段）
		toMinPhase( s->lpfCoef );
		s->lpfHist.assign( s->channels, std::vector< double >( kOutputLpfTaps, 0.0 ));
	}

	return( reinterpret_cast< jlong >( s ));
}

/**
 * 本次调用**最多**会产出多少帧。
 *
 * 不能用 `getLatency()` 估：`CDSPResampler::getLatency()` 就是返回 0（延迟体现在
 * 内部各级 step 里），上游给的正确姿势是 `getMaxOutLen()` —— 它按构造时的 `MaxInLen`
 * 算出一个保守上界。Kotlin 侧据此分配输出缓冲，JNI 侧还会再夹一次，双保险。
 */
JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeR8bResampler_maxOutFrames(
	JNIEnv*, jobject, jlong handle, jint inFrames )
{
	auto* s = reinterpret_cast< R8bState* >( handle );
	if( s == nullptr || s->rs.empty() )
	{
		return( inFrames );
	}

	return( s->rs[ 0 ] -> getMaxOutLen( inFrames ));
}

JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeR8bResampler_processBytes(
	JNIEnv* env, jobject, jlong handle, jbyteArray input, jbyteArray output,
	jint inFrames, jint inEncoding, jint outEncoding, jboolean dither )
{
	auto* s = reinterpret_cast< R8bState* >( handle );
	if( s == nullptr || ( s->rs.empty() && s->lpfCoef.empty()) || inFrames <= 0 )
	{
		// 只剩「同率 + 不滤波」这一种真的没事干的情况。同率但有低通时 `rs` 是空的，
		// 走下面那条直通分支 —— 以前这里只判 `rs.empty()`，把那条路一并挡死了。
		return( 0 );
	}

	jbyte* in = env -> GetByteArrayElements( input, nullptr );
	jbyte* out = env -> GetByteArrayElements( output, nullptr );
	if( in == nullptr || out == nullptr )
	{
		if( in != nullptr )
		{
			env -> ReleaseByteArrayElements( input, in, JNI_ABORT );
		}

		if( out != nullptr )
		{
			env -> ReleaseByteArrayElements( output, out, JNI_ABORT );
		}

		return( 0 );
	}

	std::lock_guard< std::mutex > lock( s->mutex );

	const int channels = s->channels;
	const int inBytes = inEncoding == kEncFloat ? 4 : ( inEncoding == kEnc24 ? 3 : 2 );
	const int outBytes = outEncoding == kEncFloat ? 4 : ( outEncoding == kEnc24 ? 3 : 2 );
	const int outFrameCap = env -> GetArrayLength( output ) / ( outBytes * channels );

	auto* inPtr = reinterpret_cast< uint8_t* >( in );
	auto* outPtr = reinterpret_cast< uint8_t* >( out );
	const bool doDither = ( dither == JNI_TRUE );

	if( s->rs.empty() )
	{
		// ── 无重采样：只有低通（+ 可能的位深/抖动）在干活 ──
		//
		// 输出帧数 = 输入帧数（1:1），不经过 r8brain。这里逐帧处理而不是先解交错再
		// 逐声道，因为低通的环形指针按「块内第 i 帧」推进，逐帧走同一套索引即可。
		const int n = ( inFrames < outFrameCap ) ? inFrames : outFrameCap;

		for( int i = 0; i < n; ++i )
		{
			const uint8_t* src = inPtr + static_cast< size_t >( i ) * channels * inBytes;
			uint8_t* dst = outPtr + static_cast< size_t >( i ) * channels * outBytes;

			for( int c = 0; c < channels; ++c )
			{
				const double v = readSample( src + static_cast< size_t >( c ) * inBytes, inEncoding );
				writeSample( dst + static_cast< size_t >( c ) * outBytes, outEncoding,
					applyOutputLpf( s, c, i, v ), doDither, s->rng,
					&s->ns[ static_cast< size_t >( c ) ], &s->peakAbs, &s->clipCount );
			}
		}

		if( !s->lpfCoef.empty())
		{
			const int taps = static_cast< int >( s->lpfCoef.size());
			s->lpfPos = ( s->lpfPos + n ) % taps;
		}

		env -> ReleaseByteArrayElements( input, in, JNI_ABORT );
		env -> ReleaseByteArrayElements( output, out, 0 );
		return( n * outBytes * channels );
	}

	int consumed = 0;
	int produced = 0;

	while( consumed < inFrames && produced < outFrameCap )
	{
		const int chunk = ( inFrames - consumed ) > kMaxInLen
			? kMaxInLen : ( inFrames - consumed );

		// 解交错：interleaved → 每声道 double
		for( int c = 0; c < channels; ++c )
		{
			double* dst = s->inBuf[ c ].data();
			const uint8_t* src = inPtr + ( static_cast< size_t >( consumed ) * channels + c ) * inBytes;

			for( int i = 0; i < chunk; ++i )
			{
				dst[ i ] = readSample( src, inEncoding );
				src += static_cast< size_t >( channels ) * inBytes;
			}
		}

		// 逐声道重采样：**每个声道都必须跑满这一块**。
		//
		// `process` 有状态且必须按块顺序喂。以前拿第 0 声道的返回值当控制流，
		// 它在暖机期（复位后头几块）返回 0 就 `continue`，其余声道的这一块输入
		// 就永久丢了 —— 左右从此在时间上错开，听感正是「右声道先哑一段」。
		double* ops[ 8 ] = { nullptr };
		int got = 0;
		for( int c = 0; c < channels; ++c )
		{
			double* op = nullptr;
			const int gotC = s->rs[ c ] -> process( s->inBuf[ c ].data(), chunk, op );
			ops[ c ] = op;
			if( c == 0 || gotC < got )
			{
				got = gotC;
			}
		}

		consumed += chunk;
		if( got <= 0 )
		{
			continue;
		}

		if( produced + got > outFrameCap )
		{
			// 输出装不下：所有声道一起截到同一个长度（丢一帧总比越界强）
			got = outFrameCap - produced;
			if( got <= 0 )
			{
				break;
			}
		}

		// 交错写回：各声道都写满 `got` 帧，谁也不许少写
		for( int i = 0; i < got; ++i )
		{
			for( int c = 0; c < channels; ++c )
			{
				writeSample( outPtr +
					( static_cast< size_t >( produced + i ) * channels + c ) * outBytes,
					outEncoding, applyOutputLpf( s, c, i, ops[ c ][ i ] ), doDither, s->rng,
					&s->ns[ static_cast< size_t >( c ) ], &s->peakAbs, &s->clipCount );
			}
		}

		// 所有声道处理完后再推进环形写指针（见 `applyOutputLpf` 的说明）
		if( !s->lpfCoef.empty() )
		{
			const int taps = static_cast< int >( s->lpfCoef.size());
			s->lpfPos = ( s->lpfPos + got ) % taps;
		}

		produced += got;
	}

	env -> ReleaseByteArrayElements( input, in, JNI_ABORT );
	env -> ReleaseByteArrayElements( output, out, 0 );
	return( produced * outBytes * channels );
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeR8bResampler_reset(
	JNIEnv*, jobject, jlong handle )
{
	auto* s = reinterpret_cast< R8bState* >( handle );
	if( s == nullptr )
	{
		return;
	}

	std::lock_guard< std::mutex > lock( s->mutex );
	for( r8b::CDSPResampler* p : s->rs )
	{
		p -> clear();
	}

	// 低通的历史缓冲与环形指针**也必须一起清**：
	// 不清理的话，`onFlush`（跳转 / seek / 循环回到开头）之后头 127 个样本会与
	// 上一段音频的尾巴卷积 —— 表现是每次 seek 后有一下"咔"。
	for( std::vector< double >& h : s->lpfHist )
	{
		std::fill( h.begin(), h.end(), 0.0 );
	}

	s->lpfPos = 0;

	// 噪声整形状态同样要清：跨 seek 复用会把上一个片段的量化误差带到新位置，
	// 表现为"跳转后头几十毫秒有一层薄噪声"。峰值统计**不清**（它是累计量，
	// 由 `takePeak` / `takeClipCount` 取走时才归零）。
	for( NoiseShaper& n : s->ns )
	{
		n.e1 = 0.0;
		n.e2 = 0.0;
	}
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeR8bResampler_release(
	JNIEnv*, jobject, jlong handle )
{
	auto* s = reinterpret_cast< R8bState* >( handle );
	if( s == nullptr )
	{
		return;
	}

	for( r8b::CDSPResampler* p : s->rs )
	{
		delete p;
	}

	delete s;
}

/**
 * 取走「自上次调用以来」的最大 `|样本|`（1.0 = 满刻度）与超满刻度的样本数，并归零。
 *
 * 这是**真峰值监测**，不是可选装饰：重采样会改变波形，0 dBFS 的母带转完之后
 * 可能超过 0 dBFS（互样本过冲，典型 +0.3…1 dB），而以前 `writeSample` 只是
 * 悄悄夹住 —— 削了多少次、削了多深，外面一无所知。
 *
 * 调用方（`MediaPlayerBackend`）按曲目累计，UI 只在诊断页显示，不打扰播放。
 * 两个返回值分开取是为了 JNI 签名简单：`takePeak` 返回 dBFS 前先给线性值。
 */
JNIEXPORT jdouble JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeR8bResampler_takePeak(
	JNIEnv*, jobject, jlong handle )
{
	auto* s = reinterpret_cast< R8bState* >( handle );
	if( s == nullptr )
	{
		return( 0.0 );
	}

	std::lock_guard< std::mutex > lock( s->mutex );
	const double p = s->peakAbs;
	s->peakAbs = 0.0;
	return( p );
}

JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeR8bResampler_takeClipCount(
	JNIEnv*, jobject, jlong handle )
{
	auto* s = reinterpret_cast< R8bState* >( handle );
	if( s == nullptr )
	{
		return( 0 );
	}

	std::lock_guard< std::mutex > lock( s->mutex );
	const long long c = s->clipCount;
	s->clipCount = 0;
	return( c > 2147483647LL ? 2147483647 : static_cast< jint >( c ));
}

} // extern "C"
