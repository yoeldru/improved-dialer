// נכתב: 2026-11-01 11:10 (שעון ישראל) — חייגן משופר: התראות שיחה נכנסת / שיחה פעילה
package com.yoeldru.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telecom.Call
import androidx.core.app.NotificationCompat

object Notifications {
    private const val CH_INCOMING = "incoming_calls_v1"
    private const val CH_ONGOING = "ongoing_calls_v1"
    private const val ID = 1001

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val incoming = NotificationChannel(CH_INCOMING, "שיחות נכנסות", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // הצלצול מושמע ע"י המערכת
            enableVibration(false)
        }
        val ongoing = NotificationChannel(CH_ONGOING, "שיחה פעילה", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(incoming)
        nm.createNotificationChannel(ongoing)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    private fun actionIntent(ctx: Context, action: String, req: Int): PendingIntent {
        val i = Intent(ctx, CallActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun update(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val call = CallManager.primary()
        if (call == null) {
            nm.cancel(ID)
            return
        }
        val state = CallManager.stateOf(call)
        if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
            nm.cancel(ID)
            return
        }
        val number = CallManager.numberOf(call)
        val info = ContactsRepo.lookup(ctx, number)
        val title = info.name ?: number.ifEmpty { "מספר חסוי" }

        val openIntent = Intent(ctx, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val openPi = PendingIntent.getActivity(ctx, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val ringing = state == Call.STATE_RINGING
        // עודכן: 2026-11-05 19:15 — כשמסך השיחה המלא מוצג, ההתראה שקטה (בלי חלון קטן קופץ)
        val quiet = ringing && (CallActivity.visible || System.currentTimeMillis() - CallActivity.launchedAt < 4000)
        val b = NotificationCompat.Builder(ctx, if (ringing && !quiet) CH_INCOMING else CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notif_call)
            .setContentTitle(title)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (ringing) {
            val answerIntent = Intent(ctx, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(CallActivity.EXTRA_ANSWER, true)
            val answerPi = PendingIntent.getActivity(ctx, 1, answerIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // עודכן: 2026-11-05 18:05 — תפקיד/חברה של המתקשר בהתראה
            val job = info.contactId?.let { ContactsRepo.details(ctx, it) }?.let { d ->
                listOf(d.title, d.company).filter { !it.isNullOrBlank() }.joinToString(" · ")
            }
            b.setContentText(if (job.isNullOrBlank()) "שיחה נכנסת" else "שיחה נכנסת · $job")
                .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
            if (!quiet) b.setFullScreenIntent(openPi, true)
            b
                .addAction(0, "דחה", actionIntent(ctx, CallActionReceiver.ACTION_REJECT, 2))
                .addAction(0, "ענה", answerPi)
        } else {
            val text = when (state) {
                Call.STATE_DIALING, Call.STATE_CONNECTING -> "מחייג..."
                Call.STATE_HOLDING -> "בהמתנה"
                else -> "שיחה פעילה"
            }
            b.setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "נתק", actionIntent(ctx, CallActionReceiver.ACTION_HANGUP, 3))
            val connect = call.details?.connectTimeMillis ?: 0L
            if (state == Call.STATE_ACTIVE && connect > 0) {
                b.setUsesChronometer(true).setWhen(connect).setShowWhen(true)
            }
        }
        try {
            nm.notify(ID, b.build())
        } catch (e: SecurityException) {
        }
    }
}
