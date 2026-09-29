# Compose Modifier Idioms

> Idiomatic patterns for `Modifier` usage in Compose. Covers ordering, composition, performance, and common mistakes.

## Table of Contents

1. [Modifier Order Matters](#1-modifier-order-matters)
2. [Reusable Modifier Extensions](#2-reusable-modifier-extensions)
3. [Conditional Modifiers](#3-conditional-modifiers)
4. [Performance: Lambda Modifiers](#4-performance-lambda-modifiers)
5. [Common Idioms](#5-common-idioms)
6. [Anti-Patterns](#6-anti-patterns)

---

## 1. Modifier Order Matters

Compose applies modifiers in order, left to right. This affects layout, drawing, and event handling.

```kotlin
// These produce DIFFERENT results:

// Padding INSIDE the background — background fills full size, content is inset
Modifier
    .background(Color.Red)
    .padding(16.dp)

// Padding OUTSIDE the background — background is inset, padding is transparent
Modifier
    .padding(16.dp)
    .background(Color.Red)
```

### Standard Modifier Order

Follow this order for readability:

1. **Size / fillMax** (`fillMaxSize`, `width`, `height`)
2. **Layout positioning** (`offset`, `align`)
3. **Padding (outer)** — between this composable and its parent
4. **Clip / shape** (`clip`)
5. **Background** (`background`)
6. **Border**
7. **Padding (inner)** — between background and content
8. **Click / pointer** (`clickable`, `pointerInput`)
9. **Semantics** (`semantics`, `testTag`)
10. **Graphics layer** (`graphicsLayer`, `alpha`, `rotate`)

Example:

```kotlin
Box(
    Modifier
        .fillMaxWidth()                             // 1. size
        .padding(horizontal = 24.dp)                // 3. outer padding
        .clip(MaterialTheme.shapes.large)           // 4. clip
        .background(MaterialTheme.colorScheme.surfaceVariant)  // 5. background
        .padding(20.dp)                             // 7. inner padding
        .clickable { onTap() }                      // 8. click
        .semantics { role = Role.Button }           // 9. semantics
)
```

---

## 2. Reusable Modifier Extensions

When the same modifier chain repeats, extract it as an extension. This is the Compose-idiomatic equivalent of a CSS class.

```kotlin
// Define
fun Modifier.cardSurface(): Modifier = this
    .clip(RoundedCornerShape(16.dp))
    .background(MaterialTheme.colorScheme.surfaceVariant)
    .padding(20.dp)

// Use
@Composable
fun MyCard() {
    Column(Modifier.cardSurface()) {
        Text("Title")
        Text("Body")
    }
}
```

For modifiers that need `@Composable` context (e.g., reading `MaterialTheme`):

```kotlin
@Composable
fun Modifier.cardSurface(): Modifier = this
    .clip(MaterialTheme.shapes.large)
    .background(MaterialTheme.colorScheme.surfaceVariant)
    .padding(MaterialTheme.spacing.l)
```

Note: composable modifier extensions can't be chained as `Modifier.cardSurface().clickable {}` directly — wrap with `then`:

```kotlin
Modifier
    .then(if (isCard) Modifier.cardSurface() else Modifier)
    .clickable { }
```

---

## 3. Conditional Modifiers

For optional modifiers based on state:

```kotlin
// Inline ternary
Modifier
    .fillMaxWidth()
    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)

// Extension helper (cleaner for repeated use)
fun Modifier.thenIf(condition: Boolean, modifier: Modifier.() -> Modifier): Modifier =
    if (condition) this.modifier() else this

// Usage
Modifier
    .fillMaxWidth()
    .thenIf(selected) {
        border(2.dp, MaterialTheme.colorScheme.primary)
    }
```

---

## 4. Performance: Lambda Modifiers

Some modifiers take lambdas that read state. To avoid recomposing the *whole* composable when only the modifier needs to update, prefer **lambda-form** modifiers:

```kotlin
// ❌ Causes recomposition of the whole Box when scrollState changes
Box(
    Modifier.offset(y = scrollState.value.dp)
)

// ✅ Only re-runs the offset measurement when scrollState changes
Box(
    Modifier.offset { IntOffset(0, scrollState.value) }
)
```

Same pattern for `graphicsLayer`, `padding`, `size`:

```kotlin
// Reading state inside graphicsLayer lambda — minimal recomposition
Modifier.graphicsLayer {
    alpha = animatedAlpha.value
    scaleX = animatedScale.value
    scaleY = animatedScale.value
}
```

This is one of the most important performance idioms in Compose. Use it whenever a modifier reads an animation or scroll state.

---

## 5. Common Idioms

### Press / Hover States

For interactive elements, use `InteractionSource` for proper press states:

```kotlin
val interactionSource = remember { MutableInteractionSource() }
val pressed by interactionSource.collectIsPressedAsState()
val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")

Box(
    Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = interactionSource,
            indication = null,  // disable default ripple if doing custom
        ) { onTap() }
)
```

### Centering Content

```kotlin
// Center in a Box
Box(contentAlignment = Alignment.Center) { /* content */ }

// Center a single child in a parent
Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
) { /* content */ }
```

### Aspect Ratio

```kotlin
// 16:9 image
Image(
    painter = ...,
    contentDescription = null,
    modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f),
    contentScale = ContentScale.Crop,
)
```

### Safe Click (debounce)

```kotlin
fun Modifier.safeClickable(
    debounceMs: Long = 300,
    onClick: () -> Unit,
): Modifier = composed {
    var lastClick by remember { mutableLongStateOf(0L) }
    clickable {
        val now = System.currentTimeMillis()
        if (now - lastClick >= debounceMs) {
            lastClick = now
            onClick()
        }
    }
}
```

### Conditional Visibility (don't `if {}` around composables)

```kotlin
// ❌ Loses animation state when condition flips
if (visible) {
    Box(...)
}

// ✅ Animates in/out cleanly
AnimatedVisibility(visible = visible) {
    Box(...)
}
```

---

## 6. Anti-Patterns

| Anti-Pattern                                          | Why It's Wrong                                | Fix                                            |
| ----------------------------------------------------- | --------------------------------------------- | ---------------------------------------------- |
| `Modifier.padding(8.dp).padding(8.dp)`                | Two separate padding modifiers; confusing     | Single `.padding(16.dp)` or named params       |
| `.size(0.dp)` to "hide" a composable                  | Layout still measured; bad semantics          | `AnimatedVisibility` or conditional composition |
| Creating `Modifier` inside loops                      | New allocation each frame                     | Hoist `val rowModifier = Modifier...`           |
| `Modifier.background(...).padding(...).background(...)` | Two background draws — wasteful             | Combine into one `.background()` with shape   |
| Using `Modifier.fillMaxSize()` everywhere by reflex   | Often unnecessary; can hide layout bugs       | Use only when you actually want full size     |
| `Modifier.clickable {}` on `Text` for a button        | Tap target too small (text height ≈ 20dp)     | Use `Button` or wrap in `Box.minimumInteractiveComponentSize()` |
| `Modifier.padding(...)` after `.clickable`            | Padding area isn't clickable                  | Padding before `.clickable`                   |
| `Modifier.weight(1f)` outside `Row`/`Column`/`FlowRow` | Compile error or no effect                  | Only use weight inside flex parents           |
