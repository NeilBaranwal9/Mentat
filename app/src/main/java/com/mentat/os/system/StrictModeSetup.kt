package com.mentat.os.system

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.Executors

/**
 * Rule 1 enforcement for debug builds: disk reads/writes and network on the main thread, plus VM
 * leaks, are detected. On API 28+ a violation whose stack passes through the app's own I/O layers
 * (data, system, vm, domain) crashes the app. Violations raised purely inside the platform or vendor
 * framework (common on OEM skins such as OxygenOS) are logged instead, so the debug APK stays usable
 * as a daily driver. See docs/DESIGN_DECISIONS.md.
 */
object StrictModeSetup {
    private const val TAG = "MentatStrictMode"
    private val ownLayers = listOf("com.mentat.os.data.", "com.mentat.os.system.", "com.mentat.os.vm.", "com.mentat.os.domain.")

    fun install() {
        val thread = StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog()
        val vm = StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= 28) {
            val executor = Executors.newSingleThreadExecutor()
            thread.penaltyListener(executor) { v -> if (fromAppCode(v)) crash(v) }
            vm.penaltyListener(executor) { v -> if (fromAppCode(v)) crash(v) }
        }
        StrictMode.setThreadPolicy(thread.build())
        StrictMode.setVmPolicy(vm.build())
    }

    private fun fromAppCode(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { c -> c.stackTrace.any { f -> ownLayers.any { f.className.startsWith(it) } && !f.className.contains("StrictModeSetup") } }

    private fun crash(t: Throwable) {
        Log.e(TAG, "StrictMode violation in app code", t)
        Handler(Looper.getMainLooper()).post { throw IllegalStateException("StrictMode violation in app code (Rule 1)", t) }
    }
}
