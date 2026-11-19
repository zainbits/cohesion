# Cohesion

The shared, public engine under Zain's Compose libraries (Vitreous glass, Meniscus nav bar): no finished UI, only building blocks.

- `GlassBackdrop` / `glassSource`: record content once; glass replays it, refreshed on the render thread when it scrolls.
- `GlassScene` + `GlassPass`: one offscreen pass for any outline written as AGSL (`float3 scene(float2 p)`): blur, lens, colour boost, tint (with an accent tint), lit rim, and a flat Solid fallback with no offscreen pass.
- `RoundRectScene`: up to 8 rounded rectangles with elastic stretch and smooth (liquid) merging. `CohesionAgsl` holds the shape snippets.
- `GlassPullSpec` / `GlassPullState` / `glassPullGesture` / `glassPullContent`: elastic pull with Apple's Liquid Lens springs (iOS 26.5 UIKitCore: lift 0.625 / 0.27 s, unlift 0.7 / 0.5 s); circles stretch along the drag, other shapes along x and y.
- `GlassStyle.forClarity`, `GlassMode`, `rememberGlassThrottled`, `rememberAdaptiveGlassMode`, `ProvideGlass`: the look on one slider, and Solid (or off, your choice) in battery saver or heat.

Android 13+ (minSdk 33). Apache-2.0.

Coordinates once published: `io.github.zainbits:cohesion`. Until then, consumers build it from a checkout next to theirs with a Gradle composite build:

```kotlin
includeBuild("../cohesion") {
    dependencySubstitution { substitute(module("io.github.zainbits:cohesion")).using(project(":cohesion")) }
}
```
