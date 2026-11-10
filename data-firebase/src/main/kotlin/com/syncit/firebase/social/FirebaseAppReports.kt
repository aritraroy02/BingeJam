package com.syncit.firebase.social

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.social.AppReports
import com.syncit.core.social.AppUser
import com.syncit.core.social.DeviceInfo
import com.syncit.core.social.SealedCrash
import com.syncit.core.social.Staff
import com.syncit.core.social.StaffKey
import com.syncit.core.social.StaffRole
import com.syncit.firebase.FirebaseConfig
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import com.syncit.firebase.long
import com.syncit.firebase.snapshots
import com.syncit.firebase.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * App reports on Firebase. Paths (rules in database.rules.json):
 *   staff/{emailKey} = "developer"       owner writes; the owner is fixed by email in the rules
 *   staffKeys/{emailKey}/{keyId}          {key, at}: a staff phone's public key, written by that person
 *   crashes/{id}                          SealedCrash, write-once, readable by staff only
 *   crashLimits/{uid}                     server time of our last upload (one per 5 s)
 *   users_private/{uid}/device            app version and phone, for the owner's user list
 * Notifications go through the relay (POST /broadcast), which checks the role again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FirebaseAppReports(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
    private val relayUrl: String = FirebaseConfig.PUSH_RELAY_URL,
) : AppReports {

    /** Our verified email, or null (the rules ignore unverified emails, so we do too). */
    private fun verifiedEmail(): String? =
        session.auth.currentUser?.takeIf { !it.isAnonymous && it.isEmailVerified }?.email?.trim()?.lowercase()

    override fun observeRole(): Flow<StaffRole?> = session.uid.flatMapLatest { uid ->
        val email = verifiedEmail()
        when {
            uid == null || email == null -> flowOf(null)
            email == Staff.OWNER_EMAIL -> flowOf(StaffRole.OWNER)
            else -> db.getReference("staff").child(Staff.emailKey(email)).snapshots()
                .map { s -> if (s?.value == "developer") StaffRole.DEVELOPER else null }
                .catch { emit(null) }
        }
    }

    override suspend fun staffKeys(): List<StaffKey> {
        session.requireUid()
        val all = db.getReference("staffKeys").get().await()
        return all.children.flatMap { person ->
            val emailKey = person.key ?: return@flatMap emptyList()
            person.children.mapNotNull { k ->
                val id = k.key ?: return@mapNotNull null
                val key = k.child("key").string() ?: return@mapNotNull null
                StaffKey(emailKey, id, key)
            }
        }.take(MAX_KEYS)
    }

    override suspend fun publishKey(keyId: String, publicKey: String) {
        val email = verifiedEmail() ?: error("Your email isn't verified")
        db.getReference("staffKeys").child(Staff.emailKey(email)).child(keyId)
            .setValue(mapOf("key" to publicKey, "at" to ServerValue.TIMESTAMP)).await()
    }

    override suspend fun upload(iv: String, box: String, keys: Map<String, String>, at: Long) {
        val uid = session.requireUid()
        val id = db.getReference("crashes").push().key ?: error("No id")
        db.reference.updateChildren(
            mapOf(
                "crashes/$id" to mapOf(
                    "v" to 1,
                    "iv" to iv,
                    "box" to box,
                    "keys" to keys,
                    "at" to at,
                    "uploadedAt" to ServerValue.TIMESTAMP,
                    "uid" to uid,
                ),
                "crashLimits/$uid" to ServerValue.TIMESTAMP,
            ),
        ).await()
    }

    override fun observeCrashes(): Flow<List<SealedCrash>> =
        db.getReference("crashes").orderByChild("at").limitToLast(MAX_CRASHES).snapshots().map { s ->
            s?.children?.mapNotNull(::parseCrash).orEmpty().sortedByDescending { it.at }
        }

    private fun parseCrash(c: DataSnapshot): SealedCrash? {
        val id = c.key ?: return null
        return SealedCrash(
            id = id,
            iv = c.child("iv").string() ?: return null,
            box = c.child("box").string() ?: return null,
            keys = c.child("keys").children.mapNotNull { k -> k.key?.let { key -> k.string()?.let { key to it } } }.toMap(),
            at = c.child("at").long() ?: 0L,
            uid = c.child("uid").string().orEmpty(),
        )
    }

    override fun observeUsers(): Flow<List<AppUser>> = combine(
        db.getReference("users").snapshots(),
        db.getReference("users_private").snapshots(),
        db.getReference("presence").snapshots(),
    ) { users, private, presence ->
        users?.children?.mapNotNull { u ->
            val uid = u.key ?: return@mapNotNull null
            val p = private?.child(uid)
            val pr = presence?.child(uid)
            val device = p?.child("device")
            AppUser(
                uid = uid,
                username = u.child("username").string().orEmpty(),
                displayName = u.child("displayName").string().orEmpty(),
                photoUrl = u.child("photoUrl").string(),
                email = p?.child("email")?.string(),
                provider = p?.child("provider")?.string(),
                joinedAt = u.child("createdAt").long() ?: 0L,
                online = pr?.child("online")?.value == true,
                lastSeen = pr?.child("lastSeen")?.long(),
                appVersion = device?.child("app")?.string(),
                device = device?.child("model")?.string(),
                android = device?.child("android")?.string(),
            )
        }.orEmpty().sortedByDescending { it.joinedAt }
    }

    override fun observeDevelopers(): Flow<List<String>> =
        db.getReference("staff").snapshots().map { s ->
            s?.children?.filter { it.value == "developer" }?.mapNotNull { it.key?.let(Staff::emailFromKey) }.orEmpty().sorted()
        }

    override suspend fun addDeveloper(email: String) {
        val clean = email.trim().lowercase()
        require(EMAIL.matches(clean)) { "That doesn't look like an email address" }
        require(clean != Staff.OWNER_EMAIL) { "That's the owner" }
        db.getReference("staff").child(Staff.emailKey(clean)).setValue("developer").await()
    }

    /** Takes away their access and their keys, so new reports aren't encrypted for them any more. */
    override suspend fun removeDeveloper(email: String) {
        val key = Staff.emailKey(email)
        db.reference.updateChildren(mapOf("staff/$key" to null, "staffKeys/$key" to null)).await()
    }

    override suspend fun sendNotification(title: String, body: String, toUids: List<String>?): Int? {
        val user = session.auth.currentUser ?: error("Sign in first")
        val idToken = user.getIdToken(false).await().token ?: error("Sign in first")
        val payload = JSONObject().put("title", title).put("body", body)
        if (toUids != null) payload.put("to", JSONArray(toUids))
        return withContext(Dispatchers.IO) {
            val connection = URL("${relayUrl.trimEnd('/')}/broadcast").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                when (code) {
                    in 200..299 -> runCatching { JSONObject(text).optInt("sent", -1) }.getOrNull()?.takeIf { it >= 0 }
                    403 -> error("Your account isn't allowed to send notifications")
                    else -> error("The relay answered $code")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    override suspend fun recordDevice(info: DeviceInfo) {
        val uid = session.requireUid()
        db.getReference("users_private").child(uid).child("device").setValue(
            mapOf(
                "app" to info.appVersion.take(20),
                "code" to info.versionCode,
                "model" to info.model.take(80),
                "android" to info.android.take(40),
                "at" to ServerValue.TIMESTAMP,
            ),
        ).await()
    }

    private companion object {
        const val MAX_KEYS = 10
        const val MAX_CRASHES = 500
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
