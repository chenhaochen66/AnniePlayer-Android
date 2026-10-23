package com.annieplayer.annie_music.kt.features.search

import android.content.Context
import android.content.SharedPreferences
import com.annieplayer.annie_music.kt.data.model.MusicPlatform

/**
 * 搜索平台（单曲 / 专辑 / 歌手共用的那一个）的持久化。
 *
 * 需求变更记录：先做过「重启回到默认酷我」，用户实测后要求**记住上次选的平台**，
 * 所以这里落盘到 SharedPreferences（模式与 `AppleMusicSettings` 同一套：
 * `MainActivity.onCreate` 里 init，未 init 时退化成纯内存、语义不变）。
 *
 * 存的是枚举 `name`（如 `netease` / `apple`），读不到或名字变了就回退 [defaultSearchPlatform]。
 */
object SearchSettings {
    private const val PREFS = "annie_search"
    private const val KEY_PLATFORM = "platform"

    private var prefs: SharedPreferences? = null

    /** 当前平台的内存镜像（未 [init] 时就是默认值）。 */
    @Volatile
    var platform: MusicPlatform = defaultSearchPlatform
        private set

    /** 由 `MainActivity` 调用以开启持久化（对齐 `CacheService.init` 模式）。 */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs?.getString(KEY_PLATFORM, null)
        platform = MusicPlatform.entries.firstOrNull { it.name == saved } ?: defaultSearchPlatform
    }

    /** 写入内存镜像 + 落盘。 */
    fun setPlatform(value: MusicPlatform) {
        platform = value
        prefs?.edit()?.putString(KEY_PLATFORM, value.name)?.apply()
    }
}
