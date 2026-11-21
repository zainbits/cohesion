# Cohesion

The shared, public engine under Zain's Compose libraries (Vitreous glass, Meniscus nav bar): no finished UI, only building blocks.

- `GlassBackdrop` / `glassSource`: record content once; glass replays it, refreshed on the render thread when it scrolls. `glassSource(backdrop, under = other)` layers one backdrop on another (tabs that are glass over a page, recorded with the page behind them for a button they scroll under).
- `GlassScene` + `GlassPass`: one offscreen pass for any outline written as AGSL (`float3 scene(float2 p)`): blur, lens, colour boost, tint (with an accent tint), lit rim, edge shading that turns with the light (dark on light, light on dark), a Fresnel reflection of what is around, pebble shading across the bend, and a flat Solid fallback with no offscreen pass. `drawSolid` draws a scene flat with no backdrop at all (merging and stretch still apply); the offscreen layer is only created on the first glass draw.
- `RoundRectScene`: up to 8 rounded rectangles with elastic stretch and smooth (liquid) merging. `CohesionAgsl` holds the shape snippets.
- `GlassPullSpec` / `GlassPullState` / `glassPullGesture` / `glassPullContent`: elastic pull with Apple's Liquid Lens springs (iOS 26.5 UIKitCore: lift 0.625 / 0.27 s, unlift 0.7 / 0.5 s); circles stretch along the drag, other shapes along x and y. `glassPullGesture` also takes `onLongPress` (held still: a menu, no tap) and `scrollAxis` (in a scrolling list, a drag along the list scrolls it instead of pulling).
- `GlassStyle.forClarity`, `GlassMode`, `rememberGlassThrottled`, `rememberAdaptiveGlassMode`, `ProvideGlass`: the look on one slider, and Solid (or off, your choice) in battery saver or heat.
- `glassPullExempt` / `glassPullNoTap`: inside elastic glass, controls keep their touches (no bounce), and passive zones bounce without counting as a tap.

Android 13+ (minSdk 33). Apache-2.0.

Coordinates once published: `io.github.zainbits:cohesion`. Until then, consumers build it from a checkout next to theirs with a Gradle composite build:

```kotlin
includeBuild("../cohesion") {
    dependencySubstitution { substitute(module("io.github.zainbits:cohesion")).using(project(":cohesion")) }
}
```
