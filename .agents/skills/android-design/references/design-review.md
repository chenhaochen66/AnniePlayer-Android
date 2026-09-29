# 5-Dimension Design Review

> A structured review process for any Android UI before it ships. Score each dimension 1-10. **Any dimension scoring below 7 must be addressed before considering the design complete.**

## How to Use This Review

1. Before review: ensure the screen is rendered (preview, emulator, or device)
2. For each dimension, walk through the prompts and score honestly
3. Note specific issues per dimension
4. Address anything below 7 before shipping
5. Document the final score in PRs or design handoffs

---

## Dimension 1: Visual Identity (1-10)

**Question:** Could a stranger identify this as *my* app, or could it be any Material 3 app?

### Prompts

- Is the `colorScheme.primary` distinctive (not default purple)?
- Is the typography distinctive (not default Roboto for display)?
- Is the shape language consistent and intentional (not all 12dp rounded everywhere)?
- Is there at least one *signature detail* on this screen that goes 120%?
- If you screenshotted this without the app name, would it be recognizable?

### Score Guide

| Score | Description                                                              |
| ----- | ------------------------------------------------------------------------ |
| 10    | Strong, distinctive identity. Screenshot is immediately recognizable.    |
| 8-9   | Clear identity, with one signature detail that's memorable.              |
| 7     | Custom palette and typography, but no standout moments.                  |
| 4-6   | Some customization, but mostly Material defaults.                        |
| 1-3   | "Stock Material 3 app" — defaults everywhere, no overrides.              |

---

## Dimension 2: Hierarchy & Information Architecture (1-10)

**Question:** Can the user instantly tell what's most important on this screen?

### Prompts

- Is there a clear visual hierarchy — one primary element, secondary supporting elements?
- Are there at least 3 distinct text scales actually used (not just available)?
- Does spacing reinforce grouping? Related items closer, unrelated items farther apart?
- Is there one obvious primary action, or is the user confronted with 5 equally-weighted buttons?
- Could you remove 30% of the content and the screen would still work? (If yes — remove it.)

### Score Guide

| Score | Description                                                              |
| ----- | ------------------------------------------------------------------------ |
| 10    | Crystal clear hierarchy. The eye knows exactly where to go.              |
| 8-9   | Strong hierarchy with one minor competing element.                       |
| 7     | Hierarchy exists but could be sharpened.                                 |
| 4-6   | Multiple competing elements, unclear primary action.                     |
| 1-3   | Everything is equally weighted — visual noise.                           |

---

## Dimension 3: Density & Whitespace (1-10)

**Question:** Does the screen breathe, or does it feel cramped / over-stuffed?

### Prompts

- Is there generous space around the primary content (≥24dp from screen edges)?
- Are sections separated by enough space to feel distinct (≥32dp between unrelated sections)?
- Does any single screen attempt to do too many things at once?
- Is whitespace treated as a feature (intentional), not as emptiness (something to fill)?
- Does the screen pass the "squint test" — does the layout structure still read when blurred?

### Score Guide

| Score | Description                                                              |
| ----- | ------------------------------------------------------------------------ |
| 10    | Generous, intentional whitespace. Content has room to breathe.           |
| 8-9   | Good rhythm; minor cramped spots.                                        |
| 7     | Adequate spacing; nothing breaks but nothing sings.                      |
| 4-6   | Several cramped sections; over-stuffed in places.                        |
| 1-3   | Screen is a wall of content. No breathing room.                          |

---

## Dimension 4: Material 3 Hygiene (1-10)

**Question:** Is the M3 foundation correct even where the visuals are overridden?

### Prompts

- Is `enableEdgeToEdge()` called and are insets handled correctly?
- Are all interactive elements ≥48dp in tap target size?
- Do all icons have `contentDescription` (or `null` if decorative)?
- Does the screen respect the user's dynamic font size setting?
- Is dark mode defined intentionally (warm dark, off-white text), not just inverted colors?
- Are `LazyColumn`/`LazyRow` used for any scrollable list of >5 items?
- Are window size classes considered (does it work on a tablet)?

### Score Guide

| Score | Description                                                              |
| ----- | ------------------------------------------------------------------------ |
| 10    | All M3 hygiene checks pass. Accessibility + adaptive + edge-to-edge nailed. |
| 8-9   | Strong fundamentals; 1-2 minor a11y or adaptive misses.                  |
| 7     | Works on phone in portrait; edges cases unaddressed.                     |
| 4-6   | Multiple a11y or adaptive issues; broken on some configurations.         |
| 1-3   | Hardcoded sizes, missing semantics, broken edge-to-edge.                 |

**Note:** This dimension is non-negotiable. A 6 here means real users will have problems.

---

## Dimension 5: Anti-Slop Test (1-10)

**Question:** Does this screen contain any of the banned patterns from `anti-android-slop.md`?

### Hard Disqualifiers (any one = max score 4)

- Default purple `primary` shipped unchanged
- Dynamic color as the *only* theming strategy
- Centered TopAppBar with hamburger + 3-dot menu
- Purple-to-blue gradient background
- Emoji as functional icons
- Generic "Welcome to [App]!" hero screen
- Symmetric 3-column feature card grid
- AI-generated VectorDrawable clipart
- Pure `#000000` background with neon-saturated text
- Mixed icon styles (Default + Outlined + Rounded mixed)

### Soft Issues (each one = -1 from score)

- Roboto used as display font
- Stack of elevated cards as entire screen
- Default `FloatingActionButton(Icons.Default.Add)` unchanged
- No signature detail on the screen
- All M3 component defaults shipped unchanged

### Score Guide

| Score | Description                                                              |
| ----- | ------------------------------------------------------------------------ |
| 10    | Zero slop patterns. Every Material component has intentional overrides.  |
| 8-9   | One minor soft issue.                                                    |
| 7     | A few soft issues but no hard disqualifiers.                             |
| 4-6   | One hard disqualifier or multiple soft issues.                           |
| 1-3   | Multiple hard disqualifiers — this looks AI-generated.                   |

---

## Total Score Interpretation

- **45-50:** Ship it. This is portfolio-quality work.
- **40-44:** Ship after addressing the lowest-scoring dimension.
- **35-39:** Needs another design pass before shipping.
- **30-34:** Significant rework needed.
- **Below 30:** Start over from the design system — fundamentals are missing.

---

## Quick Review Template

```
SCREEN: ___________________________

1. Visual Identity:       __/10  Notes:
2. Hierarchy:             __/10  Notes:
3. Density / Whitespace:  __/10  Notes:
4. M3 Hygiene:            __/10  Notes:
5. Anti-Slop:             __/10  Notes:

TOTAL: __/50

Hard disqualifiers present? Y / N
Action items before ship:
1.
2.
3.
```
