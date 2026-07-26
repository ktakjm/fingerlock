package com.ktakjm.fingerlock.ui

import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ktakjm.fingerlock.R
import com.ktakjm.fingerlock.data.FailureEvent
import com.ktakjm.fingerlock.data.FailureLogRepository

private const val ICON_SIZE_PX = 128

private data class AppDisplayInfo(val label: String, val icon: ImageBitmap?)

// アンインストール済みアプリはパッケージ名のみ表示
private fun resolveAppInfo(context: Context, packageName: String): AppDisplayInfo =
    try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        AppDisplayInfo(
            label = pm.getApplicationLabel(info).toString(),
            icon = pm.getApplicationIcon(info).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX).asImageBitmap(),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        AppDisplayInfo(label = packageName, icon = null)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FailureHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { FailureLogRepository.get(context) }
    val events by repo.events.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
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
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(events.asReversed(), key = { it.timestamp }) { event ->
                    FailureRow(event)
                }
            }
        }
    }
}

@Composable
private fun FailureRow(event: FailureEvent) {
    val context = LocalContext.current
    val appInfo = remember(event.packageName) { resolveAppInfo(context, event.packageName) }
    val dateTime = remember(event.timestamp) {
        val date = DateFormat.getMediumDateFormat(context).format(event.timestamp)
        val time = DateFormat.getTimeFormat(context).format(event.timestamp)
        "$date $time"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (appInfo.icon != null) {
            Image(
                bitmap = appInfo.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(text = appInfo.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = dateTime,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.history_failure_count, event.failureCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
