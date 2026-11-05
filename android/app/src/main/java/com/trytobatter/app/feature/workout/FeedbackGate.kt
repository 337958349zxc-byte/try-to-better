package com.trytobatter.app.feature.workout

data class AnalysisEvent(val sessionId: String, val exerciseId: String, val setIndex: Int, val sequence: Long, val expiresAt: Long, val text: String)

/** Rejects duplicated or stale feedback without changing the phone's rep counter. */
class FeedbackGate {
    private var lastSequence = -1L
    private var context = ""
    fun accept(event: AnalysisEvent, session: String, exercise: String, set: Int, now: Long): Boolean {
        val current = "$session/$exercise/$set"
        if(current != context) { context = current; lastSequence = -1 }
        if(event.sessionId != session || event.exerciseId != exercise || event.setIndex != set || event.expiresAt <= now || event.sequence <= lastSequence) return false
        lastSequence = event.sequence
        return true
    }
}
