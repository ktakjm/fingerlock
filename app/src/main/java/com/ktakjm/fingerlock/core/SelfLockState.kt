package com.ktakjm.fingerlock.core

import android.os.SystemClock

/**
 * FingerLock自身のセルフロック(issue #2)の解除セッションを管理する。
 *
 * 自パッケージは AppLockAccessibilityService が無視するため [LockStateManager] には乗せず、
 * MainActivity のライフサイクル内で完結させる。状態はプロセス内メモリのみ(永続化しない)。
 */
object SelfLockState {

    /** 再ロックまでの猶予。MainActivity が設定値を collect して反映する */
    @Volatile
    var graceMillis: Long = 60_000L

    private const val IN_FOREGROUND = Long.MAX_VALUE

    /** null ならロック中、IN_FOREGROUND なら解除済みで前面、それ以外は前面から離れた時刻(elapsedRealtime) */
    private var session: Long? = null

    /** onStart で呼ぶ。認証を要求すべきなら true を返す。 */
    @Synchronized
    fun shouldAuthenticate(): Boolean {
        val leftAt = session ?: return true
        if (leftAt == IN_FOREGROUND) return false
        return if (SystemClock.elapsedRealtime() - leftAt <= graceMillis) {
            session = IN_FOREGROUND
            false
        } else {
            session = null
            true
        }
    }

    /** 認証成功時に呼ぶ。 */
    @Synchronized
    fun onUnlocked() {
        session = IN_FOREGROUND
    }

    /** onStop で呼ぶ。解除状態だった場合のみ離脱時刻を記録する。 */
    @Synchronized
    fun onLeft() {
        if (session == IN_FOREGROUND) {
            session = if (graceMillis <= 0L) null else SystemClock.elapsedRealtime()
        }
    }
}
