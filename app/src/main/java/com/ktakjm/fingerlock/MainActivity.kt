package com.ktakjm.fingerlock

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ktakjm.fingerlock.core.FailureAlert
import com.ktakjm.fingerlock.core.FailureAlertDispatcher
import com.ktakjm.fingerlock.core.SelfLockState
import com.ktakjm.fingerlock.data.FailureEventType
import com.ktakjm.fingerlock.data.SettingsRepository
import com.ktakjm.fingerlock.service.AppLockAccessibilityService
import com.ktakjm.fingerlock.ui.AppListScreen
import com.ktakjm.fingerlock.ui.FailureHistoryScreen
import com.ktakjm.fingerlock.ui.FingerLockTheme
import com.ktakjm.fingerlock.ui.LockScreen
import com.ktakjm.fingerlock.ui.SettingsScreen
import com.ktakjm.fingerlock.ui.SetupScreen
import kotlinx.coroutines.launch

data class PermissionState(
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    // 通知は任意権限: 未許可でもロック機能は動く(失敗アラート通知だけ飛ばない)
    val notificationsGranted: Boolean,
) {
    val allGranted: Boolean get() = overlayGranted && accessibilityEnabled
}

fun checkPermissions(context: Context): PermissionState {
    val expected = ComponentName(context, AppLockAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    val accessibilityEnabled = enabledServices.split(':').any {
        it.equals(expected.flattenToString(), ignoreCase = true) ||
            it.equals(expected.flattenToShortString(), ignoreCase = true)
    }
    return PermissionState(
        overlayGranted = Settings.canDrawOverlays(context),
        accessibilityEnabled = accessibilityEnabled,
        notificationsGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED,
    )
}

// BiometricPromptを使うためFragmentActivityを継承する(issue #2)
class MainActivity : FragmentActivity() {

    private val selfLocked = mutableStateOf(true)
    private var promptShowing = false

    // ロックセッション(表示〜認証成功)単位の失敗カウント(issue #1をセルフロックにも適用)
    private var failureCount = 0
    private var alertFired = false
    private var failureThreshold = SettingsRepository.DEFAULT_FAILURE_THRESHOLD

    // 同セッション内で認証せずプロンプトを閉じた回数(issue #7)。誤爆頻度を測るため毎回記録する
    private var dismissCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository.get(this)
        lifecycleScope.launch {
            settings.graceSeconds.collect { SelfLockState.graceMillis = it * 1000L }
        }
        lifecycleScope.launch {
            settings.failureThreshold.collect { failureThreshold = it }
        }

        val openHistory = intent.getBooleanExtra(EXTRA_OPEN_HISTORY, false)
        val selfIcon = packageManager.getApplicationIcon(packageName)
            .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
            .asImageBitmap()
        setContent {
            FingerLockTheme {
                val locked by selfLocked
                if (locked) {
                    LockScreen(
                        label = stringResource(R.string.app_name),
                        icon = selfIcon,
                        secondaryLabel = stringResource(R.string.lock_close_button),
                        onAuthenticate = { showSelfLockPrompt() },
                        // 撮影中に閉じるとカメラが切られるので、完了(最大1.5秒)を待つ
                        onSecondary = { FailureAlertDispatcher.awaitInFlight { finish() } },
                    )
                } else {
                    FingerLockApp(initialShowHistory = openHistory)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 初回セットアップ(権限誘導画面)は認証なしで表示する
        if (!checkPermissions(this).allGranted) {
            selfLocked.value = false
            return
        }
        if (SelfLockState.shouldAuthenticate()) {
            // 未認証のまま離れて戻った場合は同一セッション扱いで失敗カウントを引き継ぐ
            if (!selfLocked.value) {
                failureCount = 0
                alertFired = false
                dismissCount = 0
            }
            selfLocked.value = true
            showSelfLockPrompt()
        }
    }

    override fun onStop() {
        super.onStop()
        // バックグラウンドではカメラを保持できない(他アプリも塞ぐ)ので温めた分を解放する
        FailureAlertDispatcher.releaseCamera()
        // 画面回転では離脱扱いにしない(猶予0でも再認証させない)
        if (!isChangingConfigurations) SelfLockState.onLeft()
    }

    private fun showSelfLockPrompt() {
        // BiometricPromptのPIN入力画面から戻る際のonStartで二重に出さない
        if (promptShowing) return
        promptShowing = true
        // キャンセルは予告なく起きるので、認証を求めている間はカメラを温めておく(issue #7)
        FailureAlertDispatcher.prepare(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptShowing = false
                SelfLockState.onUnlocked()
                selfLocked.value = false
                // 解除後もActivityは残るので、温めたカメラを明示的に手放す
                FailureAlertDispatcher.releaseCamera()
            }

            override fun onAuthenticationFailed() {
                failureCount++
                if (failureCount >= failureThreshold) fireAlert()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptShowing = false
                when (errorCode) {
                    // 自分で閉じた場合だけ発火。画面OFF等でも飛ぶERROR_CANCELEDは対象外(issue #7)
                    BiometricPrompt.ERROR_USER_CANCELED ->
                        // 閉じるとカメラが切れるので、撮影完了を待ってからアプリを閉じる
                        fireDismissed(onComplete = { finish() })

                    // 明示キャンセルはアプリを閉じる(ホームに送る必要はない)
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> finish()

                    // システム都合のキャンセルはロック画面に留まり、再試行ボタンに任せる
                    BiometricPrompt.ERROR_CANCELED -> Unit

                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                        Toast.makeText(
                            this@MainActivity, R.string.lock_locked_out, Toast.LENGTH_SHORT
                        ).show()
                        // OS側の生体認証ロックアウトは閾値未満でも無条件で発火。
                        // 離脱するとカメラが切れるので、撮影完了を待ってから閉じる
                        fireAlert(onComplete = { finish() })
                    }

                    else -> Unit
                }
            }
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_prompt_title, getString(R.string.app_name)))
            .setSubtitle(getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(promptInfo)
    }

    // 撮影・通知・履歴記録は1ロックセッションにつき1回まで(対象パッケージ=自分自身)
    private fun fireAlert(onComplete: () -> Unit = {}) {
        if (alertFired) {
            onComplete()
            return
        }
        alertFired = true
        fire(FailureEventType.BIOMETRIC_FAIL, failureCount, onComplete)
    }

    // 認証せず閉じた回数は生体失敗とは別に数える。間引きはDispatcher側のクールダウンに任せる(issue #7)
    private fun fireDismissed(onComplete: () -> Unit = {}) {
        dismissCount++
        fire(FailureEventType.DISMISSED, dismissCount, onComplete)
    }

    private fun fire(type: FailureEventType, count: Int, onComplete: () -> Unit = {}) {
        FailureAlertDispatcher.fire(
            applicationContext,
            FailureAlert(
                type = type,
                targetPackage = packageName,
                appLabel = getString(R.string.app_name),
                failureCount = count,
                timestamp = System.currentTimeMillis(),
            ),
            onComplete,
        )
    }

    companion object {
        private const val EXTRA_OPEN_HISTORY = "open_history"
        private const val ICON_SIZE_PX = 192

        /** 失敗アラート通知タップで履歴画面を直接開く */
        fun createOpenHistoryIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_HISTORY, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}

@Composable
private fun FingerLockApp(initialShowHistory: Boolean) {
    val context = LocalContext.current
    var permissions by remember { mutableStateOf(checkPermissions(context)) }

    // 設定アプリから戻ってきたときに許可状態を取り直す
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissions = checkPermissions(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(initialShowHistory) }
    when {
        !permissions.allGranted -> SetupScreen(permissions)
        showHistory -> FailureHistoryScreen(onBack = { showHistory = false })
        showSettings -> SettingsScreen(onBack = { showSettings = false })
        else -> AppListScreen(
            onOpenSettings = { showSettings = true },
            onOpenHistory = { showHistory = true },
        )
    }
}
