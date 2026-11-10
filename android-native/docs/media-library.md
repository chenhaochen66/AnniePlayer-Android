# 收藏媒体库（设计稿）

> **状态**：数据层与首页已落地。**本文件只留「还没做的」和「做的时候不能违反的约束」**，
> 已完成部分以代码为准（清单见 §2）。
> 实现与本文冲突时，先质疑实现，再质疑本文。
>
> **范围**：Kotlin 版（`android-native/`）。收藏（单曲 / 专辑 / 歌手 / 歌单）、收藏库页面、
> 用户自建播放列表、歌单解析的入口迁移。
> **不含**：播放链路、下载链路、歌词、音质面板、主页 feed。

---

## 1. 概念模型（后续页面全按这个分）

### 1.1 两个模式，互斥

收藏库有且只有两个模式，由首页顶部的分段控件切换，**状态存在数据库 `meta.mode`**：

| 模式 | 收藏什么 | 数据源 |
|---|---|---|
| 流媒体 | 在线歌曲 / 专辑 / 歌手 / 歌单 | 各平台接口 |
| 本地 | 本地歌曲 / 专辑 / 歌手 | 设备文件 + `LocalLibraryStore` 索引 |

切模式 = 换一整套数据源。**没有混合模式**。

### 1.2 四个概念不要混

| 概念 | 是什么 | 受模式影响 |
|---|---|---|
| 收藏 | 一条引用记录 | 是（分两侧存） |
| 我喜欢 | 收藏的单曲列表 | 是 |
| 自建播放列表 | 用户手动建的歌单 | 是（`scope` 切边） |
| 收藏的在线歌单 | 别人的歌单，你收藏了它 | 只在流媒体模式 |

### 1.3 自建播放列表必须选边

`UserPlaylist.scope ∈ {online, local}`，**创建时定死、创建后不可改**；加歌时按 `scope` 过滤
（本地歌单的「加入播放列表」只列出 `scope=local` 的歌单）。这样「一个歌单混着本地与在线」
在数据层不可能存在，也就不需要任何「这个歌单算哪边」的裁决。

### 1.4 收藏身份（key）

一套规则，四处复用（toggle / 去重 / 自愈 / 爱心亮不亮）。实现在 `FavoriteKeys`。

| 对象 | 键 |
|---|---|
| 在线任意 | `platform:id` |
| 本地单曲 | `Song.id`（已是 `local:<绝对路径>`，**不再拼前缀**） |
| 本地专辑 | `albumGroupKey(t)` = `"专辑名 · 专辑艺术家"`（**必须与本地曲库逐字一致**） |
| 本地歌手 | `artistDisplay` |

本地条目没有 id，键来自 tag 分组；改 tag 会让键失联 —— 每条本地收藏额外存 `paths`，
打开收藏库时用 `FavoriteSelfHeal` 反查并改写键（找不到就标记失效，**不删记录**）。

---

## 2. 已落地（改这些之前先读代码）

