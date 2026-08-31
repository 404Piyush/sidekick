package com.sidekick.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sidekick.app.ui.ConversationScreen
import com.sidekick.app.ui.HomeScreen
import com.sidekick.app.ui.Teammate
import com.sidekick.app.ui.theme.SidekickTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SidekickTheme {
                var selected by remember { mutableStateOf<Teammate?>(null) }
                val current = selected
                if (current == null) {
                    HomeScreen(onTeammateSelected = { selected = it })
                } else {
                    BackHandler { selected = null }
                    ConversationScreen(
                        teammateSlug = current.name.lowercase(),
                        teammateTitle = current.title,
                    )
                }
            }
        }
    }
}
