package com.sidekick.app.ui.components.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The set of teammates Sidekick ships with. Mirrors the seed in
 * [com.sidekick.app.data.Seed] and the `Teammate` enum in
 * [com.sidekick.app.ui.HomeScreen] — the avatar is rendered from this
 * stable slug so a teammate doesn't lose its icon across DB migrations.
 *
 * M8 introduces a per-teammate icon (coder → code, builder → wrench,
 * researcher → magnifier). User-defined teammates (added in a future
 * milestone) would render with [TeammateIcon.Unknown].
 */
enum class TeammateIcon {
    Coder,
    Builder,
    Researcher,
    Unknown;

    companion object {
        /**
         * Resolve a teammate slug to the matching [TeammateIcon].
         * Slugs come from [com.sidekick.app.data.TeammateEntity.id]
         * and are always lower-case.
         */
        fun fromSlug(slug: String): TeammateIcon = when (slug.lowercase()) {
            "coder" -> Coder
            "builder" -> Builder
            "researcher" -> Researcher
            else -> Unknown
        }
    }
}

/**
 * Circular avatar with a teammate-specific glyph drawn in pure ink
 * (no images, no chroma — matches the rest of the brand). The icon
 * is stroked at 1.5 dp so it stays crisp at small sizes (28 dp).
 *
 * The component is rendered on the assistant's bubbles (left side),
 * so the user can tell at a glance which teammate produced the
 * reply. User bubbles don't get an avatar — they're aligned right
 * and the "You" label suffices.
 *
 * @param icon Which glyph to render.
 * @param size Diameter of the circular background. Defaults to 32 dp.
 * @param modifier Reserved for caller-driven positioning.
 */
@Composable
fun TeammateAvatar(
    icon: TeammateIcon,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
) {
    // A gentle "breathing" pulse while the teammate is generating a
    // reply. Scale oscillates 1f <-> 1.08f over 900 ms. Inactive
    // avatars hold steady at 1f with no running transition.
    val pulse by rememberInfiniteTransition(label = "avatar-pulse")
        .animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "avatar-scale",
        )
    val avatarScale = if (isActive) pulse else 1f

    Box(
        modifier = modifier
            .size(size)
            .scale(avatarScale)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.65f)) {
            drawIcon(icon)
        }
    }
}

/**
 * Draw one of [TeammateIcon]'s glyphs in pure stroke (no fill).
 * Lives in a free function so the `Canvas` body stays compact.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIcon(icon: TeammateIcon) {
    val stroke = 1.5f
    val inkColor: Color = androidx.compose.ui.graphics.Color.Black
    val w = size.width
    val h = size.height
    when (icon) {
        TeammateIcon.Coder -> {
            // Code icon: angle brackets `</>` — left bracket, slash,
            // right bracket. The brackets are drawn as open chevrons.
            val thickness = w * 0.18f
            val midY = h / 2f
            // Left chevron
            drawLine(
                color = inkColor,
                start = Offset(thickness * 1.2f, midY - h * 0.18f),
                end = Offset(thickness * 0.4f, midY),
                strokeWidth = stroke,
            )
            drawLine(
                color = inkColor,
                start = Offset(thickness * 0.4f, midY),
                end = Offset(thickness * 1.2f, midY + h * 0.18f),
                strokeWidth = stroke,
            )
            // Slash
            drawLine(
                color = inkColor,
                start = Offset(w * 0.65f, h * 0.2f),
                end = Offset(w * 0.35f, h * 0.8f),
                strokeWidth = stroke,
            )
            // Right chevron
            drawLine(
                color = inkColor,
                start = Offset(w - thickness * 1.2f, midY - h * 0.18f),
                end = Offset(w - thickness * 0.4f, midY),
                strokeWidth = stroke,
            )
            drawLine(
                color = inkColor,
                start = Offset(w - thickness * 0.4f, midY),
                end = Offset(w - thickness * 1.2f, midY + h * 0.18f),
                strokeWidth = stroke,
            )
        }
        TeammateIcon.Builder -> {
            // Wrench icon: a hexagonal head with a rectangular shaft
            // jutting out at 45°. Drawn as an outline + a small inner
            // dot for the bolt hole.
            rotate(degrees = -45f, pivot = Offset(w / 2f, h / 2f)) {
                drawRect(
                    color = inkColor,
                    topLeft = Offset(w * 0.05f, h * 0.35f),
                    size = Size(w * 0.55f, h * 0.3f),
                    style = Stroke(width = stroke),
                )
                // Shaft extending to the right
                drawRect(
                    color = inkColor,
                    topLeft = Offset(w * 0.55f, h * 0.42f),
                    size = Size(w * 0.4f, h * 0.16f),
                    style = Stroke(width = stroke),
                )
                // Bolt hole (small circle at the head's far end)
                drawCircle(
                    color = inkColor,
                    radius = h * 0.05f,
                    center = Offset(w * 0.15f, h / 2f),
                    style = Stroke(width = stroke),
                )
            }
        }
        TeammateIcon.Researcher -> {
            // Magnifying glass: a circle (lens) with a diagonal handle.
            val lensRadius = w * 0.32f
            val cx = w * 0.42f
            val cy = h * 0.42f
            drawCircle(
                color = inkColor,
                radius = lensRadius,
                center = Offset(cx, cy),
                style = Stroke(width = stroke),
            )
            // Handle — line from lower-right of the circle to the
            // bottom-right corner of the canvas.
            val angle = 0.785398f // 45 degrees in radians
            val startX = cx + lensRadius * kotlin.math.cos(angle)
            val startY = cy + lensRadius * kotlin.math.sin(angle)
            drawLine(
                color = inkColor,
                start = Offset(startX, startY),
                end = Offset(w * 0.92f, h * 0.92f),
                strokeWidth = stroke * 1.6f,
            )
        }
        TeammateIcon.Unknown -> {
            // Question mark — vertical line + dot. Crude but readable
            // at 28 dp. Drawn with paths so the dot isn't full of fill.
            val path = Path().apply {
                moveTo(w * 0.45f, h * 0.30f)
                cubicTo(
                    w * 0.25f, h * 0.10f, w * 0.85f, h * 0.10f, w * 0.55f, h * 0.50f,
                )
                lineTo(w * 0.50f, h * 0.70f)
            }
            drawPath(path = path, color = inkColor, style = Stroke(width = stroke))
            drawCircle(
                color = inkColor,
                radius = h * 0.05f,
                center = Offset(w * 0.50f, h * 0.85f),
            )
        }
    }
}