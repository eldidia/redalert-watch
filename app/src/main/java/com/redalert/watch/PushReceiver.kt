package com.redalert.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

class PushReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ALERT = "redalert_alert"
        const val NOTIF_ID      = 42
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val city      = intent.getStringExtra("city")      ?: "אזור לא ידוע"
        val threat    = intent.getStringExtra("threat")    ?: "missiles"
        val countdown = intent.getStringExtra("countdown")?.toIntOrNull() ?: 90

        Log.d("PushReceiver", "Alert: $city | $threat | ${countdown}s")

        MainActivity.alertState.value = AlertState(
            active      = true,
            city        = city,
            threat      = threat,
            countdown   = countdown,
            triggeredAt = System.currentTimeMillis()
        )

        wakeScreen(ctx)
        vibrate(ctx)
        showNotification(ctx, city, threat, countdown)

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
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "redalert:wake"
            ).acquire(15_000)
        } catch (e: Exception) { Log.w("PushReceiver", "Wake: ${e.message}") }
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
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(pattern, -1)
            }
        } catch (e: Exception) { Log.w("PushReceiver", "Vibrate: ${e.message}") }
    }

    private fun showNotification(ctx: Context, city: String, threat: String, countdown: Int) {
        createChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 צבע אדום – $city")
            .setContentText("${threatToInstruction(threat)} • $countdown שנ'")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
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
