package com.ktakjm.fingerlock.core

import android.os.SystemClock

/**
 * ロック対象アプリの「解除セッション」を管理する。
 *
 * - 対象アプリの認証に成功すると、そのアプリは前面にいる間ずっと解除状態。
 * - 前面から離れた時点で離脱時刻を記録し、猶予時間内に戻れば再認証不要。
 * - 猶予 0(即時)の場合は離れた瞬間にセッションを破棄する。
 * - 画面OFF時は(設定が有効なら)全セッションを破棄する。
 */
object LockStateManager {

    @Volatile
    var lockedApps: Set<String> = emptySet()

    @Volatile
    var graceMillis: Long = 60_000L

    @Volatile
    var relockOnScreenOff: Boolean = true

    /** 値が IN_FOREGROUND なら前面で使用中、それ以外は前面から離れた時刻(elapsedRealtime) */
    private const val IN_FOREGROUND = Long.MAX_VALUE
    private val sessions = mutableMapOf<String, Long>()
    private var currentForeground: String? = null

    /**
     * 前面のActivityのwindowイベントごとに呼ぶ。ロック画面を出すべきなら true を返す。
     *
     * 同一パッケージの再イベントでも解除セッションが無ければ true を返す。ロック画面表示中は
     * 自パッケージを無視する都合で [currentForeground] が対象アプリのまま固定されるため、
     * 「同一パッケージなら何もしない」にすると、対象アプリが自タスクを前面に戻したときに
     * ロック画面が背面へ沈んだまま再ロックがかからない(issue #8)。
     */
    @Synchronized
    fun onForeground(packageName: String): Boolean {
        val now = SystemClock.elapsedRealtime()

        // 離脱の記録は「前面アプリが実際に入れ替わったとき」だけ
        if (packageName != currentForeground) {
            currentForeground?.let { previous ->
                if (sessions[previous] == IN_FOREGROUND) {
                    if (graceMillis <= 0L) sessions.remove(previous) else sessions[previous] = now
                }
            }
            currentForeground = packageName
        }

        if (packageName !in lockedApps) return false

        // 解除済みで前面(IN_FOREGROUND)なら false のままなので、解除後のアプリ内遷移では再ロックしない
        val leftAt = sessions[packageName]
        return when {
            leftAt == null -> true
            leftAt == IN_FOREGROUND -> false
            now - leftAt <= graceMillis -> {
                sessions[packageName] = IN_FOREGROUND
                false
            }
            else -> {
                sessions.remove(packageName)
                true
            }
        }
    }

    /** ロック画面での認証成功時に呼ぶ。 */
    @Synchronized
    fun onUnlocked(packageName: String) {
        sessions[packageName] = IN_FOREGROUND
    }

    /**
     * 直近のwindowイベントで観測した前面パッケージ。自パッケージ・SystemUI・Meta広告は
     * サービス側で無視されるため反映されない(ロック画面表示中は対象アプリのまま固定される)。
     * プロンプトを閉じた後の離脱先の判定に使う(issue #10)
     */
    @Synchronized
    fun foregroundPackage(): String? = currentForeground

    /** 画面消灯時に呼ぶ。設定が有効なら全セッションを破棄する。 */
    @Synchronized
    fun onScreenOff() {
        if (relockOnScreenOff) sessions.clear()
    }

    /**
     * 端末のロック解除直後(ACTION_USER_PRESENT)に呼ぶ。
     * 前面に残っているロック対象アプリのセッションが失効していれば、そのパッケージ名を返す。
     * (画面OFF→ONで同じアプリが前面のままだと WINDOW_STATE_CHANGED が発火しないため)
     */
    @Synchronized
    fun pendingLockTarget(): String? {
        val pkg = currentForeground ?: return null
        if (pkg !in lockedApps) return null
        return if (sessions[pkg] == null) pkg else null
    }
}
