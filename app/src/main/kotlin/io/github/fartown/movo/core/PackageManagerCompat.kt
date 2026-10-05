package io.github.fartown.movo.core

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

// PackageManager 带 Flags 对象的重载从 Android 13 起才有；低版本（电视 Android 9）用等价的 int 重载。

internal fun PackageManager.queryIntentActivitiesCompat(intent: Intent, flags: Long = 0L): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, flags.toInt())
    }

internal fun PackageManager.queryIntentServicesCompat(intent: Intent, flags: Long = 0L): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        queryIntentServices(intent, flags.toInt())
    }

internal fun PackageManager.resolveActivityCompat(intent: Intent, flags: Long = 0L): ResolveInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, flags.toInt())
    }

internal fun PackageManager.getApplicationInfoCompat(packageName: String, flags: Long = 0L): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, flags.toInt())
    }
