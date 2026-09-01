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
 * Each dot's alpha animates on a 600 ms cycle with a 150 ms stagger
 * between dots so the cluster appears to flow left-to-right. The
 * project uses an "ink-wash, paper-toned" aesthetic so the dots rely
 * on [androidx.compose.ui.draw.alpha] (no colour) — black-on-paper
 * fading in and out at staggered phases.
 *
 * Extracted from [com.sidekick.app.ui.ConversationScreen] in M8 so
 * snapshot tests and the new chat UI can compose it independently.
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(0, 150, 300).forEachIndexed { idx, delayMs ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = delayMs),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$idx",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}