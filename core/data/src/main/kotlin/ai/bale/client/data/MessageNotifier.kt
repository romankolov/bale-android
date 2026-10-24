package ai.bale.client.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Local notifications for incoming messages on an active session. Lockscreen content stays private. */
internal class MessageNotifier(private val context: Context) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming chat messages while connected"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun show(notificationId: Int, senderTitle: String, summary: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            android.app.PendingIntent.getActivity(
                context, notificationId, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(senderTitle)
            .setContentText(summary.take(160))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(launch)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    fun clear() {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }

    companion object {
        const val CHANNEL_ID = "bale_messages"
    }
}
