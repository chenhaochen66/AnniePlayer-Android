---
name: android-compose-ui
description: Build and modify UI in this project's native Android app (Kotlin + Jetpack Compose), not the Flutter one. Covers the project's file/ownership layout, the overlay-stack + back-press rules, theme tokens, self-drawn icons (only material-icons-core is available), frosted-glass surfaces, window insets, and the Compose/Media3 gotchas that have already bitten this codebase. Use whenever you touch anything under android-native/app/src/main/java/com/annieplayer/annie_music/kt/ — new screens, bottom bars, sheets, overlays, theming, or fixing layout/animation bugs.
---

# Android (Jetpack Compose) UI — project playbook

This repo ships **two** apps. `flutter_app/` is the behavioral baseline for legacy screens;
`android-native/` is the Kotlin/Compose port you work in. Anything Android-only (bottom
navigation, local library, glass bars) has no Flutter counterpart — design it here.

Source root: `android-native/app/src/main/java/com/annieplayer/annie_music/kt/`

## 1. Where things live (respect the ownership)

| Package | Owns |
|---|---|
| `features/shell/` | `AppShell` = root host: app-level wiring, tab state, and the **whole overlay stack**. |
| `features/search/` | Search content (`SearchScreen`) + its logic holder (`SearchScreenState`) + small pieces (`HomeOverlays`). |
| `features/library/` | Local library tab (stub today: `LocalLibraryScreen`). |
| `features/player/` | Full player page, controls, lyrics. |
| `widgets/` | Reusable presentational components + all self-drawn glyphs (`Icons.kt`). |
| `core/state/` | ViewModels / state holders (`UiState`+`UiViewModel`, `PlayerState`+`PlayerViewModel`, `AppShellState`+`AppShellViewModel`, download/selection/color/kkbox/qobuz). |
| `core/audio/` | Media3 engine backend + foreground `MusicSessionService`. |
| `core/routing/` | `OverlayStack` (declaration/back-priority contract) — currently documentation + inert data. |
| `data/` | API sources, repositories, download service, caches. |

Rules:
- **App-level wiring (audio backend `setup()`, cover-cache, permissions, SAF pickers) lives in `AppShell` and runs once.** Never put it inside a tab's content: switching tabs would recreate the engine.
- **Primary navigation state (selected tab) lives in `AppShellViewModel`, NOT in `UiState`.** `UiState` is for overlay visibility only; it already carries ~14 visibility booleans + `copyWith` with 22 params. Do not grow it further.

## 2. Overlay stack = draw order = back-press priority

`AppShell` renders overlays in one `Box`, in this order (bottom→top):
content → GlobalOverlays → MenuDrawer → player overlay → sheets → quality pickers → queue →
source picker → account sheets → modals → toasts → full-screen detail screens.

- **Later-declared composables draw on top AND their `BackHandler` wins.** So every overlay's
  declaration order is a behavioral contract: reordering changes draw order *and* back behavior.
- **The full-screen player overlay must stay a top-level child of `AppShell`** — never inside a
  tab, or switching tabs unmounts it and playback UI state is lost.
- A screen-level back rule (e.g. "back returns to the default tab") must be declared **before**
  the overlays, with `enabled = <not the default state>`, so open sheets still take back first.

## 3. Theme & styling

- **Always use `ui/theme` tokens**: `AppColors`, `AppSpacing` (`sp*`), `AppSize`, `AppRadius`
  (`r4`/`card`/`r20`…), `AppFontSize`, `AppFontWeight`, `AppLineHeight`, `AppShadow`,
  `AppDuration`, `AppEasing`, `AnnieTypography`, `AppLetterSpacing`, `AppBorder`.
  No magic numbers in screens; if a value is reusable, add a token and say so.
- Dimmed text uses the alpha tokens (`white12/24/30/38/54/70`) rather than ad-hoc alphas.

## 4. Icons

Only **`material-icons-core`** is on the classpath (`Icons.Default.Search`, `PlayArrow`, `Close`,
`MoreVert`, … exist — most other Material icons do NOT). For anything else, **self-draw** in
`widgets/Icons.kt` with `Canvas` in the same style: proportional coordinates (`w*0.28f`), rounded
caps/joins, `Stroke` for line art, `Path` for filled shapes. Existing examples: `PauseIcon`,
`PlayIcon`, `VinylRecord`, `DownloadIconPlaceholder`, `MusicNoteGlyph`, `CheckSquareIcon`.
Never pull in `material-icons-extended` just for one glyph.

## 5. Frosted glass (no backdrop blur in Compose)