| 文件 | 内容 |
|---|---|
| `data/favorites/FavoriteModels.kt` | `FavSong/Album/Artist/Playlist`、`UserPlaylist`、`FavoriteMode`、`PlaylistScope`、`FavSection`、`FavoriteSnapshot`（含 `xxxIn(mode)` 过滤） |
| `data/favorites/FavoriteKeys.kt` | §1.4 的键规则 |
| `data/favorites/FavoriteSelfHeal.kt` | 本地条目失联自愈 + 存活计数 |
| `data/favorites/FavoriteDb.kt` | SQLite：4 张表（`favorites` / `user_playlists` / `user_playlist_songs`(CASCADE) / `meta`）；**v2 加了 `track_count` / `release_date` / `creator` 三列**（`onUpgrade` 逐版本 ALTER，不 DROP） |
| `data/favorites/FavoriteStore.kt` | 门面：库 + 内存副本，对外 API 与文件版一致；一次性迁移旧 json；元信息校准 `syncAlbumMeta` / `syncPlaylistMeta` |
| `data/favorites/FavoriteSync.kt` | 校准判断的纯函数（`albumMetaChange` / `playlistMetaChange`），带单测 |
| `core/state/FavoriteViewModel.kt` | `StateFlow<FavoriteSnapshot>` + 四类 toggle |
| `widgets/HeartGlyph.kt` | 自绘爱心（描边/填充共用一条路径） |
| `widgets/SegmentedPill.kt` | 两段切换胶囊 |
| `widgets/MediaLibraryGlyph.kt` | 底栏「资料库」图标 |
| `widgets/DetailHeroActions.kt` | `DetailHeartButton` |
| `features/favorites/FavoritesScreen.kt` | 收藏库首页：模式切换 + 四行入口 + 最近添加 |
| `features/favorites/FavoriteSectionScreen.kt` | 子页外壳：顶栏 + 计数 + 四路分发 + 跳转载决 |
| `features/favorites/FavSectionLists.kt` | 艺人 / 专辑 / 播放列表三个列表（含 `localArtistStats`） |
| `features/favorites/FavSongRows.kt` | 单曲列表 + 播放/随机胶囊（「我喜欢」与自建歌单共用） |
| `features/favorites/UserPlaylistScreen.kt` | 自建播放列表详情（**只读**，增删见 §3.1） |
| `core/state/AppShellState.kt` | `AppTab.Favorites`（原 `PlaylistParse` 已删） |

**六处爱心已接**：专辑 / 歌单 / 歌手详情页（在播放胶囊左边）+ 本地专辑 / 本地歌手详情页
+ 播放页（标题行 ⋮ 左边）。点亮色是 `AppColors.heart`（固定正红，**不跟封面取色**）。

---

## 3. 待做

### 3.1 自建播放列表的增删（UI）

表已建好、`FavoriteStore` 的 API 已可用；**详情页只读版已落地**（`UserPlaylistScreen`：
播放 / 随机播放 / 点单曲开播 / ⋮ 面板）。缺的是「增」和「减」：

- 播放列表页的两个入口：`新建播放列表…`（`MultiFieldDialog` 一个字段，`scope` 取当前模式）
  / `从链接添加歌单…`（§3.3）
- 单曲 ⋮ 面板里的「加入播放列表」：按 `scope` 过滤可选项（见 §1.3），本地歌单里不列出在线歌单
- 详情页的「移除单曲」/ 顶栏「删除歌单」

**顺序要求**：增与减必须一起做。只做减 = 只能删不能加，歌单永远空着；
只做增 = 建了以后没法收拾。

不做拖拽排序。

### 3.2 `SongTile` 第二行拆成可点的歌手 / 专辑

收藏的单曲要能跳歌手和专辑。`SongTile` 第二行现在是**一整个 Text**（`歌手 · 专辑`）。
加两个可选回调，`null` 时行为完全不变：

```kotlin
SongTile(
    song = song,
    onOpenArtist = { ... },   // null = 保持纯文本
    onOpenAlbum = { ... },
)
```

跳转兜底链（各平台数据完整度不同）：

```
点歌手
├─ 在线且 artistIds 非空 → SingerRoute(id = artistIds[0], platform)
├─ 在线且 artistIds 空   → 搜索页填入 song.artist 作关键词
└─ 本地                  → 本地歌手页
点专辑
├─ 在线且 albumId 非空   → AlbumRoute(...)
├─ 在线且 albumId 空     → 不可点
└─ 本地                  → LocalAlbumRoute(albumGroupKey)
```

**已知缺口**：KKBOX 的 track 对象没有 artist id（只有 `artist_more_url`，可从 URL 抠）；
酷狗歌单走的是新接口，`singerinfo[]` 有 id（已修）。

### 3.3 歌单解析转 sheet

`AppTab.PlaylistParse` 已删，但 `PlaylistParseScreen` / `PlaylistParseScreenState` 还在
（`AppShell` 里那个 tab 分支已换成 `FavoritesScreen`）。要做的：

- 改成 `features/playlist/PlaylistParseSheet.kt`（`AppSheet` 承载）
- 内容仍是 `HomePlaylistLinkView`（`internal`，搜索页已在复用）
- `handleSubmit` 逻辑一行不改
- 入口：播放列表页的「从链接添加歌单…」

### 3.4 `FavoriteRefresher`：打开页面并行刷新封面 / 名字

