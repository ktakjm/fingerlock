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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import com.ktakjm.fingerlock.core.LockStateManager
import com.ktakjm.fingerlock.ui.FingerLockTheme

class LockActivity : FragmentActivity() {

    private var targetPackage: String = ""
    private val appLabel = mutableStateOf("")
    private val appIcon = mutableStateOf<ImageBitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this) { goHome() }

        handleIntent(intent)
        setContent {
            FingerLockTheme {
                val label by appLabel
                val icon by appIcon
                LockScreen(
                    label = label,
                    icon = icon,
                    onAuthenticate = { showPrompt() },
                    onGoHome = { goHome() },
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

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    // キャンセル系はロック画面に留まり、再試行ボタンに任せる
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> Unit

                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
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

@Composable
private fun LockScreen(
    label: String,
    icon: ImageBitmap?,
    onAuthenticate: () -> Unit,
    onGoHome: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(text = label, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(48.dp))
            Button(onClick = onAuthenticate) {
                Text(stringResource(R.string.lock_unlock_button))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onGoHome) {
                Text(stringResource(R.string.lock_go_home_button))
            }
        }
    }
}
