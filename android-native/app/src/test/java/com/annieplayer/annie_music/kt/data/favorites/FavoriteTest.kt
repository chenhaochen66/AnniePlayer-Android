package com.annieplayer.annie_music.kt.data.favorites

import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.albumGroupKey
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏库的键构造 / 失联自愈 / 序列化。
 *
 * 这些都是纯函数，不碰 Android、不碰磁盘 —— [FavoriteStore] 自己需要 `filesDir`，
 * 所以不在单测范围里；把它当成「读写一层薄壳」即可。
 */
class FavoriteTest {

    // ── 键 ──

    @Test
    fun online_song_key_is_platform_prefixed() {
        val song = song(id = "123", platform = MusicPlatform.kuwo)
        assertEquals("kuwo:123", FavoriteKeys.song(song))
    }

    @Test
    fun local_song_key_is_not_prefixed_twice() {
        // 本地 Song.id 已经是 `local:<绝对路径>`；再拼一次会变成 local:local:/…，
        // 以后改 id 规则就全丢
        val song = song(id = "local:/storage/emulated/0/Music/a.flac", platform = MusicPlatform.local)
        assertEquals("local:/storage/emulated/0/Music/a.flac", FavoriteKeys.song(song))
    }

    @Test
    fun local_path_key_matches_song_id() {
        // 「删掉本地文件」那条路只有路径、没有 Song（`FavoriteStore.removeLocalSong(path)`）。
        // 拼出来的键必须与收藏时用的 `Song.id` 逐字一致，否则删不掉且静默 —— 收藏会永远留着。
        // 这里刻意不调 `LocalTrackMeta.toSong()`：它内部要 `Uri.fromFile`，JVM 单测跑不了。
        val path = "/storage/emulated/0/Music/AnnieMusic/a.flac"
        val asFavorited = song(id = LocalTrackMeta(path = path).id, platform = MusicPlatform.local)
        assertEquals(FavoriteKeys.song(asFavorited), FavoriteKeys.localPath(path))
    }

    @Test
    fun local_album_key_matches_library_grouping() {
        // 必须与本地曲库的分组键逐字一致，否则「收藏后再打开」对不上同一组
        val withArtist = LocalTrackMeta(path = "/x/a.flac", album = "叶惠美", albumArtist = "周杰伦")
        assertEquals(
            albumGroupKey(withArtist),
            FavoriteKeys.localAlbum(withArtist.album, withArtist.albumArtist),
        )

        val blankAlbum = LocalTrackMeta(path = "/x/b.flac", album = "", albumArtist = null)
        assertEquals(
            albumGroupKey(blankAlbum),
            FavoriteKeys.localAlbum(blankAlbum.album, blankAlbum.albumArtist),
        )
    }

    @Test
    fun online_album_and_artist_use_platform_id() {
        assertEquals("netease:407156985", FavoriteKeys.album(MusicPlatform.netease, "407156985", "A", "B"))
        assertEquals("qq:0025NhlN2yWrP4", FavoriteKeys.artist(MusicPlatform.qq, "0025NhlN2yWrP4", "周杰伦"))
        // 本地专辑不看 id（那边根本没有 albumId）
        assertEquals(
            "专辑名 · 专辑艺术家",
            FavoriteKeys.album(MusicPlatform.local, null, "专辑名", "专辑艺术家"),
        )
    }

    // ── 失联自愈 ──

    private fun localAlbum(key: String, paths: List<String>) = FavAlbum(
        key = key,
        name = "专辑",
        artist = "歌手",
        platform = MusicPlatform.local,
        paths = paths,
    )

    @Test
    fun heal_album_rewrites_key_when_tag_renamed() {
        // 用户把专辑名从「旧名」改成「新名」：旧键查不到，靠 paths 找回并自愈
        val fav = localAlbum("旧名 · 周杰伦", listOf("/x/a.flac", "/x/b.flac"))
        val healed = FavoriteSelfHeal.healAlbum(fav) { path ->
            if (path.startsWith("/x/")) "新名 · 周杰伦" else null
        }
        assertEquals("新名 · 周杰伦", healed?.key)
    }

    @Test
    fun heal_album_returns_null_when_key_still_valid() {
        val fav = localAlbum("同一个名字 · 周杰伦", listOf("/x/a.flac"))
        assertNull(FavoriteSelfHeal.healAlbum(fav) { "同一个名字 · 周杰伦" })
    }

