package com.ktakjm.fingerlock.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ktakjm.fingerlock.R
import com.ktakjm.fingerlock.core.IntruderCamera
import com.ktakjm.fingerlock.data.DismissAlertMode
import com.ktakjm.fingerlock.data.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val scope = rememberCoroutineScope()
    val graceSeconds by repo.graceSeconds.collectAsState(initial = SettingsRepository.DEFAULT_GRACE_SECONDS)
    val relockOnScreenOff by repo.relockOnScreenOff.collectAsState(initial = true)
    val failureThreshold by repo.failureThreshold.collectAsState(initial = SettingsRepository.DEFAULT_FAILURE_THRESHOLD)
    val intruderPhotoEnabled by repo.intruderPhotoEnabled.collectAsState(initial = false)
    val dismissAlertMode by repo.dismissAlertMode.collectAsState(initial = DismissAlertMode.ALERT)

    var cameraGranted by remember { mutableStateOf(IntruderCamera.hasPermission(context)) }
    val cameraDeniedMessage = stringResource(R.string.settings_intruder_photo_denied)
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (granted) {
            scope.launch { repo.setIntruderPhotoEnabled(true) }
        } else {
            Toast.makeText(context, cameraDeniedMessage, Toast.LENGTH_LONG).show()
        }
    }
    // 設定アプリで権限を剥がされたままONに見せない
    LaunchedEffect(intruderPhotoEnabled, cameraGranted) {
        if (intruderPhotoEnabled && !cameraGranted) repo.setIntruderPhotoEnabled(false)
    }

    fun setIntruderPhotoEnabled(enabled: Boolean) {
        if (enabled && !cameraGranted) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        } else {
            scope.launch { repo.setIntruderPhotoEnabled(enabled) }
        }
    }

    val graceOptions = listOf(
        0 to stringResource(R.string.settings_grace_immediate),
        30 to stringResource(R.string.settings_grace_30s),
        60 to stringResource(R.string.settings_grace_1m),
        300 to stringResource(R.string.settings_grace_5m),
    )
    val thresholdOptions = listOf(2, 3, 5)
    val dismissModeOptions = listOf(
        DismissAlertMode.ALERT to stringResource(R.string.settings_dismiss_mode_alert),
        DismissAlertMode.LOG_ONLY to stringResource(R.string.settings_dismiss_mode_log_only),
        DismissAlertMode.OFF to stringResource(R.string.settings_dismiss_mode_off),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_grace_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.settings_grace_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            graceOptions.forEach { (seconds, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { repo.setGraceSeconds(seconds) } }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = graceSeconds == seconds,
                        onClick = { scope.launch { repo.setGraceSeconds(seconds) } },
                    )
                    Text(text = label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch { repo.setRelockOnScreenOff(!relockOnScreenOff) }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_screen_off_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_screen_off_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = relockOnScreenOff,
                    onCheckedChange = { scope.launch { repo.setRelockOnScreenOff(it) } },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_failure_threshold_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.settings_failure_threshold_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            thresholdOptions.forEach { count ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { repo.setFailureThreshold(count) } }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = failureThreshold == count,
                        onClick = { scope.launch { repo.setFailureThreshold(count) } },
                    )
                    Text(
                        text = stringResource(R.string.settings_failure_threshold_times, count),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_dismiss_mode_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.settings_dismiss_mode_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            dismissModeOptions.forEach { (mode, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { repo.setDismissAlertMode(mode) } }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = dismissAlertMode == mode,
                        onClick = { scope.launch { repo.setDismissAlertMode(mode) } },
                    )
                    Text(text = label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { setIntruderPhotoEnabled(!intruderPhotoEnabled) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_intruder_photo_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_intruder_photo_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = intruderPhotoEnabled,
                    onCheckedChange = { setIntruderPhotoEnabled(it) },
                )
            }
        }
    }
}
