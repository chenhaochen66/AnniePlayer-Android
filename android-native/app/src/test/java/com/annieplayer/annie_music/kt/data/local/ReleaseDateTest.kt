package com.annieplayer.annie_music.kt.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 发行日期规范化 + 专辑日期提取（本地曲库的专辑卡、专辑列表排序都吃这一套）。
 *
 * 这里的断言对应两类真实数据：本项目的打标写 `yyyy-MM-dd`，第三方工具/下载站还会写
 * `2023`、`2023/5/12`、`2023-05-12T00:00:00`。**只有年就必须只给年** ——
 * 补一个 `-01-01` 出来是编造数据，会让「排序」和「显示」都变得不可信。
 */
class ReleaseDateTest {

    private fun track(year: String?) = LocalTrackMeta(path = "/x.flac", year = year)

    // ── 规范化 ──

    @Test
    fun `完整日期原样`() = assertEquals("2023-05-12", normalizeReleaseDate("2023-05-12"))

    @Test
    fun `月日不补零也补成两位`() = assertEquals("2023-05-02", normalizeReleaseDate("2023-5-2"))

    @Test
    fun `斜杠与点都认`() {
        assertEquals("2023-05-12", normalizeReleaseDate("2023/05/12"))
        assertEquals("2023-05-12", normalizeReleaseDate("2023.05.12"))
    }

    @Test
    fun `带时间的只取日期`() = assertEquals("2023-05-12", normalizeReleaseDate("2023-05-12T00:00:00"))

    @Test
    fun `带时分秒空格分隔`() = assertEquals("2023-05-12", normalizeReleaseDate("2023-05-12 08:30:00"))

    @Test
    fun `只有年就只给年`() = assertEquals("2023", normalizeReleaseDate("2023"))

    @Test
    fun `只有年月`() = assertEquals("2023-05", normalizeReleaseDate("2023-05"))

    @Test
    fun `首尾空白`() = assertEquals("2023-05-12", normalizeReleaseDate("  2023-05-12  "))

    @Test
    fun `非法月份只保留年`() = assertEquals("2023", normalizeReleaseDate("2023-13-01"))

    @Test
    fun `非法日期只保留年月`() = assertEquals("2023-05", normalizeReleaseDate("2023-05-45"))

    @Test
    fun `认不出的返回 null`() {
        assertNull(normalizeReleaseDate(""))
        assertNull(normalizeReleaseDate("   "))
        assertNull(normalizeReleaseDate(null))
        assertNull(normalizeReleaseDate("未知"))
        assertNull(normalizeReleaseDate("12-05-2023"))
    }

    // ── 专辑级 ──

    @Test
    fun `取第一条能用的日期`() {
        val tracks = listOf(track(null), track(""), track("2021-03-04"), track("2019-01-01"))
        assertEquals("2021-03-04", localAlbumDateOf(tracks))
    }

    @Test
    fun `全都没有日期返回 null`() {
        assertNull(localAlbumDateOf(listOf(track(null), track(" "))))
        assertNull(localAlbumDateOf(emptyList()))
    }

    @Test
    fun `第一首是垃圾值会跳到下一首`() {
        val tracks = listOf(track("the 90s"), track("1998"))
        assertEquals("1998", localAlbumDateOf(tracks))
    }
}
