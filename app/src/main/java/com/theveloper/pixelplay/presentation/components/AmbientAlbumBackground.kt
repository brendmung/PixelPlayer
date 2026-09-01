package com.theveloper.pixelplay.presentation.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.theveloper.pixelplay.ui.theme.boostChroma
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

private const val ArtLayerAlpha = 0.68f
private const val ArtLayerScale = 1.22f
private const val HeaderTintAlpha = 0.52f
private const val GlowAlpha = 0.38f
private val BlurRadius = 38.dp
/** Artwork colours lose saturation once blurred and alpha-blended; put some of it back. */
private const val TintChromaBoost = 1.6f
private const val TintMinChroma = 30.0

/**
 * Ambient artwork backdrop used when the app is themed from album art.
 *
 * This is deliberately not "the cover, blurred". The artwork is fetched at thumbnail size so
 * only its broad colour masses survive, then it is blown up, blurred and tinted with the
 * generated scheme. The result reads as coloured light: a warm header band carrying the
 * artwork's own hue, falling away to the scheme's dark surface for the content below —
 * mirroring the existing header/body split, but with the colours coming from the track.
 *
 * @param colorScheme the opaque scheme for the current track (pre-ambient, so its roles still
 *   carry real colour values).
 * @param blurEnabled honours the user's "disable blur all over" preference.
 */
@Composable
fun AmbientAlbumBackground(
    albumArtUri: String?,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    blurEnabled: Boolean = true,
    hazeState: HazeState? = null
) {
    val context = LocalContext.current

    val baseColor by animateColorAsState(colorScheme.surfaceContainerLowest, tween(700), label = "ambientBase")
    val headerTint by animateColorAsState(
        colorScheme.primaryContainer.boostChroma(TintChromaBoost, TintMinChroma),
        tween(700),
        label = "ambientHeader"
    )
    val glowColor by animateColorAsState(
        colorScheme.primary.boostChroma(TintChromaBoost, TintMinChroma),
        tween(700),
        label = "ambientGlow"
    )

    Box(
        modifier = modifier
            .background(baseColor)
            // Everything drawn inside this node is what frosted surfaces elsewhere sample.
            .then(if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier)
    ) {
        Crossfade(
            targetState = albumArtUri,
            animationSpec = tween(700),
            label = "ambientArt"
        ) { uri ->
            if (uri.isNullOrBlank()) return@Crossfade
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    // Small on purpose: upscaling a low-res bitmap does part of the softening
                    // and keeps this layer nearly free on every track change. Sized up from
                    // 64px now that the explicit blur radius is lighter, so the result reads
                    // as soft colour rather than visible pixel blocks.
                    .size(Size(128, 128))
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = ArtLayerAlpha
                        scaleX = ArtLayerScale
                        scaleY = ArtLayerScale
                    }
                    .then(
                        if (blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(BlurRadius)
                        } else {
                            Modifier
                        }
                    )
            )
        }

        // Coloured light pooling at the top, behind the header.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to glowColor.copy(alpha = GlowAlpha),
                        0.45f to glowColor.copy(alpha = GlowAlpha * 0.35f),
                        1f to Color.Transparent
                    )
                )
        )

        // Header band tint, then a settle into the scheme's dark surface for the content area.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to headerTint.copy(alpha = HeaderTintAlpha),
                        0.22f to headerTint.copy(alpha = HeaderTintAlpha * 0.55f),
                        // Stops well short of fully opaque. Previously this settled onto a
                        // solid base colour by mid-screen, which left everything below the
                        // header sitting on a flat dark field with no artwork left to blur.
                        0.60f to baseColor.copy(alpha = 0.46f),
                        1f to baseColor.copy(alpha = 0.72f)
                    )
                )
        )
    }
}
