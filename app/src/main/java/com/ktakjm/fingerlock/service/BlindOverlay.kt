package com.ktakjm.fingerlock.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * 検知直後〜LockActivity表示完了までの間、対象アプリの画面を覆う目隠し(issue #5)。
 *
 * TYPE_ACCESSIBILITY_OVERLAY はほぼ1フレームで表示できる代わりにLockActivity自身より
 * 上のレイヤーに乗るため、LockActivityが描画され次第 [hideActive] で即座に外す。
 * BAL失敗などでLockActivityが来なかった場合に画面が目隠しで詰まらないよう、
 * [TIMEOUT_MILLIS] 経過で強制的に外す。
 *
 * ただし TYPE_ACCESSIBILITY_OVERLAY はキーガードよりも上に乗るため、画面OFF中の
 * 先回り表示に使うと、点灯時にロック解除画面ごと黒く覆って端末を解錠できなくする。
 * そのため消灯中の先回り分は belowKeyguard=true でキーガード下・アプリ上の
 * TYPE_APPLICATION_OVERLAY(SYSTEM_ALERT_WINDOW 許可済みが前提。BAL免除と同じ)を使い、
 * キーガードが消えた瞬間から対象アプリだけを覆う。
 */
class BlindOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { remove() }
    private var view: View? = null
    private var belowKeyguard = false

    init {
        active = this
    }

    /**
     * ロック判定直後(startActivityの前)に呼ぶ。表示中ならタイムアウトを仕切り直すだけ。
     *
     * 画面OFF時の先回り表示では [armTimeout] を false にする(消灯時間は無制限のため)。
     * その場合のタイムアウトは、解錠後の再表示(USER_PRESENT→showLock)か
     * 画面ON時の [armTimeout] 呼び出しで起動される。
     */
    fun show(armTimeout: Boolean = true, belowKeyguard: Boolean = false) {
        handler.removeCallbacks(timeout)
        if (armTimeout) handler.postDelayed(timeout, TIMEOUT_MILLIS)
        if (view != null) {
            // キーガード下に置き直したいのに検知用の最上層レイヤーのままなら作り直す
            // (呼ばれるのは消灯直後なので張り替えは見えない)。逆方向はどちらのレイヤーでも
            // 対象アプリを覆えているので、張り替えて一瞬の露出を作らない
            if (belowKeyguard && !this.belowKeyguard) detach() else return
        }
        // 色はLockActivityのwindowBackground(Theme.FingerLock.Lock)と揃えてシームレスに繋ぐ
        val blind = View(service).apply { setBackgroundColor(service.getColor(backgroundColor())) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (belowKeyguard) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        ).apply {
            // ステータスバー・カットアウト領域まで含めた全面を覆う
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            service.getSystemService(WindowManager::class.java).addView(blind, params)
            view = blind
            this.belowKeyguard = belowKeyguard
        } catch (e: Exception) {
            // サービス切断直後などでaddViewに失敗しても、目隠しなしのロックとして続行できる
        }
    }

    /** タイムアウト無しで表示中の目隠しに強制除去の期限を付ける。未表示なら何もしない。 */
    fun armTimeout() {
        if (view == null) return
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MILLIS)
    }

    /** タイムアウト時・ロック不要アプリの前面化時にも呼ばれる。未表示なら何もしない。 */
    fun remove() {
        handler.removeCallbacks(timeout)
        detach()
    }

    private fun detach() {
        val blind = view ?: return
        view = null
        try {
            service.getSystemService(WindowManager::class.java).removeView(blind)
        } catch (e: Exception) {
            // 既にWindowが無効(サービス切断など)なら残留の心配もない
        }
    }

    /** サービスの onDestroy で呼ぶ。Window残留と参照リークを防ぐ。 */
    fun release() {
        remove()
        if (active === this) active = null
    }

    private fun backgroundColor(): Int {
        val night = service.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (night) android.R.color.system_neutral1_900
        else android.R.color.system_neutral1_10
    }

    companion object {
        private const val TIMEOUT_MILLIS = 2_500L

        private var active: BlindOverlay? = null

        /**
         * LockActivityの最初のフレームが描き終わった時点(frame commit callback)に呼ぶ。
         * frame commit callbackはメインスレッド以外から呼ばれることがあるためpostする。
         */
        fun hideActive() {
            val overlay = active ?: return
            overlay.handler.post { overlay.remove() }
        }
    }
}
