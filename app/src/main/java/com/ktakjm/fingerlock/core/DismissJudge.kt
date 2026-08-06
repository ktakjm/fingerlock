package com.ktakjm.fingerlock.core

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ERROR_USER_CANCELEDが本物のユーザーキャンセルか、SystemUIのタスク退去
 * (プロンプト表示中に別タスクが前面に来ると強制クローズされ、同じエラーコードで届く)かを
 * 見分ける(issue #10)。エラーコードでは区別できないため、状況で判定する:
 *
 * 1. 表示から [MIN_SHOWN_MILLIS] 未満の強制クローズは人間の操作ではあり得ない
 *    (実測: アプリを開いたナビゲーションジェスチャーの尻尾による退去は~100ms)ので棄却
 * 2. 本物のキャンセルではプロンプトはSystemUIのウィンドウなのでホストはresumedのまま残る。
 *    受信後 [JUDGE_MILLIS] を前面のまま生き残れば本物として確定
 * 3. 前面を失った(退去でもユーザーの離脱でも起きる。onPauseはエラーの前後どちらにも来る)場合は
 *    離脱先で判定する: ホーム/ランチャーならユーザー自身が閉じて帰った=本物、
 *    他アプリのActivityが前面ならそれが退去させた犯人なので棄却。
 *    ロック画面が前面に戻っていれば一瞬の退去(再ロック済み)なので棄却
 */
class DismissJudge(
    private val activity: ComponentActivity,
    private val onGenuine: () -> Unit,
) {
    private var promptShownAt = 0L
    private var judging: Job? = null
    private var leaveJudging: Job? = null

    /** ホーム・ランチャー系パッケージ。Recents(タスク切替UI)もランチャーが持つ */
    private val homePackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        activity.packageManager
            .queryIntentActivities(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            // 判定猶予中に前面を失ったら、生存判定ではなく離脱先の判定に切り替える
            if (event == Lifecycle.Event.ON_PAUSE && judging != null) {
                judging?.cancel()
                judging = null
                judgeLeave()
            }
        })
    }

    /** プロンプトを表示するたびに呼ぶ(判定1の基準時刻) */
    fun onPromptShown() {
        promptShownAt = SystemClock.elapsedRealtime()
    }

    /** ERROR_USER_CANCELED受信時に呼ぶ。本物のキャンセルと判定できたら [onGenuine] を1回呼ぶ */
    fun submit() {
        discard()
        if (SystemClock.elapsedRealtime() - promptShownAt < MIN_SHOWN_MILLIS) return
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            judging = activity.lifecycle.coroutineScope.launch {
                delay(JUDGE_MILLIS)
                judging = null
                onGenuine()
            }
        } else {
            // 退去ではonPauseがエラーより先に届くことがある。離脱先の判定へ
            judgeLeave()
        }
    }

    /**
     * 「閉じる」・戻るなど明示的なユーザー操作での離脱時に呼ぶ。判定待ちが残っていれば
     * タスク退去ではあり得ない(ユーザーが操作できている)ので、本物として即確定させる。
     * 離脱処理が撮影を [FailureAlertDispatcher.awaitInFlight] で待てるよう、離脱前に呼ぶこと
     */
    fun flush() {
        if (judging == null) return
        discard()
        onGenuine()
    }

    private fun judgeLeave() {
        leaveJudging?.cancel()
        leaveJudging = activity.lifecycle.coroutineScope.launch {
            // ユーザー補助サービスが離脱先のwindowイベントを観測するのを待つ
            delay(LEAVE_JUDGE_MILLIS)
            leaveJudging = null
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            if (LockStateManager.foregroundPackage() in homePackages) onGenuine()
        }
    }

    private fun discard() {
        judging?.cancel()
        judging = null
        leaveJudging?.cancel()
        leaveJudging = null
    }

    companion object {
        /** 表示からこの時間未満の強制クローズは人間の操作ではないとみなす(実測: 人間は~650ms〜) */
        private const val MIN_SHOWN_MILLIS = 400L

        /**
         * 前面に残っている場合の生存判定猶予。本物のキャンセルの記録・撮影がこの分だけ遅れるが、
         * 偽陽性の撮影・通知よりも数え損ね側に倒す
         */
        private const val JUDGE_MILLIS = 500L

        /** 前面を失った場合に、離脱先の観測を待ってから判定するまでの猶予 */
        private const val LEAVE_JUDGE_MILLIS = 700L
    }
}
