package com.annieplayer.annie_music.kt.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GfxCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.ColorUtils
import com.annieplayer.annie_music.kt.core.util.extractPlayerCoverPalette
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

// ================================================================================================
// 全站变色页共享背景：**Aurora Field（色域极光）**
// ================================================================================================
//
// 一块完全程序化的缓慢流动色场。**不含任何封面位图**——连缩略图都没有。
// 输出是一片认不出出处、但和歌曲同色温、同明暗气质的光。
//
// 为什么不用「模糊封面 / 网格扭曲封面」：只要封面像素还在画面里，无论降到多少分辨率、
// 加多少层纱，都能看出原图的明暗骨架（人眼对结构极其敏感，对色相迟钝）。所以像素只用来
// **算统计量**，绝不进入绘制。
//
// ## 三个正交维度（这是整套设计的主轴）
//
// 封面能提供四类信息，这里把它们压成三个互不干扰的标量 + 一组家族，各自只驱动自己那层：
//
// | 维度 | 从封面怎么算 | 驱动什么 |
// |---|---|---|
// | **色相分布** | 4×4 网格按色相聚类成家族 | 色斑的颜色 |
// | **色度占比** | 家族色度质量 / 全图色度预算（[_clusterGrid]） | 色斑的浓度与大小 |
// | **整体明度** | 网格 luma 均值 `lumaMean` | 底色亮度 + 环境层强度 + 斑明度倾斜 |
//
// 「色度」和「明度」必须分开算：色度说**有多少颜色**，明度说**画面有多亮**。
// 合成一个量就会得到「黑底霓虹」和「白底静物」渲染成同一个样子。
//
// ## 图层（自下而上）
//
// | # | 图层 | 作用 |
// |---|---|---|
// | ① | 底色 | `BaseFloor + lumaGain × BaseSpan`，**跟随封面整体明度** |
// | ② | 色温晕 | 盖满整屏的大径向光，强度 × 主家族存在感 × 曝光 |
// | ②b | 画外轮廓光 | 圆心在屏幕外的窄径向光，制造「光源在画外」的纵深 |
// | ③ | 极光斑 ×N | 纯白径向遮罩 + `ColorFilter.tint` + `BlendMode.Screen` |
// | ④a | 亮区蒙版 | 高于平均亮度的格子 → 白色 alpha 蒙版 + `Screen` |
// | ④b | 暗区蒙版 | 低于平均亮度的格子 → 黑色 alpha 蒙版 + `Multiply` |
// | ④c | 低频起伏 | 平滑 value-noise 拉伸铺满，`Overlay` —— 治「数学渐变」的塑料感 |
// | ⑤ | 颗粒 ×2 | 粗（96px）+ 细（320px）两层白噪声，抗色带 + 胶片感 |
// | ⑥ | 暗角 | 只压最边上的 12%，给状态栏 / 底栏文字托底对比度 |
//
// ④a/④b 用 **alpha 蒙版 + `Screen`/`Multiply`** 而不是单张灰度图走 `Overlay`：
// `Overlay` 在暗底上是 `2ab`，底色 0.06 遇上 0.7 只到 0.084，等于没做事；而 `Screen` 在
// 同样的输入下是 `1−(1−a)(1−b)`，能到 0.74。亮度场只有在暗底上真正起作用，方向感才成立。
//
// ## 色斑的数量与配色
//
// 色斑**不是硬编码的**，而是从封面的 4×4 网格里**聚类出来的色彩家族**：
//
// 1. 16 个格色逐个算**色度**（`max − min`），低于 [FamilyMinChroma] 的算中性，不入族。
//    这里**不能用 HSL 饱和度**：近黑格的 `S = (max−min)/max` 会因为分母趋零而炸高，
//    `#020303` 这种肉眼纯黑能算出 S = 0.33，等于把整张黑封面判成彩色。
// 2. 剩下的按色相贪心聚类（阈值 [FamilyHueThreshold] 度），每族记录
//    **权重**（色度质量 ÷ 全图色度预算）和**重心**（在封面上的归一化位置）。
// 3. 族数超过 [BlobSlotCount] 时反复合并色相最近的两族；不足则后面的槽位权重为 0。
//
// 位置由家族重心决定（见 [_familyTargets]），再做一次斥力松弛保证色斑不会叠成一坨。
//
// 每个斑在渲染时做三件事：
//
// 1. **浓度与大小都按权重分配**（[_blobAlpha] / [_blobRadiusFactor]）。浓度有下限保护
//    （占比 4% 的颜色不会消失），**半径没有**——占比小的颜色可以是亮的，不能是大的。
//    这是「配色比例 ≈ 封面比例」唯一的实现点。
// 2. **明度台阶**：家族原始亮度被丢弃，换成按槽位分配的固定台阶
//    （[BlobTemplate.targetLightness]），再整体乘一个由 `lumaMean` 决定的倾斜。
//    同亮度的色斑叠在一起只会得到「一片均匀的中间调雾」；拉开台阶才有光源和暗部。
// 3. **无彩封面走另一条路**（[_neutralFamilies]）：网格里一格都没过色度门限时，
//    不生成任何彩色家族，只按网格亮度切出「亮区」画一个中性斑。
//    这修掉了「纯白封面被渲染成粉红斑」的老 bug——根因是 HSL 里纯白的色相是 0，
//    而 0 度就是红色，再被饱和度下限一抬就变成有效的粉红。
//
// ## 明度进背景，但不复现封面
//
// 明度只作用于「曝光」，落在安全区间内，且不做大面积染色。
//
// **上限是对着底栏 + 白色前景两条线定的，不是对着某个理论安全值定的。** 底栏
// `BottomBarGlass` 带封面位图（alpha 0.50）+ 白光层，在亮封面上本身就落在 0.35~0.50；
// 页面压在 0.13（早期取值）就是「导航栏发白、页面发黑」的割裂。而前景是**写死的纯白**
// （`AppColors.textPrimary`），不随封面明度自适应，所以底色也不能无限抬。
// [BaseSpan] 是底色那一层的旋钮；整屏明度还要靠 [LumaTiltSpan] 和 [BlobBaseAlpha]
// 一起收（色斑的 Screen 叠加才是亮度大头），三者的实测对照见 [BaseSpan] 的注释。
//
// 再往上（>0.5）亮封面的白色副标题会掉到 3.5:1 以下，那时候才需要前景自适应或加一层纱。
//
// ## 和播放页的关系
//
// **播放页不用这个。** 全屏播放页走 `PlayerCoverColorExtractor`（我们的算法 + 4×4 网格），
// 是另一套视觉语言，不受影响。本文件只服务「除播放页外所有变色页面」。

// ================================================================================================
// 参数
// ================================================================================================

/** 径向遮罩位图边长。放大到全屏约 3～4 倍，双线性插值刚好平滑。 */
private const val MaskSizePx = 384

/** 粗颗粒噪声边长：数值小 → 颗粒大 → 抗色带主力。 */
private const val CoarseNoiseSizePx = 96

/** 细颗粒噪声边长：数值大 → 颗粒细 → 只提供质感。 */
private const val FineNoiseSizePx = 320

/** 低频噪声边长。 */
private const val LowFrequencyNoiseSizePx = 256

/** 低频噪声的格点数：越少越"云"，越多越"斑"。 */
private const val LowFrequencyNoiseCells = 6

/** 低频噪声中心值与振幅（128 ± 16 → Overlay 下只有 5% 左右的明度起伏）。 */
private const val LowFrequencyNoiseBase = 128
private const val LowFrequencyNoiseAmplitude = 16

private const val CoarseNoiseAlpha = 0.038f
private const val FineNoiseAlpha = 0.016f
private const val LowFrequencyNoiseAlpha = 0.45f

/**
 * 烤图降采样倍率。
 *
 * 2 而不是 1：整片是雾，放大插值看不出来；而烤图成本与像素数成正比，
 * 砍到 1/4 像素让换封面时的重烤从 ~30ms 掉到 ~7ms。
 */
private const val LiteDownscale = 2

/** 色相慢漂移幅度（度）。各槽位周期不同，静止图上看不出动作。 */
private const val HueDriftDegrees = 4f

// ================================================================================================
// 明度（第三个正交维度）
// ================================================================================================
//
// 明度只从网格算出一个标量 `lumaMean`（0..1，Rec.709 亮度），然后派生三个量：
//
//     lumaGain    = lumaMean^[LumaGamma]                      // 0..1，压一下中点
//     baseLuma    = [BaseFloor] + lumaGain × [BaseSpan]        // 底色亮度
//     exposure    = [AmbientExposureFloor] + (1 − 该值) × lumaGain  // 环境层强度系数
//
// 底色亮度恒在 [BaseFloor]（0.030，纯黑封面）到 BaseFloor + BaseSpan（0.48，纯白封面）
// 之间。**上限不能无限抬**：前景是写死的纯白，底色再往上，文字和图标就开始失去对比度。

/**
 * lumaMean → 增益的幂次。0.85 把中点稍微抬起来，让「中灰封面」不至于太暗。
 *
 * 不要降到 0.6 以下：那会把低端（暗封面）也一起抬起来，一张九成黑的封面会被渲染成
 * 中灰，直接毁掉它的气质。这个参数只该影响中高端。
 */
private const val LumaGamma = 0.85f

/** 底色亮度下限（纯黑封面）。和旧的 `0.030` 完全一致，暗封面表现不变。 */
private const val BaseFloor = 0.030f

