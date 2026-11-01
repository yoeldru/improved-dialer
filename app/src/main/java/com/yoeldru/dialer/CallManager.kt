// נכתב: 2026-11-01 11:10 (שעון ישראל) — חייגן משופר: ניהול השיחות הפעילות
package com.yoeldru.dialer

import android.telecom.Call
import android.telecom.CallAudioState

object CallManager {
    val calls = mutableListOf<Call>()
    var service: DialerInCallService? = null
    var audioState: CallAudioState? = null

    private val listeners = LinkedHashSet<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun notifyChanged() {
        for (l in listeners.toList()) l()
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            notifyChanged()
            service?.updateNotification()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            notifyChanged()
        }
    }

    fun onAdded(call: Call) {
        calls.add(call)
        call.registerCallback(callback)
        notifyChanged()
    }

    fun onRemoved(call: Call) {
        call.unregisterCallback(callback)
        calls.remove(call)
        notifyChanged()
    }

    @Suppress("DEPRECATION")
    fun stateOf(call: Call): Int = call.state

    fun numberOf(call: Call): String = call.details?.handle?.schemeSpecificPart ?: ""

    fun isRinging(call: Call) = stateOf(call) == Call.STATE_RINGING

    /** השיחה שהמסך מציג: נכנסת > פעילה/מחייגת > בהמתנה */
    fun primary(): Call? {
        calls.firstOrNull { stateOf(it) == Call.STATE_RINGING }?.let { return it }
        calls.firstOrNull {
            val s = stateOf(it)
            s == Call.STATE_ACTIVE || s == Call.STATE_DIALING || s == Call.STATE_CONNECTING ||
                s == Call.STATE_SELECT_PHONE_ACCOUNT || s == Call.STATE_PULLING_CALL
        }?.let { return it }
        return calls.lastOrNull()
    }

    fun heldCall(): Call? = calls.firstOrNull { stateOf(it) == Call.STATE_HOLDING }
}
