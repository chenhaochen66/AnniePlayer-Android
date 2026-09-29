# Android Design Skill

A Claude skill for designing beautiful, distinctive Android interfaces with Jetpack Compose. Uses Material 3 as a structural foundation but overrides its visual defaults to avoid the recognizable "AI-slop" and template aesthetics that plague AI-generated Android UI.

## What this is

A **skill** is a folder of structured instructions that Claude reads to perform a specific task well. This skill teaches Claude how to:

- Design new Jetpack Compose screens with intentional visual identity
- Set up a hybrid Material 3 theme (custom palette + typography + shape, M3 plumbing for accessibility and motion)
- Avoid the banned patterns of AI-generated Android UI (default purple, dynamic-color-only theming, centered TopAppBar with hamburger + kebab, stacks of elevated cards, etc.)
- Review existing UI against a 5-dimension rubric
- Build a brand-spec document before writing any UI code

## Philosophy

> Keep Material 3's plumbing, replace its visual defaults.

Material 3 is a great *system* — accessibility, motion specs, edge-to-edge, dark mode adaptation. But its visual defaults are identical across every app that ships them unchanged. This skill keeps M3's structural components (`Scaffold`, `MaterialTheme`, semantics, insets) and teaches Claude to override the parts that determine how the app *looks*: the `ColorScheme`, `Typography`, `Shapes`, and component-level styling.

The result: apps that get accessibility, dark mode, and adaptive layouts for free, but don't look like every other Material You app.

## Structure

```
android-design-skill/
├── SKILL.md                          # Entry point — Claude reads this first
├── references/
│   ├── anti-android-slop.md          # Banned patterns with Compose code examples
│   ├── theming-tokens.md             # Typography, color, shape, spacing tokens
│   ├── layout-patterns.md            # Scaffold, lazy lists, adaptive design
│   ├── design-review.md              # 5-dimension scoring rubric
│   ├── compose-idioms.md             # Modifier patterns and performance tips
│   └── xml-views.md                  # Legacy XML Views guidance
└── templates/
    └── brand-spec.md                 # Fill-in-the-blanks brand document
```

`SKILL.md` is loaded into Claude's context whenever the skill triggers. The reference files are loaded on-demand when Claude needs specific guidance (e.g., it'll read `anti-android-slop.md` before reviewing UI, or `theming-tokens.md` before setting up a theme).

## Coverage

- **Primary:** Jetpack Compose with Material 3 (`androidx.compose.material3`)
- **Secondary:** XML Views (legacy projects) — see `references/xml-views.md`
- **Min SDK:** 24+ (some APIs gracefully degrade below 31)
- **Kotlin:** 1.9+
- **Compose BOM:** 2024.02+

## Installation

If you're using a Claude skill manager that accepts `.skill` packages, package the folder first:

```bash
# Using the skill-creator tooling
python -m scripts.package_skill android-design-skill
```

For manual installation in Claude Code or similar environments, place the `android-design-skill/` folder in your skills directory (commonly `~/.claude/skills/` or a project-local `.claude/skills/`).

## When this skill triggers

The skill is designed to trigger on:

- Requests to design new Compose screens or composables
- "Make this look better" / "the UI looks generic" type feedback
- Material 3 theming and customization questions
- Android app prototypes and mockups
- iOS-to-Android design ports
- Any mention of Jetpack Compose, Material You, or Android UI design

## Validation checklist

Every screen designed with this skill should pass:

- [ ] Design tokens defined in `ui/theme/` (`Color.kt`, `Type.kt`, `Shape.kt`, `Spacing.kt`)
- [ ] Custom `AppTheme` wraps `MaterialTheme` with intentional overrides
- [ ] No banned anti-slop patterns present
- [ ] 3+ distinct typography scales actually used
- [ ] Spacing uses an 8dp grid via `MaterialTheme.spacing.*`
- [ ] Dark mode colors defined intentionally (warm dark, off-white text)
- [ ] One signature detail per screen
- [ ] Consistent Material Symbols icon style (Outlined OR Rounded, not mixed)
- [ ] Minimum 48dp tap targets
- [ ] Edge-to-edge enabled; insets handled via `Scaffold` or `windowInsetsPadding`
- [ ] `LazyColumn` / `LazyRow` for any scrollable list of >5 items
- [ ] Adaptive: tested at compact, medium, and expanded `WindowSizeClass` widths
- [ ] TalkBack: all interactive elements have `contentDescription`
- [ ] 5-Dimension Review score ≥ 7/10 on all dimensions

## Contributing

Issues and PRs welcome. Particularly interested in:

- Real-world examples of screens designed with this skill (post a screenshot in an issue)
- Additional anti-slop patterns spotted in the wild
- Updates as Compose and Material 3 evolve
- Translations of the brand-spec template for non-English projects

## License

MIT — see [LICENSE](LICENSE).
