package com.annieplayer.annie_music.kt.data.sources

import com.annieplayer.annie_music.kt.data.model.KkboxAlbum
import com.annieplayer.annie_music.kt.data.model.KkboxTrack
import com.annieplayer.annie_music.kt.data.network.anyToJsonElement
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import kotlinx.coroutines.delay
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * KKBOX API（1:1 移植 Flutter `lib/data/sources/` 下的 kkbox_api.dart，直译 RN 版 services/platforms/kkboxApi.ts）。
 *
 * 保持与 RN 版一致的函数名、参数与返回语义，供音乐仓库层、KKBOX 详情页与下载服务调用。
 *
 * 与 RN 版的差异：
 * - 原 RN 版依赖原生模块 `KkboxDecoder` 解密音频；此处改为**纯 Kotlin RC4**
 *   （KSA + PRGA 逐字节实现，含 drop），不依赖任何原生代码 / platform channel。
 * - MD5 使用 `MessageDigest`（对 ASCII 输入与 RN 版手写 MD5 结果逐字节一致）。
 * - 文件写入使用 [java.io.File]；音频下载/解密采用分块读取 + 分块解密写文件。
 *
 * 依赖的 `data/sources/` 下同包 `decodeHtmlEntities` 供后续平台复用，本文件不重复定义。
 */

// ── 加解密常量（与 RN 版一致，不可更改） ──

/** 响应体 RC4 密钥（无 drop） */
const val kc1Key: String = "7f1a68f00b747f4ac1469c72e7ef492c"

/** 签名密钥 */
const val secretKey: String = "0ff29b7c9bd8fb60a3abd6b3d402b02c"

/** KKBOX 客户端 UA */
private const val _kkboxUa = "okhttp/3.14.9"

// ── RC4（纯 Kotlin，直译 RN 版 Rc4Stream） ──

/**
 * RC4 流密码（KSA + PRGA 逐字节），支持 drop 参数。
 *
 * 实例在多次 [process] 之间保持 S 盒与 i/j 状态，因此可分块调用以流式解密（与 RN 版行为一致）。
 *
 * 逐字节对齐说明：Dart 的 `key.codeUnits` 为 UTF-16 码元（0..65535），故这里用
 * `key[i].code` 取码元而非 UTF-8 字节；`& 0xFF` 显式掩码复刻 Dart 的 8 位回绕。
 */
private class _Rc4(key: String, drop: Int = 0) {
    private val _s = IntArray(256)
    private var _i = 0
    private var _j = 0

    init {
        val keyCodes = IntArray(key.length) { key[it].code }
        for (i in 0 until 256) {
            _s[i] = i
        }

        // KSA
        var j = 0
        for (i in 0 until 256) {
            j = (j + _s[i] + keyCodes[i % keyCodes.size]) and 0xFF
            val t = _s[i]
            _s[i] = _s[j]
            _s[j] = t
        }

        // drop
        for (n in 0 until drop) {
            _i = (_i + 1) and 0xFF
            _j = (_j + _s[_i]) and 0xFF
            val t = _s[_i]
            _s[_i] = _s[_j]
            _s[_j] = t
        }
    }

    /** PRGA：加密/解密输入（异或流密钥） */
    fun process(input: ByteArray): ByteArray {
        val output = ByteArray(input.size)
        for (n in input.indices) {
            _i = (_i + 1) and 0xFF
            _j = (_j + _s[_i]) and 0xFF
            val t = _s[_i]
            _s[_i] = _s[_j]
            _s[_j] = t
            val k = _s[(_s[_i] + _s[_j]) and 0xFF]
            output[n] = ((input[n].toInt() and 0xFF) xor k).toByte()
        }
        return output
    }
}

/** 一次性 RC4（key + data + drop） */
private fun _rc4(key: String, data: ByteArray, drop: Int = 0): ByteArray =
    _Rc4(key, drop).process(data)

// ── 签名 / 基础参数 / 请求 ──

