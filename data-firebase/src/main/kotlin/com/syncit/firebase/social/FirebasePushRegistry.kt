package com.syncit.firebase.social

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.social.PushRegistry
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await

/** pushTokens/{uid}/{token} {updatedAt}: owner-only; the push relay reads it with admin access. */
class FirebasePushRegistry internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
) : PushRegistry {

    override suspend fun register(token: String) {
        val uid = session.currentUid ?: return
        db.getReference("pushTokens").child(uid).child(token)
            .setValue(mapOf("updatedAt" to ServerValue.TIMESTAMP)).await()
    }

    override suspend fun unregister(token: String) {
        val uid = session.currentUid ?: return
        db.getReference("pushTokens").child(uid).child(token).removeValue().await()
    }
}
