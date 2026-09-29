---
name: android-design-skill
description: Android frontend visual design skill for Jetpack Compose (primary) and XML Views (reference). Creates beautiful, distinctive Android interfaces that use Material 3 as a foundation but override its generic defaults to avoid AI-slop and template aesthetics. Use when designing new Compose screens, reviewing Android UI quality, creating Android app prototypes, choosing visual styles, improving app aesthetics, integrating brand assets into Compose, or when the UI looks generic, Material-default, or AI-generated. Make sure to use this skill any time the user mentions Android UI, Jetpack Compose, Material 3, Material You, Android theming, or wants to build a screen, component, or visual prototype for an Android app — even if they don't explicitly say "design".
license: MIT
metadata:
  author: wholiver
  version: "1.0.0"
---

# Android Design Skill

> **TL;DR**: Design Android apps that look intentionally crafted, not Material-template-generated. Use Material 3 as structural foundation (theming, accessibility, motion) but override its visual defaults. Follow the 6 Anti-Slop Rules, use the Design Direction workflow when requirements are vague, and always pass the 5-Dimension Review before shipping.

## When to Use

- Designing new Compose screens or composables from scratch
- Reviewing or improving existing Android UI quality
- Creating Android app prototypes or mockups
- Choosing a visual style or design direction
- Integrating brand assets into a Compose theme
- When someone says "the UI looks generic" or "it looks like every other Material app"
- When building a new feature that needs visual design
- Porting iOS designs to Android

## When NOT to Use

- Pure backend / API / data layer work with no UI
- Fixing Compose compilation errors or recomposition bugs (use a Compose-expert skill)
- Performance optimization of existing composables
- Accessibility audits only (though this skill respects a11y, deeper audits need a dedicated skill)
- Legacy projects that are XML-only with no migration plan (this skill prioritizes Compose; see `references/xml-views.md` for XML guidance)

## Prerequisites

- Android Studio Hedgehog or later (Iguana+ recommended)
- Kotlin 1.9+
- Jetpack Compose BOM 2024.02+ with Material 3 (`androidx.compose.material3`)
- Min SDK 24+ recommended (some APIs gracefully degrade below 31)
- Familiarity with `@Composable`, `Modifier`, and `MaterialTheme`

---

## Core Design Philosophy

These 6 principles guide every design decision. They are adapted from real-world Android design practice and the anti-patterns observed in AI-generated and template-driven Material UI.

### Principle 1: Start From Context, Never Blank

Before designing any screen, ask:

- Is there an existing design system, UI kit, Figma file, or established theme?
- Is there an existing codebase with a custom `MaterialTheme` wrapper, color palette, or typography set?
- Are there brand guidelines or a style guide?
- Is this app meant to match an existing iOS counterpart (cross-platform parity), or feel native to Android?

If no context exists, create a **brand-spec.md** first using `templates/brand-spec.md`.

### Principle 2: Junior Designer Mode — Hypothesize Early, Iterate Fast

Don't wait for the "perfect" design. Show hypotheses with placeholders early:

- A `Box` with `MaterialTheme.colorScheme.surfaceVariant` is a **better** placeholder than a bad AI-generated `VectorDrawable`
- System fonts with good spacing > custom fonts with bad spacing
- A rough but honest layout > a polished but generic one
- A `Spacer` with a TODO comment > shipping wrong proportions

### Principle 3: Offer Variations, Not Finals

Always present **2-3 distinct design directions**, not one "final answer." Each variation should:

- Represent a different design philosophy (e.g., editorial, functional, expressive)
- Have a clear rationale for why it works
- Be visually distinct — not just `primary` color swaps in the theme

### Principle 4: Placeholder > Bad Implementation

If you cannot source a real asset (icon, image, illustration):

- Use a clean `surfaceVariant`-filled `Box` with a labeled `Text` overlay
- Never use AI-generated vector clipart
- Never use emoji as icon substitutes in production UI
- For icons, prefer **Material Symbols** (via `androidx.compose.material.icons.Icons`) — they're Google's official, exhaustive icon system. If a Material Symbol doesn't fit, use a custom SVG imported as `ImageVector` rather than emoji or stock clipart.

### Principle 5: System First, Don't Fill

Every element must earn its place. Before adding any UI element, ask:

- Does this serve the user's goal?
- Can this be combined with something else?
- What happens if I remove it?

Whitespace is a feature, not emptiness. Material 3's default density encourages over-stuffing — resist it.

### Principle 6: Anti-AI-Slop (and Anti-Material-Default)