/** MD5 十六进制小写（对齐 Dart `package:crypto` 的 `md5.convert(...).toString()`） */
private fun _md5(s: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder()
    for (b in digest) {
        val v = b.toInt() and 0xFF
        if (v < 16) sb.append('0')
        sb.append(v.toString(16))
    }
    return sb.toString()
}

/** 生成签名（timestamp 秒 + secret）。返回 (timestamp, secret) */
private fun _createSignature(): Pair<Long, String> {
    val timestamp = System.currentTimeMillis() / 1000
    val secret = _md5("06120082$timestamp$secretKey")
    return timestamp to secret
}

/** 基础请求参数（含签名；sid 非空时附带） */
private fun _getBaseParams(sid: String? = null): Map<String, String> {
    val (timestamp, secret) = _createSignature()
    val params = LinkedHashMap<String, String>()
    params["enc"] = "u"
    params["ver"] = "06120082"
    params["os"] = "android"
    params["osver"] = "13"
    params["lang"] = "en"
    params["ui_lang"] = "en"
    params["dist"] = "0021"
    params["dist2"] = "0021"
    params["resolution"] = "411x841"
    params["of"] = "j"
    params["oenc"] = "kc1"
    params["secret"] = secret
    params["timestamp"] = timestamp.toString()
    if (sid != null) params["sid"] = sid
    return params
}

/**
 * `application/x-www-form-urlencoded` 单值编码（等价 URLSearchParams）。
 *
 * 保留集 `A-Za-z0-9*-._`，空格转 `+`，其余大写十六进制。
 */
private fun _formComponent(input: String): String {
    val sb = StringBuilder()
    for (b in input.toByteArray(Charsets.UTF_8)) {
        val v = b.toInt() and 0xFF
        val isDigit = v in 0x30..0x39
        val isUpper = v in 0x41..0x5A
        val isLower = v in 0x61..0x7A
        if (isDigit || isUpper || isLower || v == 0x2A || v == 0x2D || v == 0x2E || v == 0x5F) {
            sb.append(v.toChar())
        } else if (v == 0x20) {
            sb.append('+')
        } else {
            sb.append('%')
            sb.append(v.toString(16).padStart(2, '0').uppercase())
        }
    }
    return sb.toString()
}

/** 表单编码（对齐 Dart `_formEncode`） */
private fun _formEncode(payload: Map<*, *>): String {
    val sb = StringBuilder()
    var first = true
    for ((k, v) in payload) {
        if (!first) sb.append('&')
        first = false
        sb.append(_formComponent(k.toString()))
        sb.append('=')
        sb.append(_formComponent(v.toString()))
    }
    return sb.toString()
}

/**
 * 百分号编码（对齐 Dart `Uri.encodeComponent`，保留集 `A-Za-z0-9-_.!~*'()`，大写十六进制、UTF-8）。
 *
 * `Uri.encode` / `URLEncoder` 与 Dart 语义不完全一致，故此私有实现逐字节对齐。
 */
private fun _encodeComponent(s: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
    val hexDigit = "0123456789ABCDEF"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val code = b.toInt() and 0xFF
        val c = code.toChar()
        if (unreserved.indexOf(c) >= 0) {
            sb.append(c)
        } else {
            sb.append('%')
            sb.append(hexDigit[code ushr 4])
            sb.append(hexDigit[code and 0x0F])
        }
    }
    return sb.toString()
}

/**
 * 调用 KKBOX API：拼接签名参数 → 请求 → RC4(KC1) 解密 → UTF-8 → JSON。
 *
 * 对齐 Dart 的 `validateStatus: (_) => true`：HTTP 非 2xx 也解密响应体，由业务状态码判断，
 * 故这里不校验 OkHttp 状态码。
 */
