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

    // עודכן: 2026-11-01 22:45 — שיחות ועידה: משתתפים בוועידה לא מוצגים כשיחה נפרדת
    fun topLevel(): List<Call> = calls.filter { it.parent == null }

    fun isConference(call: Call): Boolean =
        call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true || call.children.isNotEmpty()

    fun activeCall(): Call? = topLevel().firstOrNull { stateOf(it) == Call.STATE_ACTIVE }

    /** השיחה שהמסך מציג: נכנסת > פעילה/מחייגת > בהמתנה */
    fun primary(): Call? {
        val calls = topLevel()
        calls.firstOrNull { stateOf(it) == Call.STATE_RINGING }?.let { return it }
        calls.firstOrNull {
            val s = stateOf(it)
            s == Call.STATE_ACTIVE || s == Call.STATE_DIALING || s == Call.STATE_CONNECTING ||
                s == Call.STATE_SELECT_PHONE_ACCOUNT || s == Call.STATE_PULLING_CALL
        }?.let { return it }
        return calls.lastOrNull()
    }

    fun heldCall(): Call? = topLevel().firstOrNull { stateOf(it) == Call.STATE_HOLDING }

    /** מיזוג השיחה הפעילה עם השיחה שבהמתנה לשיחת ועידה */
    fun merge(): Boolean {
        val active = activeCall() ?: return false
        val held = heldCall()
        if (held != null && active.conferenceableCalls.contains(held)) {
            active.conference(held)
            return true
        }
        if (active.details?.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) == true) {
            active.mergeConference()
            return true
        }
        if (held != null) {
            active.conference(held) // ננסה בכל זאת — תלוי בחברת הסלולר
            return true
        }
        return false
    }

    fun swap() {
        val active = activeCall()
        if (active != null && active.details?.can(Call.Details.CAPABILITY_SWAP_CONFERENCE) == true) {
            active.swapConference()
            return
        }
        val held = heldCall() ?: return
        active?.hold()
        held.unhold()
    }
}
