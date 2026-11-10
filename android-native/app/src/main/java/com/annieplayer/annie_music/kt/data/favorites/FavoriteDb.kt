package com.annieplayer.annie_music.kt.data.favorites

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// ================================================================================================
// 收藏 / 自建歌单的 SQLite 存储
// ================================================================================================
//
// 为什么从 json 换成 SQLite：
// - json 是**整表读写**：3 万首时每次点爱心要重写 11~16 MB（150~400 ms，明显卡）；
// - 收藏是个天然的关系结构（歌单↔歌曲是多对多），用文件表达只能靠嵌套，改一首要重写整份；
// - 查询要排序（`added_at DESC`）、要按类型过滤，这些 SQL 天然支持，文件方案得全量读进内存再排。
//
// ## 为什么不用 Room
// 项目没有 KSP/kapt，加 Room 要引入整套注解处理器（首次编译变慢、每次改实体重编）。
// 这里只有 4 张表、十来个查询，手写 SQL 完全可控；代价是**列名没有编译期校验**，
// 所以列名全部走常量（`COL_*`），并且行 → 模型的转换抽成纯函数（见 `FavoriteRows.kt`）以便单测。
//
// ## payload 列
// 歌曲的完整信息（音质、歌手 id、封面…）序列化成一段 JSON 放在 `payload` 里 ——
// 不是偷懒，是因为这批字段的数量和形态会随平台变（新增一个字段不该动表结构）。
// 收藏**用得到的**那几项（标题/歌手/封面/时长）另外拆成独立列，方便直接 SELECT 与排序，
// 不用为了画一行列表去解 JSON。
//
// ## 线程
// 写操作都是单行 INSERT/DELETE（< 5 ms），调用方在 UI 线程直接调；批量导入放 IO 线程。

/** `favorites.kind` 的取值。 */
internal object FavKind {
    const val song = "song"
    const val album = "album"
    const val artist = "artist"
    const val playlist = "playlist"
}

/** `favorites` 表的一行（payload 未解码）。 */
internal data class FavoriteRow(
    val key: String,
    val kind: String,
    val platform: String,
    val name: String,
    val subtitle: String,
    val cover: String,
    val itemId: String,
    val duration: Int,
    val paths: String,
    val payload: String,
    val addedAt: Long,
    /** 专辑 / 歌单的曲目数（其它 kind 为 0） */
    val trackCount: Int = 0,
    /** 专辑的发行日期（其它 kind 为空） */
    val releaseDate: String = "",
    /** 歌单的创建者（其它 kind 为空） */
    val creator: String = "",
    /** 专辑歌手的平台 id（其它 kind 为空） */
    val artistId: String = "",
)

/** `user_playlists` 表的一行。 */
internal data class UserPlaylistRow(
    val id: String,
    val name: String,
    val scope: String,
    /** 封面：外部 URL（收藏导入）或空串（自建歌单的封面在 [coverBlob] 里） */
    val cover: String,
    val createdAt: Long,
    /**
     * 自建歌单的自选封面（JPEG 字节）。
     *
     * 存进库而不是文件：用户明确要求收藏的东西都在 SQLite 里。列表页**不读**这一列
     * （见 `loadUserPlaylists`），只有真要画封面时才按 id 单独取。
     */
    val coverBlob: ByteArray? = null,
)

/** `user_playlist_songs` 表的一行。 */
internal data class UserPlaylistSongRow(
    val playlistId: String,
    val position: Int,
    val songKey: String,
    val payload: String,
)

