package com.sidekick.app.ui.components.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * Three pulsing dots rendered under the assistant's bubble while a
 * response is in flight.
 *
 * Each dot bounces (a small upward offset) and fades on a 600 ms cycle
 * with a 150 ms stagger, so the cluster reads as a left-to-right wave.
 * The bounce uses a symmetric ease so the dot returns to rest before
 * the next cycle — no jarring snap.
 *
 * Ink-wash brand: no colour, just black-on-paper opacity modulation.
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row(
        modifier = modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        listOf(0, 150, 300).forEachIndexed { idx, delayMs ->
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 600,
                        delayMillis = delayMs,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$idx",
            )
            // phase 0f..1f -> dot rises 3.dp and brightens.
            val lift = phase * 3f
            val alpha = 0.30f + phase * 0.70f
            Box(
                modifier = Modifier
                    .offset(y = (-lift).dp)
                    .size(7.dp)
                    .alpha(alpha)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}
