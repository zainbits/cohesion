# Cohesion

The shared, public engine under Zain's Compose libraries (Vitreous glass, Meniscus nav bar): no finished UI, only building blocks.

- `GlassBackdrop` / `glassSource`: record content once; glass replays it, refreshed on the render thread when it scrolls. Glass on glass: `glassSource(upper, under = lower)` records a layer (glass included) over another backdrop, so higher glass pointed at `upper` shows everything under it, like a pane laid on another (a composer over a title bar's open panel). `under` goes only into the backdrop, never drawn twice on screen.
- `GlassScene` + `GlassPass`: one offscreen pass for any outline written as AGSL (`float3 scene(float2 p)`): blur, lens (Snell: a dome bends light toward its middle), colour boost, tint (with an accent tint), and one surface model for the edge: the lens curve sets the tilt everywhere, and Fresnel reflection (Schlick), the highlight (Blinn-Phong from a ring of light), edge shading and pebble shading all follow from it. A soft shadow (offset down, penumbra fading to nothing, dithered), a press lift with a soft light under the finger (`sceneLift`), and a flat Solid fallback with no offscreen pass. The pass always reaches a little past the shape, so the edge reflects and blurs what is really around it. `drawSolid` draws a scene flat with no backdrop at all (merging and stretch still apply); the offscreen layer is only created on the first glass draw.
- `RoundRectScene`: up to 8 rounded rectangles with elastic stretch and smooth (liquid) merging. `CohesionAgsl` holds the shape snippets.
- `GlassPullSpec` / `GlassPullState` / `glassPullGesture` / `glassPullContent`: elastic pull (the finger's speed on release carries into the spring back) with Apple's Liquid Lens springs (iOS 26.5 UIKitCore: lift 0.625 / 0.27 s, unlift 0.7 / 0.5 s); circles stretch along the drag, other shapes along x and y. `glassPullGesture` also takes `onLongPress` (held still: a menu, no tap) and `scrollAxis` (in a scrolling list, a drag along the list scrolls it instead of pulling).
- `GlassStyle.forClarity`, `GlassMode`, `rememberGlassThrottled`, `rememberAdaptiveGlassMode`, `rememberGlassContrast`, `ProvideGlass`: the look on one slider, Solid (or off, your choice) in battery saver or heat, and more tint with a stronger outline when the phone's High contrast text or contrast level is on.
- `glassPullExempt` / `glassPullNoTap`: inside elastic glass, controls keep their touches (no bounce), and passive zones bounce without counting as a tap.

Android 13+ (minSdk 33). Apache-2.0.

Coordinates once published: `io.github.zainbits:cohesion`. Until then, consumers build it from a checkout next to theirs with a Gradle composite build:

```kotlin
includeBuild("../cohesion") {
    dependencySubstitution { substitute(module("io.github.zainbits:cohesion")).using(project(":cohesion")) }
}
```