> **曲目数 / 发行日期 / 创建者不走这里** —— 它们由「打开详情页校准」维护（已落地，见 §4.8）。
> 本节的 Refresher 只剩「列表里的封面与名字」这一类，还没做。

列表里的 `name` / `cover` 是「上次已知」，打开页面时并行刷新覆盖：

```
进页面 → 立即渲染存储里的条目（0 网络）→ 并行刷新（Semaphore 4，逐项 runCatching）
        → 成功覆盖并写回库；失败保留原值、不提示
```

| 条目 | 刷什么 | 用哪个接口 |
|---|---|---|
| 收藏的专辑 | 封面 / 名字 | 专辑**元信息**轻接口（不拉曲目） |
| 收藏的艺人 | 头像 / 名字 | `fetchArtistAvatarByPlatform`（已有） |
| 收藏的在线歌单 | 封面 / 名字 | 歌单元信息轻接口 |
| 我喜欢 | 封面 | **按 `albumId` 去重后**刷专辑封面 |

**需要先拆「轻接口」**：各平台的专辑/歌单详情接口本来是「元信息 + 曲目」两段，代码写在一个函数里。
列表刷新只能用前半段，否则 30 张专辑就是几百条曲目。

| 平台 | 专辑元信息 | 歌单元信息 |
|---|---|---|
| 网易 | `getNeteaseAlbumDetail` ✅ | 需拆 `playlist/detail` 前半段 |
| 酷狗 | `fetchKugouAlbumInfo` ✅ | 需拆 (新接口 `list_info` 已带) |
| KKBOX | `v1/album?ids=` 批量 ✅ | `v1/playlists` ✅ |
| 酷我 / QQ / Apple | ❌ 只有完整曲目接口 | —— |

### 3.5 `snapshot()` 异步化

`FavoriteViewModel` 构造时**同步**调 `FavoriteStore.snapshot()`。几千首以上要挪到
`Dispatchers.IO` + 先出空态再补。量小时无感，但这是已知的扩展点。

---

## 4. 不能违反的约束（都是踩过的）

1. **泛型 + 序列化必须 `reified`**
   ```kotlin
   fun writeList(name: String, value: Any)        // ❌ encodeToString<Any> 运行时抛 → 写盘静默失败
   fun <T> readLegacy(name): List<T>              // ❌ "Captured type parameter T … is erased" → 迁移读 0 条
   private inline fun <reified T> readLegacyJson  // ✅
   ```
   两次都表现成「操作成功、数据没了」，编译器都不拦。局部函数不能声明 `inline reified`，
   所以这类函数要放在 object/类成员位置。

2. **写操作顺序：先写库、再改内存**。反过来的话库写失败时内存领先，下次冷启动对不上。

3. **落盘/迁移失败必须留证据**
   `lastWriteError` + `files/favorites/migrate.log`。失败在 UI 上完全看不出来（爱心照旧变红）。

4. **本地键必须与曲库同源**：本地专辑用 `albumGroupKey`（按 `albumArtist`），
   不能用 `FavoriteKeys.album` 现算（那是按 `artistDisplay`，合辑/feat. 时会不一致）。
   本地详情页收藏时**显式传 `key = albumKey`**。

5. **收藏不存歌词**：`lyric` / `tlyric` / `wordLyric` 一首 4~5 KB。播放时 `_ensureLyric` 会重新取。

6. **子页必须有 `systemBarsPadding()` + 内容 `weight(1f)`**
   （少了就是「顶栏顶到状态栏、整页比别的子页高一截」；外壳只有 `FavoriteSectionScreen` 一处）。

7. **爱心点亮色固定 `AppColors.heart`**，不跟封面取色 —— 否则同一首歌在不同封面下会闪成不同颜色。

