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
import com.ktakjm.fingerlock.core.FailureNotifier
import com.ktakjm.fingerlock.core.LockStateManager
import com.ktakjm.fingerlock.data.FailureEvent
import com.ktakjm.fingerlock.data.FailureLogRepository
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
        handleIntent(intent)
        showPrompt()
    }

    private fun handleIntent(intent: Intent) {
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""
        failureCount = 0
        alertFired = false
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
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                LockStateManager.onUnlocked(targetPackage)
                finish()
            }

            override fun onAuthenticationFailed() {
                failureCount++
                if (failureCount >= failureThreshold) fireAlert()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    // キャンセル系はロック画面に留まり、再試行ボタンに任せる
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> Unit

                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                        // OS側の生体認証ロックアウトは閾値未満でも無条件で発火
                        fireAlert()
                        Toast.makeText(
                            this@LockActivity, R.string.lock_locked_out, Toast.LENGTH_SHORT
                        ).show()
                        goHome()
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

    // 通知・履歴記録は1ロックセッションにつき1回まで
    private fun fireAlert() {
        if (alertFired) return
        alertFired = true
        val timestamp = System.currentTimeMillis()
        FailureNotifier.notify(
            applicationContext, targetPackage, appLabel.value, failureCount, timestamp
        )
        FailureLogRepository.get(this).log(
            FailureEvent(
                timestamp = timestamp,
                packageName = targetPackage,
                failureCount = failureCount,
            )
        )
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
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
