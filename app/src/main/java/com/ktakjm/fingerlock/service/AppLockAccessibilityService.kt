package com.ktakjm.fingerlock.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.ktakjm.fingerlock.LockActivity
import com.ktakjm.fingerlock.core.LockStateManager
import com.ktakjm.fingerlock.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AppLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screenReceiver: BroadcastReceiver? = null

    /** "pkg/cls" -> Activityかどうか。IMEやシステムダイアログのwindowイベントを弾くためのキャッシュ */
    private val activityCache = mutableMapOf<String, Boolean>()

    override fun onServiceConnected() {
        val repo = SettingsRepository.get(this)
        scope.launch { repo.lockedApps.collect { LockStateManager.lockedApps = it } }
        scope.launch { repo.graceSeconds.collect { LockStateManager.graceMillis = it * 1000L } }
        scope.launch { repo.relockOnScreenOff.collect { LockStateManager.relockOnScreenOff = it } }

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> LockStateManager.onScreenOff()
                    Intent.ACTION_USER_PRESENT ->
                        LockStateManager.pendingLockTarget()?.let { showLock(it) }
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == packageName || pkg == SYSTEM_UI_PACKAGE) return
        if (!isActivity(pkg, cls)) return
        if (LockStateManager.onForeground(pkg)) showLock(pkg)
    }

    private fun isActivity(pkg: String, cls: String): Boolean {
        if (activityCache.size > CACHE_LIMIT) activityCache.clear()
        return activityCache.getOrPut("$pkg/$cls") {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun showLock(target: String) {
        // オーバーレイ許可が無いとバックグラウンドからのActivity起動がOSにブロックされる
        if (!Settings.canDrawOverlays(this)) return
        startActivity(LockActivity.createIntent(this, target))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CACHE_LIMIT = 500
    }
}
