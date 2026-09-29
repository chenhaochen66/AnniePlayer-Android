# Anti-Android-Slop Rules

> Detailed catalog of banned patterns in Android UI, with Compose code examples showing both the bad and the good. Use this reference whenever you're about to ship a screen, or when reviewing existing UI.

## Why This Exists

Material 3 is a great *system*, but its defaults are visually identical across every app that uses them unchanged. AI-generated Compose code consistently produces a recognizable "Material slop" aesthetic: dynamic purple, centered top bars, stacked elevated cards, default FAB, Roboto everywhere. This document catalogs those patterns and provides intentional alternatives.

The rule is not "never use Material components." The rule is **never ship Material components with their defaults unchanged.**

---

## Table of Contents

1. [Banned: Dynamic Color as Default](#1-banned-dynamic-color-as-default)
2. [Banned: Default Purple `primary`](#2-banned-default-purple-primary)
3. [Banned: Centered TopAppBar with Hamburger + 3 Dots](#3-banned-centered-topappbar-with-hamburger--3-dots)
4. [Banned: Stack of Elevated Cards as a Screen](#4-banned-stack-of-elevated-cards-as-a-screen)
5. [Banned: Default FAB with `Icons.Default.Add`](#5-banned-default-fab-with-iconsdefaultadd)
6. [Banned: Mixed Icon Styles](#6-banned-mixed-icon-styles)
7. [Banned: Roboto as Display Font](#7-banned-roboto-as-display-font)
8. [Banned: Purple-to-Blue Gradient Backgrounds](#8-banned-purple-to-blue-gradient-backgrounds)
9. [Banned: Emoji as Functional Icons](#9-banned-emoji-as-functional-icons)
10. [Banned: Generic "Welcome!" Hero Screens](#10-banned-generic-welcome-hero-screens)
11. [Banned: Symmetric 3-Column Feature Grids](#11-banned-symmetric-3-column-feature-grids)
12. [Banned: AI-Generated VectorDrawable Clipart](#12-banned-ai-generated-vectordrawable-clipart)
13. [Banned: Neon-on-Dark "Cyber" Aesthetic](#13-banned-neon-on-dark-cyber-aesthetic)

---

## 1. Banned: Dynamic Color as Default

Dynamic color (Material You's wallpaper-derived palette) is a nice opt-in, but shipping it as the default means your app has no visual identity. Every Material You app on the user's device pulls from the same wallpaper — so they all look the same.

**❌ Bad**

```kotlin
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val colors = if (isSystemInDarkTheme())
        dynamicDarkColorScheme(ctx)
    else
        dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = colors, content = content)
}
```

**✅ Good**

```kotlin
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,  // opt-in
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
```

---

## 2. Banned: Default Purple `primary`

The Material 3 default `primary` is `#6750A4`-ish purple. Every app that doesn't override it ships with this color. It's instantly recognizable as "didn't bother."

**❌ Bad**

```kotlin
MaterialTheme(content = content)  // ships with default purple
```

**✅ Good**

```kotlin
val LightColors = lightColorScheme(
    primary = Color(0xFFE85D3A),  // warm coral, defined intentionally
    onPrimary = Color.White,
    // ...rest of the scheme
)
MaterialTheme(colorScheme = LightColors, content = content)
```

---

## 3. Banned: Centered TopAppBar with Hamburger + 3 Dots

The single most common Android UI shape: centered title, hamburger left, kebab right. It's the visual equivalent of saying "I used the new project template."

**❌ Bad**

```kotlin
Scaffold(
    topBar = {
        CenterAlignedTopAppBar(
            title = { Text("Home") },
            navigationIcon = {
                IconButton(onClick = {}) { Icon(Icons.Default.Menu, "Menu") }
            },
            actions = {
                IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More") }
            }
        )
    }
) { /* ... */ }
```

**✅ Good — Option A: Large left-aligned title, no nav drawer**

```kotlin
LargeTopAppBar(
    title = {
        Text(
            "Home",
            style = MaterialTheme.typography.displaySmall,
            fontFamily = DisplayFont,
        )
    },
    actions = {
        // Only one action, with a meaningful icon
        IconButton(onClick = {}) {
            Icon(Icons.Outlined.Search, contentDescription = "Search")
        }
    },
    colors = TopAppBarDefaults.largeTopAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = MaterialTheme.colorScheme.surface,
    ),
)
```

**✅ Good — Option B: No app bar at all on hero screens**

```kotlin
Scaffold(
    // no topBar; content starts at the top with intentional padding
    contentWindowInsets = WindowInsets.systemBars,
) { padding ->
    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 24.dp,
            bottom = padding.calculateBottomPadding(),
            start = 24.dp,
            end = 24.dp,
        )
    ) { /* ... */ }
}
```

---

## 4. Banned: Stack of Elevated Cards as a Screen

A `LazyColumn` of `Card`s with 1dp elevation is the universal "I don't know what else to do" Material layout. It works, but every Material app does it.

**❌ Bad**

```kotlin
LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    items(items) { item ->
        Card(elevation = CardDefaults.cardElevation(1.dp)) {
            Row(Modifier.padding(16.dp)) {
                Icon(Icons.Default.Star, null)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(item.title)
                    Text(item.subtitle)
                }
            }
        }
    }
}
```

**✅ Good — Use dividers, generous spacing, no elevation**

```kotlin
LazyColumn {
    itemsIndexed(items) { i, item ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                item.metadata,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (i < items.lastIndex) {
            HorizontalDivider(
                Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
```

---

## 5. Banned: Default FAB with `Icons.Default.Add`

The default FAB is fine when it serves the most common action — but shipping `FloatingActionButton(onClick = {}) { Icon(Icons.Default.Add, "Add") }` unchanged is the FAB equivalent of default purple.

**❌ Bad**

```kotlin
FloatingActionButton(onClick = {}) {
    Icon(Icons.Default.Add, contentDescription = "Add")
}
```

**✅ Good — Extended FAB with label and intentional shape**

```kotlin
ExtendedFloatingActionButton(
    onClick = {},
    icon = { Icon(Icons.Outlined.Edit, null) },
    text = { Text("New entry") },
    shape = RoundedCornerShape(20.dp),
    containerColor = MaterialTheme.colorScheme.primary,
    elevation = FloatingActionButtonDefaults.elevation(
        defaultElevation = 0.dp,  // flat FABs read more intentional
        pressedElevation = 0.dp,
    ),
)
```

Or skip the FAB entirely and put the primary action in the top bar or as an inline button at the relevant location.

---

## 6. Banned: Mixed Icon Styles

`androidx.compose.material.icons` offers four styles: `Default` (filled), `Outlined`, `Rounded`, `Sharp`, and `TwoTone`. Pick **one** and stay consistent across the entire app. Mixing them looks accidental.

**❌ Bad**

```kotlin
Icon(Icons.Default.Home, ...)
Icon(Icons.Outlined.Search, ...)
Icon(Icons.Rounded.Settings, ...)
```

**✅ Good — Pick one style globally**

```kotlin
// e.g., Outlined throughout — reads as restrained and modern
Icon(Icons.Outlined.Home, ...)
Icon(Icons.Outlined.Search, ...)
Icon(Icons.Outlined.Settings, ...)
```

---

## 7. Banned: Roboto as Display Font

Roboto is Android's system body font. It's *fine* for body text. It's not a display font — it has no character at large sizes. Shipping headlines in Roboto is the typographic equivalent of shipping default purple.

**❌ Bad**

```kotlin
Text(
    "Welcome to the app",
    style = MaterialTheme.typography.displayLarge,
    // no fontFamily — defaults to Roboto
)
```

**✅ Good — Use Google Fonts via Compose**

```kotlin
// In Type.kt
val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

val DisplayFont = FontFamily(
    Font(GoogleFont("DM Serif Display"), provider, FontWeight.Normal),
)

val BodyFont = FontFamily(
    Font(GoogleFont("Inter"), provider, FontWeight.Normal),
    Font(GoogleFont("Inter"), provider, FontWeight.Medium),
    Font(GoogleFont("Inter"), provider, FontWeight.SemiBold),
)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFont,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    // ...
)
```

---

## 8. Banned: Purple-to-Blue Gradient Backgrounds

The "AI hero gradient" — usually purple to blue or pink to violet — is the single most recognizable AI-slop pattern in mobile design. It signals "ChatGPT generated this layout."

**❌ Bad**

```kotlin
Box(
    Modifier
        .fillMaxSize()
        .background(
            Brush.verticalGradient(
                colors = listOf(Color(0xFF667EEA), Color(0xFF764BA2))
            )
        )
)
```

**✅ Good — Solid warm neutral, or a subtle single-color wash**

```kotlin
Box(
    Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
)

// Or, if you must have a gradient, use it as a subtle accent on a single component
// (e.g., a card highlight) in tones from your brand palette — not as a whole-screen background.
```

---

## 9. Banned: Emoji as Functional Icons

Emoji change appearance across OS versions, fonts, and locales. They're not icons. Material Symbols has 2,500+ icons — use them.

**❌ Bad**

```kotlin
Text("🏠 Home")
Text("⚙️ Settings")
```

**✅ Good**

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Outlined.Home, contentDescription = null)
    Spacer(Modifier.width(8.dp))
    Text("Home")
}
```

---

## 10. Banned: Generic "Welcome!" Hero Screens

A centered "Welcome to [App]!" with subtitle and "Get Started" button is the most generic onboarding pattern in existence. If the user opened the app, they don't need to be welcomed — they need to see content.

**❌ Bad**

```kotlin
Column(
    Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
) {
    Icon(Icons.Default.Star, null, modifier = Modifier.size(64.dp))
    Spacer(Modifier.height(16.dp))
    Text("Welcome to MyApp!", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(8.dp))
    Text("The best way to organize your life", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(32.dp))
    Button(onClick = {}) { Text("Get Started") }
}
```

**✅ Good — Show actual content immediately**

```kotlin
Scaffold { padding ->
    LazyColumn(contentPadding = padding) {
        item {
            Text(
                "Today",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(24.dp),
            )
        }
        items(todaysContent) { item ->
            // Real content, not a welcome screen
        }
    }
}
```

If you genuinely need first-run onboarding, make it brief (one screen, one action) and skip the "Welcome!" framing.

---

## 11. Banned: Symmetric 3-Column Feature Grids

The "three features in a row, each with an icon, title, and subtitle" pattern is universal landing-page slop. It's so common AI models default to it.

**❌ Bad**

```kotlin
Row(horizontalArrangement = Arrangement.SpaceEvenly) {
    FeatureCard(Icons.Default.Speed, "Fast", "Lightning quick")
    FeatureCard(Icons.Default.Security, "Secure", "Bank-level encryption")
    FeatureCard(Icons.Default.Star, "Loved", "Used by millions")
}
```

**✅ Good — Asymmetric editorial layout**

Pick one feature as the hero (large, with rich detail), and stack the others vertically with smaller treatment. Or use a horizontally-scrolling `LazyRow` if you genuinely have many. Asymmetry signals editorial intent.

---

## 12. Banned: AI-Generated VectorDrawable Clipart

VectorDrawables generated by AI models tend to look like 2010-era Google Doodles — flat, generic, vaguely childish. They look worse than no illustration.

**❌ Bad**

A `vector_undraw_developer.xml` of a stick figure at a laptop.

**✅ Good**

- A `Box` with `MaterialTheme.colorScheme.surfaceVariant` and a labeled `Text` overlay
- Real photography (with proper licensing)
- An abstract geometric composition built from `Canvas` primitives
- A single Material Symbol at large size with intentional spacing

---

## 13. Banned: Neon-on-Dark "Cyber" Aesthetic

`#0D1117` background with neon green/cyan text is "I asked an AI for a hacker app" aesthetic. It's not actually readable, and it dates immediately.

**❌ Bad**

```kotlin
Surface(color = Color(0xFF0D1117)) {
    Text("System online", color = Color(0xFF00FF41))
}
```

**✅ Good — Warm dark mode with intentional accent**

```kotlin
val DarkColors = darkColorScheme(
    background = Color(0xFF161614),     // warm dark, not pure black
    surface = Color(0xFF1E1E1B),
    primary = Color(0xFFF27A5C),        // brand accent, not neon
    onBackground = Color(0xFFF2F2F0),   // warm white, not pure white
)
```

---

## Quick Self-Check

Before shipping any screen, ask:

1. Could a stranger tell this is **my** app, or could it be any Material 3 app?
2. Did I override at least: `primary`, `background`, typography, and shapes?
3. Is there exactly one signature detail on this screen?
4. Are my icons all from the same style family?
5. Is my dark mode warm and intentional, not pure black + neon?

If any answer is "no" — return to the design system before continuing.