internal class FavoriteDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 歌单删掉时它的歌必须一起走，否则会留一堆孤儿行
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_FAV (
                $COL_KEY       TEXT PRIMARY KEY NOT NULL,
                $COL_KIND      TEXT NOT NULL,
                $COL_PLATFORM  TEXT NOT NULL DEFAULT '',
                $COL_NAME      TEXT NOT NULL DEFAULT '',
                $COL_SUBTITLE  TEXT NOT NULL DEFAULT '',
                $COL_COVER     TEXT NOT NULL DEFAULT '',
                $COL_ITEM_ID   TEXT NOT NULL DEFAULT '',
                $COL_DURATION  INTEGER NOT NULL DEFAULT 0,
                $COL_PATHS     TEXT NOT NULL DEFAULT '',
                $COL_PAYLOAD   TEXT NOT NULL DEFAULT '',
                $COL_ADDED_AT  INTEGER NOT NULL DEFAULT 0,
                $COL_TRACK_COUNT  INTEGER NOT NULL DEFAULT 0,
                $COL_RELEASE_DATE TEXT NOT NULL DEFAULT '',
                $COL_CREATOR      TEXT NOT NULL DEFAULT '',
                $COL_ARTIST_ID    TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX $IDX_FAV_KIND ON $TABLE_FAV ($COL_KIND, $COL_ADDED_AT DESC)",
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_PLAYLIST (
                $COL_PL_ID         TEXT PRIMARY KEY NOT NULL,
                $COL_PL_NAME       TEXT NOT NULL,
                $COL_PL_SCOPE      TEXT NOT NULL DEFAULT 'online',
                $COL_PL_COVER      TEXT NOT NULL DEFAULT '',
                $COL_PL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_PL_COVER_BLOB BLOB
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_PL_SONG (
                $COL_PS_PLAYLIST_ID TEXT NOT NULL,
                $COL_PS_POSITION    INTEGER NOT NULL,
                $COL_PS_SONG_KEY    TEXT NOT NULL,
                $COL_PS_PAYLOAD     TEXT NOT NULL,
                PRIMARY KEY ($COL_PS_PLAYLIST_ID, $COL_PS_POSITION),
                FOREIGN KEY ($COL_PS_PLAYLIST_ID) REFERENCES $TABLE_PLAYLIST ($COL_PL_ID)
                    ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX $IDX_PS_PLAYLIST ON $TABLE_PL_SONG ($COL_PS_PLAYLIST_ID, $COL_PS_POSITION)",
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_META (
                $COL_META_KEY TEXT PRIMARY KEY NOT NULL,
                $COL_META_VAL TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 收藏是「用户的资产」，不能为了升级结构就 DROP 清空 —— 逐版本 ALTER。
        //
        // v2：专辑 / 歌单多存三项（曲目数 / 发行日期 / 创建者），列表页直接显示、详情页校准。
        // `ADD COLUMN` 不动已有行（默认值填 0 / 空串），所以单曲与歌手的收藏一行都不受影响；
        // 旧的专辑记录没有这两项，卡片上就不显示，直到再打开一次它的详情页被校准。
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_FAV ADD COLUMN $COL_TRACK_COUNT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_FAV ADD COLUMN $COL_RELEASE_DATE TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE_FAV ADD COLUMN $COL_CREATOR TEXT NOT NULL DEFAULT ''")
        }
        // v3：自建歌单可以自己选封面，图片字节直接进库（用户要求「收藏的东西都在 SQLite」）。
        // 已有的自建歌单这一列为 NULL，列表照旧显示占位图。
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_PLAYLIST ADD COLUMN $COL_PL_COVER_BLOB BLOB")
        }
        // v4：收藏的专辑多存一项「歌手 id」，只给「从收藏库进专辑 → 点歌手」按 id 秒开用。
        // 旧记录为空串 → 退回按名字搜索（与 v4 之前的行为一致），不影响任何既有功能。
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE_FAV ADD COLUMN $COL_ARTIST_ID TEXT NOT NULL DEFAULT ''")
        }
    }

    // ── favorites ──

    fun loadFavorites(kind: String): List<FavoriteRow> {
        val out = ArrayList<FavoriteRow>()
        readableDatabase.query(
            TABLE_FAV,
            null,
            "$COL_KIND = ?",
            arrayOf(kind),
            null,
            null,
            "$COL_ADDED_AT DESC",
        ).use { c ->
            while (c.moveToNext()) out.add(c.toRow())
        }
        return out
    }

    fun loadFavorite(key: String): FavoriteRow? =
        readableDatabase.query(
            TABLE_FAV,
            null,
            "$COL_KEY = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        ).use { c -> if (c.moveToFirst()) c.toRow() else null }

    fun insertFavorite(row: FavoriteRow) {
        val values = ContentValues().apply {
            put(COL_KEY, row.key)
            put(COL_KIND, row.kind)
            put(COL_PLATFORM, row.platform)
            put(COL_NAME, row.name)
            put(COL_SUBTITLE, row.subtitle)
            put(COL_COVER, row.cover)
            put(COL_ITEM_ID, row.itemId)
            put(COL_DURATION, row.duration)
            put(COL_PATHS, row.paths)
            put(COL_PAYLOAD, row.payload)
            put(COL_ADDED_AT, row.addedAt)
            put(COL_TRACK_COUNT, row.trackCount)
            put(COL_RELEASE_DATE, row.releaseDate)
            put(COL_CREATOR, row.creator)
            put(COL_ARTIST_ID, row.artistId)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_FAV,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteFavorite(key: String) {
        writableDatabase.delete(TABLE_FAV, "$COL_KEY = ?", arrayOf(key))
    }

    /**
     * 把一条收藏的键换掉（本地条目改 tag 后的自愈）。
     *
     * 键是主键，改它只能是「删旧行 + 插新行」，必须在一个事务里 —— 否则中间崩一次
     * 就变成「旧键删了、新键没写」，那条收藏凭空消失。
     */
    fun replaceFavoriteKey(oldKey: String, row: FavoriteRow) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_FAV, "$COL_KEY = ?", arrayOf(oldKey))
            insertFavorite(row)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 刷新（`FavoriteRefresher`）整批更新用：只改展示字段，`payload` / `paths` 不动。 */
    fun updateFavoriteDisplay(
        key: String,
        name: String,
        subtitle: String,
        cover: String,
    ) {
        val values = ContentValues().apply {
            put(COL_NAME, name)
            put(COL_SUBTITLE, subtitle)
            put(COL_COVER, cover)
        }
        writableDatabase.update(TABLE_FAV, values, "$COL_KEY = ?", arrayOf(key))
    }

    /**
     * 校准收藏的专辑元信息（打开专辑详情页时用当时拿到的事实覆盖）。
     *
     * 只动这五项。曲目数 / 发行日期来自详情接口；名字 / 歌手 / 封面来自详情页的**入参**
     * —— 从主页或搜索进来时那是平台的当前值（收藏那一刻存下的可能是旧的），
     * 从收藏库进来时它本来就是收藏记录本身，于是值不变、不会产生写。
     */
    fun updateAlbumMeta(
        key: String,
        name: String,
        artist: String,
        cover: String,
        trackCount: Int,
        releaseDate: String,
        artistId: String,
    ) {
        val values = ContentValues().apply {
            put(COL_NAME, name)
            put(COL_SUBTITLE, artist)
            put(COL_COVER, cover)
            put(COL_TRACK_COUNT, trackCount)
            put(COL_RELEASE_DATE, releaseDate)
            put(COL_ARTIST_ID, artistId)
        }
        writableDatabase.update(TABLE_FAV, values, "$COL_KEY = ?", arrayOf(key))
    }

    /** 校准收藏的歌手元信息（名字 / 头像）；理由同 [updateAlbumMeta]。 */
    fun updateArtistMeta(key: String, name: String, picture: String) {
        val values = ContentValues().apply {
            put(COL_NAME, name)
            put(COL_COVER, picture)
        }
        writableDatabase.update(TABLE_FAV, values, "$COL_KEY = ?", arrayOf(key))
    }

    /** 校准收藏的歌单元信息（名字 / 封面 / 创建者 / 曲目数）；理由同 [updateAlbumMeta]。 */
    fun updatePlaylistMeta(
        key: String,
        name: String,
        cover: String,
        creator: String,
        trackCount: Int,
    ) {
        val values = ContentValues().apply {
            put(COL_NAME, name)
            put(COL_COVER, cover)
            put(COL_CREATOR, creator)
            put(COL_TRACK_COUNT, trackCount)
        }
        writableDatabase.update(TABLE_FAV, values, "$COL_KEY = ?", arrayOf(key))
    }

    // ── 自建播放列表 ──

    fun loadUserPlaylists(): List<UserPlaylistRow> {
        val out = ArrayList<UserPlaylistRow>()
        readableDatabase.query(
            TABLE_PLAYLIST,
            null,
            null,
            null,
            null,
            null,
            "$COL_PL_CREATED_AT DESC",
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    UserPlaylistRow(
                        id = c.getString(c.getColumnIndexOrThrow(COL_PL_ID)),
                        name = c.getString(c.getColumnIndexOrThrow(COL_PL_NAME)),
                        scope = c.getString(c.getColumnIndexOrThrow(COL_PL_SCOPE)),
                        cover = c.getString(c.getColumnIndexOrThrow(COL_PL_COVER)),
                        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_PL_CREATED_AT)),
                    ),
                )
            }
        }
        return out
    }

    fun saveUserPlaylist(row: UserPlaylistRow) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(COL_PL_ID, row.id)
                put(COL_PL_NAME, row.name)
                put(COL_PL_SCOPE, row.scope)
                put(COL_PL_COVER, row.cover)
                put(COL_PL_CREATED_AT, row.createdAt)
                // null = 保留库里原来的封面（不然「改名」会把自选封面抹掉）
                if (row.coverBlob != null) put(COL_PL_COVER_BLOB, row.coverBlob)
            }
            db.insertWithOnConflict(TABLE_PLAYLIST, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 单个自建歌单的自选封面字节（没有就是 null）。
     *
     * 单独取而不是让 `loadUserPlaylists` 一起读：列表页一次要画十几行，没必要为了
     * 「可能有、可能没有」的一列把十几份几十 KB 的 BLOB 全搬进内存。
     */
    fun loadPlaylistCover(id: String): ByteArray? = readableDatabase.query(
        TABLE_PLAYLIST,
        arrayOf(COL_PL_COVER_BLOB),
        "$COL_PL_ID = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

    fun deleteUserPlaylist(id: String) {
        // 外键 CASCADE 会把 user_playlist_songs 一起删（onConfigure 里开了约束）
        writableDatabase.delete(TABLE_PLAYLIST, "$COL_PL_ID = ?", arrayOf(id))
    }

    fun loadPlaylistSongs(playlistId: String): List<UserPlaylistSongRow> {
        val out = ArrayList<UserPlaylistSongRow>()
        readableDatabase.query(
            TABLE_PL_SONG,
            null,
            "$COL_PS_PLAYLIST_ID = ?",
            arrayOf(playlistId),
            null,
            null,
            "$COL_PS_POSITION ASC",
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    UserPlaylistSongRow(
                        playlistId = c.getString(c.getColumnIndexOrThrow(COL_PS_PLAYLIST_ID)),
                        position = c.getInt(c.getColumnIndexOrThrow(COL_PS_POSITION)),
                        songKey = c.getString(c.getColumnIndexOrThrow(COL_PS_SONG_KEY)),
                        payload = c.getString(c.getColumnIndexOrThrow(COL_PS_PAYLOAD)),
                    ),
                )
            }
        }
        return out
    }

    /**
     * 往歌单尾部追加一首。返回是否真的加了（已在表里就不加）。
     *
     * 用「取 MAX(position)+1」而不是「行数」：取消收藏/移除中间的歌之后行数会小于 max，
     * 用行数会撞主键。
     */
    fun appendPlaylistSong(playlistId: String, songKey: String, payload: String): Boolean {
        val db = writableDatabase
        var inserted = false
        db.beginTransaction()
        try {
            val exists = db.query(
                TABLE_PL_SONG,
                arrayOf(COL_PS_POSITION),
                "$COL_PS_PLAYLIST_ID = ? AND $COL_PS_SONG_KEY = ?",
                arrayOf(playlistId, songKey),
                null,
                null,
                null,
                "1",
            ).use { it.moveToFirst() }
            if (!exists) {
                val nextPos = db.rawQuery(
                    "SELECT IFNULL(MAX($COL_PS_POSITION), -1) + 1 FROM $TABLE_PL_SONG " +
                        "WHERE $COL_PS_PLAYLIST_ID = ?",
                    arrayOf(playlistId),
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                val values = ContentValues().apply {
                    put(COL_PS_PLAYLIST_ID, playlistId)
                    put(COL_PS_POSITION, nextPos)
                    put(COL_PS_SONG_KEY, songKey)
                    put(COL_PS_PAYLOAD, payload)
                }
                db.insertWithOnConflict(TABLE_PL_SONG, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                inserted = true
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun removePlaylistSong(playlistId: String, songKey: String) {
        writableDatabase.delete(
            TABLE_PL_SONG,
            "$COL_PS_PLAYLIST_ID = ? AND $COL_PS_SONG_KEY = ?",
            arrayOf(playlistId, songKey),
        )
    }

    /** 从**所有**自建歌单里移除某一首歌（本地文件被删时用）。 */
    fun removePlaylistSongEverywhere(songKey: String) {
        writableDatabase.delete(TABLE_PL_SONG, "$COL_PS_SONG_KEY = ?", arrayOf(songKey))
    }

    fun replacePlaylistSongs(playlistId: String, songs: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_PL_SONG, "$COL_PS_PLAYLIST_ID = ?", arrayOf(playlistId))
            songs.forEachIndexed { index, (songKey, payload) ->
                val values = ContentValues().apply {
                    put(COL_PS_PLAYLIST_ID, playlistId)
                    put(COL_PS_POSITION, index)
                    put(COL_PS_SONG_KEY, songKey)
                    put(COL_PS_PAYLOAD, payload)
                }
                db.insertWithOnConflict(TABLE_PL_SONG, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ── meta（模式 / 迁移标记） ──

    fun meta(key: String): String? = readableDatabase.query(
        TABLE_META,
        arrayOf(COL_META_VAL),
        "$COL_META_KEY = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1",
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun putMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put(COL_META_KEY, key)
            put(COL_META_VAL, value)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_META,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 一次性导入旧 json 时用：整表清空（只在迁移路径里调）。 */
    fun clearFavorites(kind: String) {
        writableDatabase.delete(TABLE_FAV, "$COL_KIND = ?", arrayOf(kind))
    }

    companion object {
        private const val DB_NAME = "favorites.db"
        private const val DB_VERSION = 4

        private const val TABLE_FAV = "favorites"
        private const val TABLE_PLAYLIST = "user_playlists"
        private const val TABLE_PL_SONG = "user_playlist_songs"
        private const val TABLE_META = "meta"

        private const val IDX_FAV_KIND = "idx_fav_kind"
        private const val IDX_PS_PLAYLIST = "idx_ps_playlist"

        private const val COL_KEY = "fav_key"
        private const val COL_KIND = "kind"
        private const val COL_PLATFORM = "platform"
        private const val COL_NAME = "name"
        private const val COL_SUBTITLE = "subtitle"
        private const val COL_COVER = "cover"
        private const val COL_ITEM_ID = "item_id"
        private const val COL_DURATION = "duration"
        private const val COL_PATHS = "paths"
        private const val COL_PAYLOAD = "payload"
        private const val COL_ADDED_AT = "added_at"
        private const val COL_TRACK_COUNT = "track_count"
        private const val COL_RELEASE_DATE = "release_date"
        private const val COL_CREATOR = "creator"
        private const val COL_ARTIST_ID = "artist_id"

        private const val COL_PL_ID = "id"
        private const val COL_PL_NAME = "name"
        private const val COL_PL_SCOPE = "scope"
        private const val COL_PL_COVER = "cover"
        private const val COL_PL_CREATED_AT = "created_at"
        private const val COL_PL_COVER_BLOB = "cover_blob"

        private const val COL_PS_PLAYLIST_ID = "playlist_id"
        private const val COL_PS_POSITION = "position"
        private const val COL_PS_SONG_KEY = "song_key"
        private const val COL_PS_PAYLOAD = "payload"

        private const val COL_META_KEY = "k"
        private const val COL_META_VAL = "v"
    }

    /** `favorites` 表一行 → 原始字段（payload 未碰，交给上层解码）。 */
    private fun Cursor.toRow(): FavoriteRow = FavoriteRow(
        key = getString(getColumnIndexOrThrow(COL_KEY)),
        kind = getString(getColumnIndexOrThrow(COL_KIND)),
        platform = getString(getColumnIndexOrThrow(COL_PLATFORM)),
        name = getString(getColumnIndexOrThrow(COL_NAME)),
        subtitle = getString(getColumnIndexOrThrow(COL_SUBTITLE)),
        cover = getString(getColumnIndexOrThrow(COL_COVER)),
        itemId = getString(getColumnIndexOrThrow(COL_ITEM_ID)),
        duration = getInt(getColumnIndexOrThrow(COL_DURATION)),
        paths = getString(getColumnIndexOrThrow(COL_PATHS)),
        payload = getString(getColumnIndexOrThrow(COL_PAYLOAD)),
        addedAt = getLong(getColumnIndexOrThrow(COL_ADDED_AT)),
        trackCount = getInt(getColumnIndexOrThrow(COL_TRACK_COUNT)),
        releaseDate = getString(getColumnIndexOrThrow(COL_RELEASE_DATE)),
        creator = getString(getColumnIndexOrThrow(COL_CREATOR)),
        artistId = getString(getColumnIndexOrThrow(COL_ARTIST_ID)),
    )
}
