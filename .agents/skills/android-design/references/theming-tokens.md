# Typography, Color & Shape Tokens

> The full reference for design tokens in a hybrid Material 3 setup. Covers typography with Google Fonts, light/dark color schemes, shape tokens, and how to wire them into `MaterialTheme`.

## Table of Contents

1. [Typography](#1-typography)
2. [Color](#2-color)
3. [Shape](#3-shape)
4. [Spacing (Custom Token)](#4-spacing-custom-token)
5. [Putting It All Together](#5-putting-it-all-together)

---

## 1. Typography

### Choosing Fonts

Material 3 defaults to Roboto. For a distinctive app:

- **Display font** — for headlines and screen titles. Pick something with character at large sizes. Good Google Fonts options:
  - Serif: *DM Serif Display*, *Fraunces*, *Playfair Display*, *Lora* (subtler)
  - Geometric sans: *Manrope*, *Sora*, *Space Grotesk*, *Outfit*
  - Humanist sans: *Inter Tight*, *Familjen Grotesk*

- **Body font** — for content. Optimize for readability at 14-16sp.
  - *Inter* (the modern default), *Manrope*, *Source Sans 3*, *IBM Plex Sans*

- **Mono font** (optional) — for code, numerics, technical UI.
  - *JetBrains Mono*, *IBM Plex Mono*, *Space Mono*

**Rule:** at most two font families per app. Three only for very specific reasons (display + body + mono).

### Wiring Google Fonts into Compose

Add the dependency:

```kotlin
// build.gradle.kts
implementation("androidx.compose.ui:ui-text-google-fonts:1.6.0")
```

Add the provider config in `res/values/font_certs.xml` (Android Studio's font import wizard generates this — use it).

Then in `Type.kt`:

```kotlin
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font

val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

val DisplayFont = FontFamily(
    Font(GoogleFont("DM Serif Display"), googleFontProvider, FontWeight.Normal),
)

val BodyFont = FontFamily(
    Font(GoogleFont("Inter"), googleFontProvider, FontWeight.Normal),
    Font(GoogleFont("Inter"), googleFontProvider, FontWeight.Medium),
    Font(GoogleFont("Inter"), googleFontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Inter"), googleFontProvider, FontWeight.Bold),
)
```

### Defining the Typography Scale

Material 3's `Typography` has 15 slots. Don't override all of them — define the ones you use, accept defaults elsewhere.

```kotlin
val AppTypography = Typography(
    // DISPLAY — for hero headlines (rare; usually one per screen)
    displayLarge = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Normal,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = DisplayFont,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),

    // HEADLINE — for screen titles, section heads
    headlineLarge = TextStyle(
        fontFamily = BodyFont,  // sans for headlines is fine
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),

    // TITLE — for card titles, list group headers, dialog titles
    titleLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),

    // BODY — for content text
    bodyLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFont,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFont,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),

    // LABEL — for buttons, chips, captions
    labelLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)
```

### Typography Usage Rules

- A screen should use **3-5 distinct text styles**, not all 15
- Display styles: at most one per screen (the hero)
- Pair `displayLarge` with negative `letterSpacing` (-0.25 to -0.5) for tighter, more editorial feel
- Body text below 14sp is too small for accessibility — avoid

---

## 2. Color

### Defining a Brand Palette

Material 3's `ColorScheme` has 30+ slots. Don't fight it — define the slots you care about and let defaults derive.

Start with these primary tokens:

```kotlin
// Color.kt

// Brand primary (the one color users will associate with your app)
val BrandPrimary = Color(0xFFE85D3A)         // warm coral
val BrandPrimaryDark = Color(0xFFF27A5C)     // dark mode variant
val OnBrandPrimary = Color.White
val OnBrandPrimaryDark = Color(0xFF1A0E08)

// Neutrals (warm grays read more human than cool/blue grays)
val NeutralBg = Color(0xFFFAFAF8)            // warm white
val NeutralBgDark = Color(0xFF161614)        // warm near-black
val NeutralSurface = Color(0xFFFFFFFF)
val NeutralSurfaceDark = Color(0xFF1E1E1B)
val NeutralSurfaceVariant = Color(0xFFF0EFEB)
val NeutralSurfaceVariantDark = Color(0xFF2A2A26)

// Text
val TextPrimary = Color(0xFF1A1A1A)
val TextPrimaryDark = Color(0xFFF2F2F0)
val TextSecondary = Color(0xFF6B6B6B)
val TextSecondaryDark = Color(0xFF9A9A98)

// Outline (for dividers, borders)
val Outline = Color(0xFFD4D4D0)
val OutlineDark = Color(0xFF3A3A36)

// Status colors (use sparingly)
val Success = Color(0xFF2F855A)
val Warning = Color(0xFFC05621)
val Error = Color(0xFFC53030)
```

### Wiring into `ColorScheme`

```kotlin
val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = OnBrandPrimary,
    primaryContainer = BrandPrimary.copy(alpha = 0.12f).compositeOver(NeutralBg),
    onPrimaryContainer = BrandPrimary,

    background = NeutralBg,
    onBackground = TextPrimary,

    surface = NeutralSurface,
    onSurface = TextPrimary,
    surfaceVariant = NeutralSurfaceVariant,
    onSurfaceVariant = TextSecondary,

    outline = Outline,
    outlineVariant = Outline.copy(alpha = 0.5f),

    error = Error,
    onError = Color.White,
)

val DarkColors = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = OnBrandPrimaryDark,
    primaryContainer = BrandPrimaryDark.copy(alpha = 0.20f).compositeOver(NeutralBgDark),
    onPrimaryContainer = BrandPrimaryDark,

    background = NeutralBgDark,
    onBackground = TextPrimaryDark,

    surface = NeutralSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = NeutralSurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,

    outline = OutlineDark,
    outlineVariant = OutlineDark.copy(alpha = 0.5f),

    error = Error,
    onError = Color.White,
)
```

### Dark Mode Rules

- Background should be **warm dark**, not pure `#000000`. Pure black on OLED is fine for power but feels harsh.
- Primary color often needs to be **lighter and less saturated** in dark mode — high-saturation colors vibrate on dark backgrounds.
- Text should be **off-white** (`#F2F2F0`), not pure white. Reduces eye strain.
- Test contrast: minimum 4.5:1 for body text, 3:1 for large text.

### Color Usage Rules

- **Reach for `surfaceVariant` before `surface`** for cards and grouped content. It gives subtle visual separation without elevation.
- **Use `outlineVariant` for dividers** — `outline` is for borders, `outlineVariant` is fainter and for content separation.
- **Status colors (success/warning/error) should appear at most once per screen.** They're attention-grabbing by design.
- **Don't use `primary` for body text.** It's for interactive elements and brand moments.

---

## 3. Shape

Material 3's `Shapes` defines 5 corner sizes. Override them for your brand voice:

```kotlin
// Shape.kt
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),       // M3 default is 16; bumping to 20 reads softer
    extraLarge = RoundedCornerShape(28.dp),
)
```

### Shape Language Choices

- **Sharp (0-4dp)** — utility, financial, dev tools. Reads precise.
- **Soft (8-16dp)** — most consumer apps. Friendly default.
- **Pillowy (20-28dp)** — wellness, lifestyle, content apps. Reads warm.
- **Mixed** — pick one base radius, then use *one* exception (e.g., FAB or hero card) at a different radius for emphasis.

### Asymmetric Shapes

For signature details, asymmetric corners read intentionally designed:

```kotlin
RoundedCornerShape(
    topStart = 32.dp,
    topEnd = 4.dp,
    bottomEnd = 32.dp,
    bottomStart = 4.dp,
)
```

Use sparingly — once per screen at most.

---

## 4. Spacing (Custom Token)

Material 3 has no spacing tokens out of the box. Define your own as a `CompositionLocal`:

```kotlin
// Spacing.kt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class Spacing(
    val none: Dp = 0.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 16.dp,
    val l: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    val xxxl: Dp = 64.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

val MaterialTheme.spacing: Spacing
    @Composable @ReadOnlyComposable
    get() = LocalSpacing.current
```

Usage:

```kotlin
Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m)) {
    Text("Hello")
    Text("World")
}
```

**Rule:** never use raw `.dp` literals for spacing in production composables. Reference the token. The only exception is *positioning* values like `offset = 1.dp` for visual tweaks.

### 8dp Grid Reasoning

- 4dp — tightest spacing (between icon and label inside a chip)
- 8dp — close grouping (between related controls)
- 16dp — standard padding inside containers (card content padding)
- 24dp — screen-edge padding, separation between unrelated groups
- 32dp — major section breaks
- 48dp+ — hero spacing, breathing room around display headlines

---

## 5. Putting It All Together

The complete `Theme.kt`:

```kotlin
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // Update system bar colors to match theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
```

Then in your Activity:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                MyApp()
            }
        }
    }
}
```