private suspend fun _apiCall(
    host: String,
    path: String,
    params: Map<String, String> = emptyMap(),
    payload: Any? = null,
): Map<String, Any?> {
    val allParams = LinkedHashMap<String, String>()
    allParams.putAll(_getBaseParams(params["sid"]))
    allParams.putAll(params)
    val qs = allParams.entries.joinToString("&") { (k, v) ->
        "${_encodeComponent(k)}=${_encodeComponent(v)}"
    }
    val url = "https://api-$host.kkbox.com.tw/$path?$qs"

    var body: String? = null
    val headers = mutableMapOf("User-Agent" to _kkboxUa)
    if (payload != null) {
        if (payload is String) {
            body = payload
        } else if (payload is Map<*, *>) {
            body = _formEncode(payload)
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        }
    }

    val bytes = retryUntilSuccess {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        // 对齐 Dart：payload != null 即 POST（即使 body 为 null）
        if (payload != null) {
            builder.method("POST", body?.toRequestBody(null))
        } else {
            builder.method("GET", null)
        }
        val resp = globalOkHttp.newCall(builder.build()).execute()
        resp.use { r -> r.body?.bytes() ?: ByteArray(0) }
    }

    val decrypted = _rc4(kc1Key, bytes)
    val decoded = jsonElementToAny(json.parseToJsonElement(String(decrypted, Charsets.UTF_8)))
    if (decoded is Map<*, *>) {
        return decoded.entries.associate { (k, v) -> k.toString() to v }
    }
    throw IllegalStateException("响应格式错误")
}

// ── URL 解析 ──

/** KKBOX URL 解析结果（RN 版 `{ type, id }`） */
data class KkboxUrlInfo(
    /** 'song' | 'album' */
    val type: String,
    val id: String,
)

/** 解析 KKBOX 分享链接 / 裸 id */
fun parseKkboxUrl(text: String): KkboxUrlInfo? {
    val patterns = listOf(
        Regex("/song[/-]([A-Za-z0-9\\-_\\.]+)") to "song",
        Regex("/album[/-]([A-Za-z0-9\\-_\\.]+)") to "album",
        Regex("/playlist[/-]([A-Za-z0-9\\-_\\.]+)") to "song",
    )
    for ((re, type) in patterns) {
        val m = re.find(text) ?: continue
        val id = m.groupValues.getOrNull(1) ?: continue
        return KkboxUrlInfo(type = type, id = id)
    }
    val trimmed = text.trim()
    if (Regex("^[A-Za-z0-9\\-_\\.]+$").matches(trimmed)) {
        return KkboxUrlInfo(type = "song", id = trimmed)
    }
    return null
}

// ── 音质映射 ──

private val _qualityFormatMap: Map<String, String> = mapOf(
    "128k" to "mp3_128k_chromecast",
    "192k" to "mp3_192k_kkdrm1",
    "320k" to "aac_320k_m4a_kkdrm1",
    "hifi" to "flac_16_download_kkdrm",
    "hires" to "flac_24_download_kkdrm",
)
private val _qualityOrder: List<String> = listOf("hires", "hifi", "320k", "192k", "128k")
private val _formatToQuality: Map<String, String> =
    _qualityFormatMap.entries.associate { (k, v) -> v to k }

// ── 会话管理 ──

/** 登录结果 */
data class KkboxLoginResult(
    val sid: String,
    val licContentKey: String,
    val highQuality: Boolean,
    val kkid: String,
)

/** 续期结果 */
data class KkboxSessionInfo(
    val sid: String,
    val licContentKey: String,
    val highQuality: Boolean,
)

/** 登录 KKBOX，返回会话信息（sid / licContentKey / highQuality / kkid） */
suspend fun login(email: String, password: String): KkboxLoginResult {
    val rnd = Random.Default
    val kkid = List(32) { rnd.nextInt(16).toString(16) }.joinToString("").uppercase()
    val passwd = _md5(password)
    val resp = _apiCall(
        "login",
        "login.php",
        payload = mapOf(
            "uid" to email,
            "passwd" to passwd,
            "kkid" to kkid,
            "registration_id" to "",
        ),
    )
    val status = _asInt(resp["status"])
    if (status != 2 && status != 3) {
        val errors = mapOf(
            -1 to "邮箱不存在",
            -2 to "密码错误",
            -4 to "IP 地区不支持，请使用 VPN",
            1 to "账户已过期",
        )
        throw IllegalStateException(errors[status] ?: "登录失败: $status")
    }
    return KkboxLoginResult(
        sid = _asString(resp["sid"]),
        licContentKey = _asString(resp["lic_content_key"]),
        highQuality = _truthy(resp["high_quality"]),
        kkid = kkid,
    )
}

