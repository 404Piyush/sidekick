package com.sidekick.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sidekick.app.ui.HomeScreen
import com.sidekick.app.ui.theme.SidekickTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SidekickTheme {
                HomeScreen()
            }
        }
    }
}
