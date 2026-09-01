package com.theveloper.pixelplay.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.google.android.material.color.utilities.Hct
import com.theveloper.pixelplay.data.preferences.AlbumArtPaletteStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Shared blur source for the ambient artwork backdrop. Null whenever the album-art app theme
 * is off, which is what makes every [ambientFrost] call fall back to an ordinary opaque fill.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * The album scheme with its original opaque colours, before [PixelPlayTheme] softens the
 * container roles for the ambient backdrop.
 *
 * Surfaces that paint their own opaque background — the player sheet above all — must theme
 * from this rather than from `MaterialTheme.colorScheme`, otherwise they inherit the
 * translucent roles and wash out into pale tints of the artwork colour.
 */
val LocalOpaqueColorScheme = compositionLocalOf<ColorScheme?> { null }

/**
 * The active album-art palette style. Egnus is the one style that lets the artwork backdrop
 * through to the player sheet; the others keep it solid.
 */
val LocalAlbumArtPaletteStyle = compositionLocalOf { AlbumArtPaletteStyle.default }

/**
 * How strongly a frosted surface separates itself from the backdrop. Larger blur and tint
 * push a surface visually forward, so the scale runs from panels (which should read as part
 * of the background) up to dialogs (which must clearly float above it).
 */
enum class AmbientFrostLevel {
    /** Large section containers, e.g. the library song list. Barely there. */
    Panel,

    /** Items inside a panel, e.g. song cards. Light, so panel and card stay distinguishable. */
    Card,

    /** Buttons, chips and other controls. Enough tint to read as a filled control. */
    Control,

    /**
     * App bars and tab rows. Blurs the backdrop so the header reads as part of the ambient
     * light rather than a flat colour band laid over it.
     */
    Header,

    /** Navigation bar. Heavier than [Header] so it stays legible over busy artwork. */
    NavBar,

    /** Dialogs, bottom sheets and popups. Heavy blur so content behind them is unreadable. */
    Dialog
}

private data class FrostSpec(
    val blurRadiusDp: Float,
    val tintAlpha: Float,
    val noiseFactor: Float
)

private fun AmbientFrostLevel.spec(): FrostSpec = when (this) {
    AmbientFrostLevel.Panel -> FrostSpec(blurRadiusDp = 20f, tintAlpha = 0.34f, noiseFactor = 0.04f)
    AmbientFrostLevel.Card -> FrostSpec(blurRadiusDp = 12f, tintAlpha = 0.26f, noiseFactor = 0.03f)
    AmbientFrostLevel.Control -> FrostSpec(blurRadiusDp = 20f, tintAlpha = 0.52f, noiseFactor = 0.02f)
    AmbientFrostLevel.Header -> FrostSpec(blurRadiusDp = 30f, tintAlpha = 0.46f, noiseFactor = 0.03f)
    // Heavy blur, barely any tint: the nav bar should read as the backdrop out of focus,
    // not as a coloured bar laid over it.
    AmbientFrostLevel.NavBar -> FrostSpec(blurRadiusDp = 56f, tintAlpha = 0.16f, noiseFactor = 0.02f)
    AmbientFrostLevel.Dialog -> FrostSpec(blurRadiusDp = 48f, tintAlpha = 0.74f, noiseFactor = 0.05f)
}

/**
 * True when the album-art app theme is on and there is a backdrop to actually blur.
 */
val isAmbientActive: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalAmbientAlbumArt.current && LocalHazeState.current != null

/**
 * Frosted-glass background that samples the ambient artwork backdrop behind it.
 *
 * When the album-art theme is off this degrades to `Modifier.background(fallbackColor)`, so a
 * caller can use it unconditionally and the app keeps its original appearance for users who
 * never turned the theme on.
 *
 * @param tint colour mixed into the blur. Defaults to the surface role matching [level], which
 *   in ambient mode already carries the artwork's hue.
 */
fun Modifier.ambientFrost(
    level: AmbientFrostLevel = AmbientFrostLevel.Panel,
    shape: Shape = RectangleShape,
    tint: Color? = null,
    fallbackColor: Color? = null
): Modifier = composed {
    val hazeState = LocalHazeState.current
    val ambient = LocalAmbientAlbumArt.current
    val scheme = MaterialTheme.colorScheme
    val opaqueScheme = LocalOpaqueColorScheme.current ?: scheme

    val resolvedTint = tint ?: when (level) {
        AmbientFrostLevel.Panel -> opaqueScheme.surfaceContainerLow
        AmbientFrostLevel.Card -> opaqueScheme.surfaceContainerHigh
        AmbientFrostLevel.Control -> opaqueScheme.primaryContainer
        AmbientFrostLevel.Header -> opaqueScheme.primaryContainer
        // Desaturated so the blur carries the colour rather than the tint doing it.
        AmbientFrostLevel.NavBar -> opaqueScheme.surfaceContainerLowest.boostChroma(0.35f)
        AmbientFrostLevel.Dialog -> opaqueScheme.surfaceContainer
    }

    if (!ambient || hazeState == null) {
        val solid = fallbackColor ?: when (level) {
            AmbientFrostLevel.Panel -> scheme.surfaceContainerLow
            AmbientFrostLevel.Card -> scheme.surfaceContainerHigh
            AmbientFrostLevel.Control -> scheme.primaryContainer
            AmbientFrostLevel.Header -> scheme.primaryContainer
            AmbientFrostLevel.NavBar -> scheme.surfaceContainer
            AmbientFrostLevel.Dialog -> scheme.surfaceContainer
        }
        return@composed this.clip(shape).background(solid, shape)
    }

    val spec = level.spec()
    this
        .clip(shape)
        .hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = opaqueScheme.surfaceContainerLowest,
                tint = HazeTint(resolvedTint.copy(alpha = spec.tintAlpha)),
                blurRadius = spec.blurRadiusDp.dp,
                noiseFactor = spec.noiseFactor,
                // Below API 31 there is no real blur, so lean on a heavier flat tint instead
                // of leaving the surface almost invisible against the backdrop.
                fallbackTint = HazeTint(resolvedTint.copy(alpha = spec.tintAlpha + 0.34f))
            )
        )
}