These patterns are **banned** in production UI. See `references/anti-android-slop.md` for the full list with code examples.

**Banned visual patterns (the "Material default app" look):**

- ❌ Dynamic color (`dynamicColor = true`) as the only theming strategy — looks identical to every other Material You app
- ❌ Default purple/indigo `colorScheme.primary` shipped unchanged
- ❌ Centered `TopAppBar` title with hamburger + 3-dot menu (the universal Material default)
- ❌ Stack of elevated `Card`s with default 1dp elevation as the entire screen
- ❌ Generic large FAB with default `Icons.Default.Add`
- ❌ `Icons.Default.*` filled-style icons everywhere (use outlined or rounded variants for restraint)
- ❌ Purple-to-blue gradient backgrounds
- ❌ Emoji as functional icons
- ❌ Generic "Welcome!" hero with centered text and gradient
- ❌ Symmetric 3-column feature grids
- ❌ Roboto as a display font — it's a body font (use a serif or distinctive sans for display)

**What to do instead:**

- ✅ Custom `ColorScheme` derived from a brand palette; offer dynamic color as an *opt-in*, not the default
- ✅ Material Symbols **Outlined** or **Rounded** style for restraint
- ✅ A `TopAppBar` variant that fits the app's voice (e.g., large title left-aligned, or no app bar at all on hero screens)
- ✅ Serif or distinctive sans-serif (e.g., Inter, DM Serif Display, Manrope) for display type via the `Font(googleFont = ...)` API
- ✅ One signature detail at 120% effort per screen
- ✅ Real photography or clean labeled placeholders

---

## The Hybrid Stance: M3 Foundation, Custom Surface

This skill's central philosophy: **keep Material 3's plumbing, replace its visual defaults.**

| Keep from Material 3                  | Override / customize                                   |
| ------------------------------------- | ------------------------------------------------------ |
| `MaterialTheme` wrapper               | The `ColorScheme`, `Typography`, `Shapes` passed in    |
| `Scaffold`, `Surface` semantics       | Their default colors, elevations, shapes               |
| Component contracts (`Button`, `Card`) | Their visual treatment via custom theme + modifiers   |
| Motion specs (`tween`, `spring`)      | Default durations — use intentional, slower easings    |
| Accessibility (semantics, talkback)   | Nothing — never override accessibility                 |
| Edge-to-edge + insets handling        | Nothing — always respect system bars                   |
| Dark mode adaptation                  | The dark palette itself (define it intentionally)      |
| Touch target sizes (48dp min)         | Nothing — never shrink below WCAG/M3 minimums          |

**Rule of thumb:** if a Compose API is about *behavior or accessibility*, keep its defaults. If it's about *visual appearance*, define your own.

---

## Brand Asset Protocol

When building UI for a specific brand, follow this 5-step hard flow:

1. **Ask** — Request brand guidelines, logo files, color palette, typography
2. **Search** — If not provided, search the web for "[brand name] brand guidelines"
3. **Download** — Get actual asset files (PNG/SVG logos, font files; use Google Fonts API where possible)
4. **Verify** — Confirm colors match official sources (check hex values, then convert to Compose `Color(0xFF...)`)
5. **Write brand-spec.md** — Document: primary/secondary colors (light + dark), typography pairing, logo usage rules, spacing tokens, shape tokens

**Quality threshold:** 5 real brand colors (each with light + dark variant), 10 verified design tokens, 2 font families, 8dp spacing grid.

If no brand exists, create a brand-spec.md with:

- 1 primary accent color (warm or distinctive, not default Material purple)
- 1 neutral palette (grays with warm undertone)
- 1 display font + 1 body font (Google Fonts via Compose's `GoogleFont` API)
- 8dp spacing grid
- 3 shape tokens (small, medium, large corner radii)

See `templates/brand-spec.md` for the full template.

---

## Design Direction Workflow

When requirements are vague or the user asks "what style should we use?", follow this workflow:

### Phase 1: Understand Context (5 min)

1. Ask: What is the app? Who is the user? Is this phone, tablet, foldable, Wear OS, or TV?
2. Ask: Any reference apps they admire? (Cross-platform examples are useful too — what's the *feeling*?)
3. Check: Existing design system, theme file, or brand assets?
4. Check: Cross-platform parity required (matches an iOS app), or Android-native feel?

### Phase 2: Recommend 3 Directions

Present 3 distinct design philosophies from different schools:

| School           | Example Directions                                          |
| ---------------- | ----------------------------------------------------------- |
| **Information**  | Clean data-first, chart-heavy, dense lists (e.g., trading apps)  |
| **Editorial**    | Magazine layout, serif display type, generous whitespace    |
| **Expressive**   | Bold color, asymmetric layout, motion-forward, custom shapes |
| **Functional**   | Dense utility, monospace accents, minimal decoration (e.g., dev tools) |
| **Warm Minimal** | Soft neutrals, rounded shapes, subtle texture, slower motion |
| **Material+**    | M3 components but with a strong custom palette and one signature gesture |

### Phase 3: Present Visual Anchors

For each direction, describe:

- Color palette (3-5 colors with hex values; include dark mode pair)
- Typography pairing (display + body, ideally a Google Font + system fallback)
- Layout density (comfortable / compact / spacious)
- Shape language (sharp / soft / mixed)
- Signature detail (one standout element per screen)

### Phase 4: Let User Choose

Present options clearly. After choice, lock in the design system and codify it in a `Theme.kt` file.

---

## Android Design Workflow

### Step 1: Define the Design System (Before Coding)

Create a `ui/theme/` package with these files. The structure mirrors what Android Studio generates but with intentional overrides.

```
ui/theme/
├── Color.kt        — brand palette + semantic tokens
├── Type.kt         — typography scale with Google Fonts
├── Shape.kt        — corner radius tokens
├── Spacing.kt      — 8dp grid as a custom CompositionLocal
└── Theme.kt        — assembles MaterialTheme with overrides
```

Example `Color.kt`:

```kotlin
// Brand palette (light + dark pairs)
val CoralWarm = Color(0xFFE85D3A)
val CoralWarmDark = Color(0xFFF27A5C)

val NeutralBackground = Color(0xFFFAFAF8)   // Warm white
val NeutralBackgroundDark = Color(0xFF161614)

val TextPrimary = Color(0xFF1A1A1A)
val TextPrimaryDark = Color(0xFFF2F2F0)

val TextSecondary = Color(0xFF6B6B6B)
val TextSecondaryDark = Color(0xFF9A9A98)

val LightColors = lightColorScheme(
    primary = CoralWarm,
    background = NeutralBackground,
    surface = NeutralBackground,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
)

val DarkColors = darkColorScheme(
    primary = CoralWarmDark,
    background = NeutralBackgroundDark,
    surface = NeutralBackgroundDark,
    onPrimary = Color.Black,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark,
)
```

Example `Spacing.kt` (Material 3 has no spacing tokens — define your own):

```kotlin
data class Spacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 16.dp,
    val l: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

// Usage: MaterialTheme.spacing.m
val MaterialTheme.spacing: Spacing
    @Composable @ReadOnlyComposable
    get() = LocalSpacing.current
```

Example `Theme.kt`:

```kotlin
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is OFF by default — opt in deliberately
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

See `references/theming-tokens.md` for the full Typography and Shape examples, including Google Fonts integration.

### Step 2: Build the Layout Hierarchy

Follow the patterns in `references/layout-patterns.md`:

- Use `Column` for vertical flow, `Row` for horizontal
- Use `LazyColumn` / `LazyRow` for scrollable lists (never `Column` + `verticalScroll` for long content — it composes everything eagerly)
- Use `LazyVerticalGrid` for grids
- Use `Scaffold` for screens with system bars, top app bar, FAB, or snackbar
- Use `Box` with `Modifier.fillMaxSize()` sparingly — prefer `Scaffold` slots
- Respect insets: use `Modifier.windowInsetsPadding(...)` or `Scaffold`'s `contentPadding`
- For adaptive layouts (foldables, tablets), use `WindowSizeClass` — never check screen width in pixels

### Step 3: Apply Typography Hierarchy

Follow `references/theming-tokens.md`:

- **Display** (hero titles): Serif or distinctive sans, 32-45sp, weight 600+
- **Headline** (screen / section titles): 24-28sp, weight 600
- **Title** (card titles, list section headers): 18-20sp, weight 500
- **Body** (content text): 14-16sp, weight 400, `onSurface` color
- **Label** (buttons, chips, captions): 12-14sp, weight 500, often `onSurfaceVariant`

Map these into the `Typography` object so `Text(style = MaterialTheme.typography.headlineLarge)` works idiomatically.

### Step 4: Add the Signature Detail

Each screen should have ONE element that goes 120% — a custom animation, a unique layout choice, a distinctive color moment. This is what makes the design memorable.

Examples for Compose:

- A `LazyColumn` with a parallaxing header that uses `derivedStateOf` on scroll offset
- A custom pull-to-refresh using `PullToRefreshContainer` with a branded animation
- A `FlowRow` of chips with a hand-tuned `spring()` enter animation
- A header that uses the brand's display font at an oversized scale (e.g., 56sp) with negative letter spacing
- A `BottomSheetScaffold` that peeks with intentional easing (slower than Material default)
- A subtle parallax on a hero image via `Modifier.graphicsLayer { translationY = scrollState.value * 0.5f }`

### Step 5: Review Against Checklist

Before shipping, run the 5-Dimension Review (see `references/design-review.md`).

---

## Common Mistakes to Avoid

| Mistake                                              | Fix                                                                 |
| ---------------------------------------------------- | ------------------------------------------------------------------- |
| Shipping with `dynamicColor = true` as default       | Define a brand `ColorScheme`; make dynamic color opt-in             |
| Hardcoded hex colors in composables                  | Reference `MaterialTheme.colorScheme.*` or your custom tokens       |
| Hardcoded `.dp` values scattered everywhere          | Use the `Spacing` CompositionLocal: `MaterialTheme.spacing.m`       |
| `Column` + `Modifier.verticalScroll` for long lists  | Use `LazyColumn`                                                    |
| Hardcoded screen widths (`Modifier.width(360.dp)`)   | Use `WindowSizeClass` or `BoxWithConstraints` for adaptive layouts  |
| `Icons.Default.Add` everywhere                       | Pick a consistent style: `Icons.Outlined.*` or `Icons.Rounded.*`    |
| Emoji as icons                                       | Use Material Symbols or custom `ImageVector` assets                 |
| Default purple `primary`                             | Define a brand palette in `Color.kt`                                |
| Default `TopAppBar` with hamburger + 3 dots          | Question whether you need a top bar; if yes, customize its content  |
| All content in one giant `Column`                    | Break into semantic composables with clear hierarchy                |
| No visual hierarchy (everything same `MaterialTheme.typography.bodyMedium`) | Use 3+ type scales (display, headline, title, body, label) |
| Ignoring dark mode                                   | Define `DarkColors` intentionally; test with `isSystemInDarkTheme`  |
| Generic "Welcome!" hero                              | Show actual content immediately                                     |
| Forgetting edge-to-edge                              | Call `enableEdgeToEdge()` in Activity; use `Scaffold` insets        |
| Ignoring TalkBack / semantics                        | Add `Modifier.semantics` and `contentDescription` to icons          |
| Tap targets smaller than 48dp                        | Use `Modifier.minimumInteractiveComponentSize()` or pad to 48dp     |

---

## Quick Reference: Topic Router

| Topic                                  | Reference File                       |
| -------------------------------------- | ------------------------------------ |
| Anti-AI-Slop Rules (detailed)          | `references/anti-android-slop.md`    |
| Layout Patterns & Adaptive Design      | `references/layout-patterns.md`      |
| Typography, Color & Shape Tokens       | `references/theming-tokens.md`       |
| 5-Dimension Design Review              | `references/design-review.md`        |
| Compose Modifier Idioms                | `references/compose-idioms.md`       |
| XML Views Guidance (legacy projects)   | `references/xml-views.md`            |
| Brand Spec Template                    | `templates/brand-spec.md`            |

---

## Validation Checklist

Before considering any Android design complete, verify:

- [ ] Design tokens defined: `Color.kt`, `Type.kt`, `Shape.kt`, `Spacing.kt`
- [ ] Custom `AppTheme` wraps `MaterialTheme` with intentional overrides
- [ ] No banned anti-slop / Material-default patterns present
- [ ] Typography has clear hierarchy (3+ scales actually used on screen)
- [ ] Spacing uses 8dp grid via `MaterialTheme.spacing.*`, not raw `.dp` literals
- [ ] Dark mode colors defined intentionally and tested
- [ ] One signature detail per screen
- [ ] Material Symbols used for icons (consistent style — outlined OR rounded, not mixed)
- [ ] Minimum 48dp tap targets verified
- [ ] Edge-to-edge enabled; insets handled via `Scaffold` or `windowInsetsPadding`
- [ ] `LazyColumn` / `LazyRow` for any scrollable list of >5 items
- [ ] Adaptive: tested at compact (phone), medium (foldable unfolded), expanded (tablet) widths
- [ ] TalkBack: all interactive elements have `contentDescription` or semantics
- [ ] 5-Dimension Review score ≥ 7/10 on all dimensions
