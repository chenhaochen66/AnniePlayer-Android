# XML Views Guidance (Legacy Projects)

> This skill prioritizes Jetpack Compose. Use this reference only when working in legacy XML-based projects that won't migrate, or for tasks like editing `themes.xml` and `colors.xml` in hybrid projects.

## When to Use This Reference

- The project is fully XML Views with no Compose migration planned
- You're editing `themes.xml`, `colors.xml`, or `attrs.xml` in a hybrid project
- You're styling a `Toolbar`, `BottomNavigationView`, or other View-based component that hasn't migrated to Compose
- You're integrating a Material Components library theme that needs to interop with Compose

## When NOT to Use

- Any greenfield project (use Compose)
- A project actively migrating to Compose (write new screens in Compose, migrate old ones — don't add new XML)

---

## Material Components for Android (Views)

XML projects use **Material Components for Android** (`com.google.android.material:material`), not the Compose `material3` library. They share design principles but the APIs differ.

```kotlin
// build.gradle.kts
implementation("com.google.android.material:material:1.11.0")
```

---

## Theme File Structure

```
res/
├── values/
│   ├── colors.xml
│   ├── themes.xml
│   └── styles.xml
└── values-night/
    └── themes.xml  (dark mode overrides)
```

### `colors.xml`

```xml
<resources>
    <!-- Brand palette -->
    <color name="brand_primary">#E85D3A</color>
    <color name="brand_primary_dark">#F27A5C</color>
    <color name="on_brand_primary">#FFFFFF</color>

    <!-- Neutrals -->
    <color name="neutral_background">#FAFAF8</color>
    <color name="neutral_background_dark">#161614</color>
    <color name="text_primary">#1A1A1A</color>
    <color name="text_primary_dark">#F2F2F0</color>
    <color name="text_secondary">#6B6B6B</color>
</resources>
```

### `themes.xml` (light)

```xml
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.MyApp" parent="Theme.Material3.Light.NoActionBar">
        <!-- Brand colors -->
        <item name="colorPrimary">@color/brand_primary</item>
        <item name="colorOnPrimary">@color/on_brand_primary</item>

        <!-- Surfaces -->
        <item name="android:colorBackground">@color/neutral_background</item>
        <item name="colorSurface">@color/neutral_background</item>
        <item name="colorOnSurface">@color/text_primary</item>
        <item name="colorOnSurfaceVariant">@color/text_secondary</item>

        <!-- System bars -->
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>

        <!-- Edge-to-edge -->
        <item name="android:windowDrawsSystemBarBackgrounds">true</item>

        <!-- Typography (defined in styles.xml) -->
        <item name="textAppearanceDisplayLarge">@style/TextAppearance.MyApp.DisplayLarge</item>
        <item name="textAppearanceHeadlineLarge">@style/TextAppearance.MyApp.HeadlineLarge</item>
        <item name="textAppearanceBodyLarge">@style/TextAppearance.MyApp.BodyLarge</item>
    </style>
</resources>
```

### `values-night/themes.xml` (dark)

```xml
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.MyApp" parent="Theme.Material3.Dark.NoActionBar">
        <item name="colorPrimary">@color/brand_primary_dark</item>
        <item name="android:colorBackground">@color/neutral_background_dark</item>
        <item name="colorSurface">@color/neutral_background_dark</item>
        <item name="colorOnSurface">@color/text_primary_dark</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
</resources>
```

---

## Typography in XML

Define text appearances in `styles.xml`:

```xml
<style name="TextAppearance.MyApp.DisplayLarge" parent="TextAppearance.Material3.DisplayLarge">
    <item name="android:fontFamily">@font/dm_serif_display</item>
    <item name="android:textSize">45sp</item>
    <item name="android:letterSpacing">-0.011</item>  <!-- equivalent of -0.5sp -->
    <item name="android:textColor">?attr/colorOnSurface</item>
</style>

<style name="TextAppearance.MyApp.BodyLarge" parent="TextAppearance.Material3.BodyLarge">
    <item name="android:fontFamily">@font/inter</item>
    <item name="android:textSize">16sp</item>
</style>
```

Add font files to `res/font/`:

```
res/font/
├── dm_serif_display.xml  (downloadable Google Font)
└── inter.xml
```

`res/font/inter.xml`:

```xml
<font-family xmlns:app="http://schemas.android.com/apk/res-auto"
    app:fontProviderAuthority="com.google.android.gms.fonts"
    app:fontProviderPackage="com.google.android.gms"
    app:fontProviderQuery="Inter"
    app:fontProviderCerts="@array/com_google_android_gms_fonts_certs" />
```

---

## Shape Theming in XML

```xml
<!-- styles.xml -->
<style name="ShapeAppearance.MyApp.SmallComponent" parent="ShapeAppearance.Material3.SmallComponent">
    <item name="cornerFamily">rounded</item>
    <item name="cornerSize">8dp</item>
</style>

<style name="ShapeAppearance.MyApp.LargeComponent" parent="ShapeAppearance.Material3.LargeComponent">
    <item name="cornerFamily">rounded</item>
    <item name="cornerSize">20dp</item>
</style>
```

Reference in theme:

```xml
<style name="Theme.MyApp" parent="Theme.Material3.Light.NoActionBar">
    <item name="shapeAppearanceSmallComponent">@style/ShapeAppearance.MyApp.SmallComponent</item>
    <item name="shapeAppearanceLargeComponent">@style/ShapeAppearance.MyApp.LargeComponent</item>
</style>
```

---

## Common View Components

### Toolbar (instead of TopAppBar)

```xml
<com.google.android.material.appbar.MaterialToolbar
    android:id="@+id/toolbar"
    android:layout_width="match_parent"
    android:layout_height="?attr/actionBarSize"
    android:background="?attr/colorSurface"
    app:title="Screen Title"
    app:titleTextAppearance="@style/TextAppearance.MyApp.HeadlineSmall"
    app:titleCentered="false" />
```

### BottomNavigationView

```xml
<com.google.android.material.bottomnavigation.BottomNavigationView
    android:id="@+id/bottom_nav"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:menu="@menu/bottom_nav_menu"
    app:itemTextAppearanceActive="@style/TextAppearance.MyApp.LabelMedium"
    app:itemTextAppearanceInactive="@style/TextAppearance.MyApp.LabelMedium" />
```

### RecyclerView (instead of LazyColumn)

Always use `RecyclerView` for scrollable lists in XML — never a `LinearLayout` inside a `ScrollView` for a list of items.

```xml
<androidx.recyclerview.widget.RecyclerView
    android:id="@+id/list"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:clipToPadding="false"
    android:paddingHorizontal="24dp"
    android:paddingVertical="16dp" />
```

---

## Compose-in-XML Interop

If you're in a hybrid project and want to embed Compose inside an XML layout:

```xml
<androidx.compose.ui.platform.ComposeView
    android:id="@+id/compose_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
findViewById<ComposeView>(R.id.compose_view).setContent {
    AppTheme {
        MyCompositeComponent()
    }
}
```

**Important:** the Compose theme is *independent* from the XML theme. Define your `AppTheme` Compose wrapper to mirror the XML theme tokens, or use a shared color resource source-of-truth.

---

## Anti-Slop Rules Still Apply

Every rule from `anti-android-slop.md` applies equally to XML projects. The defaults to override are the same:

- Don't ship default `colorPrimary`
- Don't use Roboto for display
- Don't use the default centered Toolbar with hamburger
- Use Material Icons via `app:srcCompat="@drawable/ic_..."` with consistent style
- Define a custom shape language

The implementation surface is different; the principles are identical.