/**
 * 底色亮度增量（纯白封面在 BaseFloor 之上再加这么多）。
 *
 * 依据是「和底栏对齐」+「白色前景还能读」两条线的交点：
 *
 * - 底栏 `BottomBarGlass` 带封面位图（alpha 0.50）+ 白光层，在亮封面上本就落在
 *   0.35~0.50 的亮度区间；页面压在 0.13 就是「导航栏发白、页面发黑」的割裂。
 * - 前景是**写死的纯白**（`AppColors.textPrimary = #FFFFFF`、`textSecondary = 白 70%`），
 *   不随封面明度自适应，所以底色越高，文字对比度掉的越快。
 *
 * ## 调亮度的时候，这三个是一组，别只动这一个
 *
 * 页面亮度的大头**不是底色**而是色斑的 Screen 叠加。实测：只把 [BaseSpan] 从 0.45 压到
 * 0.32，整屏只掉了 0.03 sRGB（#745C44 → #6C5434），几乎看不出。三个一起收才有量：
 *
 * | 组合 ([BaseSpan] / [LumaTiltSpan] / [BlobBaseAlpha]) | 暖色封面的整屏背景 | 白字对比 |
 * |---|---|---|
 * | 0.45 / 0.90 / 0.34 | #745C44 | 6.3:1 |
 * | 0.32 / 0.90 / 0.34 | #6C5434 | 7.1:1 |
 * | 0.22 / 0.62 / 0.27 | #5C4424 | 9.1:1 |
 * | **0.16 / 0.55 / 0.22（当前）** | **#443424** | **11.9:1** |
 *
 * 三项各管一层：`BaseSpan` 管底色，`LumaTiltSpan` 管「斑比底色亮多少」，
 * `BlobBaseAlpha` 管「斑有多实」。只压 `BaseSpan` 会得到「暗底 + 依然明亮的斑」，
 * 看起来还是亮。
 *
 * 不要为了提亮去改 [LumaGamma]：那会连低端一起抬起来，九成黑的封面被渲染成中灰，
 * 那张封面的气质就没了。
 */
private const val BaseSpan = 0.16f

/** 底色的色相偏置：基础量。 */
private const val BaseTint = 0.032f

/**
 * 亮底色额外增加的主色掺量（每单位 baseLuma）。
 *
 * 纯中性灰的亮背景会很「死」——底色越亮，固定色偏的占比越小，主色相就看不见了。
 * 按亮度补偿回来，亮封面才既亮又带色温。
 */
private const val BaseTintBoost = 0.14f

/** 底色冷调偏移：蓝最重、绿居中、红为零，和旧的 (0.030, 0.033, 0.040) 一致。 */
private const val BaseChillGreen = 0.003f
private const val BaseChillBlue = 0.010f

/**
 * 曝光对**环境层**（色温晕 / 画外轮廓光）的强度系数区间下限。
 *
 * 这两层盖满整屏，所以既要乘主家族存在感，也要乘曝光：0.55 意味着纯黑封面的环境层
 * 只保留一半多一点的强度，纯白封面保留全量。
 */
private const val AmbientExposureFloor = 0.55f

/**
 * 斑明度倾斜：`阶梯值 × (LumaTiltBase + LumaTiltSpan × lumaMean)`。
 *
 * 这个数直接决定色斑比底色亮多少，而**色斑的 Screen 叠加才是页面亮度的主要来源**
 * （底色只占三成左右）。所以「整体偏亮」时先看这里，而不是只压 [BaseSpan]。
 *
 * 纯黑 ×0.85，纯白 ×1.47。别拉到 1.0 以上：斑会明显压过底色，变成一块块亮斑。
 */
private const val LumaTiltBase = 0.85f
private const val LumaTiltSpan = 0.55f

/**
 * 斑饱和度的亮度补偿：`饱和度 × (1 + lumaMean × 该值)`。
 *
 * 这是提亮底色之后**必须**配套的一步。色斑用 `Screen` 叠加，而 `Screen` 在亮底上几乎
 * 不动：底色 0.60 叠上 0.60 的斑只到 0.67（+0.07），颜色基本消失，整页变成一片死灰。
 *
 * 把饱和度随亮度放大，`Screen` 才有色度可以加：底色 0.60 叠上一个饱和红
 * → 三通道分别到 0.71 / 0.64 / 0.64，读得出暖色偏移。
 *
 * 所以目标是「亮**而且**有颜色」，不是「亮成灰」。
 */
private const val SatTiltSpan = 1.10f

// ================================================================================================
// 无彩封面（纯白 / 纯黑 / 纯灰 / 单色）
// ================================================================================================
//
// 网格里没有任何一格通过色度门限时，**不能**退回「三个锚点」——因为 HSL 里纯白的色相是
// 0（`max == min` 时色相未定义，`ColorUtils.RGBToHSL` 给 0），而 0 度就是红色。再被饱和度
// 下限一抬，纯白封面就渲染成一块粉红斑。这是必须单独处理的路径，不是退化情况。

/**
 * 无彩模式的结构门限：网格亮度的标准差低于此值 ⇒ 纯色封面。
 *
 * 纯色封面没有结构，背景也不该有结构：不画任何斑，只剩底色 + 曝光。这才是「比例对」。
 */
private const val NeutralMinSpread = 0.045f

/**
 * 无彩斑明度的取值区间：亮区平均亮度映射到 [Floor, Ceil]。
 *
 * 高端要和 [BaseSpan] 拉开的幅度对应 —— 亮区必须明显亮于底色，才读得出「光从哪来」。
 */
private const val NeutralLumaFloor = 0.42f
private const val NeutralLumaCeil = 0.82f

/**
 * ② 色温晕的中心不透明度。实际值会乘主家族的存在感 `_presence(weight[0])`。
 *
 * 它是一层盖满整屏的径向光，所以必须受权重控制：固定 0.11 在全彩封面上刚好，
 * 在「九成黑 + 一小块蓝光」的封面上就足以把黑色刷成灰蓝，直接毁掉比例。
 */
private const val AmbientWashAlpha = 0.11f

/** ④ 画外轮廓光（同样乘主家族存在感）。 */
private const val RimLightAlpha = 0.13f
private const val RimLightLightness = 0.66f
private const val RimLightSaturationScale = 0.55f
private const val RimLightRadiusRatio = 0.86f

private const val TwoPi = (2 * PI).toFloat()

// ================================================================================================
// 色斑物理（弹簧 + 流场噪声）
// ================================================================================================
//
// 色斑的位置不再是 `sin/cos` 算出来的参数曲线，而是**积分出来的物理状态**：
//
//     速度 += (锚点 - 位置) × 弹簧刚度 + 噪声场(位置, 时间) × 推力
//     速度 *= 阻尼
//     位置 += 速度 × dt
//
// 前半句是弹簧（保证色斑不会漂出画面、运动带惯性），后半句是噪声（保证轨迹永不重复）。
// 物理上就是 Ornstein-Uhlenbeck 过程。
//
// 为什么不直接用随机数：逐帧随机会抖成噪点，是廉价感的来源。流场噪声的速度场是**平滑**的，
// 相邻位置方向连续，所以轨迹是流动的、有机的，但永远不闭合。
//
// 坐标全部**归一化**（0..1，相对屏幕宽高），这样物理参数与屏幕尺寸无关。

/** 弹簧刚度：色斑被拉回锚点的强度（振荡周期约 10 秒）。 */
private const val FieldSpring = 0.35f

/** 速度阻尼：让色斑收住，而不是一直荡。 */
private const val FieldDamping = 1.1f

/** 噪声推力（归一化单位 / 秒²）。与 [FieldDamping] 的比值决定稳态速度。 */
private const val FieldNoiseForce = 0.02f

/** 噪声场的空间频率（归一化坐标 × 该值 = 屏幕上大约这么多"块"）。 */
private const val FieldNoiseScale = 5f

/** 噪声场的时间演化速度（越大变化越快）。 */
private const val FieldNoiseTime = 0.045f

/** 速度上限（归一化单位 / 秒），防止噪声把色斑甩出去。 */
private const val FieldMaxSpeed = 0.03f

/**
 * 网格明暗分离蒙版的强度。**亮区和暗区分开给，因为两种混合模式的量级完全不同。**
 *
 * - `Screen(base, white) = 1`，所以亮区蒙版满强度等于把像素推到白色一半；
 * - `Multiply(base, black, α) = base × (1 − α)`，所以暗区蒙版满强度等于把像素减半。
 *
 * 早期两者共用一个 0.5，实测把一张黑白照片封面渲染成「上部 #A3A3A4 / 下部 #333434」
 * 的极端分裂 —— 整片被读成一个自上而下的渐变，正是极光方案最怕的方向感。
 * 现在的量级只够「读得出方向」，不够「读出图案」。
 *
 * 上限不要超过 0.2：再高就能从插值里看出封面构图的明暗骨架。
 */
private const val GridLumaHiAlpha = 0.14f
private const val GridLumaLoAlpha = 0.20f

/**
 * 蒙版偏离量的满量程。
 *
 * 单格亮度与全图均值的偏离极少超过 0.5（那是「一半纯黑一半纯白」的极端），
 * 所以用 0.5 而不是 1.0 做满量程；用 1.0 会让所有封面的蒙版都偏弱。
 */
private const val LumaMaskFullScale = 0.5f

/**
 * 一个色斑**槽位**的外观模板。
 *
 * 位置不在这里 —— 它由封面的 4×4 网格聚类出来的「色彩家族」重心决定（见 [_familyTargets]）。
 * 槽位只描述：多大、呼吸多快、色相漂移多慢、多亮、多饱和。
 *
 * 索引 0 = 权重最大的家族（最亮最大），往后依次变小变暗。槽位数量固定为
 * [BlobSlotCount]，家族不足时后面的槽位 alpha 归零（见 [_blobAlpha]），
 * 这样 `animateColorAsState` / `AuroraField` 的调用次数恒定，补间逻辑不用为
 * 「切歌后家族数变了」做特判。
 */
private class BlobTemplate(
    val radiusRatio: Float,
    val breathSec: Float,
    val breathAmount: Float,
    /** 色相漂移周期（秒）。各槽位取不同质数，避免同步。 */
    val hueDriftPeriodSec: Float,
    /**
     * 该槽位的排序系数（只做轻微递减）。
     *
     * **不要指望它承担「主次」**——主次由家族权重通过 [_blobAlpha] 决定。
     * 这里留一点递减只是为了「同权重的两族」也有先后，值域刻意压得很窄。
     */
    val alphaScale: Float,
    /** 相对家族原始饱和度的倍率。 */
    val saturationScale: Float,
    /** 该槽位被强制设定到的亮度（HSL 的 L）。这是「明度台阶」的来源。 */
    val targetLightness: Float,
)

/** 色斑槽位数量。固定值，让所有动画调用次数恒定。 */
private const val BlobSlotCount = 5

