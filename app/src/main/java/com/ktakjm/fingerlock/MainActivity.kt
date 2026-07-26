package com.ktakjm.fingerlock

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ktakjm.fingerlock.service.AppLockAccessibilityService
import com.ktakjm.fingerlock.ui.AppListScreen
import com.ktakjm.fingerlock.ui.FailureHistoryScreen
import com.ktakjm.fingerlock.ui.FingerLockTheme
import com.ktakjm.fingerlock.ui.SettingsScreen
import com.ktakjm.fingerlock.ui.SetupScreen

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openHistory = intent.getBooleanExtra(EXTRA_OPEN_HISTORY, false)
        setContent {
            FingerLockTheme {
                FingerLockApp(initialShowHistory = openHistory)
            }
        }
    }

    companion object {
        private const val EXTRA_OPEN_HISTORY = "open_history"

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
