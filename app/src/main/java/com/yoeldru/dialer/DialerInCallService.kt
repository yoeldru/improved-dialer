// נכתב: 2026-11-01 11:10 (שעון ישראל) — חייגן משופר: שירות השיחות (מה שהופך את האפליקציה לחייגן ברירת מחדל)
package com.yoeldru.dialer

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

class DialerInCallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
        Notifications.createChannels(this)
    }

    override fun onDestroy() {
        if (CallManager.service === this) CallManager.service = null
        Notifications.cancel(this)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        // נכתב: 2026-11-02 20:30 — זוכרים מה היה פתוח (וויז וכו') כדי לחזור אליו
        if (CallManager.calls.isEmpty()) AutoBackground.previousApp = AutoBackground.detectPreviousApp(this)
        CallManager.onAdded(call)
        updateNotification()
        // נכתב: 2026-11-02 20:40 — הקראת שם המתקשר
        if (CallManager.isRinging(call)) CallerAnnouncer.onIncoming(this, CallManager.numberOf(call))
        // שיחה יוצאת: פותחים מיד את מסך השיחה. שיחה נכנסת: ההתראה פותחת מסך מלא.
        if (!CallManager.isRinging(call)) {
            try {
                startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.onRemoved(call)
        updateNotification()
        if (CallManager.calls.none { CallManager.isRinging(it) }) CallerAnnouncer.stop()
        // כל השיחות הסתיימו — עוצרים הקלטה אם יש
        if (CallManager.calls.isEmpty()) RecordingService.stop(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.audioState = audioState
        CallManager.notifyChanged()
    }

    fun updateNotification() {
        Notifications.update(this)
    }

    fun toggleMute() {
        val muted = CallManager.audioState?.isMuted ?: false
        setMuted(!muted)
    }

    // עודכן: 2026-11-01 22:45 — בחירת יעד שמע: טלפון / רמקול / בלוטות' / אוזניות
    @Suppress("DEPRECATION")
    fun routeTo(route: Int) {
        setAudioRoute(route)
    }

    @Suppress("DEPRECATION")
    fun routeToBluetooth(device: android.bluetooth.BluetoothDevice?) {
        if (device != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            requestBluetoothAudio(device)
        } else {
            setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        }
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        val route = CallManager.audioState?.route ?: CallAudioState.ROUTE_EARPIECE
        if (route == CallAudioState.ROUTE_SPEAKER) setAudioRoute(CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        else setAudioRoute(CallAudioState.ROUTE_SPEAKER)
    }
}
