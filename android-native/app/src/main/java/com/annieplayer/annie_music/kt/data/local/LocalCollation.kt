package com.annieplayer.annie_music.kt.data.local

import android.icu.text.Collator
import android.icu.text.Transliterator
import android.os.Build
import java.util.Locale

/**
 * 本地曲库的**文字排序 / 字母分桶**。
 *
 * 之前的实现是 `title.lowercase()` 直接比 Unicode 码位，后果：
 * - 中文按码位排（不是拼音），一整片中文堆在英文后面；
 * - 分桶只看首字符是不是 A–Z，中文 / 日文 / 西里尔 / 希腊全部塞进 `#`。
 *
 * 现在分两层：
 * 1. **排序**：ICU 中文排序规则（[Collator] + `zh_CN`，PRIMARY 强度 = 忽略大小写与声调），
 *    中文就是拼音序；
 * 2. **分桶**：首字符先罗马化（`Any-Latin; Latin-ASCII`），中文 → 拼音、日文假名 → 罗马字、
 *    西里尔 / 希腊 → 拉丁转写，取第一个拉丁字母当桶；数字与真正的符号才落到 `#`。
 *
 * 两层都先比桶位再比 [Collator]，所以同一个字母桶里的项**一定连续** ——
 * 这是字母索引条能跳准的前提（英文 A 和拼音 A 挨在一起，而不是一个在头一个在尾）。
 */

/** 桶位总数：A..Z（0..25）+ `#`（26） */
const val LOCAL_BUCKET_COUNT = 27

/** `#` 桶的桶位（数字 / 符号 / 无法罗马化） */
const val LOCAL_HASH_RANK = 26

/** 找首字母时最多往后看几个字符（跳过 `《`、`「`、`!` 这类前缀符号，又不至于整句乱跳） */
private const val PREFIX_SCAN_LIMIT = 8

/**
 * 罗马化器：`Any-Latin` 把汉字转拼音、假名转罗马字、西里尔/希腊转拉丁，
 * `Latin-ASCII` 去掉声调符号（`ài` → `ai`）。
 *
 * `android.icu.text.Transliterator` 从 API 29 才有；低版本没有就用 [Collator] 排序、
 * 分桶退化成「只认拉丁字母」，功能降级但不崩。
 */
private val romanizer: Transliterator? by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
    } else {
        null
    }
}

/** 单字符 → 桶位的缓存（索引条每帧都要算，罗马化不便宜） */
private val charRankCache = HashMap<Char, Int>(512)

/** 首字符 → 桶位（0..25 = A..Z，[LOCAL_HASH_RANK] = `#`） */
private fun rankOfChar(c: Char): Int = when {
    c in 'A'..'Z' -> c - 'A'
    c in 'a'..'z' -> c - 'a'
    c.isDigit() -> LOCAL_HASH_RANK
    c.isHighSurrogate() || c.isLowSurrogate() -> LOCAL_HASH_RANK
    else -> charRankCache.getOrPut(c) { romanizedRank(c) }
}

/** 罗马化一个字符，取其中第一个拉丁字母作为桶位 */
private fun romanizedRank(c: Char): Int {
    val t = romanizer ?: return LOCAL_HASH_RANK
    val latin = runCatching { t.transliterate(c.toString()) }.getOrNull() ?: return LOCAL_HASH_RANK
    val first = latin.firstOrNull { it in 'A'..'Z' || it in 'a'..'z' } ?: return LOCAL_HASH_RANK
    return first.uppercaseChar() - 'A'
}

/**
 * 取「第一个有归属的字符」：跳过空白与前缀符号（`《`、`「`、`(`、`!` …）；
 * 碰到数字 / 表情代理对立刻定案（它们都属于 `#`，不再往后找）。
 */
private fun firstMeaningfulChar(text: String): Char? {
    val limit = minOf(text.length, PREFIX_SCAN_LIMIT)
    for (i in 0 until limit) {
        val c = text[i]
        if (c.isWhitespace()) continue
        if (c.isDigit() || c.isHighSurrogate()) return c
        if (rankOfChar(c) != LOCAL_HASH_RANK) return c
    }
    return null
}

/**
 * 文本 → 桶位。
 *
 * 规则：取 [firstMeaningfulChar] 的字母归属；数字开头的专辑/歌名留在 `#` 里，
 * 不往前面的字母里挤。
 */
fun localBucketRank(text: String): Int {
    val c = firstMeaningfulChar(text) ?: return LOCAL_HASH_RANK
    return rankOfChar(c)
}

/**
 * 同桶内的第二级排序：**拉丁字母排在汉字/假名/西里尔前面**（用户要求）。
 * 返回 0 = 拉丁，1 = 其他文字。
 */
fun localLatinRank(text: String): Int {
    val c = firstMeaningfulChar(text) ?: return 1
    return if (c in 'A'..'Z' || c in 'a'..'z') 0 else 1
}

/** 桶位 → 索引条上的字母（26 → `#`） */
fun localBucketLetter(rank: Int): Char =
    if (rank in 0..25) 'A' + rank else '#'

/**
 * 「按字母」排序的比较器：**桶位 → 拉丁优先 → ICU 中文排序**。
 *
 * 桶位优先是刻意的：`Collator` 本身把汉字排在拉丁字母之后，
 * 直接用它排会出现「英文 A 在最前、拼音 A 在最后」，字母桶就不连续、索引条跳不准。
 * 桶位相同（比如英文 "Apple" 和拼音 "爱"）时再让拉丁字母在前，最后才交给 ICU 比拼音。
 */
fun localAlphabetComparator(): Comparator<String> {
    val collator = runCatching {
        Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
            strength = Collator.PRIMARY
        }
    }.getOrNull()
    return Comparator { a, b ->
        val ra = localBucketRank(a)
        val rb = localBucketRank(b)
        if (ra != rb) {
            ra - rb
        } else {
            val la = localLatinRank(a)
            val lb = localLatinRank(b)
            if (la != lb) {
                la - lb
            } else {
                val c = collator?.compare(a, b) ?: 0
                if (c != 0) c else a.compareTo(b, ignoreCase = true)
            }
        }
    }
}
