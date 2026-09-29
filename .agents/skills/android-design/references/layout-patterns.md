# Layout Patterns & Adaptive Design

> Patterns and idioms for composing layouts in Jetpack Compose. Covers Scaffold structure, scrollable content, grids, adaptive design for foldables and tablets, and inset/edge-to-edge handling.

## Table of Contents

1. [Scaffold: The Default Screen Container](#1-scaffold-the-default-screen-container)
2. [Vertical & Horizontal Flow](#2-vertical--horizontal-flow)
3. [Scrollable Content: Always Lazy for Long Lists](#3-scrollable-content-always-lazy-for-long-lists)
4. [Grids](#4-grids)
5. [Adaptive Layouts: WindowSizeClass](#5-adaptive-layouts-windowsizeclass)
6. [Insets & Edge-to-Edge](#6-insets--edge-to-edge)
7. [Bottom Sheets & Modals](#7-bottom-sheets--modals)
8. [Navigation Patterns](#8-navigation-patterns)
9. [Sticky Headers & Collapsing Top Bars](#9-sticky-headers--collapsing-top-bars)
10. [Common Layout Mistakes](#10-common-layout-mistakes)

---

## 1. Scaffold: The Default Screen Container

`Scaffold` should be the root of nearly every full-screen composable. It handles system bars, top app bar slot, FAB slot, snackbar host, and content padding for free.

```kotlin
@Composable
fun MyScreen() {
    val snackbarHost = remember { SnackbarHostState() }

    Scaffold(
        topBar = { /* optional */ },
        bottomBar = { /* optional */ },
        floatingActionButton = { /* optional */ },
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        // ALWAYS apply contentPadding to your root content,
        // or pass it to a LazyColumn as contentPadding parameter
        Box(Modifier.padding(contentPadding)) {
            // ...
        }
    }
}
```

**Don't:** ignore `contentPadding` — content will draw under the top bar or be cut off by the nav bar.

---

## 2. Vertical & Horizontal Flow

For short, non-scrolling layouts, `Column` and `Row` are fine.

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.l),
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
) {
    Text("Title", style = MaterialTheme.typography.headlineMedium)
    Text("Subtitle", style = MaterialTheme.typography.bodyMedium)
}
```

Use `Arrangement.spacedBy(...)` instead of manual `Spacer`s — it's more readable and easier to tweak.

---

## 3. Scrollable Content: Always Lazy for Long Lists

**Rule:** if a list has more than ~5 items, or its length is unknown, use `LazyColumn`. `Column` with `Modifier.verticalScroll` composes every child eagerly — fine for a short form, catastrophic for a feed.

```kotlin
LazyColumn(
    contentPadding = PaddingValues(
        horizontal = MaterialTheme.spacing.l,
        vertical = MaterialTheme.spacing.m,
    ),
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
) {
    item {
        // Header content
    }
    items(items, key = { it.id }) { item ->
        ItemRow(item)
    }
    item {
        // Footer or load-more indicator
    }
}
```

**Always provide a `key`** when items can be added/removed/reordered — without it, animations break and recomposition is wasteful.

For horizontal scrolling, `LazyRow` works identically.

---

## 4. Grids

For grid layouts, use `LazyVerticalGrid` (or `LazyHorizontalGrid`). Two cell strategies:

```kotlin
// Fixed number of columns
LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    contentPadding = PaddingValues(16.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
) {
    items(items) { item -> ItemTile(item) }
}

// Adaptive — fits as many columns as the screen width allows, each at least minSize
LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 160.dp),
    // ...
) { /* ... */ }
```

`GridCells.Adaptive` is the right choice when you want the grid to respond to tablets and foldables without writing manual breakpoints.

For non-uniform grids (mixed item sizes), use `LazyVerticalStaggeredGrid`.

---

## 5. Adaptive Layouts: WindowSizeClass

Never hardcode `if (screenWidth > 600.dp)` checks. Use `WindowSizeClass` from `androidx.compose.material3.windowsizeclass`.

```kotlin
// In your Activity:
setContent {
    val windowSizeClass = calculateWindowSizeClass(this)
    AppTheme { MyApp(windowSizeClass) }
}

@Composable
fun MyApp(windowSizeClass: WindowSizeClass) {
    when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> CompactLayout()    // phones
        WindowWidthSizeClass.Medium -> MediumLayout()      // unfolded foldables, small tablets
        WindowWidthSizeClass.Expanded -> ExpandedLayout()  // tablets, desktops
    }
}
```

Common adaptive patterns:

- **Compact:** single-pane navigation (bottom nav, single content view)
- **Medium:** navigation rail + content
- **Expanded:** navigation rail + list + detail (two-pane)

For the list-detail pattern, use the `androidx.compose.material3.adaptive` library's `NavigableListDetailPaneScaffold`.

---

## 6. Insets & Edge-to-Edge

Modern Android apps draw edge-to-edge — content goes behind the status bar and gesture nav. Two requirements:

### In your Activity:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()  // from androidx.activity.compose
    super.onCreate(savedInstanceState)
    setContent { /* ... */ }
}
```

### In Compose:

`Scaffold` handles insets via its `contentWindowInsets` parameter. For composables outside a `Scaffold`, use `Modifier.windowInsetsPadding(...)`:

```kotlin
Box(
    Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBars)
) { /* ... */ }
```

For specific insets:

- `WindowInsets.statusBars` — top
- `WindowInsets.navigationBars` — bottom (gesture nav)
- `WindowInsets.ime` — keyboard
- `WindowInsets.safeDrawing` — combined safe area

For images or backgrounds that should extend behind the status bar, **don't** apply insets — let them draw edge-to-edge, then apply insets to text/buttons layered on top.

---

## 7. Bottom Sheets & Modals

Material 3 provides `ModalBottomSheet` and `BottomSheetScaffold`.

```kotlin
var showSheet by remember { mutableStateOf(false) }
val sheetState = rememberModalBottomSheetState()

if (showSheet) {
    ModalBottomSheet(
        onDismissRequest = { showSheet = false },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        // Sheet content
        Column(Modifier.padding(24.dp)) {
            Text("Options", style = MaterialTheme.typography.headlineSmall)
            // ...
        }
    }
}
```

**Tip:** customize the sheet shape and drag handle — defaults are recognizably Material.

---

## 8. Navigation Patterns

Match navigation to window size class:

| Width Size Class | Pattern                          | Compose API                  |
| ---------------- | -------------------------------- | ---------------------------- |
| Compact          | `BottomAppBar` with 3-5 items    | `NavigationBar`              |
| Medium           | `NavigationRail` (vertical, left) | `NavigationRail`            |
| Expanded         | `NavigationRail` + permanent drawer or list-detail | `NavigationRail` + adaptive scaffold |

**Don't** ship a `ModalNavigationDrawer` (the hamburger drawer) as the primary nav in 2025+ — it's been superseded by bottom nav and navigation rail. Use it only for *secondary* nav.

---

## 9. Sticky Headers & Collapsing Top Bars

For a top app bar that collapses on scroll:

```kotlin
val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
        LargeTopAppBar(
            title = { Text("Title") },
            scrollBehavior = scrollBehavior,
        )
    },
) { padding ->
    LazyColumn(contentPadding = padding) { /* ... */ }
}
```

For sticky headers inside a `LazyColumn`:

```kotlin
LazyColumn {
    stickyHeader {
        Surface(color = MaterialTheme.colorScheme.background) {
            Text("Section A", Modifier.padding(16.dp))
        }
    }
    items(sectionAItems) { /* ... */ }
    stickyHeader {
        // Section B header
    }
    // ...
}
```

---

## 10. Common Layout Mistakes

| Mistake                                              | Why It's Wrong                                      | Fix                                                |
| ---------------------------------------------------- | --------------------------------------------------- | -------------------------------------------------- |
| `Modifier.size(360.dp)` for screen-width content     | Breaks on tablets, foldables, landscape             | `Modifier.fillMaxWidth()` or `containerSize`-based |
| `Column { Modifier.verticalScroll(...) }` for feeds  | Composes all items eagerly; janky                   | `LazyColumn`                                       |
| Ignoring `Scaffold`'s `contentPadding`               | Content draws under top bar                         | Apply or pass to `LazyColumn.contentPadding`       |
| Manual `Spacer(Modifier.height(8.dp))` between every child | Verbose; hard to tweak                       | `Arrangement.spacedBy(8.dp)`                       |
| `Box` with `contentAlignment = Center` for layout    | Centers content but doesn't communicate intent      | Use `Column` + `Arrangement.Center` semantics      |
| Not providing `key` to `LazyColumn.items`            | Animations break on reorder; wasted recomposition   | `items(items, key = { it.id }) { ... }`            |
| Hardcoding insets (`statusBarPadding = 24.dp`)       | Wrong on different devices                          | `WindowInsets.statusBars` or `Scaffold`            |
| Using `ConstraintLayout` for simple linear flows     | Overkill; Column/Row are clearer                    | `Column`/`Row` unless you need constraint behavior |
| Nesting `LazyColumn` inside `LazyColumn`             | Compile error or broken layout                      | Use `item { }` blocks within one `LazyColumn`      |
| Putting `LazyColumn` inside `Column` with no height  | "Infinite constraints" crash                        | Constrain the parent or use `Modifier.weight(1f)`  |
