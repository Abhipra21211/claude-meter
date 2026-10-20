package com.nish.claudemeter

import android.app.AppOpsManager
import android.content.Context

object UsageStore {
    @Volatile var json: String? = null
    @Volatile var stamp: Long = 0L

    @Suppress("DEPRECATION")
    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName
        )
        return m == AppOpsManager.MODE_ALLOWED
    }
}