/**
 * 最大色斑不透明度（权重 = 100% 时的基准）。
 *
 * 实际 alpha = `BlobBaseAlpha × 槽位系数 × _presence(weight)`，
 * 所以色斑的「浓淡」直接读作家族在封面里的色度占比。
 *
 * 0.34 而不是 0.30：权重取满的情况几乎不存在（要整张封面都是实色），
 * 常规封面落在 0.4~0.6，乘出来才是 0.24~0.28 —— 和旧的固定三斑视觉量级接近。
 * 0.27 而不是更高：这个值直接乘以家族权重，是页面整体亮度的第二个主源
 * （第一个是 [LumaTiltSpan] 控制的斑明度）。偏亮时和那个一起收。
 */
private const val BlobBaseAlpha = 0.22f

/**
 * 存在感下限：权重再小，色斑也保留这个比例的存在感。
 *
 * 0.30 意味着「占比 0 的颜色」也有 30% 的浓度 —— 看起来偏高，但权重为 0 的槽位
 * 会被 [_presence] 直接短路成 0（缺席家族不画），这个 floor 只作用于**真实存在**
 * 的家族。它的作用是不让「占比 4% 的一抹颜色」彻底消失。
 */
private const val PresenceFloor = 0.30f

/**
 * 权重 → 存在感的幂次。
 *
 * 1.0 = 严格线性（占比 4% 只有 0.04 的存在感，等于看不见）；
 * 0.60 让 4% 落到 0.44、40% 落到 0.70、100% 落到 1.0 ——
 * 差距是三倍而不是二十五倍，眼睛读得出主次，又不会把小颜色饿死。
 */
private const val PresencePower = 0.60f

/**
 * 半径系数下限。
 *
 * 半径**不吃** [PresenceFloor]：占比 4% 的颜色可以是亮的，但不能是大的。
 * 0.30 意味着最小的一团也有 0.30 倍半径（直径约半个屏）——再小就只是一粒噪点了。
 */
private const val RadiusFloor = 0.30f

/**
 * 槽位目标色的饱和度区间。
 *
 * 下限保证低饱和家族不会退成纯灰（「有颜色」本身是这套背景的目的）；
 * 上限留一档余量给 [_retint] 里的 [BlobTemplate.saturationScale] 再缩。
 */
private const val MinBlobSaturation = 0.16f
private const val MaxSlotSaturation = 0.90f

/**
 * 槽位缺席（家族数不足）时用的中性色。
 *
 * 不能用纯黑：Oklab 插值从黑走向彩色会经过一段脏棕。中灰是各向同性的，
 * 补间路径最短最干净；而且该槽位的 alpha 同时归零，看不见。
 */
private val NeutralSlotColor = Color(0xFF808080)

private val BlobSlots = listOf(
    BlobTemplate(
        radiusRatio = 0.78f,
        breathSec = 23f, breathAmount = 0.09f,
        hueDriftPeriodSec = 71f,
        alphaScale = 1.00f,
        saturationScale = 1.00f, targetLightness = 0.46f,
    ),
    BlobTemplate(
        radiusRatio = 0.62f,
        breathSec = 19f, breathAmount = 0.12f,
        hueDriftPeriodSec = 89f,
        alphaScale = 0.92f,
        saturationScale = 0.80f, targetLightness = 0.32f,
    ),
    BlobTemplate(
        radiusRatio = 0.74f,
        breathSec = 27f, breathAmount = 0.08f,
        hueDriftPeriodSec = 67f,
        alphaScale = 0.86f,
        saturationScale = 0.62f, targetLightness = 0.20f,
    ),
    BlobTemplate(
        radiusRatio = 0.56f,
        breathSec = 31f, breathAmount = 0.14f,
        hueDriftPeriodSec = 83f,
        alphaScale = 0.80f,
        saturationScale = 0.55f, targetLightness = 0.16f,
    ),
    BlobTemplate(
        radiusRatio = 0.50f,
        breathSec = 37f, breathAmount = 0.10f,
        hueDriftPeriodSec = 97f,
        alphaScale = 0.74f,
        saturationScale = 0.50f, targetLightness = 0.13f,
    ),
)

/** 家族数为 0（取色失败 / 灰度封面）时的兜底锚点。 */
private val FallbackCenters = floatArrayOf(
    0.20f, 0.33f,
    0.88f, 0.20f,
    0.64f, 0.84f,
)

// ================================================================================================
// 对外契约
// ================================================================================================

/**
 * 读取封面色板（**与播放页同一套取色**）。
 *
 * 走 `extractPlayerCoverPalette`（`core/util/PlayerCoverColorExtractor.kt`，自带进程级缓存）：
 * 整图缩 64×64，`primary` = 全图算术平均、`secondary` = 最鲜艳、`accent` = 最大占比。
 *
 * 这里只取颜色，不取图 —— 颜色进 [CoverPaletteBackdrop] 之后才会变成极光。
 * 三色的「色相分离」在 [CoverPaletteBackdrop] 里做（见 [_ensureAnchorSpread]）。
 *
 * @param coverUrl 封面地址（本地路径 / `file://` / 网络 URL）；null 或空串直接回退。
 */
