package com.annieplayer.annie_music.kt.data.source

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 落雪插件注册表：持有插件清单与全局开关，负责导入 / 删除 / 启停与落盘。
 *
 * - 元信息（含声明的音源）存 `annie_shared_prefs` 的 `lx-plugins`（JSON 数组）；
 * - 插件脚本存 App 私有目录 `filesDir/lx-plugins/<id>.js`（**不放外部存储**）；
 * - 全局开关 `lx-plugin-enabled` 默认 **关**，与内置音源路由链配合：插件优先、内置兜底。
 */
object LxPluginRegistry {

    private const val TAG = "LxPlugin"
    private const val PREFS_NAME = "annie_shared_prefs"
    private const val KEY_ENABLED = "lx-plugin-enabled"
    private const val KEY_PLUGINS = "lx-plugins"
    private const val DIR_NAME = "lx-plugins"
    private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private val _enabled = MutableStateFlow(true)

    /** 全局「启用落雪插件」开关（默认开；没有可用插件时仍走内置） */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _plugins = MutableStateFlow<List<LxPlugin>>(emptyList())

    /** 已导入的插件清单 */
    val plugins: StateFlow<List<LxPlugin>> = _plugins.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 需要宿主用原生 Toast 告知用户的提示（数据层不直接碰 UI） */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var lastNoticeAt = 0L

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs?.getBoolean(KEY_ENABLED, true) ?: true
        runCatching { pluginDir(ctx).mkdirs() }
        _plugins.value = loadPlugins()
    }

    // ── 开关 ──

    fun setGlobalEnabled(value: Boolean) {
        _enabled.value = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }

    /** 选中某个插件为**唯一启用**的音源（与 Qobuz Token 管理同款单选语义） */
    fun setActive(id: String) {
        _plugins.value = _plugins.value.map { it.copy(enabled = it.id == id) }
        persist()
    }

    // ── 查询 ──

    /** 该平台是否应由插件解析（开关开 + 有已启用且声明了 `musicUrl` 的插件） */
    fun pluginFor(platform: MusicPlatform): LxPlugin? {
        if (!_enabled.value) return null
        val code = LxSourceMapper.lxCodeOf(platform) ?: return null
        return _plugins.value.firstOrNull { plugin ->
            plugin.enabled && plugin.error == null &&
                plugin.sources.any { it.source == code && it.supportsMusicUrl }
        }
    }

    suspend fun readScript(plugin: LxPlugin): String =
        withContext(Dispatchers.IO) { File(pluginDir(), plugin.fileName).readText() }

    /** 当前启用且可用的插件（不看平台）—— 用于提示"是它不支持这个平台" */
    fun activePlugin(): LxPlugin? = _plugins.value.firstOrNull { it.enabled && it.error == null }

    /** 开关开着、却没有任何"启用且声明了 musicUrl"的插件 —— 即"想用插件但没加" */
    fun noUsablePlugin(): Boolean = _plugins.value.none { plugin ->
        plugin.enabled && plugin.error == null && plugin.sources.any { it.supportsMusicUrl }
    }

    /** 节流地发一条提示（避免批量下载时刷屏） */
    fun postNotice(message: String, minIntervalMs: Long = 10_000L) {
        val now = System.currentTimeMillis()
        if (now - lastNoticeAt < minIntervalMs) return
        lastNoticeAt = now
        _notices.tryEmit(message)
    }

    // ── 导入 / 删除 ──

    /** 本地导入（SAF 选中的 `.js`） */
    suspend fun importLocal(uri: Uri): LxPlugin {
        val ctx = appContext ?: error("插件系统未初始化")
        val text = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("无法读取所选文件")
        }
        Log.d(TAG, "本地导入：读到 ${text.length} 字符")
        return importScript(text)
    }

    /** 在线导入（http/https 直链） */
    suspend fun importOnline(url: String): LxPlugin {
        val target = url.trim()
        require(target.startsWith("http://") || target.startsWith("https://")) { "链接需以 http/https 开头" }
        val text = httpGetText(target)
        Log.d(TAG, "在线导入：读到 ${text.length} 字符")
        return importScript(text)
    }

    /** 落盘并加载插件，取回它声明的音源；加载失败时仍保留插件（带 [LxPlugin.error]） */
    suspend fun importScript(script: String): LxPlugin {
        val bytes = script.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "导入插件：${bytes.size} 字节")
        require(script.isNotBlank()) { "插件内容为空" }
        require(bytes.size <= MAX_SCRIPT_BYTES) { "插件文件过大（上限 2MB）" }
        val id = sha1(script).take(16)
        require(_plugins.value.none { it.id == id }) { "该插件已导入" }

        val header = LxPluginMeta.parseHeader(script)
        val fileName = "$id.js"
        val (sources, error) = try {
            LxPluginHost.inspect(id, script, fileName) to null
        } catch (e: Exception) {
            Log.w(TAG, "插件加载失败: ${e.message}", e)
            emptyList<LxPluginSource>() to (e.message ?: "插件加载失败")
        }
        Log.d(TAG, "导入结果：sources=${sources.map { it.source }} error=$error")
        val plugin = LxPlugin(
            id = id,
            name = header.name.ifEmpty { "未命名插件" },
            version = header.version,
            author = header.author,
            description = header.description,
            fileName = fileName,
            enabled = true,
            sources = sources,
            error = error,
        )
        withContext(Dispatchers.IO) { File(pluginDir(), fileName).writeText(script) }
        // 新导入的插件直接设为唯一启用（与单选语义一致）
        _plugins.value = _plugins.value.map { it.copy(enabled = false) } + plugin
        persist()
        return plugin
    }

    fun delete(id: String) {
        val plugin = _plugins.value.firstOrNull { it.id == id } ?: return
        _plugins.value = _plugins.value.filterNot { it.id == id }
        LxPluginHost.invalidate(id)
        runCatching { File(pluginDir(), plugin.fileName).delete() }
        persist()
    }

    // ── 持久化 ──

    private fun pluginDir(context: Context? = appContext): File {
        val ctx = context ?: error("插件系统未初始化")
        return File(ctx.filesDir, DIR_NAME)
    }

    private fun persist() {
        val p = prefs ?: return
        runCatching {
            val arr = buildJsonArray {
                for (plugin in _plugins.value) {
                    add(
                        buildJsonObject {
                            put("id", plugin.id)
                            put("name", plugin.name)
                            put("version", plugin.version)
                            put("author", plugin.author)
                            put("description", plugin.description)
                            put("fileName", plugin.fileName)
                            put("enabled", plugin.enabled)
                            put("error", plugin.error)
                            put(
                                "sources",
                                buildJsonArray {
                                    for (s in plugin.sources) {
                                        add(
                                            buildJsonObject {
                                                put("source", s.source)
                                                put("actions", buildJsonArray { s.actions.forEach { add(it) } })
                                                put("qualities", buildJsonArray { s.qualities.forEach { add(it) } })
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            }
            p.edit().putString(KEY_PLUGINS, arr.toString()).apply()
        }
    }

    private fun loadPlugins(): List<LxPlugin> {
        val raw = prefs?.getString(KEY_PLUGINS, null) ?: return emptyList()
        return runCatching {
            (jsonElementToAny(json.parseToJsonElement(raw)) as? List<*>).orEmpty().mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = map["id"] as? String ?: return@mapNotNull null
                val sources = (map["sources"] as? List<*>).orEmpty().mapNotNull { s ->
                    val sm = s as? Map<*, *> ?: return@mapNotNull null
                    val code = sm["source"] as? String ?: return@mapNotNull null
                    LxPluginSource(
                        source = code,
                        actions = (sm["actions"] as? List<*>).orEmpty().mapNotNull { it as? String },
                        qualities = (sm["qualities"] as? List<*>).orEmpty().mapNotNull { it as? String },
                    )
                }
                LxPlugin(
                    id = id,
                    name = map["name"] as? String ?: "未命名插件",
                    version = map["version"] as? String ?: "",
                    author = map["author"] as? String ?: "",
                    description = map["description"] as? String ?: "",
                    fileName = map["fileName"] as? String ?: "$id.js",
                    enabled = map["enabled"] as? Boolean ?: true,
                    sources = sources,
                    error = map["error"] as? String,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
