package com.lanxin.refactor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lanxin.refactor.ui.CloudSettingsScreen
import com.lanxin.refactor.ui.CompanionScreen
import com.lanxin.refactor.ui.MemoryScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var route by remember { mutableStateOf("companion") }
                    when (route) {
                        "memory" -> MemoryScreen(onBack = { route = "companion" })
                        "cloud" -> CloudSettingsScreen(onBack = { route = "companion" })
                        else -> CompanionScreen(
                            onOpenMemory = { route = "memory" },
                            onOpenCloud = { route = "cloud" }
                        )
                    }
                }
            }
        }
    }
}
