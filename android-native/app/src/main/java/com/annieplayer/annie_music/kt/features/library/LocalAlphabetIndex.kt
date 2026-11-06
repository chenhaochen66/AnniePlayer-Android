package com.annieplayer.annie_music.kt.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.data.local.LOCAL_BUCKET_COUNT
import com.annieplayer.annie_music.kt.data.local.localBucketLetter
import com.annieplayer.annie_music.kt.data.local.localBucketRank
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import java.util.Locale

/**
 * 右侧字母索引条（A–Z + `#`）：点击或上下滑动跳到该首字母的第一项。
 *
 * 位置（用户要求）：
 * - 字母条**顶部对齐"第一首歌"**，跟着它上下移动：没上滑时第一首还在页面中部，
 *   条子就从那里往下排（下面那截被底部导航栏挡住，允许显示不全）；
 * - 上滑到第一首顶住搜索栏位置时，条子整体移到列表顶部，此时 27 个槽正好铺到
 *   **底部导航栏上方**；
 * - 槽高由调用方按可用高度算好传进来（[slotHeight]），所以字母够大、点得准。
 *
 * 交互：
 * - 拖动时字母列淡出，当前字母变成一个主色圆点（"滑条变成小圆点"），松手恢复；
 * - 常驻的白色小圆点显示滚动位置，圆心与字母槽中心**重合**（画在字母下面，字不被挡）。
 */
@Composable
internal fun LocalAlphabetIndex(
    buckets: Map<Char, Int>,
    onJump: (Int) -> Unit,
    slotHeight: Dp = 18.dp,
    modifier: Modifier = Modifier,
    /**
     * 白色小圆点所在的**槽位**（0..26，可带小数；null = 不显示圆点）。
     *
     * 用槽位而不是 0..1 的滚动比例，是因为"圆点滑到哪个字母"必须和
     * "那个字母的第一项正好顶到搜索条"严格对齐 —— 桶大小不均匀，
     * 线性比例换算会错位。换算见 [alphabetDotSlot]。
     */
    dotSlot: Float? = null,
    /**
     * 列表是降序时传 true：字母组在屏幕上是「从下往上」出现的，
     * 索引条也跟着倒着画（`#` 在最上、A 在最下），否则点击位置对不上。
     */
    reversed: Boolean = false,
) {
    if (buckets.isEmpty()) return
    val letters = remember(reversed) { if (reversed) ALPHABET.reversed() else ALPHABET }
    var active by remember { mutableStateOf<Char?>(null) }
    val slotHeightPx = with(LocalDensity.current) { slotHeight.toPx() }
    val totalHeight = slotHeight * letters.size
    // 字号跟着槽高走（槽高 ≈ 22dp → 约 12sp），比最初的固定 10sp 大一圈又不臃肿
    val letterSize = (slotHeight.value * 0.56f).coerceIn(10f, 14f).sp
    val dotSize = (slotHeight.value * 0.56f).coerceIn(10f, 14f).dp
    val pill = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .width(LocalAlphabetBarWidth)
            .height(totalHeight)
            .background(AppColors.whiteA03, pill)
            .border(1.dp, AppColors.whiteA05, pill),
    ) {
        /** 手指 y → 字母槽位（越界收敛到首尾） */
        fun jumpAt(y: Float) {
            val slot = (y / slotHeightPx).toInt().coerceIn(0, letters.lastIndex)
            val letter = letters[slot]
            active = letter
            buckets[letter]?.let(onJump)
        }

        // ① 滚动位置小圆点：圆心与字母槽中心**完全重合**，画在字母下面（字母盖在上面，
        //    所以既"重合"又不挡字）；常驻不消失，取代原来的细长滚动条。
        if (dotSlot != null) {
            val p = dotSlot.coerceIn(0f, letters.lastIndex.toFloat())
            // 降序时字母是倒着排的，槽位也要翻过来
            val visual = if (reversed) letters.lastIndex - p else p
            val y = slotHeight * visual + (slotHeight - dotSize) / 2
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = y)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(AppColors.white.copy(alpha = 0.5f)),
            )
        }

        // ② 字母列（拖动时整体淡出，只留圆点指示器）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(buckets) {
                    detectVerticalDragGestures(
                        onDragStart = { offset -> jumpAt(offset.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { change, _ ->
                        jumpAt(change.position.y)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (letter in letters) {
                val hasItems = buckets.containsKey(letter)
                Box(
                    modifier = Modifier.height(slotHeight).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = letter.toString(),
                        // 拖动中把字母列藏起来（"滑条变成小圆点"）
                        color = when {
                            active != null -> AppColors.whiteA06
                            hasItems -> AppColors.white70
                            else -> AppColors.white12
                        },
                        fontSize = letterSize,
                        fontWeight = if (hasItems) AppFontWeight.semiBold else FontWeight.Normal,
                    )
                }
            }
        }

        // ③ 拖动指示器：主色圆点 + 字母，贴在当前槽位
        active?.let { letter ->
            val index = letters.indexOf(letter)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = slotHeight * index)
                    .size(slotHeight)
                    .clip(CircleShape)
                    .background(AppColors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter.toString(),
                    color = AppColors.background,
                    fontSize = letterSize,
                    fontWeight = AppFontWeight.bold,
                )
            }
        }
    }
}

/** 索引条宽度；列表行要按这个值让出右侧空间，所以是 internal */
internal val LocalAlphabetBarWidth = 22.dp

/** A–Z + `#`（固定 27 项，保证索引条槽位高度稳定） */
private val ALPHABET: List<Char> = (('A'..'Z').toList() + '#')

