// נכתב: 2026-11-01 11:10 (שעון ישראל) — חייגן משופר: כפתורי "דחה" / "נתק" מתוך ההתראה
package com.yoeldru.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REJECT = "com.yoeldru.dialer.REJECT"
        const val ACTION_HANGUP = "com.yoeldru.dialer.HANGUP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val call = CallManager.primary() ?: return
        when (intent.action) {
            ACTION_REJECT -> call.reject(false, null)
            ACTION_HANGUP -> call.disconnect()
        }
    }
}
