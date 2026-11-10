package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **国内四平台的内置下载接口已删除**，这条约束要能被测试挡住。
 *
 * 背景：四平台的下载统一由落雪插件提供（`MusicRepository.resolveDownloadUrl` 是唯一入口），
 * `builtinDownloadUrl` 里再长出这四家的分支就等于悄悄恢复了「插件失败还能量内链接」的旧路 ——
 * 那条路上拿到的经常是加密包（酷我 `mgg`、QQ 跨源 `.mflac`），最后变成一个打不开的文件。
 * 所以四个平台在这里必须**直接抛错**，且错误文案要指向插件。
 */
class BuiltinDownloadRouteTest {

    private fun song(platform: MusicPlatform) = Song(
        id = when (platform) {
            MusicPlatform.qq -> "qq:003abc"
            MusicPlatform.kugou -> "kugou:ABC123"
            else -> "123456"
        },
        title = "t",
        artist = "a",
        cover = "",
        album = null,
        duration = 200,
        platform = platform,
        qualities = listOf(Quality.flac, Quality.k320k),
    )

    @Test
    fun four_domestic_platforms_have_no_builtin_download() = runBlocking {
        val four = listOf(
            MusicPlatform.netease,
            MusicPlatform.qq,
            MusicPlatform.kugou,
            MusicPlatform.kuwo,
        )
        for (platform in four) {
            val error = runCatching {
                builtinDownloadUrl(song(platform), Quality.flac)
            }.exceptionOrNull()
            assertTrue(
                "$platform 应当没有内置下载入口，实际返回了直链或别的异常：$error",
                error is IllegalStateException,
            )
            val message = error?.message.orEmpty()
            assertTrue(
                "$platform 的错误文案应指向插件：$message",
                message.contains("落雪插件"),
            )
        }
    }

    @Test
    fun apple_and_local_still_have_their_own_refusals() = runBlocking {
        // 这两家不是"接口被删"，而是本来就不走这条通路 —— 别让文案混成同一句
        val apple = runCatching {
            builtinDownloadUrl(song(MusicPlatform.apple), Quality.alac)
        }.exceptionOrNull()
        assertEquals("Apple Music 下载走 AppleDownloader", apple?.message)

        val local = runCatching {
            builtinDownloadUrl(song(MusicPlatform.local), Quality.flac)
        }.exceptionOrNull()
        assertEquals("本地文件无需下载", local?.message)

        // KKBOX 也一样：DRM 必须由它自己的下载器解，不许再借别家直链
        val kkbox = runCatching {
            builtinDownloadUrl(song(MusicPlatform.kkbox), Quality.flac)
        }.exceptionOrNull()
        assertEquals("KKBOX 下载走 KkboxDownloader（官方票据 + DRM 解密）", kkbox?.message)
    }

    @Test
    fun four_domestic_platforms_have_no_builtin_play_either() = runBlocking {
        // 播放同一条约束：四平台内置直链已删，没插件就直接失败（没有降级、没有兜底）
        for (platform in listOf(
            MusicPlatform.netease,
            MusicPlatform.qq,
            MusicPlatform.kugou,
            MusicPlatform.kuwo,
        )) {
            val error = runCatching {
                builtinPlayUrl(song(platform), Quality.flac)
            }.exceptionOrNull()
            assertTrue(
                "$platform 应当没有内置播放入口，实际：$error",
                error?.message?.contains("落雪插件") == true,
            )
        }
        // 不带档位的那条（旧"自动"分支）也不能偷偷出链
        val auto = runCatching { builtinPlayUrl(song(MusicPlatform.kuwo), null) }.exceptionOrNull()
        assertTrue(auto?.message?.contains("落雪插件") == true)
    }

    @Test
    fun qobuz_still_routes_builtin_and_asks_for_token_first() = runBlocking {
        // Qobuz 不在落雪覆盖范围，仍走内置；测试环境没有 token → 命中"先配置 token"那句，
        // 证明它没有被"四平台已移除"那条分支吞掉。
        val error = runCatching {
            builtinDownloadUrl(song(MusicPlatform.qobuz), Quality.flac)
        }.exceptionOrNull()
        assertEquals("请先在菜单里配置 Qobuz Token", error?.message)
    }

    @Test
    fun kkbox_play_fails_loudly_without_a_session() = runBlocking {
        // KKBOX 只走官方票据接口：没有会话必须直接报错，**绝不**回落到别家音源。
        // 测试环境里 kkboxSessionProvider 的默认实现就是 `{ null }`。
        val error = runCatching {
            com.annieplayer.annie_music.kt.data.sources.kkbox.fetchKkboxPlayUrl(
                com.annieplayer.annie_music.kt.data.model.Song(
                    id = "kkbox:test",
                    title = "test",
                    artist = "test",
                    cover = "",
                    duration = 0,
                    platform = com.annieplayer.annie_music.kt.data.model.MusicPlatform.kkbox,
                ),
            )
        }.exceptionOrNull()
        assertEquals("KKBOX 需要先登录账号才能播放", error?.message)
    }
}
