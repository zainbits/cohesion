# Cohesion

The shared, public engine under Zain's Compose libraries (Vitreous glass, Meniscus nav bar): no finished UI, only building blocks.

- `GlassBackdrop` / `glassSource`: record content once; glass replays it, refreshed on the render thread when it scrolls.
- `GlassScene` + `GlassPass`: one offscreen pass for any outline written as AGSL (`float3 scene(float2 p)`): blur, lens, colour boost, tint (with an accent tint), lit rim, and a flat Solid fallback with no offscreen pass.
- `RoundRectScene`: up to 8 rounded rectangles with elastic stretch and smooth (liquid) merging. `CohesionAgsl` holds the shape snippets.
- `GlassPullSpec` / `GlassPullState` / `glassPullGesture` / `glassPullContent`: elastic pull with Apple's Liquid Lens springs (iOS 26.5 UIKitCore: lift 0.625 / 0.27 s, unlift 0.7 / 0.5 s); circles stretch along the drag, other shapes along x and y.
- `GlassStyle.forClarity`, `GlassMode`, `rememberGlassThrottled`, `rememberAdaptiveGlassMode`, `ProvideGlass`: the look on one slider, and Solid (or off, your choice) in battery saver or heat.
- `GlassToneProbe`, `GlassTone`: adaptive text colour (dark over bright content, like iOS). It reads a thin strip of the screen beside the glass with PixelCopy on a background thread (48 x 4 pixels, never blocking), only once scrolling has stopped for 150 ms (at most once a second while the content keeps changing). `GlassBackdrop.changes`/`scrolls` drive it; `notifyChanged()` for scrolling done in code.
- `glassPullExempt` / `glassPullNoTap`: inside elastic glass, controls keep their touches (no bounce), and passive zones bounce without counting as a tap.

Android 13+ (minSdk 33). Apache-2.0.

Coordinates once published: `io.github.zainbits:cohesion`. Until then, consumers build it from a checkout next to theirs with a Gradle composite build:

```kotlin
includeBuild("../cohesion") {
    dependencySubstitution { substitute(module("io.github.zainbits:cohesion")).using(project(":cohesion")) }
}
```
