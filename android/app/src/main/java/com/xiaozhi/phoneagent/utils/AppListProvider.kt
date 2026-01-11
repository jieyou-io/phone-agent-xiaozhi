package com.xiaozhi.phoneagent.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val isSupported: Boolean = false
)

object AppListProvider {

    suspend fun getInstalledApps(
        context: Context,
        includeSystem: Boolean = false
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        packages
            .filter { app ->
                // 过滤系统应用（如果不包含系统应用）
                includeSystem || (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .map { app ->
                AppInfo(
                    packageName = app.packageName,
                    label = pm.getApplicationLabel(app).toString(),
                    icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                    isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun getSupportedApps(
        context: Context,
        prefs: PrefsManager
    ): Set<String> = withContext(Dispatchers.IO) {
        prefs.customApps.keys
    }

    suspend fun toggleAppSupport(
        prefs: PrefsManager,
        packageName: String,
        appLabel: String,
        isSupported: Boolean
    ) = withContext(Dispatchers.IO) {
        val currentApps = prefs.customApps.toMutableMap()
        if (isSupported) {
            currentApps[packageName] = appLabel
        } else {
            currentApps.remove(packageName)
        }
        prefs.customApps = currentApps
    }
}