    @Test
    fun heal_album_returns_null_when_all_files_gone() {
        val fav = localAlbum("旧名", listOf("/gone/a.flac", "/gone/b.flac"))
        assertNull(FavoriteSelfHeal.healAlbum(fav) { null })
    }

    @Test
    fun heal_artist_falls_back_to_first_surviving_name() {
        val fav = FavArtist(
            key = "旧歌手",
            name = "旧歌手",
            platform = MusicPlatform.local,
            paths = listOf("/x/a.flac"),
        )
        val healed = FavoriteSelfHeal.healArtist(fav) { listOf("新歌手") }
        assertEquals("新歌手", healed?.key)
    }

    @Test
    fun alive_count_distinguishes_lost_files() {
        val fav = localAlbum("专辑", listOf("/x/a.flac", "/x/b.flac", "/x/c.flac"))
        assertEquals(2, FavoriteSelfHeal.aliveTrackCount(fav) { p -> if (p != "/x/c.flac") "专辑" else null })

        val artist = FavArtist(key = "A", name = "A", platform = MusicPlatform.local, paths = listOf("/x/a.flac"))
        assertEquals(0, FavoriteSelfHeal.aliveTrackCount(artist) { emptyList() })
        // 在线条目没有「文件丢失」概念，返回 -1 让 UI 不去灰显
        assertEquals(-1, FavoriteSelfHeal.aliveTrackCount(
            FavAlbum(key = "kuwo:1", platform = MusicPlatform.kuwo),
        ) { "x" })
    }

    // ── 模式过滤 ──

    @Test
    fun snapshot_splits_by_mode() {
        val snapshot = FavoriteSnapshot(
            songs = listOf(
                FavSong(key = "kuwo:1", song = song("1", MusicPlatform.kuwo)),
                FavSong(key = "local:/x.flac", song = song("local:/x.flac", MusicPlatform.local)),
            ),
            albums = listOf(
                FavAlbum(key = "kuwo:9", platform = MusicPlatform.kuwo),
                FavAlbum(key = "专辑 · 歌手", platform = MusicPlatform.local),
            ),
            artists = listOf(
                FavArtist(key = "kuwo:7", platform = MusicPlatform.kuwo),
                FavArtist(key = "歌手", platform = MusicPlatform.local),
            ),
            playlists = listOf(FavPlaylist(key = "netease:5", platform = MusicPlatform.netease)),
            userPlaylists = listOf(
                UserPlaylist(id = "a", name = "在线单", scope = PlaylistScope.online),
                UserPlaylist(id = "b", name = "本地单", scope = PlaylistScope.local),
            ),
        )

        assertEquals(1, snapshot.songsIn(FavoriteMode.online).size)
        assertEquals(1, snapshot.songsIn(FavoriteMode.local).size)
        assertEquals("kuwo:9", snapshot.albumsIn(FavoriteMode.online).single().key)
        assertEquals("专辑 · 歌手", snapshot.albumsIn(FavoriteMode.local).single().key)
        assertEquals(1, snapshot.artistsIn(FavoriteMode.online).size)
        assertEquals(1, snapshot.artistsIn(FavoriteMode.local).size)
        // 收藏的在线歌单只属于流媒体模式
        assertEquals(1, snapshot.playlistsIn(FavoriteMode.online).size)
        assertTrue(snapshot.playlistsIn(FavoriteMode.local).isEmpty())
        // 自建歌单按 scope 归边，不存在"两边都出现"
        assertEquals("a", snapshot.userPlaylistsIn(FavoriteMode.online).single().id)
        assertEquals("b", snapshot.userPlaylistsIn(FavoriteMode.local).single().id)
    }

    // ── 序列化 ──

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun fav_song_survives_json_round_trip() {
        val fav = FavSong(
            key = "kuwo:123",
            song = song("123", MusicPlatform.kuwo).copy(
                album = "专辑",
                albumId = "456",
                artistNames = listOf("A", "B"),
                artistIds = listOf("1", "2"),
            ),
            addedAt = 1_700_000_000_000L,
        )
        val back = json.decodeFromString<FavSong>(json.encodeToString(fav))
        assertEquals(fav, back)
    }

