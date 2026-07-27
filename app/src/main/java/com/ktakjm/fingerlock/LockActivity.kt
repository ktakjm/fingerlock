package com.ktakjm.fingerlock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.ktakjm.fingerlock.core.FailureAlert
import com.ktakjm.fingerlock.core.FailureAlertDispatcher
import com.ktakjm.fingerlock.core.LockStateManager
import com.ktakjm.fingerlock.data.FailureEventType
import com.ktakjm.fingerlock.data.SettingsRepository
import com.ktakjm.fingerlock.ui.FingerLockTheme
import com.ktakjm.fingerlock.ui.LockScreen
import kotlinx.coroutines.launch

class LockActivity : FragmentActivity() {

    private var targetPackage: String = ""
    private val appLabel = mutableStateOf("")
    private val appIcon = mutableStateOf<ImageBitmap?>(null)

    // ロックセッション(表示〜認証成功/終了)単位の失敗カウント(issue #1)
    private var failureCount = 0
    private var alertFired = false
    private var failureThreshold = SettingsRepository.DEFAULT_FAILURE_THRESHOLD

    // 同セッション内で認証せずプロンプトを閉じた回数(issue #7)
    private var dismissCount = 0
    private var leaving = false
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this) { goHome() }
        lifecycleScope.launch {
            SettingsRepository.get(this@LockActivity).failureThreshold.collect {
                failureThreshold = it
            }
        }

        handleIntent(intent)
        setContent {
            FingerLockTheme {
                val label by appLabel
                val icon by appIcon
                LockScreen(
                    label = label,
                    icon = icon,
                    secondaryLabel = stringResource(R.string.lock_go_home_button),
                    onAuthenticate = { showPrompt() },
                    onSecondary = { goHome() },
                )
            }
        }
        showPrompt()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 同じアプリの再検知(アプリ側の画面遷移でロック画面が背面に回された場合)は前面に戻すだけ。
        // セッションを継続させ、プロンプトも二重に出さない(issue #8)
        if (intent.getStringExtra(EXTRA_TARGET_PACKAGE) == targetPackage) {
            if (!promptShowing) showPrompt()
            return
        }
        handleIntent(intent)
        showPrompt()
    }

    override fun onStart() {
        super.onStart()
        // キャンセルは予告なく起きるので、ロック画面が見えている間はカメラを温めておく(issue #7)
        FailureAlertDispatcher.prepare(this)
    }

    override fun onStop() {
        super.onStop()
        // バックグラウンドではカメラを保持できない(他アプリも塞ぐ)ので温めた分を解放する
        FailureAlertDispatcher.releaseCamera()
    }

    private fun handleIntent(intent: Intent) {
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""
        failureCount = 0
        alertFired = false
        dismissCount = 0
        leaving = false
        try {
            val info = packageManager.getApplicationInfo(targetPackage, 0)
            appLabel.value = packageManager.getApplicationLabel(info).toString()
            appIcon.value = packageManager.getApplicationIcon(info)
                .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
                .asImageBitmap()
        } catch (e: PackageManager.NameNotFoundException) {
            appLabel.value = targetPackage
            appIcon.value = null
        }
    }

    private fun showPrompt() {
        if (targetPackage.isEmpty()) {
            finish()
            return
        }
        promptShowing = true
        // 発火のたびにカメラは解放されるので、再試行のたびに温め直す(warmUpは冪等)
        FailureAlertDispatcher.prepare(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptShowing = false
                LockStateManager.onUnlocked(targetPackage)
                // 対象アプリにカメラを渡すため、finish前に解放しておく
                FailureAlertDispatcher.releaseCamera()
                finish()
            }

            override fun onAuthenticationFailed() {
                failureCount++
                if (failureCount >= failureThreshold) fireAlert()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // エラー種別によらずプロンプトは畳まれている
                promptShowing = false
                when (errorCode) {
                    // 自分で閉じた場合だけ発火。画面OFF等でも飛ぶERROR_CANCELEDは対象外(issue #7)
                    // ロック画面には留まるので、撮影完了を待たずに再試行ボタンへ戻してよい
                    BiometricPrompt.ERROR_USER_CANCELED -> fireDismissed()

                    // キャンセル系はロック画面に留まり、再試行ボタンに任せる
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> Unit

                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                        Toast.makeText(
                            this@LockActivity, R.string.lock_locked_out, Toast.LENGTH_SHORT
                        ).show()
                        // OS側の生体認証ロックアウトは閾値未満でも無条件で発火。
                        // 離脱するとカメラが切れるので、撮影完了を待ってからホームに戻る
                        fireAlert(onComplete = { goHome() })
                    }

                    else -> Unit
                }
            }
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_prompt_title, appLabel.value))
            .setSubtitle(getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(promptInfo)
    }

    // 撮影・通知・履歴記録は1ロックセッションにつき1回まで
    private fun fireAlert(onComplete: () -> Unit = {}) {
        if (alertFired) {
            onComplete()
            return
        }
        alertFired = true
        fire(FailureEventType.BIOMETRIC_FAIL, failureCount, onComplete)
    }

    // 認証せず閉じた回数は生体失敗とは別に数える。間引きはDispatcher側のクールダウンに任せる(issue #7)
    private fun fireDismissed() {
        dismissCount++
        fire(FailureEventType.DISMISSED, dismissCount)
    }

    private fun fire(type: FailureEventType, count: Int, onComplete: () -> Unit = {}) {
        FailureAlertDispatcher.fire(
            applicationContext,
            FailureAlert(
                type = type,
                targetPackage = targetPackage,
                appLabel = appLabel.value,
                failureCount = count,
                timestamp = System.currentTimeMillis(),
            ),
            onComplete,
        )
    }

    // 撮影中に離脱するとカメラが切られるので、完了(最大1.5秒)を待ってからホームに戻る。
    // 戻るジェスチャーの連打で二重に走らせない
    private fun goHome() {
        if (leaving) return
        leaving = true
        FailureAlertDispatcher.awaitInFlight {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            finish()
        }
    }

    companion object {
        private const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val ICON_SIZE_PX = 192

        fun createIntent(context: Context, targetPackage: String): Intent =
            Intent(context, LockActivity::class.java).apply {
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
    }
}