/**
 * 计算「首字母 → 该字母第一项的索引」。
 *
 * **前提**：传入的列表已经按字母排好序（[localAlphabetComparator]，先桶位再 ICU 中文排序），
 * 否则跳转位置没有意义。分桶规则见 [localBucketRank]：中文按拼音、假名按罗马字，
 * 数字与符号才归 `#`。
 */
internal fun <T> alphabetBuckets(items: List<T>, key: (T) -> String): Map<Char, Int> {
    if (items.isEmpty()) return emptyMap()
    val map = LinkedHashMap<Char, Int>(LOCAL_BUCKET_COUNT)
    items.forEachIndexed { index, item ->
        val bucket = alphabetBucketOf(key(item))
        if (!map.containsKey(bucket)) map[bucket] = index
    }
    return map
}

/** 文本 → 索引桶字母（A–Z 或 `#`） */
internal fun alphabetBucketOf(text: String): Char = localBucketLetter(localBucketRank(text))

/**
 * 把「字母 → 首项索引」摊平成 27 个槽位的首项索引表（没有内容的槽 = -1）。
 * 圆点定位只查这张表，不用再按字母反查 map。
 *
 * @param songCount 列表项数（降序镜像时要用）
 * @param reversed 列表是**降序**（字母组从下往上出现）时传 true：
 *                 表里存的是**镜像空间**的首项索引，这样 [alphabetDotSlot] 不用分方向，
 *                 画的时候再把槽位翻回来（见 [alphabetDotSlotFor]）。
 */
internal fun alphabetSlotStarts(
    buckets: Map<Char, Int>,
    songCount: Int,
    reversed: Boolean,
): IntArray {
    if (buckets.isEmpty()) return IntArray(0)
    val arr = IntArray(LOCAL_BUCKET_COUNT) { -1 }
    if (!reversed) {
        for ((letter, index) in buckets) {
            val slot = if (letter == '#') LOCAL_BUCKET_COUNT - 1 else letter - 'A'
            if (slot in arr.indices) arr[slot] = index
        }
        return arr
    }
    // 降序：按视觉顺序（index 升序）排列各桶，再换成镜像空间的首项索引
    val visual = buckets.entries
        .map { (letter, index) ->
            (if (letter == '#') LOCAL_BUCKET_COUNT - 1 else letter - 'A') to index
        }
        .sortedBy { it.second }
    visual.forEachIndexed { k, (slot, _) ->
        // 镜像首项 = N − 下一个桶的视觉起点；最后一个桶（视觉最底）从 0 开始
        arr[slot] = if (k == visual.lastIndex) 0 else songCount - visual[k + 1].second
    }
    return arr
}

/**
 * 「顶部那一首的连续位置」→ 圆点所在槽位（0..26，可带小数）。
 *
 * 规则（用户定的）：**圆点滑到哪个字母，那个字母区间的第一首就正好顶在搜索条位置**。
 * 所以：桶的第一首在顶部 → 圆点正好落在该字母槽；桶内继续滚 → 圆点线性滑向下一个
 * 有内容的字母槽，滚到下一桶第一首时正好抵达那个槽。桶大小不均匀也不会错位。
 *
 * @param songTop 顶部歌曲的位置（整数 = 第几首正好在顶部，小数 = 它已经滚过去多少）
 */
internal fun alphabetDotSlot(slotStarts: IntArray, songTop: Float, songCount: Int): Float {
    if (slotStarts.isEmpty() || songCount <= 0) return 0f
    val t = songTop.coerceIn(0f, (songCount - 1).toFloat())
    var slot = -1
    for (i in slotStarts.indices) {
        val start = slotStarts[i]
        if (start < 0) continue
        if (start <= t) slot = i else break
    }
    if (slot < 0) return 0f
    val start = slotStarts[slot]
    var nextSlot = -1
    for (i in slot + 1 until slotStarts.size) {
        if (slotStarts[i] >= 0) {
            nextSlot = i
            break
        }
    }
    val end = if (nextSlot >= 0) slotStarts[nextSlot] else songCount
    val span = (end - start).coerceAtLeast(1)
    val progress = ((t - start) / span).coerceIn(0f, 1f)
    return if (nextSlot >= 0) {
        slot + progress * (nextSlot - slot)
    } else {
        // 最后一桶：圆点停在这个字母上（它的第一首已经在顶部了）
        slot.toFloat()
    }
}

/**
 * 屏幕滚动位置 → 圆点槽位（0 = A，26 = `#`，与字母条的槽位顺序一致）。
 * 降序列表把位置镜像一下再算，最后把槽位翻回来。
 */
internal fun alphabetDotSlotFor(
    slotStarts: IntArray,
    songTop: Float,
    songCount: Int,
    reversed: Boolean,
): Float {
    if (!reversed) return alphabetDotSlot(slotStarts, songTop, songCount)
    val mirrored = alphabetDotSlot(slotStarts, songCount - 1 - songTop, songCount)
    return LOCAL_BUCKET_COUNT - 1 - mirrored
}

/** 视觉上最后一个字母组的槽位（滚到底时圆点钉在这里） */
internal fun alphabetLastBucketSlot(slotStarts: IntArray, reversed: Boolean): Int {
    if (slotStarts.isEmpty()) return -1
    return if (reversed) slotStarts.indexOfFirst { it >= 0 } else slotStarts.indexOfLast { it >= 0 }
}

/** 排序名里是否属于「按字母」的那几种（决定要不要显示索引条） */
internal fun isAlphabeticSortLabel(label: String): Boolean = when (label.lowercase(Locale.ROOT)) {
    "标题", "名字", "歌手", "艺术家", "专辑", "目录", "目录名" -> true
    else -> false
}
