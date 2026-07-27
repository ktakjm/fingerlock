package com.ktakjm.fingerlock.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.ktakjm.fingerlock.MainActivity
import com.ktakjm.fingerlock.R

/** 連続認証失敗アラートの通知(NotificationChannel: security_alerts) */
object FailureNotifier {

    private const val CHANNEL_ID = "security_alerts"
    private const val LARGE_ICON_SIZE_PX = 256
    private const val BIG_PICTURE_MAX_PX = 1024

    fun notify(context: Context, alert: FailureAlert, photoPath: String?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            MainActivity.createOpenHistoryIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = DateFormat.getTimeFormat(context).format(alert.timestamp)
        val text = context.getString(
            R.string.notification_failure_text, alert.failureCount, time
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_lock)
            // シルエット表示になるスモールアイコンの代わりに、色でランチャーアイコンと関連づける
            .setColor(ContextCompat.getColor(context, R.color.ic_launcher_background))
            .setLargeIcon(loadAppIcon(context, alert.targetPackage))
            .setContentTitle(
                context.getString(R.string.notification_failure_title, alert.appLabel)
            )
            .setContentText(text)
            .setWhen(alert.timestamp)
            .setShowWhen(true)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)

        // 撮影できていれば展開時に侵入者セルフィーを見せる(issue #3)
        photoPath?.let { IntruderPhotoStore.decode(it, BIG_PICTURE_MAX_PX) }?.let { photo ->
            builder.setStyle(
                NotificationCompat.BigPictureStyle().bigPicture(photo).setSummaryText(text)
            )
        }
        // イベントごとに別通知として残す
        manager.notify((alert.timestamp % Int.MAX_VALUE).toInt(), builder.build())
    }

    // 対象アプリのアイコンをラージアイコン(通知右側)に出す。解決できなければ省略
    private fun loadAppIcon(context: Context, packageName: String): Bitmap? =
        try {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(LARGE_ICON_SIZE_PX, LARGE_ICON_SIZE_PX)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
}
