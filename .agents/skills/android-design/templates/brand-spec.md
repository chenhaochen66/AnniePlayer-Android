# Brand Spec — [App Name]

> Fill in every section before writing UI code. If a section is "TBD," design decisions should be deferred until it's resolved.

---

## 1. Brand Summary

- **App name:**
- **One-sentence description:** What the app does and for whom.
- **Voice / personality:** Pick 3 adjectives. Examples: *quiet, precise, generous* — or — *bold, irreverent, fast* — or — *warm, considered, grounded*.
- **Reference apps:** 2-3 apps that capture the feeling you want (not the function).

---

## 2. Color Palette

### Brand Primary

| Token              | Light Hex | Dark Hex  | Notes                                        |
| ------------------ | --------- | --------- | -------------------------------------------- |
| `BrandPrimary`     | `#______` | `#______` | The signature color users will associate with the app |
| `OnBrandPrimary`   | `#______` | `#______` | Text/icon color on `BrandPrimary` (4.5:1 contrast) |

### Neutrals (warm grays preferred over cool/blue)

| Token                   | Light Hex | Dark Hex  | Notes                              |
| ----------------------- | --------- | --------- | ---------------------------------- |
| `NeutralBackground`     | `#______` | `#______` | App background                     |
| `NeutralSurface`        | `#______` | `#______` | Cards, sheets, elevated surfaces   |
| `NeutralSurfaceVariant` | `#______` | `#______` | Subtle grouping (recessed surfaces) |
| `TextPrimary`           | `#______` | `#______` | Main text color                    |
| `TextSecondary`         | `#______` | `#______` | Captions, metadata, secondary text |
| `Outline`               | `#______` | `#______` | Borders                            |
| `OutlineVariant`        | `#______` | `#______` | Dividers (fainter)                 |

### Status Colors (use sparingly)

| Token     | Hex       | Used For                          |
| --------- | --------- | --------------------------------- |
| `Success` | `#______` | Confirmations, positive state     |
| `Warning` | `#______` | Caution, non-blocking alerts      |
| `Error`   | `#______` | Errors, destructive actions       |

### Contrast Verification

- [ ] `TextPrimary` on `NeutralBackground` passes WCAG AA (4.5:1)
- [ ] `OnBrandPrimary` on `BrandPrimary` passes WCAG AA (4.5:1)
- [ ] Dark mode pairs all verified for contrast
- [ ] Status colors pass contrast on both surface and surfaceVariant

---

## 3. Typography

### Font Families

| Role    | Font Name | Source                     | Weights Used        |
| ------- | --------- | -------------------------- | ------------------- |
| Display |           | Google Fonts / Custom file |                     |
| Body    |           | Google Fonts / Custom file |                     |
| Mono (optional) |   | Google Fonts / Custom file |                     |

### Type Scale

Define which of the 15 Material 3 Typography slots you'll actually use (3-5 is typical for a clean hierarchy).

| Slot                | Font     | Size  | Weight       | Line Height | Letter Spacing |
| ------------------- | -------- | ----- | ------------ | ----------- | -------------- |
| `displayLarge`      |          |       |              |             |                |
| `headlineMedium`    |          |       |              |             |                |
| `titleLarge`        |          |       |              |             |                |
| `bodyLarge`         |          |       |              |             |                |
| `labelMedium`       |          |       |              |             |                |

---

## 4. Shape Language

Pick one base radius style:

- [ ] **Sharp** (0-4dp) — utility / financial / dev tools
- [ ] **Soft** (8-16dp) — consumer default
- [ ] **Pillowy** (20-28dp) — wellness / lifestyle / content

### Shape Tokens

| Token         | Corner Radius | Used For                                  |
| ------------- | ------------- | ----------------------------------------- |
| `extraSmall`  |          dp   | Chips, small inputs                       |
| `small`       |          dp   | Text fields, small cards                  |
| `medium`      |          dp   | Standard cards, dialogs                   |
| `large`       |          dp   | Bottom sheets, large surfaces             |
| `extraLarge`  |          dp   | Hero containers, FABs                     |

### Signature Shape (optional)

If using one asymmetric / distinctive shape for a single moment per screen, describe it here:

```
e.g., Hero cards use:
RoundedCornerShape(topStart = 32.dp, topEnd = 4.dp, bottomEnd = 32.dp, bottomStart = 4.dp)
```

---

## 5. Spacing

Confirm the 8dp grid (or document deviation):

| Token  | dp       | Used For                                      |
| ------ | -------- | --------------------------------------------- |
| `xs`   | 4.dp     | Icon-to-label, tightest grouping              |
| `s`    | 8.dp     | Between closely related controls              |
| `m`    | 16.dp    | Standard container padding                    |
| `l`    | 24.dp    | Screen-edge padding                           |
| `xl`   | 32.dp    | Section breaks                                |
| `xxl`  | 48.dp    | Hero spacing                                  |

---

## 6. Iconography

- **Icon style:** [ ] Outlined  [ ] Rounded  [ ] Sharp  [ ] Filled (pick one — stay consistent)
- **Source:** [ ] Material Symbols  [ ] Custom set  [ ] Mix (document where custom is used)
- **Default size in UI:** ____ dp (typical: 24dp)
- **Touch target wrap:** all icons used as tap targets are wrapped to ≥ 48dp

---

## 7. Motion & Easing

- **Default easing:** [ ] `FastOutSlowInEasing`  [ ] Custom `CubicBezierEasing(...)`  
- **Default duration:** ___ ms (M3 default 300ms; consider 250ms for a snappier feel, or 400ms for a more deliberate one)
- **Signature motion:** Describe one motion moment per screen that goes beyond default (e.g., bottom sheet peeks with a slower spring, list items stagger-fade on first load).

---

## 8. Logo Usage

- **Wordmark file:** path / URL
- **Icon-only mark file:** path / URL  
- **Minimum size:** ___ dp height
- **Clear space rule:** at least ___ dp of breathing room on all sides
- **Don't:**
  - Recolor outside the approved palette
  - Apply effects (shadow, gradient, outline)
  - Stretch or skew

---

## 9. Surface Treatment

How does the app distinguish surfaces? Pick one strategy:

- [ ] **Elevation-based** — cards use 1-3dp elevation (M3 default; least distinctive)
- [ ] **Color-based** — surfaces use `surface` vs `surfaceVariant` for separation (no shadows)
- [ ] **Border-based** — surfaces use a 1dp `outlineVariant` border, no elevation
- [ ] **Mixed** — describe when each is used

---

## 10. Photography & Imagery

- **Style:** describe the photographic style if applicable (e.g., natural light, muted, editorial; or technical/diagrammatic)
- **Crop ratios:** 16:9, 4:3, 1:1, custom — document standard ratios
- **Placeholder strategy:** what to show when an image fails to load
- **Don't:**
  - AI-generated stock-style imagery
  - Mixed photographic styles within one screen

---

## 11. Sign-Off Checklist

- [ ] All color hex values verified against an official source or designer
- [ ] Light + dark mode pairs defined for every color token
- [ ] WCAG AA contrast verified for all text-on-background combinations
- [ ] Google Fonts provider configured if applicable
- [ ] Logo files downloaded and version-controlled in the project
- [ ] Spacing follows 8dp grid (or deviation documented with rationale)
- [ ] Shape language picked and consistent
- [ ] One signature detail defined per archetype screen

When all boxes are checked, codify the tokens into `ui/theme/` files (Color.kt, Type.kt, Shape.kt, Spacing.kt, Theme.kt) before writing any screens.