/** 续期会话 */
suspend fun renewSession(sid: String): KkboxSessionInfo {
    val resp = _apiCall("login", "check.php", params = mapOf("sid" to sid))
    val status = _asInt(resp["status"])
    if (status != 2 && status != 3) throw IllegalStateException("会话续期失败")
    return KkboxSessionInfo(
        sid = _asString(resp["sid"]),
        licContentKey = _asString(resp["lic_content_key"]),
        highQuality = _truthy(resp["high_quality"]),
    )
}

/** 设备认证 */
suspend fun authDevice(sid: String, kkid: String) {
    val resp = _apiCall(
        "ds",
        "active_sid.php",
        params = mapOf("sid" to sid),
        payload = mapOf(
            "ui_lang" to "en",
            "of" to "j",
            "os" to "android",
            "enc" to "u",
            "sid" to sid,
            "ver" to "06120082",
            "kkid" to kkid,
            "lang" to "en",
            "oenc" to "kc1",
            "osver" to "13",
        ),
    )
    if (_asInt(resp["status"]) != 1) throw IllegalStateException("设备认证失败")
}

/**
 * 登出。
 *
 * RN 版 kkboxApi.ts 无服务端登出接口，会话清理由 store 层负责；
 * 此处保留以对齐会话管理 API 面（无副作用）。
 */
suspend fun logout() {
    // 无服务端接口：与 RN 版一致，无副作用
}

// ── 歌曲 / 歌词 / 专辑 ──

/** 歌词结果（对齐 Dart `({String lrc, String? tlyric})`，翻译恒为 null） */
data class KkboxLyric(
    val lrc: String,
    val tlyric: String?,
)

/** 按 id 批量获取歌曲原始 json（映射请用 [mapSongToKkboxTrack]） */
suspend fun getSongs(sid: String, ids: List<String>): List<Map<String, Any?>> {
    val resp = _apiCall(
        "ds",
        "v2/song",
        params = mapOf("sid" to sid),
        payload = mapOf(
            "ids" to ids.joinToString(","),
            "fields" to "song_id,song_name,artist_name,album_name,artist_role,song_idx," +
                "album_photo_info,song_is_explicit,song_more_url,album_more_url,artist_more_url," +
                "genre_name,is_lyrics,audio_quality,album_date",
        ),
    )
    if (_statusType(resp) != "OK") throw IllegalStateException("获取歌曲信息失败")
    val data = resp["data"]
    val songs = if (data is Map<*, *>) data["songs"] else null
    if (songs is List<*>) {
        return songs.filterIsInstance<Map<*, *>>()
            .map { e -> e.entries.associate { (k, v) -> k.toString() to v } }
    }
    return emptyList()
}

/** 获取歌词（原始 lrc + 翻译歌词，翻译恒为 null，对齐 RN 版） */
suspend fun getSongLyrics(sid: String, songId: String): KkboxLyric {
    val resp = _apiCall("ds", "v1/song/$songId/lyrics", params = mapOf("sid" to sid))
    if (_statusType(resp) != "OK") {
        return KkboxLyric(lrc = "[00:00.00]暂无歌词", tlyric = null)
    }
    val data = resp["data"]
    val items = if (data is Map<*, *>) data["lyrics"] else null
    val list: List<Any?> = if (items is List<*>) items else emptyList()

    val sb = StringBuilder()
    for (item in list) {
        if (item !is Map<*, *>) continue
        val content = item["content"]
        if (!_truthy(content)) {
            sb.append('\n')
            continue
        }
        val timeMs = _asInt(item["start_time"])
        val min = timeMs / 60000
        val sec = (timeMs / 1000) % 60
        val ms = timeMs % 1000
        sb.append('[')
            .append(min.toString().padStart(2, '0'))
            .append(':')
            .append(sec.toString().padStart(2, '0'))
            .append('.')
            .append(ms.toString().padStart(3, '0'))
            .append(']')
            .append(content)
            .append('\n')
    }
    val lrc = sb.toString()
    return KkboxLyric(
        lrc = if (lrc.isNotEmpty()) lrc else "[00:00.00]暂无歌词",
        tlyric = null,
    )
}

