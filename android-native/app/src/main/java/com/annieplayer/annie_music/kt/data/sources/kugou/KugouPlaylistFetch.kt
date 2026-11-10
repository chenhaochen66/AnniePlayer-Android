package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.sources.common.signParams

// ================================================================================================
// 酷狗歌单底层取数（GCID 解码 + 歌单详情分页）
// ================================================================================================
//
// 歌单详情只有一条路：`gateway.kugou.com/pubsongs/v2/get_other_list_file_nofilt`。
// 它一次给齐三样：歌单元信息（`list_info`）、曲目、**每首歌的 `singerinfo[]`**
// （歌手 id / 名 / 头像）。老的 `special/song_v2` 连歌手字段都没有，那条路整段已删除，
// 所以「歌单里的歌点不进歌手页」这个缺口在这条链路上不存在。
//
// ## 签名
// `signature = md5(SECRET + sorted("k=v") 无分隔拼接 + SECRET)`，SECRET = [kugouPlaylistSecret]。
// 它**不是** keyA / keyB —— 那两个是别的接口的盐，混用会得到完全不同的值。
// 请求头**只需要 UA**：实测把 `mid` / `dfid` / `KG-*` 全去掉，响应一字不差。
//
// ## 两个 id
// 分享链接给的是 gcid 短码（先 [decodeGcid] 换成 `collection_…`），纯数字链接给的是
// `specialid`。**传哪一个都能出结果**（两个都不传才是错的），所以两条入口共用一个函数。
//
// ## 分页
// `begin_idx` 是偏移，`pagesize` 实测可到 300。先要 300，按响应里的 `count` 决定续不续。

private const val kugouPlaylistDetailUrl =
    "https://gateway.kugou.com/pubsongs/v2/get_other_list_file_nofilt"

/** 这条接口只认这一档 UA（概念版客户端），换成别的虽然也能过，但保持与抓包一致 */
private const val kugouPlaylistDetailUa = "Android16-1070-11450-201-0-playlist-wifi"

/** 单页条数：实测 300 可用，229 首的歌单一次拉完 */
internal const val kugouPlaylistPageSize = 300

/** 最多翻几页（防 `count` 撒谎时死循环） */
private const val kugouPlaylistMaxPages = 20

/**
 * 歌单详情请求的参数表（**纯函数**，签名与分页都建立在它上面，可单测）。
 *
 * 返回的是 `LinkedHashMap`，但签名会在 `signParams` 里重新按 key 字典序排，所以顺序无所谓。
 * 两个 id 只加实际有值的那个 —— 空串参数会改变签名结果。
 */
internal fun kugouPlaylistDetailParams(
    globalCollectionId: String,
    specialId: String,
    beginIdx: Int,
    pageSize: Int,
): LinkedHashMap<String, String> {
    val params = linkedMapOf(
        "appid" to "3116",
        "area_code" to "1",
        "begin_idx" to beginIdx.toString(),
        "clientver" to "11450",
        "mode" to "1",
        "module" to "CloudMusic",
        "need_rd" to "0",
        "need_sort" to "1",
        "pagesize" to pageSize.toString(),
        "type" to "0",
        "userid" to "0",
    )
    if (globalCollectionId.isNotEmpty()) params["global_collection_id"] = globalCollectionId
    if (specialId.isNotEmpty()) params["specialid"] = specialId
    return params
}

/**
 * 拉一整张歌单，返回**合并了所有页**的 `data`（含 `list_info` 与 `songs`）。
 *
 * 第一页失败返回 null；后续页失败保留已拿到的部分 —— 一页抖动不该让整张歌单打不开。
 */
internal suspend fun fetchKugouPlaylistDetail(
    globalCollectionId: String,
    specialId: String,
): Map<String, Any?>? {
    val songs = ArrayList<Any?>()
    var first: Map<String, Any?>? = null
    var begin = 0
    for (page in 0 until kugouPlaylistMaxPages) {
        val data = fetchKugouPlaylistDetailPage(globalCollectionId, specialId, begin) ?: break
        if (first == null) first = data
        val pageSongs = data["songs"] as? List<*> ?: emptyList<Any?>()
        if (pageSongs.isEmpty()) break
        songs.addAll(pageSongs)
        val total = (data["count"] as? Number)?.toInt() ?: 0
        if (total <= songs.size) break
        begin += pageSongs.size
    }
    val head = first ?: return null
    return head + ("songs" to songs)
}

/** 单页；`error_code != 0` 或网络失败返回 null */
private suspend fun fetchKugouPlaylistDetailPage(
    globalCollectionId: String,
    specialId: String,
    beginIdx: Int,
): Map<String, Any?>? {
    val params = kugouPlaylistDetailParams(
        globalCollectionId = globalCollectionId,
        specialId = specialId,
        beginIdx = beginIdx,
        pageSize = kugouPlaylistPageSize,
    )
    val signature = signParams(params, kugouPlaylistSecret)
    val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
    val resp = runCatching {
        httpGetJson(
            "$kugouPlaylistDetailUrl?$query&signature=$signature",
            headers = mapOf("User-Agent" to kugouPlaylistDetailUa),
        )
    }.getOrNull() ?: return null
    if (!kugouNumEq(resp["error_code"], 0)) return null
    return kugouAsMap(resp["data"])
}

/**
 * gcid 短码 → `global_collection_id`（`collection_{uid}_{listid}_0`）。
 *
 * gcid 是分享链接里的那一段（`gcid_abc123`），不是 `collection_…` 本身：
 * 拿 `collection_…` 再喂进来会被服务端判失败。
 */
internal suspend fun decodeGcid(gcid: String): String {
    val ct = System.currentTimeMillis().toString()
    val params = linkedMapOf(
        "appid" to "1005",
        "clienttime" to ct,
        "clientver" to "20109",
        "dfid" to "-",
        "mid" to "0",
        "uuid" to "-",
    )
    val bodyStr = "{\"ret_info\":1,\"data\":[{\"id\":\"$gcid\",\"id_type\":2}]}"
    val sig = signParams(params, kugouKeyA, bodyStr)
    val qs = params.entries.joinToString("&") { "${it.key}=${it.value}" }
    val url = "https://t.kugou.com/v1/songlist/batch_decode?$qs&signature=$sig"
    val data = httpPostJson(
        url,
        headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10)",
            "Content-Type" to "application/json",
            "Referer" to "https://m.kugou.com/",
        ),
        body = bodyStr,
    )
    if (!kugouNumEq(data["status"], 1)) throw IllegalStateException("GCID decode failed")
    val dataField = data["data"]
    val list = if (dataField is Map<*, *>) dataField["list"] else null
    if (list !is List<*> || list.isEmpty()) {
        throw IllegalStateException("GCID decode failed")
    }
    val firstMap = list.first() as? Map<*, *>
    return firstMap?.get("global_collection_id") as? String
        ?: throw IllegalStateException("GCID decode failed")
}
