package com.anastasiia.appblocker.ui

import android.content.Intent
import android.content.pm.PackageManager
import com.anastasiia.appblocker.core.SELF_PACKAGE

data class AppInfo(val packageName: String, val label: String)

fun launchableApps(pm: PackageManager): List<AppInfo> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcherIntent, 0)
        .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.packageName }
        .filter { it.packageName != SELF_PACKAGE }
        .sortedBy { it.label.lowercase() }
}
