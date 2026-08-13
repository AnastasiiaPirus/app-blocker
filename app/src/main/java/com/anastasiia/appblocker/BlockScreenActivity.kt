package com.anastasiia.appblocker

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

class BlockScreenActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PACKAGE = "blocked_package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val (label, icon) = try {
            val pm = packageManager
            val info = pm.getApplicationInfo(pkg ?: "", 0)
            pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info).toBitmap()
        } catch (e: PackageManager.NameNotFoundException) {
            (pkg ?: "App") to null
        }

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        icon?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(72.dp).padding(bottom = 16.dp),
                            )
                        }
                        Text(label, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Blocked",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
                        )
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
