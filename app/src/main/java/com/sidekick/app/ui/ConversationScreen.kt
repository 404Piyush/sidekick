package com.sidekick.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sidekick.app.ui.theme.SidekickTheme

/**
 * Placeholder conversation screen for M0. The agent loop, message history,
 * and streaming tokens land here in M1 / M4.
 */
@Composable
fun ConversationScreen(teammateTitle: String) {
    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = teammateTitle,
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Conversation — coming in M1.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationScreenPreview() {
    SidekickTheme {
        ConversationScreen(teammateTitle = "Coder")
    }
}
