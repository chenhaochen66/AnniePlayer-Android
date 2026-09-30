package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.util.extractPaletteFromCover
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import kotlinx.coroutines.CancellationException

// ================================================================================================
// 详情页共享：封面动态取色背景（取色逻辑与播放页一致）
// ================================================================================================

/** 背景模糊半径（与播放页 `PlayerBackdropBlur` 同为 60dp，Flutter `ImageFilter.blur(60)`）。 */
private val BackdropBlurRadius = 60.dp

/** 模糊封面图不透明度（压暗到 ~55%，保证上层正文对比度）。 */
private const val BackdropImageAlpha = 0.72f

/** 取色染色渐变：顶部主色不透明度。 */
private const val BackdropTintTopAlpha = 0.40f

/** 取色染色渐变：底部页面底色不透明度（近乎不透明，让列表区域始终可读）。 */
/**
 * 取色渐变**底部**的兜底权重。
 *
 * 关键教训：这个值曾经是 0.92 / 0.70，且底部用的是 `palette.background` —— 而取色器只改
 * primary / secondary，**background 永远是 fallback 的 `#0A0A1A`**，于是页面下半部分被刷成
 * 一块平的死深蓝，「背景色」整体看不到（真机反馈）。现在压到 0.30：取色（封面颜色）主导，
 * 底部只做轻微压暗，保证正文对比度。
 */
private const val BackdropTintBottomAlpha = 0.30f

/**
 * 可读性压暗：顶部黑 42% → 透明。
 *
 * 模糊封面最亮的是顶部（`ContentScale.Crop` 放大后顶部占比最大），仅靠染色渐变在浅色封面上
 * 无法保证白字对比度（实测最坏可到 ~2:1），故额外叠一层顶部向下的暗化膜。
 */
private const val BackdropScrimTopAlpha = 0.30f

/**
 * 全局可读性暗纱。
 *
 * 玻璃面板已降到 5% 白（≈全透明），正文等于直接压在「模糊封面 + 取色渐变」上，因此需要一层
 * 均匀暗纱把整体压暗，保证白字 / 弱化文字仍有对比度；同时它不会把取色「洗掉」（色彩仍然透出来）。
 */
private const val BackdropVeilAlpha = 0.26f

/** 顶部轻高光高度占比（玻璃受光面，避免大色块渐变显得死板）。 */
private const val BackdropTopHighlightFraction = 0.32f

/**
 * 读取封面动态色板（与播放页取色逻辑完全一致）。
 *
 * - 取色走 [extractPaletteFromCover]（`core/util/ColorExtractor.kt`，内部自带进程级缓存）；
 * - 成功：返回 `ColorPalette.fallback.copyWith(primary = …, secondary = …)`；
 *   失败（URL 为空 / 解码失败）：保持 [ColorPalette.fallback]；
 * - 组合内再用 `remember` 维护一份「url → palette」内存缓存，同一 url 在本页面存活期间
 *   只触发一次取色调用（页面关闭后缓存随组合销毁，但 ColorExtractor 的进程级缓存仍在，
 *   再次进入不会重复下载 / 解码）。
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
            extractPaletteFromCover(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // 仅 primary / secondary 是提取值；background / surface 沿用 fallback
        // （与 ColorExtractor 的输出语义一致，避免覆盖页面的深色底）
        val resolved = if (extracted != null) {
            ColorPalette.fallback.copyWith(
                primary = extracted.primary,
                secondary = extracted.secondary,
            )
        } else {
            ColorPalette.fallback
        }
        paletteCache[url] = resolved
        palette = resolved
    }
    return palette
}

/**
 * 封面取色氛围背景（详情页底层，自下而上四层）。
 *
 * ① [AppColors.background] 纯色底；
 * ② 封面图：`ContentScale.Crop` 放大裁剪 + `Modifier.blur(60dp)` 模糊**自身内容**
 *    （复用播放页的既有写法；API < 31 时 blur 为 no-op、自动降级为原图，不崩），
 *    透明度 [BackdropImageAlpha]；
 * ③ 取色染色：`primary@30% → background@92%` 的垂直渐变（顶部着色、底部收束到页面底色）；
 * ③-a 顶部可读性压暗（浅色封面下保证白字对比度，见 [BackdropScrimTopAlpha]）；
 * ④ 顶部轻高光（玻璃受光面）。
 *
 * 整体观感：**液态玻璃 + 封面取色氛围 + 毛玻璃列表容器**；正文始终压在深色收束区上，保证可读。
 *
 * @param coverUrl 封面地址（null / 空串时只有纯色底与染色，不显示封面层）。
 * @param palette 由 [rememberCoverPalette] 取得（或页面级色板），必须是 `fallback` 语义的
 *                完整色板（background / surface 有效）。
 */
@Composable
fun CoverPaletteBackdrop(
    sheen: Boolean = true,
    coverUrl: String?,
    palette: ColorPalette,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(AppColors.background)) {
        // ② 封面模糊层（blur 只糊自身内容；API<31 自动降级为原图）
        if (!coverUrl.isNullOrEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(BackdropBlurRadius)
                    .alpha(BackdropImageAlpha),
            )
        }
        // ③ 取色染色层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.primary.copy(alpha = BackdropTintTopAlpha),
                            palette.background.copy(alpha = BackdropTintBottomAlpha),
                        ),
                    ),
                ),
        )
        // ③-a 顶部压暗（可读性兜底）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = BackdropScrimTopAlpha),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        // ④ 顶部轻高光
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(BackdropTopHighlightFraction)
                // ④ 可读性暗纱：面板已近乎全透明，这层负责把正文的对比度垫回来（取色仍是主角）
                .background(Color.Black.copy(alpha = BackdropVeilAlpha))
                // ⑤ 背景层的高光（可选）：详情页要液态玻璃质感 → sheen = true；
                //    搜索页 / 歌手详情只要「正在播放封面」的纯色氛围 → sheen = false（两道白光全透明）。
                .background(
                    Brush.verticalGradient(
                        0f to AppColors.glassBorder.copy(alpha = if (sheen) 0.13f else 0f),
                        0.5f to Color.Transparent,
                        1f to AppColors.glass.copy(alpha = if (sheen) 0.08f else 0f),
                    ),
                ),
        )
    }
}

// 注：本文件曾提供 `SnapshotPaletteAndRestore`（详情页把色板临时写入全局 `ColorViewModel`）。
// 已移除，原因：详情页只用**本地** palette 渲染（0 处读 `Annie.palette`），全局色板应由
// `AppShell` 按「当前播放歌曲」驱动；旧实现把进页时的色板快照在退出时写回，会在用户于详情页
// 里播放新歌之后把**陈旧色板**覆盖上去，导致播放页的取色（背景 / 歌词指示条）失效。
