package com.ktakjm.fingerlock.core

import android.content.Context
import com.ktakjm.fingerlock.data.FailureEvent
import com.ktakjm.fingerlock.data.FailureLogRepository
import com.ktakjm.fingerlock.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 失敗アラートの発生源(対象アプリのロック画面 / セルフロック)によらない共通の入力 */
data class FailureAlert(
    val targetPackage: String,
    val appLabel: String,
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 全アクションを実行する。1ロックセッションにつき1回だけ呼ぶこと(重複抑止は発火元の責務)。
     *
     * @param onComplete 完了後にメインスレッドで呼ばれる。撮影中にアプリがバックグラウンドへ
     *   落ちるとカメラが切断されるため、画面を閉じる処理はこのコールバックまで待たせる。
     */
    fun fire(context: Context, alert: FailureAlert, onComplete: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            val photoPath = capturePhoto(app, alert.timestamp)
            IntruderCamera.release()
            // 写真のデコードを伴うのでメインスレッドから外す
            withContext(Dispatchers.IO) { FailureNotifier.notify(app, alert, photoPath) }
            FailureLogRepository.get(app).log(
                FailureEvent(
                    timestamp = alert.timestamp,
                    packageName = alert.targetPackage,
                    failureCount = alert.failureCount,
                    photoPath = photoPath,
                )
            )
            onComplete()
        }
    }

    /** 閾値の1回手前で呼ぶ。カメラの初期化(0.5〜1秒)を先に済ませ、発火時は即撮影できるようにする */
    fun prepare(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (SettingsRepository.get(app).intruderPhotoEnabled.first()) {
                IntruderCamera.warmUp(app)
            }
        }
    }

    /** ロックセッションの終了時(認証成功・画面離脱)に呼び、温めたカメラを解放する */
    fun releaseCamera() = IntruderCamera.release()

    private suspend fun capturePhoto(app: Context, timestamp: Long): String? {
        if (!SettingsRepository.get(app).intruderPhotoEnabled.first()) return null
        return IntruderCamera.capture(app, timestamp)
    }
}
