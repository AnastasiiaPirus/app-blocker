package com.anastasiia.appblocker.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.anastasiia.appblocker.BlockerService

fun isBlockerServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, BlockerService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}
