package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.sources.common.signParams

// ================================================================================================
// 酷狗「怎么跟服务端说话」：设备 MID + 公开 JSON 的 UA + android 签名 POST
// ================================================================================================
//
// 只放**传输层**（设备身份 / 签名 / 请求头），不放业务：业务在本包的业务文件
// （Playlist / Lyric / Album / Search / Artist / Play / SongMeta …）与
// `data.sources.home.KugouHome.kt` 里。
//
// 为什么放在平台包里而不是用途包里：主页要发同样口径的签名请求，但它属于「用途」；
// 依赖方向只能是 用途包 → 平台包。主页用自己的私有副本就等于把传输层又抄了一遍
// （历史上正是如此：签名 POST 在主页文件里被复制了一份）。

/** `mobilecdn.kugou.com` 系列（公开 JSON，不需要签名）的 UA。 */
internal val kugouPlainHeaders: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
)

/**
 * 酷狗设备 MID：`md5(随机 guid)` 当作 16 进制大整数转十进制。
 *
 * 与 `KuGouMusicApi` 的 `calculateMid` 一致；服务端只要求格式合法，实测随机值即可通过。
 */
internal fun kugouMid(): String {
    val guid = java.util.UUID.randomUUID().toString()
    val md = java.security.MessageDigest.getInstance("MD5").digest(guid.toByteArray())
    return java.math.BigInteger(1, md).toString()
}

/**
 * 酷狗 `gateway.kugou.com` 的 android 签名 POST。
 *
 * 签名 = `md5(keyA + 排序后的 k=v 串 + body + keyA)`，与解析专辑曲目用的是同一套
 * （`signParams`）。头部里 `kg-*` 那几个是服务端识别客户端来源用的，缺了会被拒。
 *
 * @param extraParams 业务参数（会与 `dfid/mid/uuid/appid/clientver/clienttime` 合并后一起参与签名）
 */
internal suspend fun kugouSignedPost(
    path: String,
    extraParams: Map<String, String>,
    body: String,
    mid: String = kugouMid(),
): Map<String, Any?> {
    val clientTime = (System.currentTimeMillis() / 1000).toString()
    val params = linkedMapOf(
        "dfid" to "-",
        "mid" to mid,
        "uuid" to "-",
        "appid" to "1005",
        "clientver" to "20489",
        "clienttime" to clientTime,
    )
    params.putAll(extraParams)
    val signature = signParams(params, kugouKeyA, body)
    val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
    return httpPostJson(
        "https://gateway.kugou.com$path?$query&signature=$signature",
        mapOf(
            "dfid" to "-",
            "clienttime" to clientTime,
            "mid" to mid,
            "kg-rc" to "1",
            "kg-thash" to "5d816a0",
            "kg-rec" to "1",
            "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
            "User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi",
            "Content-Type" to "application/json",
        ),
        body,
    )
}

/**
 * 酷狗 web 签名 GET（对齐 musicfree `webSign`）：1058 参数集 + web 盐（[kugouKeyB]）+ `KG-*` 头。
 *
 * 与 [kugouSignedPost] 的区别只在参数集与请求头：这条走网页版客户端身份，搜索/歌手接口只认它。
 */
internal suspend fun kugouWebSignGet(
    url: String,
    extraParams: List<String>,
    page: Int = 1,
): Map<String, Any?> {
    val mid = System.currentTimeMillis().toString()
    val all = listOf(
        "dfid=-",
        "mid=$mid",
        "uuid=$mid",
        "appid=1058",
        "srcappid=2919",
        "clientver=1000",
        "clienttime=$mid",
        "pagesize=30",
        "page=$page",
        "userid=440908392",
        "token=f7524337c1ae877929a1497cf3d5d37e5c4cb8073fc298e492a67babc376a9d4",
    ) + extraParams
    val map = all.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val sig = signParams(map, kugouKeyB)
    return httpGetJson(
        "$url?${all.joinToString("&")}&signature=$sig",
        headers = mapOf(
            "dfid" to "-",
            "mid" to mid,
            "clienttime" to mid,
            "User-Agent" to "Android712-AndroidPhone-10518-18-0-NetMusic-wifi",
            "KG-FAKE" to "440908392",
            "KG-THash" to "3e5ec6b",
            "KG-Tid" to "1",
            "KG-Rec" to "1",
            "KG-RC" to "1",
            "KG-RF" to "00869891",
        ),
    )
}