8. **收藏记录的展示字段都是「上次已知」，列表页绝不为它们发请求**
   收藏时把当时的名字 / 封面 / 曲目数 / 发行日期 / 创建者一并写进记录；
   之后**只有打开了那张专辑 / 那位歌手 / 那个歌单的详情页**，才用当时拿到的事实校准
   （`FavoriteViewModel.syncAlbumMeta` / `syncArtistMeta` / `syncPlaylistMeta`
   → `FavoriteSync` 里的三个纯函数）。真值来源分三类，别混：

   | 对象 | 字段 | 来源 |
   |---|---|---|
   | 专辑 | 曲目数 / 发行日期 | 接口（`fetchAlbumSongsByPlatform` 的 `songs.size` / `pub`） |
   | 专辑 | 名字 / 歌手 / 封面 | **详情页入参** `AlbumDetailData` —— 从主页或搜索进来时是平台当前值；从收藏库进来时它就是收藏记录本身，于是值不变、不写库 |
   | 歌手 | 头像 | 接口（`fetchArtistAvatarByPlatform`；**已收藏时无视 `data.picture` 非空也拉**） |
   | 歌单 | 名字 / 封面 / 创建者 / 曲目数 | 接口（`PlaylistService.fetchPlaylist`） |

   - 列表页若自己发请求，30 张专辑就是 30 次详情请求，且每滚一次都要等网络才有数字；
   - 详情页校准失败（网络错）**不写库**，否则会把「加载失败 = 0 首」写进去；
   - 传空串 / 0 一律视为「这次没拿到」，保留原值（`orKeep`），所以调用方不必自己判断；
   - 专辑「接口级」的名字 / 封面仍拿不到（专辑详情接口只返回曲目与发行日期），
     那要等 §3.4 的 Refresher + 轻接口。

9. **本地发行日期统一走 `normalizeReleaseDate`**（`yyyy` / `yyyy-MM` / `yyyy-MM-dd`）。
   曲库的 `year` tag 有 `2023`、`2023-05-12`、`2023-05-12T00:00:00`、`2023/5/12` 各种形态，
   各自 `take(4)` 会到处出现「只显示年份」；只有年就只给年，**不要补 `-01-01`**。

10. **收藏里的本地单曲必须用曲库的实时对象，不能用收藏快照**
     `FavSong.song` 是收藏那一刻整首 `Song` 的序列化（标题 / 艺术家 / 专辑 / 封面都在里面），
     而本地 tag 随时会改。列表行本来就取 `LocalTrackMeta`，所以**队列也必须过一次
     `rememberLiveSongs()`** —— 否则同一个文件「列表里是新标题、播放页是旧标题」。
     曲库里查不到（文件被移走 / 改名）才回落到快照。

11. **清图片缓存必须走 Coil 的 `DiskCache.clear()`，不能删 `image_cache` 目录**
    `ImageLoader` 单例在内存里握着 `DiskLruCache` 和它的 journal；从外部
    `deleteRecursively()` 掉目录会让 journal 一起消失，之后封面持续加载不出来，
    **并且没有任何报错**（只是空白/转圈），只有杀进程重建 ImageLoader 才恢复。
    同一类问题还有 Media3 的播放缓存：那个目录正被 `SimpleCache` 连着索引占着，
    必须走 `Cache` API（见 `PlayerBackend.clearPlaybackCache()`）。
    自带目录（`assets` / `downloads` / `kkbox_play`）没有别人握着，可以删。

---

## 5. 明确不做

| 项目 | 原因 |
|---|---|
| 混合模式 / 混合歌单 | 数据层不存在，不需要 |
| 收藏列表的后台定时刷新 | 只在**打开页面**时刷（§3.4），曲目数只在你打开详情页时校准 |
| 流媒体单曲的收藏快照刷新 | 平台侧歌曲元数据极少变；真要刷得按 `songId` 批量拉，各平台接口形状最杂。快照 + 详情页校准已够 |
| 占位类型的字母索引条 | 量级不到 |
| 歌单拖拽排序 | 与收藏无关，另一个量级 |
| 失效收藏自动清理 | 自动扫描并清理不做（用户可能只是拔了卡）。**例外**：用户主动「连文件一起删」时会撤掉该条单曲收藏与自建歌单里的引用（`deleteTrack(deleteFile = true)` → `FavoriteStore.removeLocalSong`）；「只从曲库移除、文件留在盘上」不撤 —— 文件还在，收藏仍然有效 |
| json 分片 | 换 SQLite 后不需要（本来就是单行写） |