/** 专辑详情原始数据（album + songs 原始 json） */
data class KkboxAlbumData(
    val album: Map<String, Any?>,
    val songs: List<Map<String, Any?>>,
)

    /** 获取专辑信息与歌曲列表 */
    suspend fun getKkboxAlbum(sid: String, albumId: String): KkboxAlbumData {
    val resp = _apiCall("ds", "v1/album/$albumId", params = mapOf("sid" to sid))
    if (_statusType(resp) != "OK") throw IllegalStateException("获取专辑信息失败")
    val data = resp["data"]
    val albumRaw = if (data is Map<*, *>) data["album"] else null
    val album = if (albumRaw is Map<*, *>) {
        albumRaw.entries.associate { (k, v) -> k.toString() to v }
    } else {
        emptyMap()
    }
    val songsRaw = if (data is Map<*, *>) data["songs"] else null
    val songs = if (songsRaw is List<*>) {
        songsRaw.filterIsInstance<Map<*, *>>()
            .map { e -> e.entries.associate { (k, v) -> k.toString() to v } }
    } else {
        emptyList()
    }
    return KkboxAlbumData(album = album, songs = songs)
}

// ── 播放票据 / 直链 ──

/** 播放票据结果（uris + 可能被续期后的 sid） */
data class KkboxTicket(
    val uris: List<Map<String, Any?>>,
    val sid: String,
)

/** 获取播放票据，含 -1/-4/2/-2 状态码重试与续期逻辑 */
suspend fun getTicket(
    sid: String,
    songId: String,
    kkid: String,
    playMode: String? = null,
    retried: Boolean = false,
): KkboxTicket {
    val timestamp = System.currentTimeMillis() / 1000
    val payload = anyToJsonElement(
        mapOf<String, Any?>(
            "sid" to sid,
            "song_id" to songId,
            "ver" to "06120082",
            "os" to "android",
            "osver" to "13",
            "kkid" to kkid,
            "dist" to "0021",
            "dist2" to "0021",
            "timestamp" to timestamp,
            "play_mode" to playMode,
        ),
    ).toString()
    val resp = _apiCall("ticket", "v1/ticket", params = mapOf("sid" to sid), payload = payload)

    val status = _asInt(resp["status"])
    if (status != 1) {
        if (status == -1) {
            val renewed = renewSession(sid)
            return getTicket(renewed.sid, songId, kkid, playMode)
        }
        if (status == -4) {
            authDevice(sid, kkid)
            return getTicket(sid, songId, kkid, playMode)
        }
        if (status == 2) {
            delay(500L)
            return getTicket(sid, songId, kkid, playMode)
        }
        if (status == -2) {
            if (retried) {
                val js = anyToJsonElement(resp).toString()
                throw IllegalStateException(
                    "获取播放票据失败(status=-2): " +
                        (if (js.length > 200) js.substring(0, 200) else js),
                )
            }
            return getTicket(sid, songId, kkid, playMode, true)
        }
        if (retried) throw IllegalStateException("获取播放票据失败(status=$status)")
        return getTicket(sid, songId, kkid, playMode, true)
    }

    val urisRaw = resp["uris"]
    val uris = if (urisRaw is List<*>) {
        urisRaw.filterIsInstance<Map<*, *>>()
            .map { e -> e.entries.associate { (k, v) -> k.toString() to v } }
    } else {
        emptyList()
    }
    return KkboxTicket(uris = uris, sid = sid)
}