Compose cannot blur *what is behind* a node; `Modifier.blur` blurs the node's **own content** (and is
a no-op below API 31 — this codebase relies on that graceful degradation). A convincing glass bar =
**translucent surface + highlight gradient + hairline border + soft offset shadow**, e.g.:
`clip(shape)` → `background(surfaceLight.copy(alpha ≈ 0.82))` →
`background(Brush.verticalGradient(glass → Transparent))` → `border(thin, glassBorder, shape)` →
`dropShadow(...)`. Keep text/icons crisp — never blur the bar itself.

`Modifier.dropShadow(shape, Shadow(radius, color, offset))` is the way to get an **offset** shadow
(`Modifier.shadow` cannot offset). It is what the mini-player and bottom bar use.

## 6. Window insets (the pitfall that already bit us)

- `Modifier.safeDrawingPadding()` **includes the IME** — using it at the root makes the whole UI
  (mini player, floating buttons) jump up when the keyboard opens. For a layout that must NOT move
  with the keyboard (matching the Flutter baseline's `resizeToAvoidBottomInset: false`), use
  `Modifier.systemBarsPadding()` and give bottom sheets/inputs their own `Modifier.imePadding()`.
- Bottom bars: prefer keeping the **glass surface flush with the screen bottom** and pushing the
  **content** up with the navigation-bar inset (inner padding) rather than lifting the whole bar
  with an outer `navigationBarsPadding()` — an outer inset makes the bar float too high.
- The Activity also sets `android:windowSoftInputMode="adjustNothing"` for the same reason.

## 7. Compose gotchas already learned here

- `Modifier.matchParentSize()` hands the child **fixed** constraints → a smaller child (e.g. a 3dp
  bar) gets **centred** in the parent. Use `requiredWidth`/`wrapContentWidth(Start)` or draw it with
  `drawBehind` instead of expecting start alignment.
- Inside a **`LazyColumn` item** the main axis is **unbounded**, so `fillMaxHeight()` resolves to
  `minHeight` (=0) and collapses. Use `Modifier.height(IntrinsicSize.Min)` on the row, or draw the
  decoration with `Modifier.drawBehind { drawRoundRect(...) }` (the reliable choice).
- Before animating *into* a screen, don't hardcode a copy of a backdrop — `Modifier.blur` needs a
  separate node/copy if you really want a blurred backdrop.
- Give each list a **stable unique key**; duplicate keys in a lazy list throw and crash the screen —
  de-duplicate by id before handing the list to `items(key = ...)`.

## 8. Media3 / playback invariants

- `SimpleCache` allows **one instance per directory per process** — hold it in a process-level
  singleton; a second construction throws `IllegalStateException` (and this is what a
  rotate/Activity-recreation used to crash on).
- A `MediaSessionService` only wires the notification/foreground machinery if you call
  **`addSession(session)`**; returning the session from `onGetSession()` alone means it never calls
  `startForeground()` and the system kills you ~5s later with
  `ForegroundServiceDidNotStartInTimeException`. Wire it in `onCreate`/`onStartCommand`, and
  `removeSession` in `onDestroy`.
- Clearing the playback cache must go through Media3's `Cache.removeResource()` (exposed via
  `PlayerBackend.clearPlaybackCache()`); deleting the live cache directory corrupts its index.

## 9. Data-layer conventions worth keeping

- Multi-artist fields arrive as raw strings; **split names and ids, then keep them index-aligned**,
  and if the counts differ, drop the ids rather than pairing the wrong one (a wrong id jumps to the
  wrong artist). Shared helpers live in `data/sources/ArtistNames.kt`.
- Prefer parsing ids out of the response you already fetched; only fall back to a per-song detail
  call when the album/playlist response truly carries none, and cache that result.
- Cache via `CacheService` with an explicit TTL; sizes/clearing are surfaced through
  `AssetCache.cacheItems()` / `clearCacheCategories()` and the cache dialog.

## 10. Workflow for any change here

1. Read the real file(s) first; this codebase is dense with intent comments — keep them in Chinese
   and keep them attached to the code they describe.
2. Move-only refactors stay **behavior-neutral**: preserve overlay order, `LaunchedEffect` keys,
   modifier chains and defaults; then prove it with a build + a diff summary.
3. Build: `.\gradlew.bat :app:assembleDebug --console=plain` with workdir
   `D:\AnnieMusic\android-native` → must print `BUILD SUCCESSFUL`; run twice to confirm a clean
   incremental build.
4. Install & look at it: `& "D:\ANSSDK\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk`,
   then `adb exec-out screencap -p > shot.png` and actually inspect the screenshot — layout/contrast
   bugs (gesture-bar overlap, clipping, cramped spacing) are invisible in code review.
5. Never use `!!`, `as any`, `@Suppress` to hide a real error, and never reformat untouched code.
6. Do not commit unless the user explicitly asks.
