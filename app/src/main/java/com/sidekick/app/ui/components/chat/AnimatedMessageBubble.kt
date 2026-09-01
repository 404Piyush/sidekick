package com.sidekick.app.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A wrapper that animates its [content] in the first time it appears.
 *
 * Entry is direction-aware: assistant bubbles slide in from the left,
 * user bubbles from the right, matching where each sits in the
 * transcript. A gentle scale-in (spring) plus a fade completes the
 * "settling onto the page" feel. The brand is "ink on paper", so the
 * spring is heavily damped — no bouncy overshoot, just a soft land.
 *
 * The animation fires once per instance: `AnimatedVisibility` enters
 * only when the item is first composed (each turn is a distinct
 * LazyColumn item with a stable key), so a streaming assistant turn
 * that accumulates text does NOT re-run the entry animation on every
 * token. No extra gating state is needed for that.
 *
 * @param visible Whether the bubble is shown. When `false` the bubble
 *                fades out over 180 ms.
 * @param fromUser When `true`, entry slides from the right (user side);
 *                 otherwise from the left (assistant side).
 * @param content The bubble content. Layout is the content's job.
 */
@Composable
fun AnimatedMessageBubble(
    visible: Boolean,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(durationMillis = 220)) +
            scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                initialScale = 0.96f,
            ) +
            slideInHorizontally(
                animationSpec = tween(durationMillis = 220),
                initialOffsetX = { fullWidth ->
                    if (fromUser) fullWidth / 6 else -fullWidth / 6
                },
            ),
        exit = fadeOut(animationSpec = tween(durationMillis = 180)),
    ) {
        Column {
            content()
        }
    }
}
