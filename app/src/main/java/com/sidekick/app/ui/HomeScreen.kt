package com.sidekick.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sidekick.app.ui.theme.SidekickTheme

enum class Teammate(val title: String, val tagline: String) {
    CODER("Coder", "Builds and fixes apps, scripts, and websites."),
    BUILDER("Builder", "Turns an idea or a photo into a finished page."),
    RESEARCHER("Researcher", "Reads, summarises, and answers from anything you give it."),
}

@Composable
fun HomeScreen(
    onTeammateSelected: (Teammate) -> Unit = {},
) {
    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Sidekick",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "Pick a teammate.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Teammate.entries.forEach { teammate ->
                TeammateCard(teammate = teammate, onClick = { onTeammateSelected(teammate) })
            }
        }
    }
}

@Composable
private fun TeammateCard(teammate: Teammate, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = teammate.title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = teammate.tagline,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F3EF)
@Composable
private fun HomeScreenPreview() {
    SidekickTheme {
        HomeScreen()
    }
}