/**
 * Pushes a colour's chroma up while holding its hue and tone, used to stop album palettes
 * reading as washed-out pastels once they are alpha-blended over the backdrop. A red cover
 * should still look red, not pale pink.
 */
fun Color.boostChroma(factor: Float, minChroma: Double = 0.0): Color {
    if (factor == 1f && minChroma == 0.0) return this
    return runCatching {
        val hct = Hct.fromInt(toArgb())
        val boosted = (hct.chroma * factor).coerceAtLeast(minChroma)
        Color(Hct.from(hct.hue, boosted, hct.tone).toInt()).copy(alpha = alpha)
    }.getOrDefault(this)
}

/**
 * Opens up the opaque container roles so the ambient artwork backdrop reads through them.
 * Text and icon roles stay fully opaque so contrast is unaffected; only the fills that would
 * otherwise hide the backdrop are softened, which is what gives dark filled buttons their glow.
 *
 * Shared by the app theme and by the player sheet, which applies it to its own album scheme
 * only under the Egnus palette — every other palette keeps solid player surfaces.
 */
fun ColorScheme.toAmbientColorScheme(): ColorScheme = copy(
    // Only `background` fully opens up: it is the app root fill, so clearing it is what lets
    // the backdrop through. `surface` stays opaque because dialogs and bottom sheets rely on
    // it to stay readable over arbitrary content.
    background = Color.Transparent,
    // Chroma is boosted before the alpha is applied. Blending a colour over the backdrop
    // costs saturation, so without this a strongly coloured cover ends up as a pastel wash —
    // a red album reading as pale pink.
    surfaceContainerLowest = surfaceContainerLowest.ambient(AmbientSurfaceAlpha),
    surfaceContainerLow = surfaceContainerLow.ambient(AmbientSurfaceAlpha),
    surfaceContainer = surfaceContainer.ambient(AmbientSurfaceAlpha),
    surfaceContainerHigh = surfaceContainerHigh.ambient(AmbientRaisedSurfaceAlpha),
    surfaceContainerHighest = surfaceContainerHighest.ambient(AmbientRaisedSurfaceAlpha),
    surfaceVariant = surfaceVariant.ambient(AmbientRaisedSurfaceAlpha),
    primary = primary.ambient(AmbientFilledAlpha),
    secondary = secondary.ambient(AmbientFilledAlpha),
    tertiary = tertiary.ambient(AmbientFilledAlpha),
    primaryContainer = primaryContainer.ambient(AmbientContainerAlpha),
    secondaryContainer = secondaryContainer.ambient(AmbientContainerAlpha),
    tertiaryContainer = tertiaryContainer.ambient(AmbientContainerAlpha)
)

private fun Color.ambient(alpha: Float): Color =
    boostChroma(AmbientChromaBoost, minChroma = AmbientMinChroma).copy(alpha = alpha)

private const val AmbientChromaBoost = 1.55f
private const val AmbientMinChroma = 26.0
private const val AmbientSurfaceAlpha = 0.66f
private const val AmbientRaisedSurfaceAlpha = 0.74f
private const val AmbientContainerAlpha = 0.70f
private const val AmbientFilledAlpha = 0.82f

/**
 * Restores fully opaque album colours for a subtree, and switches off ambient frosting inside
 * it. Used for the player sheet under every palette except Egnus, so "now playing" keeps the
 * solid, high-contrast background it had before the album-art theme existed.
 */
@Composable
fun SolidSurfaceTheme(
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val opaqueScheme = LocalOpaqueColorScheme.current
    if (!enabled || opaqueScheme == null || !LocalAmbientAlbumArt.current) {
        content()
        return
    }
    CompositionLocalProvider(LocalAmbientAlbumArt provides false) {
        MaterialTheme(
            colorScheme = opaqueScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content
        )
    }
}

/**
 * The album scheme with its original opaque colours, falling back to the active theme.
 *
 * Modal bottom sheets and dialogs render in their own window, so a [ambientFrost] there cannot
 * sample the app's backdrop — there is nothing of the app behind them to blur. Translucent
 * roles in that context just show the dimmed content underneath, which reads as a washed-out
 * popup rather than glass. Those surfaces therefore stay opaque.
 */
@Composable
fun opaqueColorScheme(): ColorScheme = LocalOpaqueColorScheme.current ?: MaterialTheme.colorScheme

/**
 * A [Dialog] whose window can sample the app's ambient backdrop.
 *
 * A dialog renders in its own window, so an ordinary [ambientFrost] inside one has no app
 * content behind it to blur. Haze's dialog host bridges the two windows, which lets surfaces
 * inside the dialog frost against the same backdrop the rest of the app uses.
 *
 * Falls back to a plain [Dialog] when the album-art theme is off, so behaviour is unchanged
 * for everyone else.
 */
@Composable
fun AmbientDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    val hazeState = LocalHazeState.current
    if (hazeState != null) {
        HazeDialog(
            hazeState = hazeState,
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = content
        )
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = content
        )
    }
}
