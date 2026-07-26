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

    fun notify(
        context: Context,
        targetPackage: String,
        appLabel: String,
        failureCount: Int,
        timestamp: Long,
    ) {
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
        val time = DateFormat.getTimeFormat(context).format(timestamp)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_lock)
            // シルエット表示になるスモールアイコンの代わりに、色でランチャーアイコンと関連づける
            .setColor(ContextCompat.getColor(context, R.color.ic_launcher_background))
            .setLargeIcon(loadAppIcon(context, targetPackage))
            .setContentTitle(context.getString(R.string.notification_failure_title, appLabel))
            .setContentText(
                context.getString(R.string.notification_failure_text, failureCount, time)
            )
            .setWhen(timestamp)
            .setShowWhen(true)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // イベントごとに別通知として残す
        manager.notify((timestamp % Int.MAX_VALUE).toInt(), notification)
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
