package com.sidekick.app.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A wrapper that animates its [content] in with a fade + slide
 * combination the first time it appears.
 *
 * The animation is intentionally subtle — the brand is "ink on paper",
 * so we don't add springy scale or rotation. A 12 dp upward slide
 * over 240 ms with a fade-in is enough to make the message feel like
 * it's "settling into" the transcript without stealing focus from the
 * surrounding UI.
 *
 * The animation only fires once per [AnimatedMessageBubble] instance —
 * subsequent recompositions (e.g., as the partial response grows)
 * skip the entry animation. This avoids the bubble jumping around on
 * every LLM token.
 *
 * @param visible Whether the bubble is currently shown. When `false`,
 *                the bubble fades out.
 * @param content The bubble content. The wrapper only handles the
 *                entry/exit animation; layout is the content's job.
 */
@Composable
fun AnimatedMessageBubble(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Track the *first* composition with `visible == true`. Subsequent
    // recompositions (the streaming assistant turn accumulates text)
    // shouldn't re-trigger the entry animation — that would make the
    // bubble "settle" on every token.
    var hasAnimatedIn by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) hasAnimatedIn = true
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing)) +
            slideInVertically(
                animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing),
                initialOffsetY = { fullHeight -> -fullHeight / 8 },
            ),
        exit = fadeOut(animationSpec = tween(durationMillis = 180)),
    ) {
        Column {
            content()
        }
    }
    // `hasAnimatedIn` is read on every recomposition to gate the
    // re-entry animation in future variants. The reference is here
    // so the compiler doesn't strip it.
    @Suppress("UNUSED_EXPRESSION") hasAnimatedIn
}