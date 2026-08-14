package com.anastasiia.appblocker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anastasiia.appblocker.core.GateAction
import com.anastasiia.appblocker.ui.EditAppsScreen
import com.anastasiia.appblocker.ui.GateScreen
import com.anastasiia.appblocker.ui.MainScreen
import com.anastasiia.appblocker.ui.MainViewModel

private enum class Screen { Main, EditApps, Gate }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                val viewModel: MainViewModel = viewModel()
                var screen by remember { mutableStateOf(Screen.Main) }
                var gateAction by remember { mutableStateOf<GateAction?>(null) }
                when (screen) {
                    Screen.Main -> MainScreen(viewModel, onEditApps = { screen = Screen.EditApps })
                    Screen.EditApps -> EditAppsScreen(viewModel, onDone = { screen = Screen.Main })
                    Screen.Gate -> GateScreen(
                        viewModel,
                        action = gateAction ?: GateAction.Disable,
                        onDone = { screen = Screen.Main },
                        onBack = { screen = Screen.Main },
                    )
                }
            }
        }
    }
}
