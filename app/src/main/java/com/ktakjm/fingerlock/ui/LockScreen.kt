package com.ktakjm.fingerlock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ktakjm.fingerlock.R

/** 他アプリ用(LockActivity)とセルフロック(MainActivity)で共用するロック画面 */
@Composable
fun LockScreen(
    label: String,
    icon: ImageBitmap?,
    secondaryLabel: String,
    onAuthenticate: () -> Unit,
    onSecondary: () -> Unit,
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
            TextButton(onClick = onSecondary) {
                Text(secondaryLabel)
            }
        }
    }
}
