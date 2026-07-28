package com.ktakjm.fingerlock.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
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
    private var blind: BlindOverlay? = null

    /** "pkg/cls" -> Activityかどうか。IMEやシステムダイアログのwindowイベントを弾くためのキャッシュ */
    private val activityCache = mutableMapOf<String, Boolean>()

    override fun onServiceConnected() {
        blind = BlindOverlay(this)
        val repo = SettingsRepository.get(this)
        scope.launch { repo.lockedApps.collect { LockStateManager.lockedApps = it } }
        scope.launch { repo.graceSeconds.collect { LockStateManager.graceMillis = it * 1000L } }
        scope.launch { repo.relockOnScreenOff.collect { LockStateManager.relockOnScreenOff = it } }

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        LockStateManager.onScreenOff()
                        // 消灯後に再ロックが必要になるなら、この時点で目隠しを被せておく。
                        // USER_PRESENTはキーガードが消えて対象アプリが露出した後に届くため、
                        // 受信してから被せたのでは解錠直後のチラ見えに間に合わない
                        if (LockStateManager.pendingLockTarget() != null) {
                            blind?.show(armTimeout = false)
                        } else {
                            blind?.remove()
                        }
                    }
                    Intent.ACTION_SCREEN_ON ->
                        // キーガード無し設定だとUSER_PRESENTが飛ばず目隠しが残り続けるため、
                        // 点灯時点でキーガードが居なければタイムアウトだけ起動しておく
                        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) {
                            blind?.armTimeout()
                        } else Unit
                    Intent.ACTION_USER_PRESENT -> {
                        val pending = LockStateManager.pendingLockTarget()
                        if (pending != null) showLock(pending) else blind?.remove()
                    }
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == packageName || pkg == SYSTEM_UI_PACKAGE) return
        if (!isActivity(pkg, cls)) return
        if (LockStateManager.onForeground(pkg)) {
            showLock(pkg)
        } else {
            // ロック不要なアプリが前面に来たら目隠しは用済み。画面OFF中に被せた目隠しが
            // 別アプリ(キーガード上のカメラ起動など)を覆ったまま残るのを防ぐ自己修復
            blind?.remove()
        }
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
        // Activity起動完了までのチラ見えを目隠しで塞ぐ(issue #5)。外すのはLockActivity側
        blind?.show()
        startActivity(LockActivity.createIntent(this, target))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        blind?.release()
        blind = null
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