    @Test
    fun user_playlist_survives_json_round_trip() {
        val playlist = UserPlaylist(
            id = "uuid-1",
            name = "我的歌单",
            scope = PlaylistScope.local,
            createdAt = 1_700_000_000_000L,
            songs = listOf(song("local:/x.flac", MusicPlatform.local)),
        )
        val back = json.decodeFromString<UserPlaylist>(json.encodeToString(playlist))
        assertEquals(playlist, back)
        assertEquals(PlaylistScope.local, back.scope)
    }

    @Test
    fun song_with_quality_size_map_survives_json_round_trip() {
        // `Song.qualitySizes` 是 `Map<Quality, Long>`（key 是枚举），往返必须还原成同一张表。
        // 这条顺带确认：枚举 key 的 map 在 kotlinx.serialization 里是支持的
        // （所以「收藏不落盘」不是它的问题，别再往这上面猜）。
        val song = song("1", MusicPlatform.kuwo).copy(
            qualitySizes = mapOf(Quality.flac to 30_000_000L, Quality.k320k to 8_000_000L),
        )
        val lenient = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            allowStructuredMapKeys = true
        }
        val back = lenient.decodeFromString<Song>(lenient.encodeToString(song))
        assertEquals(song.qualitySizes, back.qualitySizes)
    }

    @Test
    fun compact_json_round_trips_and_is_meaningfully_smaller() {
        // 存储用 `encodeDefaults = false`：`Song` 近 30 个字段，收藏只用得到十来个，
        // 其余全是默认值。这里同时守住两件事：往返等价 + 确实变小。
        val fav = FavSong(
            key = "kuwo:650138169",
            song = song("650138169", MusicPlatform.kuwo).copy(
                album = "专辑名",
                albumId = "102977952",
                artistNames = listOf("周杰伦"),
                artistIds = listOf("1234"),
                qualities = listOf(Quality.flac, Quality.k320k),
            ),
            addedAt = 1_700_000_000_000L,
        )
        val compact = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            allowStructuredMapKeys = true
        }
        val full = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            allowStructuredMapKeys = true
        }
        val compactText = compact.encodeToString(fav)
        val fullText = full.encodeToString(fav)

        assertEquals(fav, compact.decodeFromString<FavSong>(compactText))
        assertTrue(
            "不写默认值应当明显更小（compact=${compactText.length} full=${fullText.length}）",
            compactText.length * 3 < fullText.length * 2,
        )
    }

    @Test
    fun legacy_json_from_previous_version_decodes() {
        // 迁移路径的守门测试：这是上一版真的写到磁盘上的那一段（891 字节、1 首），
        // 迁移读不出来 = 用户的收藏在升级时凭空消失。
        val text = """[{"key":"local:/storage/emulated/0/Music/AnnieMusic/kkbox_drm_kkbox_SnG2kJpM2cLmyKFh1O.flac",""" +
            """"song":{"id":"local:/storage/emulated/0/Music/AnnieMusic/kkbox_drm_kkbox_SnG2kJpM2cLmyKFh1O.flac",""" +
            """"title":"Yummy","artist":"Justin Bieber",""" +
            """"cover":"file:///data/user/0/com.annieplayer.annie_music.kt/files/local_library/covers/e7aae765_5c489fd1.jpg",""" +
            """"album":"Changes","albumId":null,"duration":208,"platform":"local",""" +
            """"playUrl":"file:///storage/emulated/0/Music/AnnieMusic/kkbox_drm_kkbox_SnG2kJpM2cLmyKFh1O.flac",""" +
            """"lyric":null,"tlyric":null,"wordLyric":null,"lyricTranslateLangs":[],"lyricTranslateLang":null,""" +
            """"qualities":["flac"],"qualitySizes":{},"albumArtist":"Justin Bieber","trackNumber":"5",""" +
            """"discNumber":null,"releaseDate":"2020-02","artistNames":["Justin Bieber"],"artistIds":[],""" +
            """"composer":null,"genre":null,"isrc":null,"copyright":null,"version":null,"mediaMid":null},""" +
            """"addedAt":1794223995356}]"""
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            allowStructuredMapKeys = true
        }
        val list = json.decodeFromString<List<FavSong>>(text)
        assertEquals(1, list.size)
        assertEquals("Yummy", list[0].song.title)
        assertEquals(MusicPlatform.local, list[0].song.platform)
        assertEquals(1_794_223_995_356L, list[0].addedAt)
    }

    private fun song(id: String, platform: MusicPlatform) = Song(
        id = id,
        title = "标题",
        artist = "歌手",
        cover = "",
        duration = 200,
        platform = platform,
    )
}