/** 播放直链结果 */
data class KkboxPlayResult(
    val url: String,
    val sid: String,
)

private fun _firstUri(
    uris: List<Map<String, Any?>>,
    test: (Map<String, Any?>) -> Boolean,
): Map<String, Any?>? {
    for (u in uris) {
        if (test(u)) return u
    }
    return null
}

private fun _uriUrl(u: Map<String, Any?>?): String =
    if (u == null) "" else (u["url"] ?: "").toString()

private fun _uriName(u: Map<String, Any?>?): String =
    if (u == null) "" else (u["name"] ?: "").toString()

/** 获取播放直链（优先 mp3_128k_chromecast，否则含 chromecast 的，再次取首个） */
suspend fun getPlayUrl(sid: String, songId: String, kkid: String): KkboxPlayResult {
    val ticket = getTicket(sid, songId, kkid, "chromecast")
    val formatName = "mp3_128k_chromecast"
    val uris = ticket.uris
    val found = _firstUri(uris) { _uriName(it) == formatName }

    var url: String
    if (found != null) {
        url = _uriUrl(found)
    } else {
        val cc = _firstUri(uris) { _uriName(it).contains("chromecast") }
        url = _uriUrl(cc)
        if (url.isEmpty()) url = if (uris.isNotEmpty()) _uriUrl(uris.first()) else ""
    }
    return KkboxPlayResult(url = url, sid = ticket.sid)
}

/** 下载直链结果 */
data class KkboxDownloadResult(
    val url: String,
    val formatName: String,
    val sid: String,
)

/** 获取下载直链（按 quality 请求，缺失时按 [_qualityOrder] 降级） */
suspend fun getDownloadUrl(
    sid: String,
    songId: String,
    kkid: String,
    quality: String,
): KkboxDownloadResult {
    val requestedFormat = _qualityFormatMap[quality] ?: "flac_24_download_kkdrm"
    val playMode = if (requestedFormat == "mp3_128k_chromecast") "chromecast" else null
    val ticket = getTicket(sid, songId, kkid, playMode)
    val uris = ticket.uris

    val found = _firstUri(uris) { _uriName(it) == requestedFormat }
    if (found != null) {
        return KkboxDownloadResult(
            url = _uriUrl(found),
            formatName = _uriName(found),
            sid = ticket.sid,
        )
    }
    for (q in _qualityOrder) {
        if (q == quality) continue
        val fn = _qualityFormatMap[q] ?: continue
        val f = _firstUri(uris) { _uriName(it) == fn }
        if (f != null) {
            return KkboxDownloadResult(
                url = _uriUrl(f),
                formatName = _uriName(f),
                sid = ticket.sid,
            )
        }
    }
    return KkboxDownloadResult(
        url = if (uris.isNotEmpty()) _uriUrl(uris.first()) else "",
        formatName = if (uris.isNotEmpty()) _uriName(uris.first()) else "",
        sid = ticket.sid,
    )
}

// ── 音频解密 / 下载（纯 Kotlin，替代原生 KkboxDecoder） ──

/** RC4 drop=512 解密音频数据 */
fun decryptAudio(data: ByteArray, licContentKey: String): ByteArray =
    _rc4(licContentKey, data, 512)

/** 下载并解密音频，返回解密后的字节（Range: bytes=1024- 跳过 KKBOX 包裹头） */
suspend fun downloadKkboxAudio(url: String, licContentKey: String): ByteArray {
    val pair = retryUntilSuccess {
        val req = Request.Builder().url(url)
            .header("User-Agent", _kkboxUa)
            .header("Range", "bytes=1024-")
            .build()
        val resp = globalOkHttp.newCall(req).execute()
        resp.use { r -> r.code to (r.body?.bytes() ?: ByteArray(0)) }
    }
    val status = pair.first
    if (status < 200 || status >= 300) throw IllegalStateException("下载失败: $status")
    return decryptAudio(pair.second, licContentKey)
}

