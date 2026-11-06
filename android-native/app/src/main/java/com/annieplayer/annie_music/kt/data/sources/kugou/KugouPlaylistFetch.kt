package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.sources.common.signParams

// ================================================================================================
// 酷狗歌单底层取数（GCID 解码 / 分页 / 数字 id 的 HTML 兜底）
// ================================================================================================

private val _reGlobalData by lazy {
    Regex(
        "global\\.data\\s*=\\s*(\\[.*?\\]);",
        RegexOption.DOT_MATCHES_ALL,
    )
}
private val _reName by lazy { Regex("name:\\s*\"([^\"]*)\"") }
private val _rePic by lazy { Regex("pic:\\s*\"([^\"]*)\"") }

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

internal suspend fun fetchSongPage(
    globalId: String,
    page: Int = 1,
    pageSize: Int = 300,
): Map<String, Any?> {
    val ct = System.currentTimeMillis().toString()
    val params = linkedMapOf(
        "appid" to "1058",
        "clienttime" to ct,
        "clientver" to "20000",
        "global_specialid" to globalId,
        "mid" to ct,
        "page" to "$page",
        "pagesize" to "$pageSize",
        "plat" to "0",
        "specialid" to "0",
        "srcappid" to "2919",
        "uuid" to ct,
        "version" to "8000",
    )
    val sig = signParams(params, kugouKeyB)
    val qs = params.entries.joinToString("&") { "${it.key}=${it.value}" }
    val url =
        "https://mobiles.kugou.com/api/v5/special/song_v2?$qs&signature=$sig"
    val data = httpGetJson(
        url,
        headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0)",
            "Referer" to "https://m3ws.kugou.com/share/index.php",
            "mid" to ct,
            "dfid" to "-",
            "clienttime" to ct,
        ),
    )
    if (!kugouNumEq(data["status"], 1)) {
        throw IllegalStateException("fetch songs failed")
    }
    val d = data["data"]
    return kugouAsMap(d) ?: emptyMap()
}

internal suspend fun fetchPlaylistInfo(globalId: String): KugouPlaylistInfo {
    val ct = System.currentTimeMillis()
    val mid = System.currentTimeMillis()
    val uuid = System.currentTimeMillis()
    val params =
        "appid=1058&specialid=0&global_specialid=$globalId&format=jsonp" +
            "&srcappid=2919&clientver=20000&clienttime=$ct&mid=$mid&uuid=$uuid&dfid=-"
    val paramMap = linkedMapOf<String, String>()
    for (p in params.split("&")) {
        val idx = p.indexOf('=')
        if (idx >= 0) paramMap[p.substring(0, idx)] = p.substring(idx + 1)
    }
    val sig = signParams(paramMap, kugouKeyB)
    val url =
        "https://mobiles.kugou.com/api/v5/special/info_v2?$params&signature=$sig"
    try {
        val data = httpGetJson(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0)",
                "Referer" to "https://m3ws.kugou.com/share/index.php",
                "mid" to "0",
                "dfid" to "-",
                "clienttime" to "0",
            ),
        )
        if (kugouNumEq(data["status"], 1)) {
            val info = data["data"]
            if (info is Map<*, *>) {
                return KugouPlaylistInfo(
                    name = kugouFirstNonEmpty(
                        listOf(info["specialname"], info["name"]),
                    ),
                    cover = kugouFirstNonEmpty(
                        listOf(info["imgurl"], info["img"], info["pic"]),
                    ).replace("{size}", "800"),
                )
            }
        }
    } catch (_: Throwable) {
        // 与 Dart 一致：忽略并返回空
    }
    return KugouPlaylistInfo("", "")
}

/** 数字 id 路径：老版 HTML 页面里藏着 `global.data = [...]` */
internal suspend fun fetchSpecialPage(pid: String): KugouSpecialPage {
    val url =
        "https://www2.kugou.kugou.com/yueku/v9/special/single/$pid-5-9999.html" +
            "?_=${System.currentTimeMillis()}"
    val html = httpGetText(
        url,
        headers = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36",
            "Accept" to "application/json",
        ),
    )
    val songMatch = _reGlobalData.find(html)
        ?: return KugouSpecialPage("", "", emptyList())
    val songs = jsonElementToAny(
        json.parseToJsonElement(songMatch.groupValues[1]),
    )
    val songsList = if (songs is List<*>) songs.map { it } else emptyList<Any?>()
    val nameMatch = _reName.find(html)
    val coverMatch = _rePic.find(html)
    return KugouSpecialPage(
        name = nameMatch?.groupValues?.getOrNull(1) ?: "",
        cover = (coverMatch?.groupValues?.getOrNull(1) ?: "")
            .replace("{size}", "800"),
        songs = songsList,
    )
}
