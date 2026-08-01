package com.ktakjm.fingerlock.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLaunch: Runnable? = null

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
                        // TYPE_ACCESSIBILITY_OVERLAYはキーガードより上に乗り、点灯時に
                        // ロック解除画面ごと黒く覆ってしまうため、消灯中の先回り分だけは
                        // キーガード下・アプリ上のレイヤー(TYPE_APPLICATION_OVERLAY)で被せる
                        if (LockStateManager.pendingLockTarget() != null) {
                            blind?.show(armTimeout = false, belowKeyguard = true)
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
        // TODO: Meta広告フィルタ(com.facebook.ads.*除外)の実機検証用の一時ログ。検証完了後に削除する。
        // logcatは数時間で流れるため、katana関連だけ内部ファイルに残す
        if (pkg == FACEBOOK_PACKAGE) {
            debugLog(if (cls.startsWith(META_ADS_CLASS_PREFIX)) "IGNORED(ads): $cls" else "event: $cls")
        }
        if (pkg == packageName || pkg == SYSTEM_UI_PACKAGE) return
        // Meta Audience Networkの全画面広告は、他アプリの広告なのにFacebookアプリ内の
        // Activity(AudienceNetworkRemoteActivity等)として再生され、広告の間ずっと前面に残る。
        // クラス空間が広告SDK専用(com.facebook.ads.*)でFacebook本体のUI(com.facebook.katana.*)とは
        // 明確に分かれており、個人コンテンツを表示しないため、ロック判定から丸ごと除外する
        if (cls.startsWith(META_ADS_CLASS_PREFIX)) return
        if (!isActivity(pkg, cls)) return
        if (LockStateManager.onForeground(pkg)) {
            scheduleLock(pkg)
        } else {
            // ロック不要なアプリが前面に来たら予約中のロックは取り消す。広告SDKが計測用に
            // ロック対象のトランポリンActivity(Meta広告→Facebook等)を一瞬だけ起動する場合、
            // 即finishして元のアプリのイベントがすぐ来るので、ここで誤発火を握り潰せる
            cancelPendingLaunch()
            // 目隠しも用済み。画面OFF中に被せた目隠しが別アプリ(キーガード上のカメラ起動など)を
            // 覆ったまま残るのを防ぐ自己修復を兼ねる
            blind?.remove()
        }
    }

    /**
     * 目隠しを即座に被せたうえで、LockActivityの起動は [LOCK_CONFIRM_MILLIS] だけ遅らせる。
     * 猶予中に別アプリが前面に来たら(=検知したのは一瞬で消えるトランポリンだったら)取り消す。
     * 対象アプリの画面は目隠しが覆っているので、遅延によるチラ見えはない。
     */
    private fun scheduleLock(target: String) {
        // オーバーレイ許可が無いとバックグラウンドからのActivity起動がOSにブロックされる
        if (!Settings.canDrawOverlays(this)) {
            blind?.remove()
            return
        }
        blind?.show()
        cancelPendingLaunch()
        pendingLaunch = Runnable {
            pendingLaunch = null
            startActivity(LockActivity.createIntent(this, target))
        }.also { handler.postDelayed(it, LOCK_CONFIRM_MILLIS) }
    }

    private fun cancelPendingLaunch() {
        pendingLaunch?.let { handler.removeCallbacks(it) }
        pendingLaunch = null
    }

    // TODO: Meta広告フィルタの実機検証用。検証完了後にdebugLog関連は丸ごと削除する
    private fun debugLog(message: String) {
        try {
            val file = java.io.File(filesDir, DEBUG_LOG_FILE)
            if (file.length() > DEBUG_LOG_LIMIT_BYTES) file.delete()
            val time = java.time.LocalDateTime.now().toString()
            file.appendText("$time $message\n")
        } catch (e: Exception) {
            // 調査用ログが書けなくても本来の動作には影響させない
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

    /** 解錠直後の再ロック用。前面が対象アプリだと確定しているので遅延なしで起動する。 */
    private fun showLock(target: String) {
        // オーバーレイ許可が無いとバックグラウンドからのActivity起動がOSにブロックされる。
        // その場合でも消灯時に被せた目隠しだけが残って画面を塞がないよう外しておく
        if (!Settings.canDrawOverlays(this)) {
            blind?.remove()
            return
        }
        // Activity起動完了までのチラ見えを目隠しで塞ぐ(issue #5)。外すのはLockActivity側
        blind?.show()
        startActivity(LockActivity.createIntent(this, target))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cancelPendingLaunch()
        blind?.release()
        blind = null
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val META_ADS_CLASS_PREFIX = "com.facebook.ads."
        private const val CACHE_LIMIT = 500

        // TODO: Meta広告フィルタの実機検証用。検証完了後に削除する
        private const val FACEBOOK_PACKAGE = "com.facebook.katana"
        private const val DEBUG_LOG_FILE = "debug-events.log"
        private const val DEBUG_LOG_LIMIT_BYTES = 256 * 1024L

        /**
         * トランポリン検知の確認猶予。短いとfinish→元アプリのイベント到着に間に合わず
         * 誤発火し、長いと正規のロックでプロンプト表示がその分遅れる(目隠しは即時なので
         * 覗き見の猶予にはならない)
         */
        private const val LOCK_CONFIRM_MILLIS = 400L
    }
}
