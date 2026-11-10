package com.annieplayer.annie_music.kt.data.favorites

import android.content.Context
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.serialization.json.Json
import java.io.File

// ================================================================================================
// 收藏媒体库的存储门面（SQLite + 内存副本）
// ================================================================================================
//
// 对外 API 与之前用 json 文件时**完全一致**：UI 层（`FavoriteViewModel`、四个详情页、播放页）
// 一行都不用改，换的只是内部实现。
//
// ## 两层结构
// - **SQLite**（[FavoriteDb]）是事实来源：单行 INSERT/DELETE，收藏一首不会重写整份数据；
// - **内存副本**（`xxxCache`）是运行时缓存：`snapshot()` 被 UI 高频调用，不能每次都查库。
//
// 写操作是「先写库、再改内存」，两边顺序固定 —— 反过来的话库写失败时内存会领先，
// 下次冷启动就对不上了。
//
// ## 迁移
// 老版本用 `filesDir/favorites/*.json`。首次启动会把它们导入 SQLite，然后把文件改名成
// `.migrated` 留档（不直接删，万一导入逻辑有 bug 还能捞回来）。迁移只跑一次，标记在 `meta` 表。
//
// ## 读库失败的处理
// 坏行（payload 解不出来）逐条跳过，不让一条脏数据把整张列表打挂。

internal object FavoriteStore {

    private const val META_MODE = "mode"
    private const val META_MIGRATED = "json_migrated_v1"

    private const val LEGACY_SONGS = "songs.json"
    private const val LEGACY_ALBUMS = "albums.json"
    private const val LEGACY_ARTISTS = "artists.json"
    private const val LEGACY_PLAYLISTS = "playlists.json"
    private const val LEGACY_USER_PLAYLISTS = "user_playlists.json"
    private const val LEGACY_MODE = "mode"

    private val json = Json {
        ignoreUnknownKeys = true
        // 不写默认值：`Song` 近 30 个字段，收藏只用得到十来个（见 docs/media-library.md §4.5）
        encodeDefaults = false
        allowStructuredMapKeys = true
    }

    private var appContext: Context? = null
    private var db: FavoriteDb? = null

    // 内存副本（null = 还没读过库）
    private var songsCache: List<FavSong>? = null
    private var albumsCache: List<FavAlbum>? = null
    private var artistsCache: List<FavArtist>? = null
    private var playlistsCache: List<FavPlaylist>? = null
    private var userPlaylistsCache: List<UserPlaylist>? = null

    /** 自建歌单的自选封面（id → 字节；null 值表示「查过、没有」） */
    private val coverCache = HashMap<String, ByteArray?>()
    private var songKeysCache: Set<String>? = null
    private var albumKeysCache: Set<String>? = null
    private var artistKeysCache: Set<String>? = null
    private var playlistKeysCache: Set<String>? = null
    private var modeCache: FavoriteMode? = null