/**
 * 流式下载 + RC4(drop=512) 分块解密写文件（纯 Kotlin，无原生）。
 *
 * [onProgress] 回调范围 0..1，语义对齐 RN 版 JS 回退分支。
 */
suspend fun streamDecryptKkboxAudio(
    url: String,
    licContentKey: String,
    outputPath: String,
    onProgress: ((Double) -> Unit)? = null,
) {
    onProgress?.invoke(0.1)

    val path = if (outputPath.startsWith("file://")) outputPath.substring(7) else outputPath
    val file = File(path)
    val parent = file.parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }

    val resp = retryUntilSuccess {
        val req = Request.Builder().url(url)
            .header("User-Agent", _kkboxUa)
            .header("Range", "bytes=1024-")
            .build()
        globalOkHttp.newCall(req).execute()
    }
    resp.use { r ->
        val status = r.code
        if (status < 200 || status >= 300) throw IllegalStateException("下载失败: $status")

        val total = r.header("Content-Length")?.toLongOrNull() ?: 0L
        val rc4 = _Rc4(licContentKey, 512)
        val out = file.outputStream()
        var received = 0
        try {
            val input = r.body?.byteStream()
            if (input != null) {
                input.use { ins ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buffer)
                        if (n <= 0) break
                        val chunk = if (n == buffer.size) buffer.copyOf() else buffer.copyOf(n)
                        out.write(rc4.process(chunk))
                        received += n
                        if (total > 0) {
                            onProgress?.invoke(
                                0.4 + 0.5 * (received.toDouble() / total.toDouble()),
                            )
                        }
                    }
                }
            }
        } finally {
            out.flush()
            out.close()
        }
    }
    onProgress?.invoke(1.0)
}

// ── 格式 / 封面 / 映射 ──

/** 格式名 → 音质 key（未知默认 128k） */
fun formatNameToQuality(formatName: String): String =
    _formatToQuality[formatName] ?: "128k"

/** 是否 DRM 格式（非 mp3_128k_chromecast 即 DRM，需解密） */
fun isDrmFormat(formatName: String): Boolean =
    formatName != "mp3_128k_chromecast"

/** 音质 → 文件扩展名 */
fun qualityExtension(quality: String): String {
    if (quality == "hifi" || quality == "hires") return ".flac"
    if (quality == "320k") return ".m4a"
    return ".mp3"
}

/** 拼接封面 URL（替换模板占位符、去除逗号多规格、扩展名转小写） */
fun getCoverUrl(urlTemplate: String, size: Int = 2048): String {
    var url = urlTemplate
        .replace("{width}", size.toString())
        .replace("{height}", size.toString())
        .replace("{format}", "jpg")
    if (url.contains(",")) {
        val parts = url.split("/").toMutableList()
        val imgRe by lazy { Regex("\\.(jpg|jpeg|png)", RegexOption.IGNORE_CASE) }
        for (i in parts.indices) {
            if (parts[i].contains(",") && imgRe.containsMatchIn(parts[i])) {
                parts[i] = parts[i].split(",").first()
            }
        }
        url = parts.joinToString("/")
    }
    url = Regex("\\.(JPG|JPEG|PNG)").replace(url) { it.value.lowercase() }
    return url
}

