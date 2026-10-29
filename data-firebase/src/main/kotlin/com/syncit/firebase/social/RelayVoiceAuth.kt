package com.syncit.firebase.social

import com.syncit.core.voice.VoiceAuth
import com.syncit.firebase.FirebaseConfig
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await

/** Room voice chat signs in to the relay (push-relay/src/voice.js) with our Firebase ID token. */
internal class RelayVoiceAuth(
    private val session: FirebaseSession,
    baseUrl: String = FirebaseConfig.PUSH_RELAY_URL,
) : VoiceAuth {
    override val voiceUrl: String = baseUrl.trimEnd('/').replaceFirst("https://", "wss://") + "/voice"

    override suspend fun idToken(forceRefresh: Boolean): String? {
        val user = session.auth.currentUser?.takeUnless { it.isAnonymous } ?: return null
        return user.getIdToken(forceRefresh).await().token
    }
}
