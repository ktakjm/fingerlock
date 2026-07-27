package com.ktakjm.fingerlock.core

import android.content.Context
import android.os.SystemClock
import com.ktakjm.fingerlock.data.FailureEvent
import com.ktakjm.fingerlock.data.FailureEventType
import com.ktakjm.fingerlock.data.FailureLogRepository
import com.ktakjm.fingerlock.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 失敗アラートの発生源(対象アプリのロック画面 / セルフロック)によらない共通の入力 */
data class FailureAlert(
    val type: FailureEventType,
    val targetPackage: String,
    val appLabel: String,
    /** 同一ロックセッション内で、この種別のイベントが何回目か */
    val failureCount: Int,
    val timestamp: Long,
)

/**
 * 失敗アラートに対する実行アクション(撮影 → 通知 → 履歴記録)を集約した単一の発火経路。
 *
 * トリガー(閾値到達・ロックアウト・将来のキャンセル検知 issue #7)とアクション(撮影 issue #3、
 * 将来のメール送付 issue #4)を分離しておくため、発火元は [fire] を呼ぶだけにする。
 */
object FailureAlertDispatcher {

    /** キャンセルは正規ユーザーのうっかり離脱でも飛ぶので、同一アプリでは間引く(issue #7) */
    private const val DISMISS_COOLDOWN_MILLIS = 5 * 60 * 1000L

    /** [awaitInFlight] で離脱を待たせる上限。カメラが固まっても画面操作を長く止めない */
    private const val EXIT_WAIT_TIMEOUT_MILLIS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 種別×パッケージごとの直近発火時刻。解除セッションと同じくプロセス内メモリのみ */
    private val lastFiredAt = mutableMapOf<Pair<FailureEventType, String>, Long>()

    private var inFlight: Job? = null

    /**
     * 種別に応じたアクションを実行する。クールダウン中なら何もせず [onComplete] だけ呼ぶ。
     * 同一ロックセッション内の重複抑止は発火元の責務。
     *
     * @param onComplete 完了後にメインスレッドで呼ばれる。撮影中にアプリがバックグラウンドへ
     *   落ちるとカメラが切断されるため、画面を閉じる処理はこのコールバックまで待たせる。
     */
    fun fire(context: Context, alert: FailureAlert, onComplete: () -> Unit = {}) {
        val app = context.applicationContext
        val actions = actionsFor(alert.type)
        if (!shouldFire(alert, actions.cooldownMillis)) {
            onComplete()
            return
        }
        val job = scope.launch {
            val photoPath = if (actions.capturePhoto) capturePhoto(app, alert.timestamp) else null
            IntruderCamera.release()
            if (actions.notify) {
                // 写真のデコードを伴うのでメインスレッドから外す
                withContext(Dispatchers.IO) { FailureNotifier.notify(app, alert, photoPath) }
            }
            FailureLogRepository.get(app).log(
                FailureEvent(
                    timestamp = alert.timestamp,
                    packageName = alert.targetPackage,
                    failureCount = alert.failureCount,
                    photoPath = photoPath,
                    type = alert.type,
                )
            )
        }
        inFlight = job
        // ジョブの中から呼ぶと [awaitInFlight] が自分自身を待つ形になるので、完了後に外から呼ぶ
        job.invokeOnCompletion { scope.launch { onComplete() } }
    }

    /**
     * ロック画面が表示されている間ずっと呼んでおく。カメラの初期化(0.5〜1秒)を先に済ませ、
     * 発火時のシャッターを詰める。特にキャンセル検知は閾値のような予告がないので予熱が効く。
     */
    fun prepare(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (SettingsRepository.get(app).intruderPhotoEnabled.first()) {
                IntruderCamera.warmUp(app)
            }
        }
    }

    /**
     * 撮影を含むアクションの実行中なら、完了を待ってから [onDone] を呼ぶ。
     * 離脱するとカメラが切断されるため、画面を閉じる操作はこれを通す。
     */
    fun awaitInFlight(onDone: () -> Unit) {
        val job = inFlight
        if (job == null || job.isCompleted) {
            onDone()
            return
        }
        scope.launch {
            withTimeoutOrNull(EXIT_WAIT_TIMEOUT_MILLIS) { job.join() }
            onDone()
        }
    }

    /** ロックセッションの終了時(認証成功・画面離脱)に呼び、温めたカメラを解放する */
    fun releaseCamera() = IntruderCamera.release()

    private suspend fun capturePhoto(app: Context, timestamp: Long): String? {
        if (!SettingsRepository.get(app).intruderPhotoEnabled.first()) return null
        return IntruderCamera.capture(app, timestamp)
    }

    /** イベント種別ごとの実行アクション。issue #4 のメール送付もここに列を足す */
    private data class AlertActions(
        val capturePhoto: Boolean,
        val notify: Boolean,
        /** 同一パッケージで再発火させない間隔。0 なら間引かない */
        val cooldownMillis: Long,
    )

    private fun actionsFor(type: FailureEventType): AlertActions = when (type) {
        // 閾値到達・ロックアウトはセッション単位で1回に絞られているので間引かない
        FailureEventType.BIOMETRIC_FAIL ->
            AlertActions(capturePhoto = true, notify = true, cooldownMillis = 0L)

        FailureEventType.DISMISSED ->
            AlertActions(capturePhoto = true, notify = true, DISMISS_COOLDOWN_MILLIS)
    }

    /**
     * クールダウン中なら false。抑止分は通知・撮影だけでなく履歴記録も行わない。
     *
     * ロック画面での「閉じる→再試行→閉じる」も、セルフロックを何度も開き直すケースも、
     * セッションをまたぐため単調増加時計での間引きで揃えて抑える。
     */
    @Synchronized
    private fun shouldFire(alert: FailureAlert, cooldownMillis: Long): Boolean {
        if (cooldownMillis <= 0L) return true
        val key = alert.type to alert.targetPackage
        val now = SystemClock.elapsedRealtime()
        val last = lastFiredAt[key]
        if (last != null && now - last < cooldownMillis) return false
        lastFiredAt[key] = now
        return true
    }
}