@Composable
fun rememberCoverPalette(coverUrl: String?): ColorPalette {
    // url → palette 内存缓存：避免同一页内切来切去时重复取色
    val paletteCache = remember { mutableMapOf<String, ColorPalette>() }
    var palette by remember(coverUrl) {
        mutableStateOf(coverUrl?.let { paletteCache[it] } ?: ColorPalette.fallback)
    }

    LaunchedEffect(coverUrl) {
        val url = coverUrl
        if (url.isNullOrEmpty()) {
            palette = ColorPalette.fallback
            return@LaunchedEffect
        }
        paletteCache[url]?.let { cached ->
            palette = cached
            return@LaunchedEffect
        }
        val extracted = try {
            extractPlayerCoverPalette(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val resolved = extracted ?: ColorPalette.fallback
        paletteCache[url] = resolved
        palette = resolved
    }
    return palette
}

/**
 * 全站变色页共用背景：**Aurora Field**（图层与设计依据见文件头）。
 *
 * @param sheen 历史参数，**已废弃且不再使用**。高光感由色斑自身的径向衰减与画外轮廓光提供。
 * @param coverUrl 封面地址。**不会被读取成图**，只用来派生运动相位——
 *                 不同封面得到不同的极光形态，换歌时画面会整体「换一口气」。
 * @param palette 由 [rememberCoverPalette] 取得，或用 `LocalCoverThemePalette.current`。
 *                这里**真正被用的是 `coverGrid` / `coverGridDim`**（4×4 网格，聚类成
 *                色彩家族）；`primary` / `secondary` / `accent` 只在网格取不到时做兜底。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun CoverPaletteBackdrop(
    sheen: Boolean = false,
    coverUrl: String?,
    palette: ColorPalette,
    modifier: Modifier = Modifier,
) {
    // 遮罩 / 三种噪声都只生成一次
    val mask = remember { _createRadialMask(MaskSizePx) }
    val coarseNoise = remember { _brushOf(_createNoise(CoarseNoiseSizePx, 11L)) }
    val fineNoise = remember { _brushOf(_createNoise(FineNoiseSizePx, 7331L)) }
    val lowFrequencyNoise = remember {
        _createLowFrequencyNoise(LowFrequencyNoiseSizePx, LowFrequencyNoiseCells)
    }
    // 封面的 4×4 网格 → 明暗分离蒙版（亮区 Screen / 暗区 Multiply）。
    // 这是整片背景里**唯一**用到「封面空间结构」的一层，而且只取明暗、不取颜色；
    // 它负责让雾有方向（左上稍亮、右下稍暗），不负责让雾有图案。
    val gridLuma = remember(palette.coverGrid, palette.coverGridDim) {
        _createGridLumaMasks(palette.coverGrid, palette.coverGridDim)
    }

    // 网格的亮度统计。`lumaMean` 是「整体明度」这个维度的全部输入，
    // 由它派生出底色亮度、环境层强度和斑明度倾斜。
    val gridUsable = palette.coverGridDim > 0 &&
        palette.coverGrid.size >= palette.coverGridDim * palette.coverGridDim
    val lumaMean = remember(palette.coverGrid) { _lumaMean(palette.coverGrid) }
    val lumaGain = remember(lumaMean) { lumaMean.coerceIn(0f, 1f).pow(LumaGamma) }
    val baseLuma = remember(lumaGain) { BaseFloor + lumaGain * BaseSpan }
    val exposure = remember(lumaGain) { AmbientExposureFloor + (1f - AmbientExposureFloor) * lumaGain }
    val saturationBoost = remember(lumaMean) { 1f + lumaMean.coerceIn(0f, 1f) * SatTiltSpan }

    // 4×4 网格 → **色彩家族**。三条路径，互斥且顺序固定：
    //
    // 1. 有彩：色相聚类（绝大多数封面）。
    // 2. 无彩但有结构：切出亮区做中性斑（纯灰、单色、黑白照）。
    // 3. 无彩且无结构：一个斑都不画（纯白 / 纯黑 / 纯色），只剩底色 + 曝光。
    //
    // 网格本身取不到（取色失败）时才退到三锚点兜底 —— 那种情况连"无彩"都判断不了。
    val families = remember(
        gridUsable,
        palette.coverGrid,
        palette.coverGridDim,
        palette.primary,
        palette.secondary,
        palette.accent,
    ) {
        if (!gridUsable) {
            _fallbackFamilies(
                _ensureAnchorSpread(palette.primary, palette.secondary, palette.accent),
            )
        } else {
            _clusterGrid(palette.coverGrid, palette.coverGridDim)
                .ifEmpty { _neutralFamilies(palette.coverGrid, palette.coverGridDim) }
        }
    }

    // 槽位目标色：只带「色相 + 饱和度」，亮度留到绘制时按槽位台阶 / 家族明度决定。
    // 补间用中性亮度 0.5，切歌时 Oklab 插值不会扫过一圈彩虹。
    //
    // 饱和度**不设下限**：无彩家族的饱和度是 0，必须原样传 0，否则色相 0（红色）
    // 会把它染成粉色 —— 这正是「纯白封面渲染成粉红斑」的根因。
    val slotColors = remember(families) {
        Array(BlobSlotCount) { i ->
            families.getOrNull(i)?.let {
                _hslToColor(
                    hue = it.hue,
                    saturation = if (it.saturation <= 0f) {
                        0f
                    } else {
                        it.saturation.coerceIn(MinBlobSaturation, MaxSlotSaturation)
                    },
                    lightness = 0.5f,
                )
            } ?: NeutralSlotColor
        }
    }
    val slotWeights = remember(families) {
        FloatArray(BlobSlotCount) { i -> families.getOrNull(i)?.weight ?: 0f }
    }
    // 每槽位的最终明度：无彩家族自带（那是它的全部信息），否则用槽位台阶 × 明度倾斜。
    val slotLightnesses = remember(families, lumaMean) {
        val tilt = LumaTiltBase + LumaTiltSpan * lumaMean.coerceIn(0f, 1f)
        FloatArray(BlobSlotCount) { i ->
            val family = families.getOrNull(i)
            family?.lightnessHint ?: (BlobSlots[i].targetLightness * tilt)
        }
    }

    // 极光是**一张按封面算出来的静态图**：没有补间、没有帧时钟、没有物理积分。
    //
    // 每帧重画这十来层全屏混合的代价远大于它换来的观感 —— 骁龙 680 上实测 44 ms/帧
    // （其中 82% 是纯填充），而色相漂移幅度只有 4°、周期以十秒计，静止根本看不出来。
    // 切歌直接换图、不做 900ms 补间：补间期间每帧都要重烤一次，等于把卡顿
    // 精确地放在用户最容易注意到的那一刻。
    val animatedColors = slotColors
    val animatedWeights = slotWeights

    val base = remember(palette.primary, baseLuma) { _baseColorFor(palette.primary, baseLuma) }

    // 相位种子：色相漂移的起始相位（同一封面同一形态）
    val seed = remember(coverUrl) {
        (((coverUrl?.hashCode() ?: 0) and 0xFFFF).toFloat() / 65535f) * TwoPi
    }

    // 家族重心 → 色斑锚点（含斥力松弛）。静态下位置直接落在锚点上
    // （`step` 的首次调用就是「初始化到锚点」）。
    val targets = remember(families) { _familyTargets(families) }
    val field = remember(targets) { AuroraField(BlobSlotCount).also { it.step(1f, targets) } }

    // ── 昂贵的 Skia 对象按「最终色」重建 ──
    //
    // `animatedColors` / `slotLightnesses` 都是每次重组重建的数组，所以用**内容哈希**当 key。
    // 四个依赖「最终色」的对象共用这一份 key：颜色、明度台阶、饱和度补偿、封面
    // 任何一个变了都要重算 —— 只拿其中一部分当 key 会留下「明度变了但色斑不动」的陈旧值。
    val tintKeys = listOf(
        animatedColors.contentHashCode(),
        saturationBoost,
        slotLightnesses.contentHashCode(),
        coverUrl,
    )

    val slotTints = remember(tintKeys) {
        Array(BlobSlotCount) { i ->
            val slot = BlobSlots[i]
            _retint(
                source = animatedColors[i],
                hueDelta = sin(seed + i * 2.1f) * HueDriftDegrees,
                saturationScale = slot.saturationScale * saturationBoost,
                targetLightness = slotLightnesses[i],
            )
        }
    }
    val slotFilters = remember(tintKeys) {
        slotTints.map { ColorFilter.tint(it) }
    }
    /** ④a 亮区蒙版用的染色滤镜（主家族当前色），和色斑同一批 key 重建 */
    val gridTintFilter = remember(tintKeys) {
        ColorFilter.tint(slotTints[0])
    }
    val rimTint = remember(tintKeys) {
        _retint(
            source = animatedColors[0],
            hueDelta = 0f,
            saturationScale = RimLightSaturationScale,
            targetLightness = RimLightLightness,
        )
    }

    // 径向光的几何只跟画布尺寸有关：尺寸没变就复用**同一个 Brush 实例**
    // （Compose 的 ShaderBrush 内部按 size 缓存 Skia Shader，前提是实例不变）
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val ambientBrush = remember(canvasSize, tintKeys) {
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        if (w <= 0f || h <= 0f) {
            null
        } else {
            Brush.radialGradient(
                colorStops = arrayOf(
                    0f to slotTints[0].copy(alpha = 1f),
                    0.55f to slotTints[0].copy(alpha = 0.45f),
                    1f to Color.Transparent,
                ),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = max(w, h) * 1.08f,
            )
        }
    }
    val rimBrush = remember(canvasSize, tintKeys) {
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        if (w <= 0f || h <= 0f) {
            null
        } else {
            Brush.radialGradient(
                colorStops = arrayOf(
                    0f to rimTint.copy(alpha = 1f),
                    0.45f to rimTint.copy(alpha = 0.42f),
                    1f to Color.Transparent,
                ),
                center = Offset(w * -0.06f, h * 0.03f),
                radius = max(w, h) * RimLightRadiusRatio,
            )
        }
    }

    // 暗角 brush 只跟画布尺寸有关，两档共用一份
    val vignetteBrush = remember(canvasSize) {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.24f),
            0.12f to Color.Transparent,
            0.88f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.26f),
        )
    }

    val stack = AuroraStack(
        base = base,
        mask = mask,
        filters = slotFilters,
        ambientBrush = ambientBrush,
        rimBrush = rimBrush,
        weights = animatedWeights,
        exposure = exposure,
        field = field,
        seed = seed,
        grid = gridLuma,
        gridTintFilter = gridTintFilter,
        lowNoise = lowFrequencyNoise,
        coarseNoise = coarseNoise,
        fineNoise = fineNoise,
        vignette = vignetteBrush,
    )

    // 烤图 key 只含「换个封面才会变」的量：稳态下图不重建，一帧只有一次放大 blit
    val bakeKeys = listOf(
        animatedColors.contentHashCode(),
        animatedWeights.contentHashCode(),
        saturationBoost,
        slotLightnesses.contentHashCode(),
        exposure,
        coverUrl,
        canvasSize,
    )

    Box(
        modifier = modifier
            .background(base)
            .onSizeChanged { canvasSize = it },
    ) {
        // 整叠（底色→色晕→色斑→网格明暗→噪声→暗角）烤成一张 1/2 分辨率的图。
        // 每帧十余层全屏混合在这类 GPU 上是 40ms 起步（SM6225 实测 44ms/帧，
        // 其中 82% 是纯填充），而烤图后每帧只剩一次 blit。
        val baked = remember(bakeKeys) {
            val w = canvasSize.width / LiteDownscale
            val h = canvasSize.height / LiteDownscale
            if (w <= 1 || h <= 1) null else bakeAurora(w, h) { drawAuroraStack(stack, 0f) }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            baked ?: return@Canvas
            drawImage(
                image = baked,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

// ================================================================================================
// 绘制
// ================================================================================================

/**
 * 一整叠背景层（底色 → 色温晕/轮廓光 → 色斑 → 网格明暗场 → 噪声 → 暗角）的输入。
 *
 * 打包成一份是为了让「逐帧画」和「烤成一张图」走同一段绘制代码：
 * 两套各写一遍，视觉必然在某一档悄悄跑偏，而且没人会去比对。
 */
private class AuroraStack(
    val base: Color,
    val mask: ImageBitmap,
    val filters: List<ColorFilter>,
    val ambientBrush: Brush?,
    val rimBrush: Brush?,
    val weights: FloatArray,
    val exposure: Float,
    val field: AuroraField,
    val seed: Float,
    val grid: GridLumaMasks?,
    val gridTintFilter: ColorFilter,
    val lowNoise: ImageBitmap,
    val coarseNoise: Brush,
    val fineNoise: Brush,
    val vignette: Brush,
)

/** 把 [AuroraStack] 整叠画到当前 DrawScope（屏幕，或 [bakeAurora] 的离屏位图）。 */
private fun DrawScope.drawAuroraStack(s: AuroraStack, seconds: Float) {
    val w = size.width.roundToInt().coerceAtLeast(1)
    val h = size.height.roundToInt().coerceAtLeast(1)
    val full = IntSize(w, h)

    drawRect(color = s.base)

    _drawAurora(
        mask = s.mask,
        filters = s.filters,
        ambientBrush = s.ambientBrush,
        rimBrush = s.rimBrush,
        weights = s.weights,
        exposure = s.exposure,
        field = s.field,
        seconds = seconds,
        seed = s.seed,
    )

    // 封面构图的明暗分离场：只给方向不给图案。
    //
    // 两张 alpha 蒙版而不是一张灰度图走 Overlay：Overlay 在暗底上是 `2ab`，
    // 底色 0.06 遇上 0.7 只到 0.084（几乎没动）；而 Screen 在同样输入下是
    // `1−(1−a)(1−b)`，能到 0.74。亮度场只有用 Screen/Multiply 才真正生效。
    //
    // 强度必须压得很低（见 [GridLumaHiAlpha] / [GridLumaLoAlpha]）：这两种混合是在
    // 「推向白 / 减半」而不是「加一点」，满强度会直接把画面撕成上亮下暗两半。
    s.grid?.let { masks ->
        // 亮区：alpha 蒙版 + Screen。染色而不是纯白 —— 纯白会把画面洗成灰，
        // 用主家族的当前色（已含明度台阶）染色，光才带着封面的色温。
        drawImage(
            image = masks.hi,
            dstSize = full,
            alpha = GridLumaHiAlpha * s.exposure,
            colorFilter = s.gridTintFilter,
            blendMode = BlendMode.Screen,
            filterQuality = FilterQuality.Low,
        )
        // 暗区：黑色 alpha 蒙版 + Multiply → 按蒙版强度压暗
        drawImage(
            image = masks.lo,
            dstSize = full,
            alpha = GridLumaLoAlpha,
            blendMode = BlendMode.Multiply,
            filterQuality = FilterQuality.Low,
        )
    }

    // 低频明度起伏：值噪声拉伸铺满整屏，给渐变一点「不均匀」。
    // 完美的线性渐变看起来是算出来的，带一点点低频起伏才像真实的漫射光。
    drawImage(
        image = s.lowNoise,
        dstSize = full,
        alpha = LowFrequencyNoiseAlpha,
        blendMode = BlendMode.Overlay,
        filterQuality = FilterQuality.Low,
    )

    // 颗粒两层：粗颗粒抗色带，细颗粒给质感
    drawRect(brush = s.coarseNoise, alpha = CoarseNoiseAlpha, blendMode = BlendMode.Overlay)
    drawRect(brush = s.fineNoise, alpha = FineNoiseAlpha, blendMode = BlendMode.Softlight)

    // 暗角：只压最边上的 12%，中间 76% 完全交给色场。
    // 压得太深会把整片读成「一个自上而下的渐变」——这是极光方案最怕的方向感。
    drawRect(brush = s.vignette)
}

/** 把一段 DrawScope 绘制烤进一张 [ImageBitmap]（弱 GPU 档专用）。 */
private fun bakeAurora(w: Int, h: Int, block: DrawScope.() -> Unit): ImageBitmap {
    val image = ImageBitmap(w, h)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = GfxCanvas(image),
        size = Size(w.toFloat(), h.toFloat()),
        block = block,
    )
    return image
}

/**
 * 画色温晕 + 画外轮廓光 + N 个极光斑。
 *
 * 全部用 `BlendMode.Screen`：叠加时取「更亮的那个」，所以色斑相遇处会自然发亮而不是糊成泥。
 *
 * 三层都受家族权重控制（不是固定强度）：色斑的浓度与半径由各自槽位的权重决定，
 * 色温晕和画外轮廓光由**主家族的权重**决定。所以一张九成黑的封面渲染出来就是
 * 一片近黑 + 一小团微光，而不是把整屏刷成浅色。
 *
 * 颜色在调用处按量化色相算好（见那里的长注释），本函数是**纯绘制**：
 * 不含颜色推导，也不新建任何 Skia 对象。
 *
 * @param filters 与各槽位一一对应的 tint 滤镜（同一批 key 重建）。
 * @param ambientBrush 色温晕的径向渐变（几何 + 颜色都已缓存；`null` = 画布尺寸还没测出来）
 * @param rimBrush 画外轮廓光的径向渐变（同上）
 * @param weights 槽位家族权重（已补间，0..1）；0 表示该槽位本帧不画。
 * @param exposure 明度曝光系数（[AmbientExposureFloor]..1），只作用于环境层。
 */
private fun DrawScope._drawAurora(
    mask: ImageBitmap,
    filters: List<ColorFilter>,
    ambientBrush: Brush?,
    rimBrush: Brush?,
    weights: FloatArray,
    exposure: Float,
    field: AuroraField,
    seconds: Float,
    seed: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val longSide = max(w, h)

    // 主家族的「存在感」：整片背景的底噪（色温晕 / 画外轮廓光）都乘它。
    //
    // 这一层是必须的 —— 否则一张九成黑的封面，光是这两层固定强度的径向光
    // 就已经把整屏刷成了一片浅色，色斑算得再准也白搭。
    //
    // 再乘 exposure：环境层盖满整屏，所以它既要服从「有多少颜色」，也要服从「画面多亮」。
    val ambient = _presence(weights[0]) * exposure

    // ② 色温晕：圆心居中、半径盖满整屏，给上下半屏同一份基础色温。
    // 用槽位 0（权重最大的家族）的色相 —— 它最能代表这张封面的色温。
    if (ambientBrush != null) {
        drawRect(
            brush = ambientBrush,
            // 整体不透明度走 drawRect 的 alpha：它与 Shader 内的 1 / 0.45 / 0 逐档相乘，
            // 与原实现把 alpha 写进 colorStops 完全等价，但 Shader 因此可以跨帧复用
            alpha = AmbientWashAlpha * ambient,
            blendMode = BlendMode.Screen,
        )
    }

    // ②b 画外轮廓光：圆心落在屏幕左上之外，只有一瓣照进来。
    // 它比主斑再亮一档但面积很小，视觉上等价于「光源在画外」——
    // 平面渐变和有纵深场景的差别，基本就靠这一层。
    if (rimBrush != null) {
        drawRect(
            brush = rimBrush,
            alpha = RimLightAlpha * ambient,
            blendMode = BlendMode.Screen,
        )
    }

    // ③ 极光斑：位置来自物理积分（弹簧 + 流场噪声），轨迹永不重复。
    //    浓度和大小都从家族权重派生 —— 封面里占比大的颜色，就是屏幕上更大更实的那一坨。
    BlobSlots.forEachIndexed { index, slot ->
        val weight = weights[index]
        val alpha = _blobAlpha(weight, slot)
        if (alpha <= 0.002f) return@forEachIndexed
        val phase = seed + index * 2.1f
        val centerX = field.x[index] * w
        val centerY = field.y[index] * h
        val breath = 1f + sin(seconds * TwoPi / slot.breathSec + phase * 0.71f) * slot.breathAmount
        val radius = longSide * slot.radiusRatio * _blobRadiusFactor(weight) * breath
        val diameter = (radius * 2f).roundToInt().coerceAtLeast(1)

        withTransform({ translate(left = centerX - radius, top = centerY - radius) }) {
            drawImage(
                image = mask,
                dstSize = IntSize(diameter, diameter),
                alpha = alpha,
                colorFilter = filters.getOrNull(index),
                blendMode = BlendMode.Screen,
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

// ================================================================================================
// 位图生成（各只跑一次）
// ================================================================================================

// ================================================================================================
// 色斑物理
// ================================================================================================

/**
 * 各色斑槽位的位置与速度（**归一化坐标**，0..1 相对屏幕宽高）。
 *
 * 每帧由 [step] 积分一次：弹簧把色斑拉向目标，流场噪声把它推离直线。
 * 状态是普通 `FloatArray`（不是 Compose State）—— 重绘由外层 `seconds` 驱动，
 * 每帧读到的都是最新值，不需要额外的重组开销。
 */
private class AuroraField(val count: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private var initialized = false
    private var time = 0f

    /**
     * 推进一帧。
     *
     * @param dt 距上一帧的秒数（外层已夹到 ≤0.1，避免卡顿后一步跳变）。
     * @param targets 归一化的目标位置，长度 2×[count]（x0,y0,x1,y1,…）。
     */
    fun step(dt: Float, targets: FloatArray) {
        if (dt <= 0f) return
        if (!initialized) {
            for (i in 0 until count) {
                x[i] = targets[i * 2]
                y[i] = targets[i * 2 + 1]
            }
            initialized = true
            return
        }
        time += dt
        for (i in 0 until count) {
            val tx = targets[i * 2]
            val ty = targets[i * 2 + 1]

            // 弹簧：拉回锚点，带惯性
            vx[i] += (tx - x[i]) * FieldSpring * dt
            vy[i] += (ty - y[i]) * FieldSpring * dt

            // 流场噪声：平滑但永不重复的推力。
            // 两个分量取不同的空间偏移，否则色斑会沿着同一条对角斜线来回走。
            val nx = _valueNoise3(
                x[i] * FieldNoiseScale,
                y[i] * FieldNoiseScale,
                time * FieldNoiseTime + i * 31.7f,
            )
            val ny = _valueNoise3(
                x[i] * FieldNoiseScale + 47.3f,
                y[i] * FieldNoiseScale + 19.1f,
                time * FieldNoiseTime + i * 31.7f,
            )
            vx[i] += nx * FieldNoiseForce * dt
            vy[i] += ny * FieldNoiseForce * dt

            // 阻尼：让色斑收住，不至于一直荡
            val damp = (1f - FieldDamping * dt).coerceIn(0f, 1f)
            vx[i] *= damp
            vy[i] *= damp

            // 限速
            val speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            if (speed > FieldMaxSpeed) {
                val k = FieldMaxSpeed / speed
                vx[i] *= k
                vy[i] *= k
            }

            // 积分 + 兜底夹取（弹簧理论上不会跑出去，这里只防极端参数）
            x[i] = (x[i] + vx[i] * dt).coerceIn(-0.2f, 1.2f)
            y[i] = (y[i] + vy[i] * dt).coerceIn(-0.2f, 1.2f)
        }
    }
}

/** 三线性 value noise，返回 -1..1。 */
private fun _valueNoise3(px: Float, py: Float, pz: Float): Float {
    val xi = floor(px).toInt()
    val yi = floor(py).toInt()
    val zi = floor(pz).toInt()
    val xf = px - xi
    val yf = py - yi
    val zf = pz - zi
    // smoothstep：格点之间一阶连续，色斑转弯才顺滑
    val u = xf * xf * (3f - 2f * xf)
    val v = yf * yf * (3f - 2f * yf)
    val w = zf * zf * (3f - 2f * zf)

    val c000 = _hash3(xi, yi, zi)
    val c100 = _hash3(xi + 1, yi, zi)
    val c010 = _hash3(xi, yi + 1, zi)
    val c110 = _hash3(xi + 1, yi + 1, zi)
    val c001 = _hash3(xi, yi, zi + 1)
    val c101 = _hash3(xi + 1, yi, zi + 1)
    val c011 = _hash3(xi, yi + 1, zi + 1)
    val c111 = _hash3(xi + 1, yi + 1, zi + 1)

    return _lerp(
        _lerp(_lerp(c000, c100, u), _lerp(c010, c110, u), v),
        _lerp(_lerp(c001, c101, u), _lerp(c011, c111, u), v),
        w,
    )
}

/** 整数 hash → -1..1 的确定性伪随机值（Int 溢出 wrap 是预期行为）。 */
private fun _hash3(x: Int, y: Int, z: Int): Float {
    var h = x * 0x27d4eb2d + y * 0x165667b1 + z * 0x9e3779b9
    h = (h xor (h ushr 15)) * 0x85ebca6b
    h = (h xor (h ushr 13)) * 0xc2b2ae35
    h = h xor (h ushr 16)
    return (h and 0xFFFF).toFloat() / 32767.5f - 1f
}

private fun _lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

// ================================================================================================
// 封面网格 → 色彩家族
// ================================================================================================
//
// 这是「颜色种类变多、比例接近封面」的核心。
//
// 输入是封面的 4×4 网格（16 个格色，和播放页共用 `extractPlayerCoverPalette` 的产出）。
// 输出是若干「色彩家族」，每族带四样东西：
//
// | 字段 | 含义 | 用途 |
// |---|---|---|
// | `weight` | 该族的**色度质量占整张封面的比例**（0..1） | 色斑浓度与大小 |
// | `hue` | 家族色相（**色度加权的圆周**均值） | 色斑颜色 |
// | `saturation` | 家族平均饱和度 | 色斑颜色 |
// | `cx` / `cy` | 家族重心在封面上的位置（归一化 0..1） | 色斑锚点（[_familyTargets]） |
//
// ## 为什么权重用「色度质量」而不是「面积」或「HSL 饱和度」
//
// HSL 的 S 在接近黑色时完全失真：`#020303` 这种肉眼纯黑的格子会算出 S = 0.33，
// 因为 S = (max − min) / max，而 max 只有 3/255 —— 分母趋零把值炸了。
// 于是一张 90% 纯黑、只有一小块蓝光的封面，会被判成「整屏都是蓝」，渲染出一片
// 全屏蓝色雾。这就是「比例不对」的真正来源。
//
// 正确的度量是**色度（Chroma = max − min，0..1）**，它是绝对量，不受亮度影响：
//
//     #020303 → 0.004    #080A0C → 0.016    #213848 → 0.153    #FF0000 → 1.000
//
// 所以权重定义为 `Σ色度 / (16 × [ChromaFullScale])`：
// 整张封面都是实色时接近 1，只有一小块发光时接近 0。位置、色相、重心也一律按
// 色度加权 —— 亮而艳的格子说话，暗而糊的格子不投票。
//
// 有了 weight，一张七成蓝、两成红、一成金的封面出来就是一大片蓝 + 一块淡红 + 一点金，
// 而不是三团等权的雾。这是「配色比例 ≈ 封面比例」这条诉求唯一的实现点。

/**
 * 家族归属的色相门限（度）。超过这个距离就算另一个家族。
 *
 * 20° 是权衡出来的：4×4 网格的每格是整块区域的平均色，同色系相邻格的色相抖动
 * 通常在 10° 以内（能合住）；真正不同色系的格子间距远大于 20°（能分开）。
 */
private const val FamilyHueThreshold = 20f

/**
 * 色度门限：低于此值的格点算中性（黑 / 灰 / 白），不入族，只喂底色和亮度场。
 *
 * 0.06 相当于 max − min ≥ 15/255。实测一张「九成黑 + 一小块蓝光」的封面，
 * 黑格色度在 0.004~0.016 之间，真正的发光区在 0.07~0.15 之间，这个门限正好卡在中间。
 */
private const val FamilyMinChroma = 0.06f

/**
 * 「一颗实色格点」的色度基准。
 *
 * 整张 16 格都是这个色度时，家族权重 = 1。
 *
 * 0.30 对应一个中等饱和的实色（`#4A5A6A` 的色度是 0.13，`#3366CC` 是 0.60，
 * 淡紫灰 `#A1A6C5` 是 0.14）。取 0.42 会让绝大多数柔和色调的封面权重掉到 0.3 以下、
 * 画面整体偏暗；取 0.30 让「整屏一个柔和主色」也能拿到 0.5 左右的权重。
 */
private const val ChromaFullScale = 0.30f

/** 色斑锚点的最小间距（归一化）。低于它就用斥力推开，避免色斑叠成一坨。 */
private const val TargetMinDistance = 0.22f

/** 色斑锚点的坐标安全区。 */
private const val TargetMinCoord = 0.10f
private const val TargetMaxCoord = 0.90f

/** 斥力松弛的迭代次数（只在切歌时跑一次，代价可以忽略）。 */
private const val TargetRelaxIterations = 24

/** 一个「色彩家族」：封面上色相相近的一组格点，或一个无彩的亮区。 */
private class ColorFamily(
    /** 权重：该族的色度质量占整张封面的比例（0..1）。 */
    val weight: Float,
    val hue: Float,
    val saturation: Float,
    /** 家族重心（归一化 0..1）。 */
    val cx: Float,
    val cy: Float,
    /**
     * 该族自带的明度。null = 用槽位的明度台阶（[BlobTemplate.targetLightness]）。
     *
     * 只有无彩模式会填这个值：那种情况下明度就是家族的全部信息，
     * 不能再被槽位阶梯覆盖掉（否则「暗区」会被抬成 0.46，直接失真）。
     */
    val lightnessHint: Float? = null,
)

/**
 * 聚类累加器。
 *
 * **一切按色度加权**：色度高的格子说话，暗而糊的格子不投票。所以色相、饱和度、
 * 重心、质量全都是同一个 `mass` 加权出来的，合并两簇只要逐个字段相加。
 *
 * 色相用 **cos/sin 累加**而不是直接加角度：色相是环形的，355° 和 5° 的算术平均
 * 是 180°（完全反色）。圆周累加天然没有这个问题。
 */
private class FamilyAcc {
    /** 色度质量 = Σ chroma（0..格数）。既是权重分子，也是所有均值的分母。 */
    var mass = 0f
    var hueCos = 0f
    var hueSin = 0f
    var saturation = 0f
    var cx = 0f
    var cy = 0f

    /** 当前代表色相（色度加权的圆周均值，0..360）。 */
    fun hueAngle(): Float {
        val deg = atan2(hueSin, hueCos) * 180f / PI.toFloat()
        return (deg % 360f + 360f) % 360f
    }

    fun merge(other: FamilyAcc) {
        mass += other.mass
        hueCos += other.hueCos
        hueSin += other.hueSin
        saturation += other.saturation
        cx += other.cx
        cy += other.cy
    }

    fun toFamily(chromaBudget: Float): ColorFamily = ColorFamily(
        weight = (mass / chromaBudget).coerceIn(0f, 1f),
        hue = hueAngle(),
        saturation = if (mass > 0f) saturation / mass else 0f,
        cx = if (mass > 0f) cx / mass else 0.5f,
        cy = if (mass > 0f) cy / mass else 0.5f,
    )
}

/**
 * 把 N×N 网格聚类成色彩家族。
 *
 * 贪心匹配：每个格点先找色相距离 < [FamilyHueThreshold] 的已有簇，找不到就开新簇。
 * 簇数超过 [BlobSlotCount] 后反复合并色相最近的两簇 —— 这样族数永远不超过槽位数，
 * 绘制侧不需要为「这次切歌有几个家族」做任何特判。
 *
 * @return 按权重降序；取不到任何家族时返回空列表。
 */
private fun _clusterGrid(grid: List<Color>, dim: Int): List<ColorFamily> {
    if (dim <= 0 || grid.size < dim * dim) return emptyList()

    val accs = ArrayList<FamilyAcc>(dim * dim)
    for (gy in 0 until dim) {
        for (gx in 0 until dim) {
            // 注意：判据是**色度**不是 HSL 饱和度。近黑格的 S 会因为分母趋零而炸高，
            // 用它做门限会把整张黑封面判成彩色（详见文件里 FamilyMinChroma 的注释）。
            val color = grid[gy * dim + gx]
            val chroma = max(color.red, max(color.green, color.blue)) -
                min(color.red, min(color.green, color.blue))
            if (chroma < FamilyMinChroma) continue

            val hsl = _hslOf(color)
            var acc = accs.firstOrNull { _hueGap(it.hueAngle(), hsl[0]) < FamilyHueThreshold }
            if (acc == null) {
                acc = FamilyAcc()
                accs += acc
            }
            val rad = hsl[0] * PI.toFloat() / 180f
            acc.mass += chroma
            acc.hueCos += cos(rad) * chroma
            acc.hueSin += sin(rad) * chroma
            acc.saturation += hsl[1] * chroma
            acc.cx += (gx + 0.5f) / dim * chroma
            acc.cy += (gy + 0.5f) / dim * chroma
        }
    }
    if (accs.isEmpty()) return emptyList()

    // 族数超上限：每次合并色相最近的两族（16 个格点最多 16 轮，代价可忽略）
    while (accs.size > BlobSlotCount) {
        var bestI = -1
        var bestJ = -1
        var bestGap = Float.MAX_VALUE
        for (i in accs.indices) {
            for (j in i + 1 until accs.size) {
                val gap = _hueGap(accs[i].hueAngle(), accs[j].hueAngle())
                if (gap < bestGap) {
                    bestGap = gap
                    bestI = i
                    bestJ = j
                }
            }
        }
        if (bestI < 0) break
        accs[bestI].merge(accs[bestJ])
        accs.removeAt(bestJ)
    }

    // 权重的分母是**整张封面**的色度上限：16 格 × ChromaFullScale。
    // 分母里含中性格（黑 / 灰 / 白）—— 它们不产生色斑，但它们占的位置本身就是
    // 「这里没有颜色」的证据，必须计入分母，否则少数彩色会被放大成满屏。
    val chromaBudget = dim * dim * ChromaFullScale
    return accs.map { it.toFamily(chromaBudget) }.sortedByDescending { it.weight }
}

/**
 * 网格取不到（灰度封面 / 取色失败）时的兜底：把三个锚点当成三个家族。
 *
 * 权重给 0.70 / 0.40 / 0.20 —— 没有网格就没有占比信息，这个量级对应
 * 「画面有明显主色但不至于铺满」，正常封面里常见的中位水平。
 */
private fun _fallbackFamilies(anchors: Array<Color>): List<ColorFamily> {
    val weights = floatArrayOf(0.70f, 0.40f, 0.20f)
    return anchors.mapIndexed { i, color ->
        val hsl = _hslOf(color)
        ColorFamily(
            weight = weights[i],
            hue = hsl[0],
            saturation = hsl[1],
            cx = FallbackCenters[i * 2],
            cy = FallbackCenters[i * 2 + 1],
        )
    }
}

// ================================================================================================
// 网格亮度统计 + 无彩模式
// ================================================================================================

/** Rec.709 亮度。网格亮度、亮度场、`lumaMean` 全部用这一个公式。 */
private fun _lumaOf(color: Color): Float =
    0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

/** 网格的亮度均值（0..1）。取不到网格时返回 0（等价于纯黑封面）。 */
private fun _lumaMean(grid: List<Color>): Float {
    if (grid.isEmpty()) return 0f
    var sum = 0f
    for (c in grid) sum += _lumaOf(c)
    return sum / grid.size
}

/** 网格亮度的标准差。用来判断「这张封面有没有明暗结构」。 */
private fun _lumaSpread(grid: List<Color>): Float {
    if (grid.isEmpty()) return 0f
    val mean = _lumaMean(grid)
    var sum = 0f
    for (c in grid) {
        val d = _lumaOf(c) - mean
        sum += d * d
    }
    return sqrt(sum / grid.size)
}

/**
 * 无彩封面 → **一个中性家族**（没有颜色，只有明暗）。
 *
 * 触发条件：网格里没有任何一格通过色度门限（`_clusterGrid` 返回空）。
 * 此时**绝不能**走三锚点兜底 —— 纯白在 HSL 里色相是 0（`max == min` 时色相未定义），
 * 而 0 度就是红色，再被饱和度下限一抬就变成粉红斑。这是必须单独处理的路径。
 *
 * 做法：以网格亮度均值为界切出「亮区」，把亮区的**面积**当权重、**亮度加权重心**当锚点、
 * **平均亮度映射到明度区间**当明度。暗区不画斑 —— 背景底色本来就是暗的，
 * 再画一个暗斑没有视觉作用，那部分交给底色和 ④b 暗区蒙版。
 *
 * @return 空列表 = 纯色封面（`lumaSpread < [NeutralMinSpread]`）。**纯色封面没有结构，
 *         背景也不该有结构**：只剩底色 + 曝光，这才是「比例对」。
 */
private fun _neutralFamilies(grid: List<Color>, dim: Int): List<ColorFamily> {
    if (dim <= 0 || grid.size < dim * dim) return emptyList()
    if (_lumaSpread(grid) < NeutralMinSpread) return emptyList()

    val mean = _lumaMean(grid)
    var count = 0
    var sumX = 0f
    var sumY = 0f
    var sumLuma = 0f
    for (i in 0 until dim * dim) {
        val luma = _lumaOf(grid[i])
        if (luma <= mean) continue
        count++
        sumX += (i % dim + 0.5f) / dim
        sumY += (i / dim + 0.5f) / dim
        sumLuma += luma
    }
    if (count == 0) return emptyList()

    val hiLuma = sumLuma / count
    return listOf(
        ColorFamily(
            weight = count.toFloat() / (dim * dim),
            hue = 0f,
            // 0 饱和度是这条路径的全部意义：色相 0 在饱和度为 0 时是无害的
            saturation = 0f,
            cx = sumX / count,
            cy = sumY / count,
            lightnessHint = NeutralLumaFloor +
                hiLuma.coerceIn(0f, 1f) * (NeutralLumaCeil - NeutralLumaFloor),
        ),
    )
}

/**
 * 家族权重 → 色斑「存在感」（0..1）。不透明度与半径都从它派生。
 *
 * 两个参数承担不同职责：
 * - [PresenceFloor] 保证低占比的颜色**不会消失**。占比 4% 的发光区算出来是 0.44，
 *   不是 0.04 —— 否则「颜色比例对了」和「画面一片死黑」就没区别了。
 * - [PresencePower] 压平权重曲线，让 4% 与 100% 的差距落在「隐约一抹」到「铺满」之间，
 *   而不是「看不见」到「铺满」。
 *
 * 但**半径不吃这个 floor**（见 [_blobRadiusFactor]）—— 占比小的颜色可以是亮的，
 * 却不能是大的，否则又会变成满屏色雾。
 */
private fun _presence(weight: Float): Float {
    if (weight <= 0f) return 0f
    return PresenceFloor +
        (1f - PresenceFloor) * weight.coerceIn(0f, 1f).pow(PresencePower)
}

/**
 * 家族权重 → 半径系数（线性，无 floor）。
 *
 * 占比 4% 的颜色得到 0.32 倍半径（直径约半个屏），占比 100% 得到 1 倍。
 * 这是「比例对不对」在**大小**上的落实：小面积的颜色绝不允许铺满整屏。
 */
private fun _blobRadiusFactor(weight: Float): Float {
    if (weight <= 0f) return 0f
    return RadiusFloor + (1f - RadiusFloor) * weight.coerceIn(0f, 1f)
}

/**
 * 家族权重 → 槽位不透明度。
 *
 * 槽位模板的 [BlobTemplate.alphaScale] 只做轻微递减，**不承担主次** ——
 * 主次全部由权重决定。
 */
private fun _blobAlpha(weight: Float, slot: BlobTemplate): Float =
    (BlobBaseAlpha * slot.alphaScale * _presence(weight)).coerceIn(0f, MaxBlobAlpha)

/** 单个色斑的不透明度上限（权重参数溢出时的保险）。 */
private const val MaxBlobAlpha = 0.42f

/**
 * 家族重心 → 色斑锚点（归一化坐标，长度 2 × [BlobSlotCount]）。
 *
 * 先直接取重心并夹进安全区，再做几轮**斥力松弛**：任意两个锚点距离小于
 * [TargetMinDistance] 就沿连线反向各推一半。
 *
 * 没有这一步，一张「中心色块主导」的封面会让所有家族重心都挤在 0.5/0.5 附近，
 * 五个色斑叠成一坨白斑 —— 恰恰是最难看的结果。
 *
 * 家族数不足时缺席槽位用 [FallbackCenters] 占位；它们的 alpha 是 0，画不出来。
 */
private fun _familyTargets(families: List<ColorFamily>): FloatArray {
    val out = FloatArray(BlobSlotCount * 2)
    for (i in 0 until BlobSlotCount) {
        val family = families.getOrNull(i)
        val fx = family?.cx ?: FallbackCenters[(i % 3) * 2]
        val fy = family?.cy ?: FallbackCenters[(i % 3) * 2 + 1]
        out[i * 2] = fx.coerceIn(TargetMinCoord, TargetMaxCoord)
        out[i * 2 + 1] = fy.coerceIn(TargetMinCoord, TargetMaxCoord)
    }

    // 只有**真实存在**的家族才参与斥力松弛。缺席槽位的 alpha 是 0、画不出来，
    // 让它们参与会把真家族的锚点从「封面上的实际重心」挤走 —— 那是纯粹的精度损失。
    val active = families.size.coerceAtMost(BlobSlotCount)
    repeat(TargetRelaxIterations) {
        for (i in 0 until active) {
            for (j in i + 1 until active) {
                val dx = out[j * 2] - out[i * 2]
                val dy = out[j * 2 + 1] - out[i * 2 + 1]
                val dist = sqrt(dx * dx + dy * dy)
                if (dist >= TargetMinDistance) continue
                if (dist < 1e-4f) {
                    // 完全重合：按索引取一个确定性的黄金角方向推开，结果可复现
                    val angle = i * 2.39996f + j * 1.61803f
                    out[j * 2] = (out[j * 2] + cos(angle) * TargetMinDistance * 0.5f)
                        .coerceIn(TargetMinCoord, TargetMaxCoord)
                    out[j * 2 + 1] = (out[j * 2 + 1] + sin(angle) * TargetMinDistance * 0.5f)
                        .coerceIn(TargetMinCoord, TargetMaxCoord)
                    continue
                }
                val push = (TargetMinDistance - dist) * 0.5f
                val ux = dx / dist * push
                val uy = dy / dist * push
                out[i * 2] = (out[i * 2] - ux).coerceIn(TargetMinCoord, TargetMaxCoord)
                out[i * 2 + 1] = (out[i * 2 + 1] - uy).coerceIn(TargetMinCoord, TargetMaxCoord)
                out[j * 2] = (out[j * 2] + ux).coerceIn(TargetMinCoord, TargetMaxCoord)
                out[j * 2 + 1] = (out[j * 2 + 1] + uy).coerceIn(TargetMinCoord, TargetMaxCoord)
            }
        }
    }
    return out
}

/** 明暗分离蒙版：`hi` 提亮（Screen），`lo` 压暗（Multiply）。 */
private class GridLumaMasks(val hi: ImageBitmap, val lo: ImageBitmap)

/**
 * 把封面的 N×N 网格转成**两张 alpha 蒙版**（拉伸铺满时就是「封面构图的明暗场」）。
 *
 * 为什么不是一张灰度图走 `Overlay`：`Overlay` 在暗底上退化成 `2ab`，底色 0.06 遇上
 * 0.7 只到 0.084 —— 白封面唯一能提亮的那一层实际上等于不存在。拆成两张 alpha 蒙版走
 * `Screen` / `Multiply` 之后，同一份输入能到 0.74，方向感才真正成立。
 *
 * 蒙版本身不用灰度承载"强弱"，而是用 **alpha 通道**：位图 RGB 纯白（hi）或纯黑（lo），
 * 偏离平均亮度的量写进 alpha，绘制时再乘 [GridLumaAlpha]。这样同一个 4×4 小位图
 * 拉大后由双线性插值自然过渡，不会出现格子边。
 *
 * 偏离量以 `[LumaMaskFullScale]` 为满量程：真实封面的单格偏离很少超过 0.5，
 * 用 1.0 做满量程会让所有蒙版都偏弱。
 */
private fun _createGridLumaMasks(grid: List<Color>, dim: Int): GridLumaMasks? {
    if (dim <= 0 || grid.size < dim * dim) return null
    val count = dim * dim
    val mean = _lumaMean(grid)

    val hiPixels = IntArray(count)
    val loPixels = IntArray(count)
    for (i in 0 until count) {
        val dev = _lumaOf(grid[i]) - mean
        val hiAlpha = if (dev > 0f) (dev / LumaMaskFullScale * 255f).roundToInt() else 0
        val loAlpha = if (dev < 0f) (-dev / LumaMaskFullScale * 255f).roundToInt() else 0
        hiPixels[i] = (hiAlpha.coerceIn(0, 255) shl 24) or 0x00FFFFFF
        loPixels[i] = (loAlpha.coerceIn(0, 255) shl 24) or 0x00000000
    }
    val hiBmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
    hiBmp.setPixels(hiPixels, 0, dim, 0, 0, dim, dim)
    val loBmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
    loBmp.setPixels(loPixels, 0, dim, 0, 0, dim, dim)
    return GridLumaMasks(hiBmp.asImageBitmap(), loBmp.asImageBitmap())
}

/**
 * 纯白径向遮罩：中心不透明，向外按非线性曲线衰减到全透明。
 *
 * 停靠点刻意不是线性的——`0.22` 处还有 78%，`0.48` 处剩 34%，之后快速归零。
 * 线性衰减会画出一块「圆盘」，肉眼能看出边界；这种曲线看起来是「一束光」。
 *
 * 颜色不写死在这里：绘制时用 `ColorFilter.tint` 上色，所以一张遮罩服务所有色斑，
 * 且色相漂移 / 切歌补间期间**不需要重建位图**。
 */
private fun _createRadialMask(size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = RadialGradient(
            radius,
            radius,
            radius,
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xC7FFFFFF.toInt(),
                0x57FFFFFF.toInt(),
                0x1AFFFFFF.toInt(),
                0x00FFFFFF,
            ),
            floatArrayOf(0f, 0.22f, 0.48f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawCircle(radius, radius, radius, paint)
    return bitmap.asImageBitmap()
}

/**
 * 确定性灰度白噪声（固定种子，每次安装同一张）。
 *
 * 用 `BlendMode.Overlay` 叠加后，中灰几乎不改变底色，只有亮暗两端被轻微推一下——
 * 效果是把大面积渐变的「色带台阶」打散。这是渐变背景看起来「细腻」而不是
 * 「一圈一圈」的关键。
 */
private fun _createNoise(size: Int, seed: Long): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val random = Random(seed)
    val pixels = IntArray(size * size)
    for (i in pixels.indices) {
        val v = random.nextInt(256)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap
}

/**
 * 低频明度噪声：`cells × cells` 个随机格点做双线性插值。
 *
 * 和白噪声不同，这张图是**平滑**的：相邻像素差很小，放大铺满全屏后表现为
 * 极缓慢的明暗起伏。叠在渐变的 `Overlay` 上，会让人看到的光像是穿过了一层介质，
 * 而不是显示器直接算出来的纯数学函数。
 */
private fun _createLowFrequencyNoise(size: Int, cells: Int): ImageBitmap {
    val random = Random(90210L)
    val grid = Array(cells + 1) { IntArray(cells + 1) {
        LowFrequencyNoiseBase + random.nextInt(LowFrequencyNoiseAmplitude * 2 + 1) -
            LowFrequencyNoiseAmplitude
    } }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val gy = y.toFloat() / size * cells
        val y0 = gy.toInt().coerceIn(0, cells - 1)
        val fy = gy - y0
        for (x in 0 until size) {
            val gx = x.toFloat() / size * cells
            val x0 = gx.toInt().coerceIn(0, cells - 1)
            val fx = gx - x0
            val v00 = grid[y0][x0]
            val v10 = grid[y0][x0 + 1]
            val v01 = grid[y0 + 1][x0]
            val v11 = grid[y0 + 1][x0 + 1]
            val top = v00 + (v10 - v00) * fx
            val bottom = v01 + (v11 - v01) * fx
            val v = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
            pixels[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap()
}

/** 把位图包成可平铺的 [ShaderBrush]。 */
private fun _brushOf(bitmap: Bitmap): ShaderBrush =
    ShaderBrush(
        ImageShader(
            bitmap.asImageBitmap(),
            TileMode.Repeated,
            TileMode.Repeated,
        ),
    )

// ================================================================================================
// 颜色运算
// ================================================================================================

// ── 锚点色相分离 ────────────────────────────────────────────────────────────────
//
// 播放页那套取色（`extractPlayerCoverPalette`）产出的是「全图算术平均 / 最鲜艳 / 最大占比」。
// 它们的定位是给播放页铺背景和网格用的，不是给「三个不同色相的色斑」用的：
//
// - `primary` 是全图平均，几乎是必然地偏灰、低饱和；
// - `secondary` 和 `accent` 经常与 `primary` 同色相（同一张图的主导色系）。
//
// 直接拿这三个当 Aurora 的锚点，色场会退化成一片单色雾。所以这里做两件事：
// 提饱和度下限 + 拉色相间距。**取色算法仍然是播放页那一套**，这一步只是适配。

/** 锚点饱和度下限：低于此值抬高（全图平均色常常接近灰）。 */
private const val MinAnchorSaturation = 0.18f

/** 两个锚点之间的最小色相距离（度）。 */
private const val MinAnchorHueGap = 25f

/** 色相不够开时依次使用的旋转偏移（度）。 */
private val AnchorHueFallbacks = floatArrayOf(34f, -30f, 66f)

/**
 * 把取色结果拉开成三个可区分的色相。
 *
 * 第一个锚点保留原色相；后两个若与已确定的锚点色相距离不足 [MinAnchorHueGap]，
 * 就按 [AnchorHueFallbacks] 旋转派生（保留原饱和度与亮度，只换色相）。
 */
private fun _ensureAnchorSpread(a: Color, b: Color, c: Color): Array<Color> {
    val hslA = _hslOf(a)
    val hslB = _hslOf(b)
    val hslC = _hslOf(c)

    val hueA = hslA[0]
    val hueB = if (_hueGap(hueA, hslB[0]) >= MinAnchorHueGap) {
        hslB[0]
    } else {
        hueA + AnchorHueFallbacks[0]
    }
    val hueC = if (_hueGap(hueA, hslC[0]) >= MinAnchorHueGap &&
        _hueGap(hueB, hslC[0]) >= MinAnchorHueGap
    ) {
        hslC[0]
    } else {
        hueA + AnchorHueFallbacks[1]
    }

    return arrayOf(
        _hslToColor(hueA, max(hslA[1], MinAnchorSaturation), hslA[2]),
        _hslToColor(hueB, max(hslB[1], MinAnchorSaturation), hslB[2]),
        _hslToColor(hueC, max(hslC[1], MinAnchorSaturation), hslC[2]),
    )
}

/** [Color] → HSL 三元组（h 0..360, s 0..1, l 0..1）。 */
private fun _hslOf(color: Color): FloatArray {
    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(
        (color.red * 255f).roundToInt().coerceIn(0, 255),
        (color.green * 255f).roundToInt().coerceIn(0, 255),
        (color.blue * 255f).roundToInt().coerceIn(0, 255),
        hsl,
    )
    return hsl
}

/** 色相环形距离（度），结果 0..180。 */
private fun _hueGap(a: Float, b: Float): Float {
    val raw = abs(a - b) % 360f
    return if (raw > 180f) 360f - raw else raw
}

/**
 * 底色：跟随封面整体明度的灰，掺主色。
 *
 * @param baseLuma 底色亮度（[BaseFloor] + lumaGain × [BaseSpan]）。
 *                 纯黑封面给 0.030（和旧版完全一致），纯白封面给 0.33。
 *
 * 不用纯黑：纯黑会让色斑边缘显得脏（对比过强，渐变的收尾暴露出来）。
 * 也不用 LunaBeat 那种「压到 luma ≤ 0.36 的取色」：那样整页都在封面色相里，
 * 一眼就能对上封面。这里底色只掺主色，色相主体交给上面的极光层。
 *
 * 主色掺量随 [baseLuma] 增长（[BaseTintBoost]）：亮底色如果还是 3.2% 的色偏，
 * 会变成一片没有色温的死灰。
 */
private fun _baseColorFor(primary: Color, baseLuma: Float): Color {
    val tint = BaseTint + baseLuma * BaseTintBoost
    return Color(
        red = baseLuma + primary.red * tint,
        green = baseLuma + BaseChillGreen + primary.green * tint,
        blue = baseLuma + BaseChillBlue + primary.blue * tint,
        alpha = 1f,
    )
}

/**
 * 锚点色 → 斑块色：保留色相骨架，按斑位重设饱和度与亮度。
 *
 * 这一步是「明度台阶」的实现点。锚点保留的是封面真实的色相，
 * 而亮度和饱和度被换成设计值——色相来自内容，明度结构来自设计。
 *
 * **饱和度不设下限**：无彩家族传进来的饱和度是 0，必须原样传成 0，
 * 否则色相（未定义时是 0 度 = 红色）会把它变成粉色。彩色封面的饱和度来自家族
 * （≥ 0.09，因为过了色度门限），乘上最大 1.0 的系数本来就远高于任何下限。
 *
 * @param hueDelta 色相漂移（度），慢周期正弦，肉眼不可见但画面不死。
 */
private fun _retint(
    source: Color,
    hueDelta: Float,
    saturationScale: Float,
    targetLightness: Float,
): Color {
    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(
        (source.red * 255f).roundToInt().coerceIn(0, 255),
        (source.green * 255f).roundToInt().coerceIn(0, 255),
        (source.blue * 255f).roundToInt().coerceIn(0, 255),
        hsl,
    )
    val hue = ((hsl[0] + hueDelta) % 360f + 360f) % 360f
    val saturation = (hsl[1] * saturationScale).coerceIn(0f, MaxSlotSaturation)
    return _hslToColor(hue, saturation, targetLightness.coerceIn(0.05f, 0.95f))
}

/** HSL(0..360, 0..1, 0..1) → 不透明 [Color]。hue 超出 0..360 会自动归一化。 */
private fun _hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val chroma = (1f - abs(lightness * 2f - 1f)) * saturation
    val second = (1f - abs(((h / 60f) % 2f) - 1f)) * chroma
    val base = lightness - 0.5f * chroma
    val r: Float
    val g: Float
    val b: Float
    when (h.toInt() / 60) {
        0 -> { r = chroma; g = second; b = 0f }
        1 -> { r = second; g = chroma; b = 0f }
        2 -> { r = 0f; g = chroma; b = second }
        3 -> { r = 0f; g = second; b = chroma }
        4 -> { r = second; g = 0f; b = chroma }
        else -> { r = chroma; g = 0f; b = second }
    }
    return Color(
        red = (r + base).coerceIn(0f, 1f),
        green = (g + base).coerceIn(0f, 1f),
        blue = (b + base).coerceIn(0f, 1f),
        alpha = 1f,
    )
}
