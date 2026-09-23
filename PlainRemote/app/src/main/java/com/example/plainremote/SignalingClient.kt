package com.example.plainremote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Uses Firebase Firestore purely as a "phone book" to help this phone
 * and the technician's browser find each other and swap a few KB of
 * text (the WebRTC SDP offer/answer and ICE candidates). Once that
 * handshake finishes, the actual screen video and remote-control
 * commands travel directly between the two devices over WebRTC - none
 * of that goes through Firebase, which is what keeps this comfortably
 * inside the free Spark plan even for long support sessions.
 *
 * Document layout for a session with code "123456":
 *   sessions/123456                       { offer, answer, createdAt }
 *   sessions/123456/callerCandidates/*    ICE candidates from the phone
 *   sessions/123456/calleeCandidates/*    ICE candidates from the browser
 *
 * The phone is always the "caller" (it creates the session/offer); the
 * technician's browser is always the "callee" (it joins with a code and
 * answers).
 */
class SignalingClient(private val sessionCode: String) {

    private val db = FirebaseFirestore.getInstance()
    private val sessionRef = db.collection("sessions").document(sessionCode)
    private val listeners = mutableListOf<ListenerRegistration>()

    /** Publishes our offer and calls [onAnswer] once the technician answers. */
    fun createSession(offer: SessionDescription, onAnswer: (SessionDescription) -> Unit) {
        val data = hashMapOf(
            "offer" to hashMapOf(
                "type" to offer.type.canonicalForm(),
                "sdp" to offer.description
            ),
            "createdAt" to Timestamp.now()
        )
        sessionRef.set(data)

        listeners += sessionRef.addSnapshotListener { snap, _ ->
            val answer = snap?.get("answer") as? Map<*, *> ?: return@addSnapshotListener
            val sdp = answer["sdp"] as? String ?: return@addSnapshotListener
            onAnswer(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }
    }

    /** Streams ICE candidates the technician's browser has gathered. */
    fun listenForRemoteCandidates(onCandidate: (IceCandidate) -> Unit) {
        listeners += sessionRef.collection("calleeCandidates")
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val d = change.document
                        val mid = d.getString("sdpMid") ?: ""
                        val index = (d.getLong("sdpMLineIndex") ?: 0L).toInt()
                        val candidate = d.getString("candidate") ?: return@forEach
                        onCandidate(IceCandidate(mid, index, candidate))
                    }
                }
            }
    }

    /** Publishes an ICE candidate this phone has gathered. */
    fun sendLocalCandidate(candidate: IceCandidate) {
        val data = hashMapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "candidate" to candidate.sdp
        )
        sessionRef.collection("callerCandidates").add(data)
    }

    fun close() {
        listeners.forEach { it.remove() }
        listeners.clear()
    }

    /** Removes the session doc so codes don't linger in Firestore forever. */
    fun deleteSession() {
        sessionRef.collection("callerCandidates").get().addOnSuccessListener { docs ->
            docs.forEach { it.reference.delete() }
        }
        sessionRef.collection("calleeCandidates").get().addOnSuccessListener { docs ->
            docs.forEach { it.reference.delete() }
        }
        sessionRef.delete()
    }

    companion object {
        /** A short human-readable code the customer can read aloud over the phone. */
        fun randomCode(): String = (100000..999999).random().toString()
    }
}