/** 原始歌曲 json → [KkboxTrack] */
fun mapSongToKkboxTrack(raw: Map<String, Any?>): KkboxTrack {
    val photo = raw["album_photo_info"]
    val coverTemplate = (if (photo is Map<*, *>) photo["url_template"] else null)?.toString() ?: ""
    val songMoreUrl = (raw["song_more_url"] ?: "").toString()
    var songId = ""
    if (songMoreUrl.isNotEmpty()) {
        val parts = songMoreUrl.split("/")
        songId = if (parts.isNotEmpty()) parts.last() else ""
    }
    if (songId.isEmpty()) {
        songId = if (raw["song_id"] != null) raw["song_id"].toString() else ""
    }

    val artistRole = raw["artist_role"]
    val mainArtists = if (artistRole is Map<*, *>) artistRole["mainartists"] else null
    val artist: String = if (_truthy(raw["artist_name"])) {
        raw["artist_name"].toString()
    } else if (mainArtists is List<*> && mainArtists.isNotEmpty() && _truthy(mainArtists.first())) {
        mainArtists.first().toString()
    } else {
        ""
    }

    val albumId = if (raw["album_id"] != null) raw["album_id"].toString() else ""
    val releaseDate = if (_truthy(raw["album_date"])) raw["album_date"].toString() else null

    val qualities = ArrayList<String>()
    val aq = raw["audio_quality"]
    if (aq is List<*>) {
        for (q in aq) {
            qualities.add(q.toString())
        }
    }

    return KkboxTrack(
        id = songId,
        encryptedId = _asString(raw["encrypted_song_id"]),
        title = (raw["song_name"] ?: "").toString(),
        artist = artist,
        cover = if (coverTemplate.isNotEmpty()) getCoverUrl(coverTemplate) else "",
        album = (raw["album_name"] ?: "").toString(),
        albumId = albumId,
        albumArtist = artist,
        trackNumber = _asInt(raw["song_idx"]),
        releaseDate = releaseDate,
        duration = _asInt(raw["duration_ms"]) / 1000,
        qualities = qualities,
    )
}

/** 原始专辑 json + 歌曲 json 列表 → [KkboxAlbum] */
fun mapAlbumToKkboxAlbum(
    raw: Map<String, Any?>,
    rawSongs: List<Map<String, Any?>>,
): KkboxAlbum {
    val rawAlbum = raw["album"]
    val album: Map<String, Any?> = if (rawAlbum is Map<*, *>) {
        rawAlbum.entries.associate { (k, v) -> k.toString() to v }
    } else {
        raw
    }

    val photo = album["album_photo_info"]
    val coverTemplate = (if (photo is Map<*, *>) photo["url_template"] else null)?.toString() ?: ""
    val albumCover = if (coverTemplate.isNotEmpty()) getCoverUrl(coverTemplate) else ""
    val albumArtist = (album["artist_name"] ?: "").toString()
    val albumName = (album["album_name"] ?: "").toString()

    val tracks = rawSongs.map { s ->
        val t = mapSongToKkboxTrack(s)
        KkboxTrack(
            id = t.id,
            encryptedId = t.encryptedId,
            title = t.title,
            artist = if (t.artist.isNotEmpty()) t.artist else albumArtist,
            cover = if (t.cover.isNotEmpty()) t.cover else albumCover,
            album = if (t.album.isNotEmpty()) t.album else albumName,
            albumId = t.albumId,
            albumArtist = t.albumArtist,
            trackNumber = t.trackNumber,
            duration = t.duration,
            qualities = t.qualities,
            releaseDate = t.releaseDate,
        )
    }

    return KkboxAlbum(
        id = (album["album_id"] ?: "").toString(),
        encryptedId = _asString(album["encrypted_album_id"]),
        title = albumName,
        artist = albumArtist,
        cover = albumCover,
        year = (album["album_date"] ?: "").toString(),
        tracks = tracks,
    )
}

/** 可用音质列表（缺省 ['128k']） */
fun getAvailableQualities(song: Map<String, Any?>): List<String> {
    val q = song["audio_quality"]
    if (q is List<*>) return q.map { it.toString() }
    return listOf("128k")
}

// ── 内部辅助 ──

private fun _statusType(resp: Map<String, Any?>): String? {
    val s = resp["status"]
    return if (s is Map<*, *>) s["type"]?.toString() else null
}

/** JS 真值语义：null / false / 0 / '' / NaN 为假，其余为真 */
private fun _truthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
    is String -> value.isNotEmpty()
    else -> true
}

/** 取整（对齐 Dart `_asInt`：int / num 直取，字符串 tryParse，其余 0） */
private fun _asInt(value: Any?): Int = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Double -> value.toInt()
    is Float -> value.toInt()
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}

/** 转字符串（null -> ''） */
private fun _asString(value: Any?): String = value?.toString() ?: ""