    /** 最近一次落库失败的原因（正常情况下为 null）；排查时读它，不需要翻日志 */
    @Volatile
    internal var lastWriteError: String? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        val database = FavoriteDb(context)
        db = database
        runCatching { migrateLegacyJsonIfNeeded(database) }
    }

    private fun legacyDir(): File =
        File(appContext?.filesDir ?: File("/data/local/tmp"), "favorites")

    // ── 模式 ──

    fun mode(): FavoriteMode {
        modeCache?.let { return it }
        val raw = runCatching { db?.meta(META_MODE) }.getOrNull().orEmpty()
        val resolved = FavoriteMode.entries.firstOrNull { it.name == raw } ?: FavoriteMode.online
        modeCache = resolved
        return resolved
    }

    fun setMode(mode: FavoriteMode) {
        modeCache = mode
        runCatching { db?.putMeta(META_MODE, mode.name) }
            .onFailure { lastWriteError = "setMode: ${it.message}" }
    }

    // ── 读取 ──

    fun songs(): List<FavSong> = songsCache ?: run {
        val list = runCatching {
            db?.loadFavorites(FavKind.song).orEmpty().mapNotNull(::favSongOf)
        }.getOrDefault(emptyList())
        songsCache = list
        list
    }

    fun albums(): List<FavAlbum> = albumsCache ?: run {
        val list = runCatching {
            db?.loadFavorites(FavKind.album).orEmpty().mapNotNull(::favAlbumOf)
        }.getOrDefault(emptyList())
        albumsCache = list
        list
    }

    fun artists(): List<FavArtist> = artistsCache ?: run {
        val list = runCatching {
            db?.loadFavorites(FavKind.artist).orEmpty().mapNotNull(::favArtistOf)
        }.getOrDefault(emptyList())
        artistsCache = list
        list
    }

    fun playlists(): List<FavPlaylist> = playlistsCache ?: run {
        val list = runCatching {
            db?.loadFavorites(FavKind.playlist).orEmpty().mapNotNull(::favPlaylistOf)
        }.getOrDefault(emptyList())
        playlistsCache = list
        list
    }

    fun userPlaylists(): List<UserPlaylist> = userPlaylistsCache ?: run {
        val list = runCatching {
            val database = db ?: return@runCatching emptyList()
            database.loadUserPlaylists().map { row ->
                UserPlaylist(
                    id = row.id,
                    name = row.name,
                    scope = PlaylistScope.entries.firstOrNull { it.name == row.scope }
                        ?: PlaylistScope.online,
                    cover = row.cover,
                    createdAt = row.createdAt,
                    songs = database.loadPlaylistSongs(row.id)
                        .mapNotNull { songRow -> songOfPayload(songRow.payload) },
                )
            }
        }.getOrDefault(emptyList())
        userPlaylistsCache = list
        list
    }

    fun snapshot(): FavoriteSnapshot = FavoriteSnapshot(
        mode = mode(),
        songs = songs(),
        albums = albums(),
        artists = artists(),
        playlists = playlists(),
        userPlaylists = userPlaylists(),
    )

    // ── 单曲 ──

    fun isSongLiked(song: Song): Boolean = FavoriteKeys.song(song) in songKeys()

    private fun songKeys(): Set<String> =
        songKeysCache ?: songs().mapTo(HashSet()) { it.key }.also { songKeysCache = it }

    /** 切换收藏，返回**切换后**的状态（true = 已收藏）。 */
    fun toggleSong(song: Song): Boolean {
        val key = FavoriteKeys.song(song)
        val current = songs()
        val existing = current.firstOrNull { it.key == key }
        return if (existing != null) {
            write { it.deleteFavorite(key) }
            commitSongs(current.filterNot { it.key == key })
            false
        } else {
            val fav = FavSong(key, song.withoutLyrics(), System.currentTimeMillis())
            write { it.insertFavorite(favSongRow(fav)) }
            commitSongs(listOf(fav) + current)
            true
        }
    }

    fun removeSong(key: String) {
        val current = songs()
        if (current.none { it.key == key }) return
        write { it.deleteFavorite(key) }
        commitSongs(current.filterNot { it.key == key })
    }

    /**
     * 本地文件**被删除**后，撤掉它的收藏与自建歌单里的引用。
     *
     * 只在「连文件一起删」时调用：只从曲库移除（文件还在盘上）时收藏仍然有效 ——
     * 那种情况用户只是不想让它出现在曲库里，重新扫描还能加回来。
     *
     * 自建歌单里那条也要删：留一条指向已删文件的歌，那个歌单就永远播不完整。
     */
    fun removeLocalSong(path: String) {
        if (path.isBlank()) return
        val songKey = FavoriteKeys.localPath(path)
        removeSong(songKey)
        write { it.removePlaylistSongEverywhere(songKey) }
        userPlaylistsCache = null
    }

    private fun commitSongs(list: List<FavSong>) {
        songsCache = list
        songKeysCache = null
    }

    // ── 专辑 ──

    fun isAlbumFavorite(key: String): Boolean = key in albumKeys()

    private fun albumKeys(): Set<String> =
        albumKeysCache ?: albums().mapTo(HashSet()) { it.key }.also { albumKeysCache = it }

    fun toggleAlbum(fav: FavAlbum): Boolean {
        val current = albums()
        val existing = current.firstOrNull { it.key == fav.key }
        return if (existing != null) {
            write { it.deleteFavorite(fav.key) }
            commitAlbums(current.filterNot { it.key == fav.key })
            false
        } else {
            val withTime = fav.copy(addedAt = System.currentTimeMillis())
            write { it.insertFavorite(favAlbumRow(withTime)) }
            commitAlbums(listOf(withTime) + current)
            true
        }
    }

    fun removeAlbum(key: String) {
        val current = albums()
        if (current.none { it.key == key }) return
        write { it.deleteFavorite(key) }
        commitAlbums(current.filterNot { it.key == key })
    }

    /** 刷新（`FavoriteRefresher`）整表替换用。 */
    fun replaceAlbums(list: List<FavAlbum>) {
        write { database ->
            database.clearFavorites(FavKind.album)
            list.forEach { database.insertFavorite(favAlbumRow(it)) }
        }
        commitAlbums(list)
    }

    private fun commitAlbums(list: List<FavAlbum>) {
        albumsCache = list
        albumKeysCache = null
    }

    // ── 歌手 ──

    fun isArtistFavorite(key: String): Boolean = key in artistKeys()

    private fun artistKeys(): Set<String> =
        artistKeysCache ?: artists().mapTo(HashSet()) { it.key }.also { artistKeysCache = it }

    fun toggleArtist(fav: FavArtist): Boolean {
        val current = artists()
        val existing = current.firstOrNull { it.key == fav.key }
        return if (existing != null) {
            write { it.deleteFavorite(fav.key) }
            commitArtists(current.filterNot { it.key == fav.key })
            false
        } else {
            val withTime = fav.copy(addedAt = System.currentTimeMillis())
            write { it.insertFavorite(favArtistRow(withTime)) }
            commitArtists(listOf(withTime) + current)
            true
        }
    }

    fun removeArtist(key: String) {
        val current = artists()
        if (current.none { it.key == key }) return
        write { it.deleteFavorite(key) }
        commitArtists(current.filterNot { it.key == key })
    }

    fun replaceArtists(list: List<FavArtist>) {
        write { database ->
            database.clearFavorites(FavKind.artist)
            list.forEach { database.insertFavorite(favArtistRow(it)) }
        }
        commitArtists(list)
    }

    /**
     * 自愈：把一条本地专辑收藏的键换成新键（用户改了 tag 之后）。
     *
     * 只换键，其余字段照旧 —— 名字 / 封面由别的路径维护（详情页校准）。
     * 找不到新键时调用方**不调它**（`FavoriteSelfHeal` 返回 null），记录原样保留。
     */
    fun rekeyAlbum(oldKey: String, healed: FavAlbum) {
        if (oldKey == healed.key) return
        write { it.replaceFavoriteKey(oldKey, favAlbumRow(healed)) }
        commitAlbums(albums().map { if (it.key == oldKey) healed else it })
    }

    /** 自愈：本地歌手的键（理由同 [rekeyAlbum]）。 */
    fun rekeyArtist(oldKey: String, healed: FavArtist) {
        if (oldKey == healed.key) return
        write { it.replaceFavoriteKey(oldKey, favArtistRow(healed)) }
        commitArtists(artists().map { if (it.key == oldKey) healed else it })
    }

    private fun commitArtists(list: List<FavArtist>) {
        artistsCache = list
        artistKeysCache = null
    }

    // ── 歌单 ──

    fun isPlaylistFavorite(key: String): Boolean = key in playlistKeys()

    private fun playlistKeys(): Set<String> =
        playlistKeysCache ?: playlists().mapTo(HashSet()) { it.key }.also { playlistKeysCache = it }

    fun togglePlaylist(fav: FavPlaylist): Boolean {
        val current = playlists()
        val existing = current.firstOrNull { it.key == fav.key }
        return if (existing != null) {
            write { it.deleteFavorite(fav.key) }
            commitPlaylists(current.filterNot { it.key == fav.key })
            false
        } else {
            val withTime = fav.copy(addedAt = System.currentTimeMillis())
            write { it.insertFavorite(favPlaylistRow(withTime)) }
            commitPlaylists(listOf(withTime) + current)
            true
        }
    }

    /**
     * 校准收藏的专辑 / 歌单元信息（**只在打开详情页时调**）。
     *
     * 列表页永远显示收藏那一刻存下来的值；只有用户真的打开了那张专辑 / 那个歌单，
     * 才拿接口返回的当前事实来对齐（曲目数涨了、发行日期改了）。
     *
     * 未收藏 → 什么都不做（收藏是用户动作，不能因为「浏览过」就写进去）；
     * 值一样 → 也不写库（避免每次打开详情都产生一次写）。
     *
     * @return 是否真的更新了（调用方据此决定要不要刷新快照）
     */
    fun syncAlbumMeta(
        key: String,
        name: String,
        artist: String,
        cover: String,
        trackCount: Int,
        releaseDate: String,
        artistId: String,
    ): Boolean {
        val current = albums()
        val updated = albumMetaChange(
            existing = current.firstOrNull { it.key == key },
            name = name,
            artist = artist,
            cover = cover,
            trackCount = trackCount,
            releaseDate = releaseDate,
            artistId = artistId,
        ) ?: return false
        write { it.updateAlbumMeta(key, name, artist, cover, trackCount, releaseDate, artistId) }
        commitAlbums(current.map { if (it.key == key) updated else it })
        return true
    }

    /** 歌手版 [syncAlbumMeta]：名字与头像。 */
    fun syncArtistMeta(key: String, name: String, picture: String): Boolean {
        val current = artists()
        val updated = artistMetaChange(
            existing = current.firstOrNull { it.key == key },
            name = name,
            picture = picture,
        ) ?: return false
        write { it.updateArtistMeta(key, name, picture) }
        commitArtists(current.map { if (it.key == key) updated else it })
        return true
    }

    /** 歌单版 [syncAlbumMeta]：名字 / 封面 / 创建者 / 曲目数。 */
    fun syncPlaylistMeta(
        key: String,
        name: String,
        cover: String,
        creator: String,
        trackCount: Int,
    ): Boolean {
        val current = playlists()
        val updated = playlistMetaChange(
            existing = current.firstOrNull { it.key == key },
            name = name,
            cover = cover,
            creator = creator,
            trackCount = trackCount,
        ) ?: return false
        write { it.updatePlaylistMeta(key, name, cover, creator, trackCount) }
        commitPlaylists(current.map { if (it.key == key) updated else it })
        return true
    }

    /**
     * 幂等加入收藏（链接导入用）：已经在收藏里就什么都不做。
     *
     * 和 [togglePlaylist] 的区别很重要：导入是「确保它在收藏里」，
     * 用 toggle 的话第二次导入会把它从收藏里删掉。
     *
     * @return 是否真的新增了（false = 本来就在）
     */
    fun addPlaylist(fav: FavPlaylist): Boolean {
        val current = playlists()
        if (current.any { it.key == fav.key }) return false
        val withTime = fav.copy(addedAt = System.currentTimeMillis())
        write { it.insertFavorite(favPlaylistRow(withTime)) }
        commitPlaylists(listOf(withTime) + current)
        return true
    }

    fun removePlaylist(key: String) {
        val current = playlists()
        if (current.none { it.key == key }) return
        write { it.deleteFavorite(key) }
        commitPlaylists(current.filterNot { it.key == key })
    }

    fun replacePlaylists(list: List<FavPlaylist>) {
        write { database ->
            database.clearFavorites(FavKind.playlist)
            list.forEach { database.insertFavorite(favPlaylistRow(it)) }
        }
        commitPlaylists(list)
    }

    private fun commitPlaylists(list: List<FavPlaylist>) {
        playlistsCache = list
        playlistKeysCache = null
    }

    // ── 自建播放列表 ──

    fun userPlaylist(id: String): UserPlaylist? = userPlaylists().firstOrNull { it.id == id }

    /** 新建或整体覆盖（同 id 覆盖，歌单内的歌也一起替换）。 */
    fun saveUserPlaylist(playlist: UserPlaylist, coverBlob: ByteArray? = null) {
        write { database ->
            database.saveUserPlaylist(
                UserPlaylistRow(
                    id = playlist.id,
                    name = playlist.name,
                    scope = playlist.scope.name,
                    cover = playlist.cover,
                    createdAt = playlist.createdAt,
                    coverBlob = coverBlob,
                ),
            )
            database.replacePlaylistSongs(
                playlist.id,
                playlist.songs.map { FavoriteKeys.song(it) to json.encodeToString(it) },
            )
        }
        coverCache.remove(playlist.id)
        userPlaylistsCache = null
    }

    /** 自建歌单的自选封面字节（没有返回 null）。带内存副本：列表滚动时不该反复查库。 */
    fun playlistCover(id: String): ByteArray? {
        if (coverCache.containsKey(id)) return coverCache[id]
        val bytes = runCatching { db?.loadPlaylistCover(id) }.getOrNull()
        coverCache[id] = bytes
        return bytes
    }

    fun deleteUserPlaylist(id: String) {
        write { it.deleteUserPlaylist(id) }
        userPlaylistsCache = null
    }

    /** 往自建歌单里加一首（已存在则忽略）。返回是否真的加进去了。 */
    fun addSongToUserPlaylist(playlistId: String, song: Song): Boolean {
        var added = false
        write { database ->
            added = database.appendPlaylistSong(
                playlistId,
                FavoriteKeys.song(song),
                json.encodeToString(song.withoutLyrics()),
            )
        }
        if (added) userPlaylistsCache = null
        return added
    }

    fun removeSongFromUserPlaylist(playlistId: String, songId: String, platform: MusicPlatform) {
        write { it.removePlaylistSong(playlistId, FavoriteKeys.online(platform, songId)) }
        userPlaylistsCache = null
    }

    /**
     * 批量移除（自建歌单的多选）。
     *
     * 一个事务写完再清一次缓存：逐首调 `removeSongFromUserPlaylist` 会是 N 次写盘 +
     * N 次 `reload()`（每次都重读**所有**歌单的歌曲），几十首时已经明显卡。
     */
    fun removeSongsFromUserPlaylist(playlistId: String, songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val keys = songs.map { FavoriteKeys.online(it.platform, it.id) }
        write { db -> keys.forEach { db.removePlaylistSong(playlistId, it) } }
        userPlaylistsCache = null
        return keys.size
    }

    /** 测试 / 切换账号用：把内存副本丢掉，下次读库。 */
    internal fun invalidate() {
        songsCache = null
        albumsCache = null
        artistsCache = null
        playlistsCache = null
        userPlaylistsCache = null
        coverCache.clear()
        songKeysCache = null
        albumKeysCache = null
        artistKeysCache = null
        playlistKeysCache = null
        modeCache = null
    }

    // ── 内部：写库包装 ──

    /** 统一收口写库异常，失败原因留在 [lastWriteError] 而不是静默消失。 */
    private inline fun write(block: (FavoriteDb) -> Unit) {
        val database = db ?: return
        runCatching { block(database) }
            .onSuccess { lastWriteError = null }
            .onFailure { lastWriteError = "${it::class.java.simpleName}: ${it.message}" }
    }

    // ── 内部：行 ↔ 模型 ──

    private fun songOfPayload(payload: String): Song? =
        if (payload.isEmpty()) null
        else runCatching { json.decodeFromString<Song>(payload) }.getOrNull()

    private fun favSongOf(row: FavoriteRow): FavSong? {
        val song = songOfPayload(row.payload) ?: return null
        return FavSong(key = row.key, song = song.withoutLyrics(), addedAt = row.addedAt)
    }

    private fun favAlbumOf(row: FavoriteRow) = FavAlbum(
        key = row.key,
        id = row.itemId,
        name = row.name,
        artist = row.subtitle,
        cover = row.cover,
        platform = platformOf(row.platform),
        trackCount = row.trackCount,
        releaseDate = row.releaseDate,
        artistId = row.artistId,
        paths = decodePaths(row.paths),
        addedAt = row.addedAt,
    )

    private fun favArtistOf(row: FavoriteRow) = FavArtist(
        key = row.key,
        id = row.itemId,
        name = row.name,
        picture = row.cover,
        platform = platformOf(row.platform),
        paths = decodePaths(row.paths),
        addedAt = row.addedAt,
    )

    private fun favPlaylistOf(row: FavoriteRow) = FavPlaylist(
        key = row.key,
        id = row.itemId,
        name = row.name,
        cover = row.cover,
        platform = platformOf(row.platform),
        creator = row.creator,
        trackCount = row.trackCount,
        addedAt = row.addedAt,
    )

    private fun favSongRow(fav: FavSong) = FavoriteRow(
        key = fav.key,
        kind = FavKind.song,
        platform = fav.song.platform.name,
        name = fav.song.title,
        subtitle = fav.song.artist,
        cover = fav.song.cover,
        itemId = fav.song.id,
        duration = fav.song.duration,
        paths = "",
        payload = json.encodeToString(fav.song),
        addedAt = fav.addedAt,
    )

    private fun favAlbumRow(fav: FavAlbum) = FavoriteRow(
        key = fav.key,
        kind = FavKind.album,
        platform = fav.platform.name,
        name = fav.name,
        subtitle = fav.artist,
        cover = fav.cover,
        itemId = fav.id,
        duration = 0,
        paths = encodePaths(fav.paths),
        payload = "",
        addedAt = fav.addedAt,
        trackCount = fav.trackCount,
        releaseDate = fav.releaseDate,
        artistId = fav.artistId,
    )

    private fun favArtistRow(fav: FavArtist) = FavoriteRow(
        key = fav.key,
        kind = FavKind.artist,
        platform = fav.platform.name,
        name = fav.name,
        subtitle = "",
        cover = fav.picture,
        itemId = fav.id,
        duration = 0,
        paths = encodePaths(fav.paths),
        payload = "",
        addedAt = fav.addedAt,
    )

    private fun favPlaylistRow(fav: FavPlaylist) = FavoriteRow(
        key = fav.key,
        kind = FavKind.playlist,
        platform = fav.platform.name,
        name = fav.name,
        subtitle = "",
        cover = fav.cover,
        itemId = fav.id,
        duration = 0,
        paths = "",
        payload = "",
        addedAt = fav.addedAt,
        creator = fav.creator,
        trackCount = fav.trackCount,
    )

    private fun platformOf(name: String): MusicPlatform =
        MusicPlatform.entries.firstOrNull { it.name == name } ?: MusicPlatform.kuwo

    private fun encodePaths(paths: List<String>): String =
        if (paths.isEmpty()) "" else json.encodeToString(paths)

    private fun decodePaths(raw: String): List<String> =
        if (raw.isEmpty()) emptyList()
        else runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    // ── 内部：旧 json 迁移 ──

    private fun migrateLegacyJsonIfNeeded(database: FavoriteDb) {
        if (database.meta(META_MIGRATED) == "1") return
        val dir = legacyDir()

        readLegacyJson<FavSong>(dir, LEGACY_SONGS).forEach {
            database.insertFavorite(favSongRow(it))
            logMigrate(dir, "insert song: ${it.key.take(50)}")
        }
        readLegacyJson<FavAlbum>(dir, LEGACY_ALBUMS).forEach { database.insertFavorite(favAlbumRow(it)) }
        readLegacyJson<FavArtist>(dir, LEGACY_ARTISTS).forEach { database.insertFavorite(favArtistRow(it)) }
        readLegacyJson<FavPlaylist>(dir, LEGACY_PLAYLISTS).forEach { database.insertFavorite(favPlaylistRow(it)) }
        readLegacyJson<UserPlaylist>(dir, LEGACY_USER_PLAYLISTS).forEach { playlist ->
            database.saveUserPlaylist(
                UserPlaylistRow(
                    id = playlist.id,
                    name = playlist.name,
                    scope = playlist.scope.name,
                    cover = playlist.cover,
                    createdAt = playlist.createdAt,
                ),
            )
            database.replacePlaylistSongs(
                playlist.id,
                playlist.songs.map { FavoriteKeys.song(it) to json.encodeToString(it) },
            )
        }

        // 模式：老版本存在文本文件里
        val legacyModeFile = File(dir, LEGACY_MODE)
        if (legacyModeFile.isFile) {
            runCatching {
                val raw = legacyModeFile.readText().trim()
                if (FavoriteMode.entries.any { it.name == raw }) database.putMeta(META_MODE, raw)
            }
        }

        // 文件改名留档（不删：万一导入逻辑有问题还能捞回来），再打迁移标记
        listOf(
            LEGACY_SONGS, LEGACY_ALBUMS, LEGACY_ARTISTS,
            LEGACY_PLAYLISTS, LEGACY_USER_PLAYLISTS, LEGACY_MODE,
        ).forEach { name ->
            val file = File(dir, name)
            if (file.isFile) runCatching { file.renameTo(File(dir, "$name.migrated")) }
        }
        database.putMeta(META_MIGRATED, "1")
    }

    /**
     * 读一份老 json 并解码。**必须 `reified`**：泛型 `T` 一旦在普通函数里被擦除，
     * `json.decodeFromString<List<T>>` 会在运行时报
     * "Captured type parameter T … cannot be supported because T is erased"，
     * 而迁移里这个异常会被吞成「读到 0 条」—— 表现就是升级后收藏全没了。
     *
     * 局部函数不能声明 `inline reified`，所以放在 object 成员位置。
     */
    private inline fun <reified T> readLegacyJson(dir: File, name: String): List<T> {
        val file = File(dir, name)
        if (!file.isFile) {
            logMigrate(dir, "$name: missing")
            return emptyList()
        }
        return try {
            val text = file.readText()
            if (text.isBlank()) {
                logMigrate(dir, "$name: blank")
                return emptyList()
            }
            val list = json.decodeFromString<List<T>>(text)
            logMigrate(dir, "$name: ok ${list.size} items / ${text.length} chars")
            list
        } catch (e: Throwable) {
            logMigrate(dir, "$name: FAILED ${e::class.java.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /**
     * 迁移留痕：`files/favorites/migrate.log`。
     *
     * 迁移是「用户收藏的生死关」，读不到 / 解不出 / 条数不对都必须能事后查到，
     * 否则升级完收藏凭空消失，连从哪查起都不知道。
     */
    private fun logMigrate(dir: File, msg: String) {
        lastWriteError = msg
        runCatching { File(dir, "migrate.log").appendText("$msg\n") }
    }
}

/**
 * 收藏不存歌词：`lyric` / `tlyric` / `wordLyric` 一首就是 4~5 KB，占单首体积九成以上。
 * 播放时 `PlayerViewModel` 会照常 `_ensureLyric` 重新取。
 */
private fun Song.withoutLyrics(): Song =
    if (lyric == null && tlyric == null && wordLyric == null) {
        this
    } else {
        copy(lyric = null, tlyric = null, wordLyric = null)
    }
