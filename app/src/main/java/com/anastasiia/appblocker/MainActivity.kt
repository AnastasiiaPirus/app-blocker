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
import com.anastasiia.appblocker.ui.AppInfo
import com.anastasiia.appblocker.ui.ConfirmScreen
import com.anastasiia.appblocker.ui.EditAppsScreen
import com.anastasiia.appblocker.ui.GateScreen
import com.anastasiia.appblocker.ui.MainScreen
import com.anastasiia.appblocker.ui.MainViewModel
import com.anastasiia.appblocker.ui.ScheduleEditorScreen

private enum class Screen { Main, EditApps, ScheduleEditor, Gate, Confirm }

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
                var scheduleApp by remember { mutableStateOf<AppInfo?>(null) }
                val onGate: (GateAction) -> Unit = { action ->
                    gateAction = action
                    screen = Screen.Gate
                }
                when (screen) {
                    Screen.Main -> MainScreen(
                        viewModel,
                        onEditApps = { screen = Screen.EditApps },
                        onGate = onGate,
                        onConfirm = { screen = Screen.Confirm },
                    )
                    Screen.EditApps -> EditAppsScreen(
                        viewModel,
                        onDone = { screen = Screen.Main },
                        onGate = onGate,
                        onEditSchedule = { app ->
                            scheduleApp = app
                            screen = Screen.ScheduleEditor
                        },
                    )
                    Screen.ScheduleEditor -> {
                        val app = scheduleApp
                        if (app == null) {
                            screen = Screen.EditApps
                        } else {
                            ScheduleEditorScreen(
                                viewModel,
                                pkg = app.packageName,
                                label = app.label,
                                onDone = { screen = Screen.EditApps },
                                onGate = onGate,
                            )
                        }
                    }
                    Screen.Gate -> {
                        val action = gateAction
                        if (action == null) {
                            screen = Screen.Main
                        } else {
                            GateScreen(
                                viewModel,
                                action = action,
                                onDone = { screen = Screen.Main },
                                onBack = { screen = Screen.Main },
                            )
                        }
                    }
                    Screen.Confirm -> ConfirmScreen(viewModel, onDone = { screen = Screen.Main })
                }
            }
        }
    }
}
