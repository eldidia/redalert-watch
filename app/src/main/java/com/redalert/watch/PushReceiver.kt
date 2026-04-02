package com.redalert.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import me.pushy.sdk.Pushy
import me.pushy.sdk.model.PushyMessage

/**
 * PushReceiver – מקבל Push מ-Pushy כשמגיעה התראת צבע אדום.
 *
 * Payload שמגיע מהשרת:
 * {
 *   "city":      "תל אביב",
 *   "threat":    "missiles",
 *   "countdown": "90"
 * }
 */
class PushReceiver : me.pushy.sdk.config.PushyActivityReceiver() {

    companion object {
        const val CHANNEL_ALERT = "redalert_alert"
        const val NOTIF_ID      = 42
    }

    override fun onReceive(ctx: Context, message: PushyMessage) {
        Log.d("PushReceiver", "Push received: ${message.data}")

        val city      = message.data.optString("city",      "אזור לא ידוע")
        val threat    = message.data.optString("threat",    "missiles")
        val countdown = message.data.optString("countdown", "90").toIntOrNull() ?: 90

        // עדכן state → MainActivity יאזין ויעבור למסך ההתראה
        MainActivity.alertState.value = AlertState(
            active      = true,
            city        = city,
            threat      = threat,
            countdown   = countdown,
            triggeredAt = System.currentTimeMillis()
        )

        // הדלק מסך
        wakeScreen(ctx)

        // רטט
        vibrate(ctx)

        // הצג נוטיפיקציה (למקרה שהאפליקציה ברקע)
        showNotification(ctx, city, threat, countdown)

        // פתח Activity
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
    }

    private fun wakeScreen(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "redalert:wake"
            )
            wl.acquire(15_000)
        } catch (e: Exception) {
            Log.w("PushReceiver", "Wake failed: ${e.message}")
        }
    }

    private fun vibrate(ctx: Context) {
        val pattern = longArrayOf(0, 700, 150, 700, 150, 700, 150, 700)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)
                    .defaultVibrator
                    .vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                    .vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.w("PushReceiver", "Vibrate failed: ${e.message}")
        }
    }

    private fun showNotification(ctx: Context, city: String, threat: String, countdown: Int) {
        createChannel(ctx)

        val openPi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 צבע אדום – $city")
            .setContentText("${threatToInstruction(threat)} • $countdown שנ'")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(openPi, true)
            .setColor(0xFFCC0000.toInt())
            .setAutoCancel(true)
            .build()

        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ALERT) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "צבע אדום",
                NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 150, 700, 150, 700)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
